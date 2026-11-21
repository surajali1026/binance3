package com.example.alerts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AlertScheduler {
    private const val PERIODIC_WORK_NAME = "binance_pro_alert_periodic_check"
    private const val FAST_FALLBACK_WORK_NAME = "binance_pro_alert_fast_fallback"

    fun syncBackgroundMonitoring(
        context: Context,
        hasActiveAlerts: Boolean,
        backgroundMonitorEnabled: Boolean,
        fromBackground: Boolean = false
    ) {
        val appContext = context.applicationContext
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull()
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
                val started = runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, AlertMonitorService::class.java)
                    )
                    true
                }.getOrDefault(false)

                if (!started || fromBackground) {
                    scheduleImmediateFallbackCheck(appContext, delaySeconds = if (started) 45L else 10L)
                }
            } else {
                runCatching { appContext.stopService(Intent(appContext, AlertMonitorService::class.java)) }
            }
        } else {
            workManager?.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager?.cancelUniqueWork(FAST_FALLBACK_WORK_NAME)
            runCatching { appContext.stopService(Intent(appContext, AlertMonitorService::class.java)) }
        }
    }

    fun scheduleImmediateFallbackCheck(context: Context, delaySeconds: Long = 15L) {
        val workManager = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<AlertCheckWorker>()
            .setConstraints(constraints)
        if (delaySeconds <= 0L) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        } else {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        workManager.enqueueUniqueWork(
            FAST_FALLBACK_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
    }
}
