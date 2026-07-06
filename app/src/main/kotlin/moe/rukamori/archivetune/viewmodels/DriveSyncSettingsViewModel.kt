/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.constants.DriveSyncWifiOnlyKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.gdrive.DriveAuthorizationOutcome
import moe.rukamori.archivetune.gdrive.DriveSyncScheduler
import moe.rukamori.archivetune.gdrive.GoogleDriveSyncRepositoryLocator
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import javax.inject.Inject

data class DriveSyncCounts(
    val synced: Int,
    val downloaded: Int,
)

@HiltViewModel
class DriveSyncSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        database: MusicDatabase,
    ) : ViewModel() {
        private val repository = GoogleDriveSyncRepositoryLocator.get(context)

        val isConnected: StateFlow<Boolean> = repository.isConnected
        val connectedAccountEmail: StateFlow<String?> = repository.connectedAccountEmail

        val counts: StateFlow<DriveSyncCounts> =
            database
                .allSongs()
                .map { songs ->
                    DriveSyncCounts(
                        synced = songs.count { it.song.driveFileId != null },
                        downloaded = songs.count { it.song.localMediaStoreUri != null },
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DriveSyncCounts(0, 0))

        suspend fun beginAuthorization(): DriveAuthorizationOutcome = repository.beginAuthorization()

        suspend fun completeAuthorization(result: ActivityResult): DriveAuthorizationOutcome = repository.completeAuthorization(result)

        fun disconnect() = repository.disconnect()

        fun syncNow() {
            val wifiOnly = context.dataStore[DriveSyncWifiOnlyKey] != false
            DriveSyncScheduler.enqueueImmediateSync(context, wifiOnly)
        }
    }
