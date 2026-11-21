package com.example.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms background price alert monitoring after device reboot or app update
 * so active alerts continue triggering even before the user reopens the app.
 */
class AlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = AppContainer.get(appContext).chartStateRepository
                val activeAlerts = repo.getActiveAlertsOnce()
                val prefs = repo.getPreferencesOnce()
                AlertScheduler.syncBackgroundMonitoring(
                    context = appContext,
                    hasActiveAlerts = activeAlerts.isNotEmpty(),
                    backgroundMonitorEnabled = prefs.backgroundMonitorEnabled,
                    fromBackground = true
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
