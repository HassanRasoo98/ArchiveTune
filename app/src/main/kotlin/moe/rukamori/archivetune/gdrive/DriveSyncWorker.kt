/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.gdrive

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.di.DriveSyncWorkerEntryPoint
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Uploads every downloaded song that hasn't been backed up yet to the user's Google Drive.
 * A no-op on the `foss` flavor since [GoogleDriveSyncRepositoryLocator] resolves to a stub there.
 */
class DriveSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repository = GoogleDriveSyncRepositoryLocator.get(applicationContext)
            if (!repository.isConnected.value) return@withContext Result.success()

            val database =
                EntryPointAccessors
                    .fromApplication(applicationContext, DriveSyncWorkerEntryPoint::class.java)
                    .musicDatabase()

            try {
                database.getSongsPendingDriveSync().forEach { song ->
                    val localUriString = song.song.localMediaStoreUri ?: return@forEach
                    syncOne(repository, database, song, localUriString)
                }
                Result.success()
            } catch (e: Exception) {
                Timber.tag(LogTag).w(e, "Drive sync pass failed")
                Result.retry()
            }
        }

    private suspend fun syncOne(
        repository: GoogleDriveSyncRepository,
        database: MusicDatabase,
        song: Song,
        localUriString: String,
    ) {
        val songId = song.song.id
        val localUri = localUriString.toUri()
        val mimeType = queryColumn(localUri, MediaStore.Audio.Media.MIME_TYPE) ?: "application/octet-stream"
        val displayName = queryColumn(localUri, MediaStore.Audio.Media.DISPLAY_NAME) ?: song.song.title

        repository
            .uploadSong(localUri, displayName, mimeType)
            .onSuccess { driveFileId ->
                database.query {
                    val current = getSongByIdBlocking(songId)?.song ?: return@query
                    upsert(current.copy(driveFileId = driveFileId, driveSyncedAt = LocalDateTime.now()))
                }
                syncSidecarFiles(repository, database, song, displayName, driveFileId)
            }.onFailure { error ->
                Timber.tag(LogTag).w(error, "Failed to sync %s to Drive", songId)
            }
    }

    /**
     * Best-effort: uploads a thumbnail and a metadata/lyrics JSON alongside the audio file so a
     * separate client (e.g. a web player) can build a richer library without re-fetching anything.
     * These ride along with the one-time audio upload above — a failure here doesn't affect
     * [Result.retry] since the song is already marked synced and won't be revisited.
     */
    private suspend fun syncSidecarFiles(
        repository: GoogleDriveSyncRepository,
        database: MusicDatabase,
        song: Song,
        audioDisplayName: String,
        audioDriveFileId: String,
    ) {
        val baseName = audioDisplayName.substringBeforeLast(".")

        val thumbnailFileName =
            song.song.thumbnailUrl?.let { url ->
                runCatching { uploadThumbnail(repository, url, baseName) }
                    .onFailure { error -> Timber.tag(LogTag).w(error, "Thumbnail sync failed for %s", song.song.id) }
                    .getOrNull()
            }

        val lyrics =
            runCatching { database.getLyricsById(song.song.id) }
                .onFailure { error -> Timber.tag(LogTag).w(error, "Lyrics lookup failed for %s", song.song.id) }
                .getOrNull()
                ?.takeIf { it.lyrics != LyricsEntity.LYRICS_NOT_FOUND }

        val metadata =
            JSONObject().apply {
                put("title", song.song.title)
                put("artists", JSONArray(song.artists.map { it.name }))
                put("album", song.album?.title)
                put("year", song.song.year)
                put("durationSeconds", song.song.duration)
                put("audioFileName", audioDisplayName)
                put("audioFileId", audioDriveFileId)
                thumbnailFileName?.let { put("thumbnailFileName", it) }
                lyrics?.let {
                    put("lyrics", it.lyrics)
                    put("lyricsSource", it.source)
                }
            }

        repository
            .uploadBytes("$baseName.json", "application/json", metadata.toString().toByteArray())
            .onFailure { error -> Timber.tag(LogTag).w(error, "Metadata sync failed for %s", song.song.id) }
    }

    private suspend fun uploadThumbnail(
        repository: GoogleDriveSyncRepository,
        url: String,
        baseName: String,
    ): String? {
        val (bytes, mimeType) =
            thumbnailClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("Thumbnail download failed (${response.code})")
                val contentType = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "image/jpeg"
                (response.body?.bytes() ?: error("Empty thumbnail body")) to contentType
            }
        val extension =
            when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
        val fileName = "$baseName.$extension"
        return repository.uploadBytes(fileName, mimeType, bytes).getOrThrow().let { fileName }
    }

    private val thumbnailClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun queryColumn(
        uri: Uri,
        column: String,
    ): String? =
        applicationContext.contentResolver
            .query(uri, arrayOf(column), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val LogTag = "DriveSyncWorker"
    }
}
