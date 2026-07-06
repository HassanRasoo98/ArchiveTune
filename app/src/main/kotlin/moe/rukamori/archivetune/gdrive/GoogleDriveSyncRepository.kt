/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.gdrive

import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.StateFlow

sealed interface DriveAuthorizationOutcome {
    data class Authorized(
        val accountEmail: String?,
    ) : DriveAuthorizationOutcome

    data class ConsentRequired(
        val intentSenderRequest: IntentSenderRequest,
    ) : DriveAuthorizationOutcome

    data class Failed(
        val message: String? = null,
    ) : DriveAuthorizationOutcome
}

/**
 * Backs up downloaded songs to a "ArchiveTune Downloads" folder in the user's Google Drive.
 * Only implemented for the `gms` flavor (see [GoogleDriveSyncRepositoryLocator]) since it depends
 * on Play Services; the `foss` flavor gets a no-op implementation so common UI/worker code never
 * needs to know which flavor it's running in.
 */
interface GoogleDriveSyncRepository {
    val isConnected: StateFlow<Boolean>
    val connectedAccountEmail: StateFlow<String?>

    /**
     * Starts (or silently resumes) authorization. May return [DriveAuthorizationOutcome.ConsentRequired]
     * if the user needs to see the account picker/consent screen — launch its [IntentSenderRequest]
     * and feed the result to [completeAuthorization].
     */
    suspend fun beginAuthorization(): DriveAuthorizationOutcome

    suspend fun completeAuthorization(result: ActivityResult): DriveAuthorizationOutcome

    fun disconnect()

    suspend fun uploadSong(
        localFileUri: Uri,
        displayName: String,
        mimeType: String,
    ): Result<String>
}
