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
import moe.rukamori.archivetune.di.DriveSyncWorkerEntryPoint
import timber.log.Timber
import java.time.LocalDateTime

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
                    syncOne(repository, database, song.song.id, localUriString, song.song.title)
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
        songId: String,
        localUriString: String,
        title: String,
    ) {
        val localUri = localUriString.toUri()
        val mimeType = queryColumn(localUri, MediaStore.Audio.Media.MIME_TYPE) ?: "application/octet-stream"
        val displayName = queryColumn(localUri, MediaStore.Audio.Media.DISPLAY_NAME) ?: title

        repository
            .uploadSong(localUri, displayName, mimeType)
            .onSuccess { driveFileId ->
                database.query {
                    val current = getSongByIdBlocking(songId)?.song ?: return@query
                    upsert(current.copy(driveFileId = driveFileId, driveSyncedAt = LocalDateTime.now()))
                }
            }.onFailure { error ->
                Timber.tag(LogTag).w(error, "Failed to sync %s to Drive", songId)
            }
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
