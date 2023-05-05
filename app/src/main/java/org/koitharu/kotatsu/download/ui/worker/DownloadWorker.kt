package org.koitharu.kotatsu.download.ui.worker

import android.app.NotificationManager
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import okio.IOException
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.domain.MangaDataRepository
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadState2
import org.koitharu.kotatsu.download.ui.service.PausingHandle
import org.koitharu.kotatsu.local.data.LocalManga
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.PagesCache
import org.koitharu.kotatsu.local.data.input.LocalMangaInput
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.utils.ext.copyToSuspending
import org.koitharu.kotatsu.utils.ext.deleteAwait
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.runCatchingCancellable
import org.koitharu.kotatsu.utils.progress.TimeLeftEstimator
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class DownloadWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val okHttp: OkHttpClient,
	private val cache: PagesCache,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	notificationFactoryFactory: DownloadNotificationFactory.Factory,
) : CoroutineWorker(appContext, params) {

	private val notificationFactory = notificationFactoryFactory.create(params.id)
	private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

	@Volatile
	private lateinit var currentState: DownloadState2

	private val pausingHandle = PausingHandle()
	private val timeLeftEstimator = TimeLeftEstimator()
	private val notificationThrottler = Throttler(400)
	private val pausingReceiver = PausingReceiver(params.id, pausingHandle)

	override suspend fun doWork(): Result {
		setForeground(getForegroundInfo())
		val mangaId = inputData.getLong(MANGA_ID, 0L)
		val manga = mangaDataRepository.findMangaById(mangaId) ?: return Result.failure()
		val chaptersIds = inputData.getLongArray(CHAPTERS_IDS)?.takeUnless { it.isEmpty() }
		currentState = DownloadState2(manga, isIndeterminate = true)
		return try {
			downloadMangaImpl(chaptersIds)
			Result.success(currentState.toWorkData())
		} catch (e: CancellationException) {
			throw e
		} catch (e: IOException) {
			e.printStackTraceDebug()
			Result.retry()
		} catch (e: Exception) {
			e.printStackTraceDebug()
			currentState = currentState.copy(error = e)
			Result.failure(currentState.toWorkData())
		}
	}

	override suspend fun getForegroundInfo() = ForegroundInfo(
		id.hashCode(),
		notificationFactory.create(null),
	)

	private suspend fun downloadMangaImpl(chaptersIds: LongArray?) {
		var manga = currentState.manga
		val chaptersIdsSet = chaptersIds?.toMutableSet()
		withMangaLock(manga) {
			ContextCompat.registerReceiver(
				applicationContext,
				pausingReceiver,
				PausingReceiver.createIntentFilter(id),
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			val destination = localMangaRepository.getOutputDir(manga)
			checkNotNull(destination) { applicationContext.getString(R.string.cannot_find_available_storage) }
			val tempFileName = "${manga.id}_$id.tmp"
			var output: LocalMangaOutput? = null
			try {
				if (manga.source == MangaSource.LOCAL) {
					manga = localMangaRepository.getRemoteManga(manga)
						?: error("Cannot obtain remote manga instance")
				}
				val repo = mangaRepositoryFactory.create(manga.source)
				val data = if (manga.chapters.isNullOrEmpty()) repo.getDetails(manga) else manga
				output = LocalMangaOutput.getOrCreate(destination, data)
				val coverUrl = data.largeCoverUrl ?: data.coverUrl
				downloadFile(coverUrl, destination, tempFileName, repo.source).let { file ->
					output.addCover(file, MimeTypeMap.getFileExtensionFromUrl(coverUrl))
				}
				val chapters = checkNotNull(
					if (chaptersIdsSet == null) {
						data.chapters
					} else {
						data.chapters?.filter { x -> chaptersIdsSet.remove(x.id) }
					},
				) { "Chapters list must not be null" }
				check(chapters.isNotEmpty()) { "Chapters list must not be empty" }
				check(chaptersIdsSet.isNullOrEmpty()) {
					"${chaptersIdsSet?.size} of ${chaptersIds?.size} requested chapters not found in manga"
				}
				for ((chapterIndex, chapter) in chapters.withIndex()) {
					val pages = runFailsafe(pausingHandle) {
						repo.getPages(chapter)
					}
					for ((pageIndex, page) in pages.withIndex()) {
						runFailsafe(pausingHandle) {
							val url = repo.getPageUrl(page)
							val file = cache.get(url)
								?: downloadFile(url, destination, tempFileName, repo.source)
							output.addPage(
								chapter = chapter,
								file = file,
								pageNumber = pageIndex,
								ext = MimeTypeMap.getFileExtensionFromUrl(url),
							)
						}
						publishState(
							currentState.copy(
								totalChapters = chapters.size,
								currentChapter = chapterIndex,
								totalPages = pages.size,
								currentPage = pageIndex,
								eta = timeLeftEstimator.getEta(),
							),
						)

						if (settings.isDownloadsSlowdownEnabled) {
							delay(SLOWDOWN_DELAY)
						}
					}
					if (output.flushChapter(chapter)) {
						runCatchingCancellable {
							localStorageChanges.emit(LocalMangaInput.of(output.rootFile).getManga())
						}.onFailure(Throwable::printStackTraceDebug)
					}
				}
				publishState(currentState.copy(isIndeterminate = true))
				output.mergeWithExisting()
				output.finish()
				val localManga = LocalMangaInput.of(output.rootFile).getManga()
				localStorageChanges.emit(localManga)
				publishState(currentState.copy(localManga = localManga))
			} catch (e: Exception) {
				if (e !is CancellationException) {
					publishState(currentState.copy(error = e))
				}
				throw e
			} finally {
				withContext(NonCancellable) {
					applicationContext.unregisterReceiver(pausingReceiver)
					output?.closeQuietly()
					output?.cleanup()
					File(destination, tempFileName).deleteAwait()
				}
			}
		}
	}

	private suspend fun <R> runFailsafe(
		pausingHandle: PausingHandle,
		block: suspend () -> R,
	): R {
		if (pausingHandle.isPaused) {
			publishState(currentState.copy(isPaused = true))
			pausingHandle.awaitResumed()
			publishState(currentState.copy(isPaused = false))
		}
		var countDown = MAX_FAILSAFE_ATTEMPTS
		failsafe@ while (true) {
			try {
				return block()
			} catch (e: IOException) {
				if (countDown <= 0) {
					publishState(currentState.copy(isPaused = true, error = e))
					countDown = MAX_FAILSAFE_ATTEMPTS
					pausingHandle.pause()
					pausingHandle.awaitResumed()
					publishState(currentState.copy(isPaused = false, error = null))
				} else {
					countDown--
					delay(DOWNLOAD_ERROR_DELAY)
				}
			}
		}
	}

	private suspend fun downloadFile(
		url: String,
		destination: File,
		tempFileName: String,
		source: MangaSource,
	): File {
		val request = Request.Builder()
			.url(url)
			.tag(MangaSource::class.java, source)
			.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.get()
			.build()
		val call = okHttp.newCall(request)
		val file = File(destination, tempFileName)
		val response = call.clone().await()
		file.outputStream().use { out ->
			checkNotNull(response.body).byteStream().copyToSuspending(out)
		}
		return file
	}

	private suspend fun publishState(state: DownloadState2) {
		currentState = state
		if (!state.isPaused && state.max > 0) {
			timeLeftEstimator.tick(state.progress, state.max)
		} else {
			timeLeftEstimator.emptyTick()
			notificationThrottler.reset()
		}
		val notification = notificationFactory.create(state)
		if (state.isFinalState) {
			notificationManager.notify(id.toString(), id.hashCode(), notification)
		} else if (notificationThrottler.throttle()) {
			notificationManager.notify(id.hashCode(), notification)
		}
		setProgress(state.toWorkData())
	}

	private suspend inline fun <T> withMangaLock(manga: Manga, block: () -> T) = try {
		localMangaRepository.lockManga(manga.id)
		block()
	} finally {
		localMangaRepository.unlockManga(manga.id)
	}

	@Reusable
	class Scheduler @Inject constructor(
		@ApplicationContext private val context: Context,
		private val dataRepository: MangaDataRepository,
	) {

		private val workManager: WorkManager
			inline get() = WorkManager.getInstance(context)

		suspend fun schedule(manga: Manga, chaptersIds: Collection<Long>?) {
			dataRepository.storeManga(manga)
			val data = Data.Builder()
				.putLong(MANGA_ID, manga.id)
			if (!chaptersIds.isNullOrEmpty()) {
				data.putLongArray(CHAPTERS_IDS, chaptersIds.toLongArray())
			}
			scheduleImpl(listOf(data.build())).await()
		}

		suspend fun schedule(manga: Collection<Manga>) {
			val data = manga.map {
				dataRepository.storeManga(it)
				Data.Builder()
					.putLong(MANGA_ID, it.id)
					.build()
			}
			scheduleImpl(data).await()
		}

		fun observeWorks(): Flow<List<WorkInfo>> = workManager
			.getWorkInfosByTagLiveData(TAG)
			.asFlow()

		suspend fun cancel(id: UUID) {
			workManager.cancelWorkById(id).await()
		}

		private fun scheduleImpl(data: Collection<Data>): Operation {
			val constraints = Constraints.Builder()
				.setRequiresStorageNotLow(true)
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val requests = data.map { inputData ->
				OneTimeWorkRequestBuilder<DownloadWorker>()
					.setConstraints(constraints)
					.addTag(TAG)
					.keepResultsForAtLeast(3, TimeUnit.DAYS)
					.setInputData(inputData)
					.setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
					.build()
			}
			return workManager.enqueue(requests)
		}
	}

	private companion object {

		const val MAX_FAILSAFE_ATTEMPTS = 2
		const val DOWNLOAD_ERROR_DELAY = 500L
		const val SLOWDOWN_DELAY = 100L
		const val MANGA_ID = "manga_id"
		const val CHAPTERS_IDS = "chapters"
		const val TAG = "download"
	}
}
