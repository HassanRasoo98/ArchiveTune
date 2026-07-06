/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DriveSyncEnabledKey
import moe.rukamori.archivetune.constants.DriveSyncWifiOnlyKey
import moe.rukamori.archivetune.gdrive.DriveAuthorizationOutcome
import moe.rukamori.archivetune.gdrive.DriveSyncScheduler
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.DriveSyncSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSyncSettings(
    navController: NavController,
    viewModel: DriveSyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val connectedEmail by viewModel.connectedAccountEmail.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val (autoSyncEnabled, setAutoSyncEnabled) = rememberPreference(DriveSyncEnabledKey, false)
    val (wifiOnly, setWifiOnly) = rememberPreference(DriveSyncWifiOnlyKey, true)

    val onAutoSyncEnabledChange: (Boolean) -> Unit = { enabled ->
        setAutoSyncEnabled(enabled)
        if (enabled) {
            DriveSyncScheduler.schedulePeriodicSync(context, wifiOnly)
        } else {
            DriveSyncScheduler.cancelPeriodicSync(context)
        }
    }
    val onWifiOnlyChange: (Boolean) -> Unit = { newWifiOnly ->
        setWifiOnly(newWifiOnly)
        if (autoSyncEnabled) {
            DriveSyncScheduler.schedulePeriodicSync(context, newWifiOnly)
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scope.launch {
                errorMessage =
                    when (val outcome = viewModel.completeAuthorization(result)) {
                        is DriveAuthorizationOutcome.Failed -> outcome.message
                        else -> null
                    }
            }
        }

    fun connect() {
        scope.launch {
            when (val outcome = viewModel.beginAuthorization()) {
                is DriveAuthorizationOutcome.ConsentRequired -> consentLauncher.launch(outcome.intentSenderRequest)
                is DriveAuthorizationOutcome.Failed -> errorMessage = outcome.message
                is DriveAuthorizationOutcome.Authorized -> errorMessage = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.google_drive_sync)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.account)) {
                item {
                    PreferenceEntry(
                        title = {
                            Text(
                                text =
                                    when {
                                        !isConnected -> stringResource(R.string.drive_sync_not_connected)
                                        connectedEmail.isNullOrBlank() -> stringResource(R.string.drive_sync_connected)
                                        else -> stringResource(R.string.drive_sync_connected_as, connectedEmail.orEmpty())
                                    },
                                modifier = Modifier.alpha(if (isConnected) 1f else 0.5f),
                            )
                        },
                        description = errorMessage,
                        icon = { Icon(painterResource(R.drawable.backup), null) },
                        trailingContent = {
                            if (isConnected) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.disconnect()
                                        onAutoSyncEnabledChange(false)
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(R.string.action_disconnect))
                                }
                            } else {
                                OutlinedButton(onClick = ::connect, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.action_connect))
                                }
                            }
                        },
                    )
                }
            }

            if (isConnected) {
                PreferenceGroup(title = stringResource(R.string.options)) {
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.drive_sync_auto_sync)) },
                            description = stringResource(R.string.drive_sync_auto_sync_description),
                            checked = autoSyncEnabled,
                            onCheckedChange = onAutoSyncEnabledChange,
                        )
                    }
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.drive_sync_wifi_only)) },
                            checked = wifiOnly,
                            onCheckedChange = onWifiOnlyChange,
                        )
                    }
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.drive_sync_now)) },
                            description = stringResource(R.string.drive_sync_status_format, counts.synced, counts.downloaded),
                            icon = { Icon(painterResource(R.drawable.sync), null) },
                            onClick = viewModel::syncNow,
                        )
                    }
                }
            }
        }
    }
}
