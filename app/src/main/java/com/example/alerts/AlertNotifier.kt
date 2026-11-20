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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

object AlertNotifier {
    const val CHANNEL_ALERTS = "binance_pro_price_alerts_loud_beep_v2"
    const val CHANNEL_SERVICE = "binance_pro_monitor_service"
    const val SERVICE_NOTIFICATION_ID = 9001

    private val vibrationPattern = longArrayOf(0L, 350L, 140L, 350L, 140L, 500L)
    private const val SAMPLE_RATE = 44_100

    @Volatile
    private var lastBeepTimestampMs: Long = 0L

    private val cachedBeepPcm: ShortArray by lazy { synthesizeLoudBeepWaveform() }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Price & Technical Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Loud beep alerts for price levels, breakouts, and trendlines"
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

        // Always play loud beep & vibration immediately even if POST_NOTIFICATIONS is not granted yet.
        if (loudSoundEnabled) {
            playLoudBeepAlert(context, force = false)
        }
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
            .setSilent(true)
            .setContentIntent(tapIntent)

        if (vibrationEnabled) {
            builder.setVibrate(vibrationPattern)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }

    /**
     * Synthesizes and plays a loud, high-clarity 4-pulse alarm beep ("BEEP-BEEP-BEEP-BEEEEP!").
     * Automatically routes via USAGE_ALARM (or USAGE_MEDIA if alarm volume is muted) so the beep
     * is reliably audible on all devices, with ToneGenerator fallback.
     */
    fun playLoudBeepAlert(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBeepTimestampMs < 1200L) return
        lastBeepTimestampMs = now

        val appCtx = context.applicationContext
        Thread {
            val playedViaTrack = playPcmLoudBeep(appCtx)
            if (!playedViaTrack) {
                playFallbackToneBeep()
            }
        }.apply {
            name = "AlertLoudBeepThread"
            isDaemon = true
            start()
        }
    }

    private fun playPcmLoudBeep(context: Context): Boolean {
        return runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val alarmVol = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
            val alarmMax = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 1).coerceAtLeast(1)
            val mediaVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val useMediaFallback = alarmVol == 0 && mediaVol > 0
            val usage = if (useMediaFallback || (alarmVol.toFloat() / alarmMax < 0.15f && mediaVol > 0)) {
                AudioAttributes.USAGE_MEDIA
            } else {
                AudioAttributes.USAGE_ALARM
            }

            val pcm = cachedBeepPcm
            val byteSize = pcm.size * 2
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(byteSize)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return false
            }

            try {
                track.setVolume(AudioTrack.getMaxVolume())
                val written = track.write(pcm, 0, pcm.size)
                if (written <= 0) {
                    track.release()
                    return false
                }
                track.play()
                val durationMs = (pcm.size * 1000L) / SAMPLE_RATE
                Thread.sleep(durationMs + 150L)
                true
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }.getOrElse { false }
    }

    /**
     * Generates 4 loud high-clarity dual-harmonic beeps (~2.0s total):
     * 3 crisp 200ms beeps (2000Hz + 3000Hz) followed by 1 longer 520ms confirmation beep (2400Hz + 3600Hz).
     */
    internal fun synthesizeLoudBeepWaveform(): ShortArray {
        data class Pulse(val startMs: Int, val durationMs: Int, val freq1: Double, val freq2: Double)

        val pulses = listOf(
            Pulse(startMs = 0, durationMs = 210, freq1 = 2000.0, freq2 = 3000.0),
            Pulse(startMs = 310, durationMs = 210, freq1 = 2000.0, freq2 = 3000.0),
            Pulse(startMs = 620, durationMs = 210, freq1 = 2000.0, freq2 = 3000.0),
            Pulse(startMs = 960, durationMs = 520, freq1 = 2400.0, freq2 = 3600.0)
        )
        val totalMs = 1580
        val totalSamples = (SAMPLE_RATE * totalMs) / 1000
        val buffer = ShortArray(totalSamples)
        val fadeSamples = (SAMPLE_RATE * 6) / 1000 // 6ms smooth edge ramp to eliminate clicks

        for (p in pulses) {
            val startSample = (SAMPLE_RATE * p.startMs) / 1000
            val lenSamples = (SAMPLE_RATE * p.durationMs) / 1000
            val endSample = min(totalSamples, startSample + lenSamples)
            for (i in startSample until endSample) {
                val local = i - startSample
                val fromEnd = endSample - 1 - i
                val env = when {
                    local < fadeSamples -> local.toDouble() / fadeSamples
                    fromEnd < fadeSamples -> fromEnd.toDouble() / fadeSamples
                    else -> 1.0
                }
                val t = local.toDouble() / SAMPLE_RATE.toDouble()
                // Rich dual-harmonic + slight soft-clipped punch for maximum loudness on phone speakers
                val raw = 0.65 * sin(2.0 * PI * p.freq1 * t) + 0.35 * sin(2.0 * PI * p.freq2 * t)
                val boosted = (raw * 1.35).coerceIn(-1.0, 1.0)
                val sample = (boosted * env * 32000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer[i] = sample.toShort()
            }
        }
        return buffer
    }

    private fun playFallbackToneBeep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            val handler = Handler(Looper.getMainLooper())
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260)
            handler.postDelayed({
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260)
            }, 340L)
            handler.postDelayed({
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 480)
                handler.postDelayed({ runCatching { tone.release() } }, 550L)
            }, 680L)
        }
    }

    @Suppress("DEPRECATION")
    fun triggerVibration(context: Context) {
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
