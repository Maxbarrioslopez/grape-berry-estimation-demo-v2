package com.gaiaspa.metrics_detection.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Punto de entrada único para programar trabajos de sincronización con WorkManager.
 *
 * ## Rol en la arquitectura
 * Actúa como fachada sobre Jetpack WorkManager, ocultando los detalles de construcción
 * de [Constraints] y políticas de encolado ([ExistingPeriodicWorkPolicy], [ExistingWorkPolicy]).
 * Tanto la sincronización periódica como la manual delegan en [SyncWorker].
 *
 * ## Trabajos registrados
 * | Nombre               | Tipo      | Política        |
 * |----------------------|-----------|-----------------|
 * | `SyncLotesWork`      | Periódico | KEEP            |
 * | `ManualSyncWork`     | Una vez   | KEEP            |
 *
 * Ambos requieren conectividad de red ([NetworkType.CONNECTED]).
 */
object SyncManager {

    private const val PERIODIC_SYNC_WORK_NAME = "SyncLotesWork"
    private const val MANUAL_SYNC_WORK_NAME = "ManualSyncWork"

    /**
     * Programa una sincronización periódica que se ejecuta cada 15 minutos
     * cuando el dispositivo está conectado a Internet.
     *
     * @param context Contexto de aplicación usado para obtener la instancia de [WorkManager].
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Intervalo de 15 minutos según el comentario.
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

    /**
     * Programa una sincronización manual que se ejecuta una vez
     * cuando el dispositivo está conectado a Internet.
     *
     * @param context Contexto de aplicación usado para obtener la instancia de [WorkManager].
     */
    fun enqueueManualSync(context: Context) {
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
