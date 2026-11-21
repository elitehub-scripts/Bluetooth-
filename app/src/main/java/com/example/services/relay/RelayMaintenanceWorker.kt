package com.example.services.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.BluetoothGlobalShareApp
import java.io.File
import java.util.concurrent.TimeUnit

class RelayMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as? BluetoothGlobalShareApp ?: return Result.success()
            val container = app.container
            val now = System.currentTimeMillis()
            container.relayEngine.cleanupExpired(now)

            // Prune relay history older than 14 days
            container.database.relayHistoryDao().pruneOlderThan(now - 14L * 24 * 60 * 60 * 1000)

            // Clean up orphaned temp partial files older than 7 days
            val tempDir = File(applicationContext.filesDir, "received_temp")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > 7L * 24 * 60 * 60 * 1000 && file.name.endsWith(".part")) {
                        file.delete()
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "bgs_relay_maintenance_periodic"

        fun schedule(context: Context) {
            val wm = try {
                WorkManager.getInstance(context)
            } catch (_: IllegalStateException) {
                try {
                    WorkManager.initialize(
                        context.applicationContext,
                        androidx.work.Configuration.Builder().build()
                    )
                    WorkManager.getInstance(context)
                } catch (_: Exception) {
                    return
                }
            }
            val request = PeriodicWorkRequestBuilder<RelayMaintenanceWorker>(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

class BootMaintenanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            RelayMaintenanceWorker.schedule(context)
        }
    }
}
