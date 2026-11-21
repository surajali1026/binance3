package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.alerts.AlertNotifier
import com.example.alerts.AlertScheduler
import com.example.ui.navigation.MainNavigation
import com.example.ui.theme.BinanceProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationSymbol by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification posts automatically once granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        requestNotificationPermissionIfNeeded()
        notificationSymbol = intent?.getStringExtra(AlertNotifier.EXTRA_ALERT_SYMBOL)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            BinanceProTheme(darkTheme = true) {
                MainNavigation(initialSymbolFromNotification = notificationSymbol)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Sync alert monitoring from foreground Activity context so ForegroundService starts cleanly on Android 12+
        lifecycleScope.launch(Dispatchers.IO) {
            val container = AppContainer.get(applicationContext)
            val hasAlerts = container.chartStateRepository.getActiveAlertsOnce().isNotEmpty()
            val prefs = container.chartStateRepository.getPreferencesOnce()
            AlertScheduler.syncBackgroundMonitoring(
                context = applicationContext,
                hasActiveAlerts = hasAlerts,
                backgroundMonitorEnabled = prefs.backgroundMonitorEnabled,
                fromBackground = false
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sym = intent.getStringExtra(AlertNotifier.EXTRA_ALERT_SYMBOL)
        if (!sym.isNullOrBlank()) {
            notificationSymbol = sym
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
