package com.gaiaspa.metrics_detection.worker

import android.content.Context
import androidx.work.*
import com.gaiaspa.metrics_detection.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Single entry point for scheduling synchronization jobs with WorkManager.
 *
 * In DEMO_MODE all scheduling is skipped to prevent cloud operations.
 */
object SyncManager {

    private const val PERIODIC_SYNC_WORK_NAME = "SyncLotesWork"
    private const val MANUAL_SYNC_WORK_NAME = "ManualSyncWork"

    fun schedulePeriodicSync(context: Context) {
        if (BuildConfig.DEMO_MODE) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    fun enqueueManualSync(context: Context) {
        if (BuildConfig.DEMO_MODE) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val manualSyncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            manualSyncRequest
        )
    }
}
