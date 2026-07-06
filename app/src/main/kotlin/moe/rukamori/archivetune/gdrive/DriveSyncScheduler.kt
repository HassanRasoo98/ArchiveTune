/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.gdrive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object DriveSyncScheduler {
    private const val PERIODIC_WORK_NAME = "drive_sync_periodic"
    private const val ONE_TIME_WORK_NAME = "drive_sync_now"

    /** Safety-net sync that runs periodically in case a one-shot enqueue was missed. */
    fun schedulePeriodicSync(
        context: Context,
        wifiOnly: Boolean,
    ) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

        val request =
            PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** Enqueued right after a download finishes, or when the user taps "Sync now". */
    fun enqueueImmediateSync(
        context: Context,
        wifiOnly: Boolean,
    ) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

        val request =
            OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(constraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
