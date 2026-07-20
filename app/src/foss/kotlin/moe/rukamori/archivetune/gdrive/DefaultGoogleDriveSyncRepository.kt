/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.gdrive

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DefaultGoogleDriveSyncRepository(
    context: Context,
) : GoogleDriveSyncRepository {
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedAccountEmail: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun beginAuthorization(): DriveAuthorizationOutcome = unavailable()

    override suspend fun completeAuthorization(result: ActivityResult): DriveAuthorizationOutcome = unavailable()

    override fun disconnect() = Unit

    override suspend fun uploadSong(
        localFileUri: Uri,
        displayName: String,
        mimeType: String,
    ): Result<String> = Result.failure(UnsupportedOperationException(UnavailableMessage))

    override suspend fun uploadBytes(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<String> = Result.failure(UnsupportedOperationException(UnavailableMessage))

    private fun unavailable() = DriveAuthorizationOutcome.Failed(UnavailableMessage)

    private companion object {
        const val UnavailableMessage = "Google Drive sync is not available in this build"
    }
}
