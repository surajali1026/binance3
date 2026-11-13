package com.example.alerts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AlertScheduler {
    private const val PERIODIC_WORK_NAME = "binance_pro_alert_periodic_check"

    fun syncBackgroundMonitoring(
        context: Context,
        hasActiveAlerts: Boolean,
        backgroundMonitorEnabled: Boolean
    ) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull()
        if (hasActiveAlerts) {
            if (workManager != null) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val periodicRequest = PeriodicWorkRequestBuilder<AlertCheckWorker>(
                    15, TimeUnit.MINUTES
                ).setConstraints(constraints).build()

                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            }

            if (backgroundMonitorEnabled) {
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AlertMonitorService::class.java)
                    )
                }
            } else {
                runCatching { context.stopService(Intent(context, AlertMonitorService::class.java)) }
            }
        } else {
            workManager?.cancelUniqueWork(PERIODIC_WORK_NAME)
            runCatching { context.stopService(Intent(context, AlertMonitorService::class.java)) }
        }
    }
}
