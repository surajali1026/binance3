package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.alerts.AlertNotifier
import com.example.ui.navigation.MainNavigation
import com.example.ui.theme.BinanceProTheme

class MainActivity : ComponentActivity() {
    private var notificationSymbol by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sym = intent.getStringExtra(AlertNotifier.EXTRA_ALERT_SYMBOL)
        if (!sym.isNullOrBlank()) {
            notificationSymbol = sym
        }
    }
}
