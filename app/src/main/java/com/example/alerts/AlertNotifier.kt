package com.example.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object AlertNotifier {
    const val CHANNEL_ALERTS = "binance_pro_price_alerts_custom_v1"
    const val CHANNEL_SERVICE = "binance_pro_monitor_service"
    const val SERVICE_NOTIFICATION_ID = 9001

    private val vibrationPattern = longArrayOf(0L, 350L, 140L, 350L, 140L, 500L)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Price & Technical Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Loud alerts for price levels, breakouts, and trendlines"
            enableVibration(true)
            vibrationPattern = this@AlertNotifier.vibrationPattern
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Market Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps live Binance Futures alert monitoring active"
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(serviceChannel)
    }

    fun buildMonitorNotification(context: Context, activeAlertCount: Int): Notification {
        ensureChannels(context)
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Futures Alert Monitor Active")
            .setContentText("Watching $activeAlertCount active alert${if (activeAlertCount == 1) "" else "s"} via Binance stream")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyAlertTriggered(
        context: Context,
        symbol: String,
        title: String,
        body: String,
        loudSoundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        ensureChannels(context)

        if (vibrationEnabled) {
            triggerVibration(context)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            symbol.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ALERT_SYMBOL, symbol)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)

        // Play the app's own alert sequence; suppress the device's default notification sound.
        builder.setSilent(true)
        if (loudSoundEnabled) playCustomAlertTone()

        if (vibrationEnabled) {
            builder.setVibrate(vibrationPattern)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }

    /** Plays a distinctive rising double-beep instead of the phone default ringtone. */
    private fun playCustomAlertTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            val handler = Handler(Looper.getMainLooper())
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            handler.postDelayed({
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 320)
                handler.postDelayed({ tone.release() }, 420L)
            }, 230L)
        }
    }
    @Suppress("DEPRECATION")
    private fun triggerVibration(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
            } else {
                vibrator.vibrate(vibrationPattern, -1)
            }
        }
    }

    const val EXTRA_ALERT_SYMBOL = "extra_alert_symbol"
}
