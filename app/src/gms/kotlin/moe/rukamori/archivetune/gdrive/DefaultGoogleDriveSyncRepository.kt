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
import androidx.activity.result.IntentSenderRequest
import androidx.datastore.preferences.core.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.DriveSyncAccountEmailKey
import moe.rukamori.archivetune.constants.DriveSyncFolderIdKey
import moe.rukamori.archivetune.utils.dataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DefaultGoogleDriveSyncRepository(
    private val context: Context,
) : GoogleDriveSyncRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authorizationClient = Identity.getAuthorizationClient(context)
    private val authorizationRequest =
        AuthorizationRequest
            .builder()
            .setRequestedScopes(listOf(Scope(DriveFileScope)))
            .build()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override val connectedAccountEmail: StateFlow<String?> =
        context.dataStore.data
            .map { it[DriveSyncAccountEmailKey] }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val isConnected: StateFlow<Boolean> =
        context.dataStore.data
            .map { it[DriveSyncAccountEmailKey] != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun beginAuthorization(): DriveAuthorizationOutcome =
        withContext(Dispatchers.IO) {
            runCatching { Tasks.await(authorizationClient.authorize(authorizationRequest)) }
                .fold(
                    onSuccess = { result -> handleAuthorizationResult(result) },
                    onFailure = { error ->
                        Timber.tag(LogTag).w(error, "beginAuthorization failed")
                        DriveAuthorizationOutcome.Failed(error.message)
                    },
                )
        }

    override suspend fun completeAuthorization(result: ActivityResult): DriveAuthorizationOutcome =
        withContext(Dispatchers.IO) {
            runCatching {
                val authorizationResult = authorizationClient.getAuthorizationResultFromIntent(result.data)
                persistAuthorizedAccount(authorizationResult)
                DriveAuthorizationOutcome.Authorized(authorizationResult.toGoogleSignInAccount()?.email)
            }.getOrElse { error ->
                Timber.tag(LogTag).w(error, "completeAuthorization failed")
                DriveAuthorizationOutcome.Failed(error.message)
            }
        }

    override fun disconnect() {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs.remove(DriveSyncAccountEmailKey)
                prefs.remove(DriveSyncFolderIdKey)
            }
        }
    }

    override suspend fun uploadSong(
        localFileUri: Uri,
        displayName: String,
        mimeType: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes =
                    context.contentResolver.openInputStream(localFileUri)?.use { it.readBytes() }
                        ?: error("Unable to read $localFileUri")
                uploadBytesInternal(displayName, mimeType, bytes)
            }.onFailure { error ->
                Timber.tag(LogTag).w(error, "uploadSong failed for %s", displayName)
            }
        }

    override suspend fun uploadBytes(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { uploadBytesInternal(displayName, mimeType, bytes) }
                .onFailure { error ->
                    Timber.tag(LogTag).w(error, "uploadBytes failed for %s", displayName)
                }
        }

    private suspend fun uploadBytesInternal(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String {
        val token = freshAccessToken() ?: error("Not authorized with Google Drive")
        val folderId = ensureSyncFolder(token)

        val metadata =
            JSONObject().apply {
                put("name", displayName)
                put("parents", JSONArray().put(folderId))
            }
        val body =
            MultipartBody
                .Builder()
                .setType("multipart/related".toMediaType())
                .addPart(
                    metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()),
                ).addPart(
                    bytes.toRequestBody(mimeType.toMediaType()),
                ).build()
        val request =
            Request
                .Builder()
                .url("$DriveUploadUrl?uploadType=multipart&fields=id")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Drive upload failed (${response.code}): $responseBody")
            JSONObject(responseBody).getString("id")
        }
    }

    private fun handleAuthorizationResult(result: AuthorizationResult): DriveAuthorizationOutcome {
        val pendingIntent = result.pendingIntent
        return if (result.hasResolution() && pendingIntent != null) {
            DriveAuthorizationOutcome.ConsentRequired(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            scope.launch { persistAuthorizedAccount(result) }
            DriveAuthorizationOutcome.Authorized(result.toGoogleSignInAccount()?.email)
        }
    }

    private suspend fun persistAuthorizedAccount(result: AuthorizationResult) {
        val email = result.toGoogleSignInAccount()?.email.orEmpty()
        context.dataStore.edit { prefs -> prefs[DriveSyncAccountEmailKey] = email }
    }

    /** Silently re-requests an access token for an already-granted scope (no UI shown). */
    private suspend fun freshAccessToken(): String? =
        withContext(Dispatchers.IO) {
            runCatching { Tasks.await(authorizationClient.authorize(authorizationRequest)) }
                .getOrElse { error ->
                    Timber.tag(LogTag).w(error, "Failed to obtain fresh Drive access token")
                    return@withContext null
                }.let { result -> if (result.hasResolution()) null else result.accessToken }
        }

    private suspend fun ensureSyncFolder(token: String): String {
        context.dataStore.data.map { it[DriveSyncFolderIdKey] }.first()?.let { return it }

        val query =
            "mimeType='application/vnd.google-apps.folder' and name='$DriveFolderName' " +
                "and 'root' in parents and trashed=false"
        val listRequest =
            Request
                .Builder()
                .url("$DriveFilesUrl?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
        val existingId =
            httpClient.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("files")
                        ?.takeIf { it.length() > 0 }
                        ?.getJSONObject(0)
                        ?.getString("id")
                }
            }

        val folderId =
            existingId ?: run {
                val createBody =
                    JSONObject()
                        .apply {
                            put("name", DriveFolderName)
                            put("mimeType", "application/vnd.google-apps.folder")
                            put("parents", JSONArray().put("root"))
                        }.toString()
                        .toRequestBody("application/json; charset=UTF-8".toMediaType())
                val createRequest =
                    Request
                        .Builder()
                        .url("$DriveFilesUrl?fields=id")
                        .addHeader("Authorization", "Bearer $token")
                        .post(createBody)
                        .build()
                httpClient.newCall(createRequest).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Failed to create Drive folder (${response.code}): $responseBody")
                    JSONObject(responseBody).getString("id")
                }
            }

        context.dataStore.edit { prefs -> prefs[DriveSyncFolderIdKey] = folderId }
        return folderId
    }

    private companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
        const val DriveFolderName = "ArchiveTune Downloads"
        const val DriveFilesUrl = "https://www.googleapis.com/drive/v3/files"
        const val DriveUploadUrl = "https://www.googleapis.com/upload/drive/v3/files"
        const val LogTag = "GoogleDriveSync"
    }
}
