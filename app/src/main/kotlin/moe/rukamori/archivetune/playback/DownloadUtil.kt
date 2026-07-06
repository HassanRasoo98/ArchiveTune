/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.DriveSyncEnabledKey
import moe.rukamori.archivetune.constants.DriveSyncWifiOnlyKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.gdrive.DriveSyncScheduler
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
    ) {
        private val appContext = context
        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = DEFAULT_MAX_PARALLEL_DOWNLOADS
                    },
                ).connectionPool(
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }.build()
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val dataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            mediaOkHttpClient,
                        ),
                    ).setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory().setCache(playerCache).setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                    ),
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val length = if (dataSpec.length >= 0) dataSpec.length else 1
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    return@Factory dataSpec
                }
                val lowDataModeActive = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                songUrlCache[streamCacheKey]
                    ?.takeIf {
                        it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    }?.let {
                        return@Factory dataSpec.withUri(it.url.toUri())
                    }
                val playbackData =
                    runBlocking(Dispatchers.IO) {
                        context.retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForDownload(
                                mediaId,
                                audioQuality = requestedAudioQuality,
                                connectivityManager = connectivityManager,
                                networkMetered = lowDataModeActive,
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                songUrlCache[streamCacheKey] =
                    AuthScopedCacheValue(
                        url = streamUrl,
                        expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                        authFingerprint = playbackData.authFingerprint,
                    )
                dataSpec.withUri(streamUrl.toUri())
            }

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                downloadExecutor,
            ).apply {
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                            if (download.state == Download.STATE_COMPLETED) {
                                downloadScope.launch { exportDownloadedSongIfNeeded(download.request.id) }
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloadScope.launch { clearExportedSong(download.request.id) }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                val cursor = downloadManager.downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    result[cursor.download.request.id] = cursor.download
                }
                downloads.value = result
                result.values
                    .filter { it.state == Download.STATE_COMPLETED }
                    .forEach { download -> exportDownloadedSongIfNeeded(download.request.id) }
            }
            downloadScope.launch {
                var previousFingerprint: String? = null
                YouTube.authStateFlow
                    .map { it.fingerprint }
                    .distinctUntilChanged()
                    .collect { fingerprint ->
                        if (previousFingerprint != null && previousFingerprint != fingerprint) {
                            songUrlCache.clear()
                        }
                        previousFingerprint = fingerprint
                    }
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            if (lowDataModeActive) AudioQuality.LOW else audioQuality

        private fun buildSongUrlCacheKey(
            mediaId: String,
            requestedAudioQuality: AudioQuality,
        ): String = "$mediaId:${requestedAudioQuality.name}"

        private fun persistPlaybackMetadata(
            mediaId: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            downloadScope.launch {
                runCatching {
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L
                    val resolvedCodecs =
                        format.mimeType
                            .substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                            .substringBefore("\"")

                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.split(";")[0],
                                codecs = resolvedCodecs,
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = contentLength,
                                loudnessDb = playbackData.audioConfig?.loudnessDb,
                                perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                                playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                            ),
                        )

                        val now = LocalDateTime.now()
                        val existing = getSongByIdBlocking(mediaId)?.song

                        val updatedSong =
                            if (existing != null) {
                                if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = playbackData.videoDetails?.title ?: "Unknown",
                                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                                    thumbnailUrl =
                                        playbackData.videoDetails
                                            ?.thumbnail
                                            ?.thumbnails
                                            ?.lastOrNull()
                                            ?.url,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)
                    }
                }
            }
        }

        /**
         * Once a download finishes, copy the fully-cached bytes out of the opaque ExoPlayer
         * cache into a real file in the public Music folder (via MediaStore), so the song is
         * playable outside the app (Files app, other players) and syncable to Google Drive.
         * [Song.toMediaItem]/[moe.rukamori.archivetune.models.MediaMetadata.toMediaItem] prefer
         * this file for playback whenever it's present.
         */
        private suspend fun exportDownloadedSongIfNeeded(mediaId: String) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Timber.tag(LogTag).d("Export check starting for %s", mediaId)
                    val existingSong = database.getSongByIdBlocking(mediaId)?.song
                    if (existingSong == null) {
                        Timber.tag(LogTag).w("Export skipped for %s: no SongEntity row yet", mediaId)
                        return@runCatching
                    }
                    if (existingSong.localMediaStoreUri != null) {
                        Timber.tag(LogTag).d("Export skipped for %s: already exported to %s", mediaId, existingSong.localMediaStoreUri)
                        return@runCatching
                    }
                    val format = database.format(mediaId).first()
                    if (format == null) {
                        Timber.tag(LogTag).w("Export skipped for %s: no FormatEntity row yet", mediaId)
                        return@runCatching
                    }
                    val uri = writeCachedSongToMediaStore(mediaId, existingSong.title, format)
                    if (uri == null) {
                        Timber.tag(LogTag).w("Export failed for %s: writeCachedSongToMediaStore returned null (see prior log line)", mediaId)
                        return@runCatching
                    }
                    Timber.tag(LogTag).i("Exported %s to %s", mediaId, uri)
                    database.query {
                        val current = getSongByIdBlocking(mediaId)?.song ?: return@query
                        upsert(
                            current.copy(
                                localMediaStoreUri = uri.toString(),
                                dateDownload = current.dateDownload ?: LocalDateTime.now(),
                            ),
                        )
                    }
                    triggerDriveSyncIfEnabled()
                }.onFailure { error ->
                    Timber.tag(LogTag).w(error, "Failed to export downloaded song %s to device storage", mediaId)
                }
            }
        }

        private fun writeCachedSongToMediaStore(
            mediaId: String,
            title: String,
            format: FormatEntity,
        ): android.net.Uri? {
            val spans = downloadCache.getCachedSpans(mediaId).sortedBy { it.position }
            Timber.tag(LogTag).d(
                "writeCachedSongToMediaStore(%s): %d cached span(s), contentLength=%d",
                mediaId,
                spans.size,
                format.contentLength,
            )
            if (spans.isEmpty()) {
                Timber.tag(LogTag).w("writeCachedSongToMediaStore(%s): no cached spans found in downloadCache", mediaId)
                return null
            }

            val spanFiles = mutableListOf<File>()
            var expectedPosition = 0L
            for (span in spans) {
                val file = span.file
                if (file == null) {
                    Timber.tag(LogTag).w("writeCachedSongToMediaStore(%s): span at %d has no backing file", mediaId, span.position)
                    return null
                }
                if (span.position != expectedPosition) {
                    Timber.tag(LogTag).w(
                        "writeCachedSongToMediaStore(%s): gap in cache, expected position %d but span starts at %d",
                        mediaId,
                        expectedPosition,
                        span.position,
                    )
                    return null
                }
                spanFiles += file
                expectedPosition += span.length
            }
            if (format.contentLength > 0 && expectedPosition < format.contentLength) {
                Timber.tag(LogTag).w(
                    "writeCachedSongToMediaStore(%s): only %d of %d bytes cached",
                    mediaId,
                    expectedPosition,
                    format.contentLength,
                )
                return null
            }

            val resolver = appContext.contentResolver
            val fileName = sanitizeFileName(title)
            var uri: android.net.Uri? = null
            for ((extension, mimeType) in resolveAudioContainerCandidates(format.mimeType)) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, "$fileName.$extension")
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$DownloadFolderName")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                uri =
                    runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) }
                        .onFailure { error ->
                            Timber.tag(LogTag).w(error, "writeCachedSongToMediaStore(%s): insert with MIME %s rejected", mediaId, mimeType)
                        }.getOrNull()
                if (uri != null) {
                    Timber.tag(LogTag).d("writeCachedSongToMediaStore(%s): accepted MIME %s", mediaId, mimeType)
                    break
                }
            }
            if (uri == null) {
                Timber.tag(LogTag).w("writeCachedSongToMediaStore(%s): MediaStore rejected every candidate MIME type", mediaId)
                return null
            }

            val writeResult =
                runCatching {
                    resolver.openOutputStream(uri)?.use { output ->
                        spanFiles.forEach { file -> file.inputStream().use { it.copyTo(output) } }
                    } ?: error("Unable to open MediaStore output stream for $uri")
                }
            if (writeResult.isFailure) {
                Timber.tag(LogTag).w(writeResult.exceptionOrNull(), "writeCachedSongToMediaStore(%s): failed writing to %s", mediaId, uri)
                runCatching { resolver.delete(uri, null, null) }
                return null
            }

            val donePendingValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, donePendingValues, null, null)
            return uri
        }

        private suspend fun clearExportedSong(mediaId: String) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val existingSong = database.getSongByIdBlocking(mediaId)?.song ?: return@runCatching
                    val storedUri = existingSong.localMediaStoreUri ?: return@runCatching
                    runCatching { appContext.contentResolver.delete(storedUri.toUri(), null, null) }
                    database.query {
                        val current = getSongByIdBlocking(mediaId)?.song ?: return@query
                        upsert(current.copy(localMediaStoreUri = null, dateDownload = null))
                    }
                }.onFailure { error ->
                    Timber.tag(LogTag).w(error, "Failed to clean up exported song for %s", mediaId)
                }
            }
        }

        /**
         * MediaProvider's allow-list of MIME types it accepts for MediaStore.Audio.Media inserts
         * varies by OS version/OEM and rejects some legitimate audio MIME types (e.g. "audio/webm"
         * is refused by some MediaProvider builds despite being a real, IANA-registered type).
         * Candidates are tried in order; the first one MediaProvider accepts is used.
         */
        private fun resolveAudioContainerCandidates(mimeType: String): List<Pair<String, String>> {
            val normalized = mimeType.lowercase()
            return when {
                normalized.contains("webm") ->
                    listOf("weba" to "audio/webm", "mka" to "audio/x-matroska", "opus" to "audio/opus")
                normalized.contains("ogg") -> listOf("ogg" to "audio/ogg")
                else -> listOf("m4a" to "audio/mp4")
            }
        }

        private fun sanitizeFileName(raw: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "song" }

        private fun triggerDriveSyncIfEnabled() {
            if (BuildConfig.DISTRIBUTION != "gms") return
            if (appContext.dataStore[DriveSyncEnabledKey] != true) return
            val wifiOnly = appContext.dataStore[DriveSyncWifiOnlyKey] != false
            DriveSyncScheduler.enqueueImmediateSync(appContext, wifiOnly)
        }

        companion object {
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 12
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 24
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 5L
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 256 * 1024
            private const val DownloadFolderName = "ArchiveTune"
            private const val LogTag = "DownloadUtil"
        }
    }
