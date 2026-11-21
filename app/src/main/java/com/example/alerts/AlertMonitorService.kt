package com.example.alerts

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.AppContainer
import com.example.data.remote.BinanceSocketManager
import com.example.data.remote.BybitPayloadParser
import com.example.data.remote.MarketEndpointMode
import com.example.data.repository.CandleAggregator
import com.example.domain.model.AlertCondition
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class AlertMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val subscribedStreams = mutableSetOf<String>()
    private val lastWsTickMs = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val notification = AlertNotifier.buildMonitorNotification(this, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                AlertNotifier.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AlertNotifier.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob == null || monitorJob?.isActive != true) {
            startMonitoring()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When user swipes app away from recents, immediately arm a WorkManager fallback
        // in addition to START_STICKY + android:stopWithTask="false" so alerts never drop.
        AlertScheduler.scheduleImmediateFallbackCheck(applicationContext, delaySeconds = 5L)
    }

    private fun startMonitoring() {
        val container = AppContainer.get(applicationContext)
        val stateRepo = container.chartStateRepository
        val marketRepo = container.marketRepository
        val socketManager = container.socketManager

        monitorJob = serviceScope.launch {
            // Ensure endpoint mode is initialized even after cold process/service restart
            if (marketRepo.tickers.value.isEmpty()) {
                runCatching { marketRepo.refreshMarketOverview() }
            }

            // Observe active alerts, preferences, and endpointMode to keep subscriptions minimal and accurate
            launch {
                combine(
                    stateRepo.activeAlerts,
                    stateRepo.timeframes,
                    stateRepo.preferences,
                    marketRepo.endpointMode
                ) { alerts, timeframes, prefs, mode ->
                    listOf(alerts, timeframes, prefs, mode)
                }.collectLatest { tuple ->
                    @Suppress("UNCHECKED_CAST")
                    val alerts = tuple[0] as List<PriceAlert>
                    @Suppress("UNCHECKED_CAST")
                    val timeframes = tuple[1] as List<Timeframe>
                    val prefs = tuple[2] as com.example.domain.model.ChartPreferences
                    val mode = tuple[3] as MarketEndpointMode

                    if (!prefs.backgroundMonitorEnabled) {
                        clearSubscriptions(socketManager)
                        stopSelf()
                        return@collectLatest
                    }

                    if (alerts.isEmpty()) {
                        // Double-check database once to avoid stopping on transient initial flow emission
                        delay(1_200L)
                        if (stateRepo.getActiveAlertsOnce().isEmpty()) {
                            clearSubscriptions(socketManager)
                            stopSelf()
                        }
                        return@collectLatest
                    }

                    val updatedNotification = AlertNotifier.buildMonitorNotification(
                        this@AlertMonitorService,
                        alerts.size
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this@AlertMonitorService,
                            AlertNotifier.SERVICE_NOTIFICATION_ID,
                            updatedNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    }

                    val desiredStreams = buildDesiredStreams(alerts, timeframes, mode)
                    updateSubscriptions(socketManager, desiredStreams)
                }
            }

            // Evaluate tick alerts on live ticker updates
            launch {
                socketManager.tickerUpdates.collect { updates ->
                    if (updates.isNotEmpty()) {
                        lastWsTickMs.set(System.currentTimeMillis())
                    }
                    val alerts = stateRepo.getActiveAlertsOnce()
                    if (alerts.isEmpty()) return@collect
                    val prefs = stateRepo.getPreferencesOnce()
                    val now = System.currentTimeMillis()
                    for (u in updates) {
                        val lastPrice = u.lastPrice ?: continue
                        val matching = alerts.filter {
                            it.symbol.equals(u.symbol, ignoreCase = true) &&
                                it.priceSource == PriceSource.LAST &&
                                !it.condition.needsCandleClose
                        }
                        for (alert in matching) {
                            evaluateAndDispatchTick(
                                alert,
                                lastPrice,
                                now,
                                stateRepo,
                                prefs.loudSoundEnabled,
                                prefs.vibrationEnabled
                            )
                        }
                    }
                }
            }

            // Evaluate Mark & Index tick alerts
            launch {
                socketManager.markPriceUpdates.collect { updates ->
                    if (updates.isNotEmpty()) {
                        lastWsTickMs.set(System.currentTimeMillis())
                    }
                    val alerts = stateRepo.getActiveAlertsOnce()
                    if (alerts.isEmpty()) return@collect
                    val prefs = stateRepo.getPreferencesOnce()
                    val now = System.currentTimeMillis()
                    for (u in updates) {
                        val matching = alerts.filter {
                            it.symbol.equals(u.symbol, ignoreCase = true) &&
                                (it.priceSource == PriceSource.MARK || it.priceSource == PriceSource.INDEX) &&
                                !it.condition.needsCandleClose
                        }
                        for (alert in matching) {
                            val price = if (alert.priceSource == PriceSource.MARK) u.markPrice else u.indexPrice
                            if (price != null) {
                                evaluateAndDispatchTick(
                                    alert,
                                    price,
                                    now,
                                    stateRepo,
                                    prefs.loudSoundEnabled,
                                    prefs.vibrationEnabled
                                )
                            }
                        }
                    }
                }
            }

            // Evaluate closed-candle & trendline-touch candle alerts
            launch {
                socketManager.klineUpdates.collect { update ->
                    lastWsTickMs.set(System.currentTimeMillis())
                    if (!update.candle.isClosed) return@collect
                    val alerts = stateRepo.getActiveAlertsOnce()
                    if (alerts.isEmpty()) return@collect
                    val prefs = stateRepo.getPreferencesOnce()
                    val matching = alerts.filter {
                        it.symbol.equals(update.symbol, ignoreCase = true) &&
                            it.priceSource == update.priceSource &&
                            (it.condition.needsCandleClose || it.condition == AlertCondition.TRENDLINE_TOUCH)
                    }
                    for (alert in matching) {
                        val res = AlertEvaluator.evaluateClosedCandle(alert, update.candle)
                        if (res.triggered) {
                            stateRepo.recordAlertTriggered(alert, res.triggeredPrice, res.message)
                            AlertNotifier.notifyAlertTriggered(
                                context = this@AlertMonitorService,
                                symbol = alert.symbol,
                                title = "${alert.symbol} Alert Triggered",
                                body = res.message,
                                loudSoundEnabled = prefs.loudSoundEnabled,
                                vibrationEnabled = prefs.vibrationEnabled
                            )
                        }
                    }
                }
            }

            // 6-second REST Polling Fallback: guarantees alerts fire even if WebSocket stalls or is geo-blocked in background
            launch {
                while (isActive) {
                    delay(6_000L)
                    val alerts = stateRepo.getActiveAlertsOnce()
                    if (alerts.isEmpty()) continue
                    val prefs = stateRepo.getPreferencesOnce()
                    if (!prefs.backgroundMonitorEnabled) continue

                    val now = System.currentTimeMillis()
                    val wsStale = now - lastWsTickMs.get() > 5_000L
                    val hasCandleAlerts = alerts.any { it.condition.needsCandleClose }
                    if (!wsStale && !hasCandleAlerts) continue

                    runCatching {
                        if (wsStale) {
                            marketRepo.refreshMarketOverview()
                        }
                        val tickers = marketRepo.tickers.value
                        val timeframes = stateRepo.timeframes.first()
                        val evalTime = System.currentTimeMillis()

                        for (alert in alerts) {
                            if (!alert.condition.needsCandleClose && wsStale) {
                                val ticker = tickers[alert.symbol]
                                val price = if (ticker != null) {
                                    when (alert.priceSource) {
                                        PriceSource.LAST -> ticker.lastPrice
                                        PriceSource.MARK -> ticker.markPrice
                                        PriceSource.INDEX -> ticker.indexPrice
                                    }
                                } else {
                                    val tf1m = Timeframe.DEFAULTS.first { it.id == "1m" }
                                    marketRepo.fetchKlines(
                                        symbol = alert.symbol,
                                        pair = alert.symbol,
                                        priceSource = alert.priceSource,
                                        timeframe = tf1m
                                    ).getOrNull()?.lastOrNull()?.close
                                }
                                if (price != null && price > 0.0) {
                                    evaluateAndDispatchTick(
                                        alert = alert,
                                        price = price,
                                        now = evalTime,
                                        stateRepo = stateRepo,
                                        loudSound = prefs.loudSoundEnabled,
                                        vibration = prefs.vibrationEnabled
                                    )
                                }
                            } else if (alert.condition.needsCandleClose) {
                                val tf = timeframes.firstOrNull { it.id == alert.timeframeId }
                                    ?: Timeframe.DEFAULTS.first { it.id == "15m" }
                                val candles = marketRepo.fetchKlines(
                                    symbol = alert.symbol,
                                    pair = alert.symbol,
                                    priceSource = alert.priceSource,
                                    timeframe = tf
                                ).getOrNull() ?: continue
                                val lastClosed = candles.lastOrNull { it.isClosed } ?: continue
                                val res = AlertEvaluator.evaluateClosedCandle(alert, lastClosed)
                                if (res.triggered) {
                                    stateRepo.recordAlertTriggered(alert, res.triggeredPrice, res.message)
                                    AlertNotifier.notifyAlertTriggered(
                                        context = this@AlertMonitorService,
                                        symbol = alert.symbol,
                                        title = "${alert.symbol} Alert Triggered",
                                        body = res.message,
                                        loudSoundEnabled = prefs.loudSoundEnabled,
                                        vibrationEnabled = prefs.vibrationEnabled
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun evaluateAndDispatchTick(
        alert: PriceAlert,
        price: Double,
        now: Long,
        stateRepo: com.example.data.repository.ChartStateRepository,
        loudSound: Boolean,
        vibration: Boolean
    ) {
        val result = AlertEvaluator.evaluateTick(alert, price, now)
        if (result.triggered) {
            stateRepo.recordAlertTriggered(alert, result.triggeredPrice, result.message)
            AlertNotifier.notifyAlertTriggered(
                context = this,
                symbol = alert.symbol,
                title = "${alert.symbol} Alert Triggered",
                body = result.message,
                loudSoundEnabled = loudSound,
                vibrationEnabled = vibration
            )
        } else if (alert.lastEvaluatedPrice != result.nextEvaluatedPrice) {
            stateRepo.updateAlertLastEvaluatedPrice(alert.id, result.nextEvaluatedPrice)
        }
    }

    private fun buildDesiredStreams(
        alerts: List<PriceAlert>,
        timeframes: List<Timeframe>,
        endpointMode: MarketEndpointMode
    ): Set<String> {
        val streams = mutableSetOf<String>()
        if (endpointMode == MarketEndpointMode.BYBIT_LINEAR) {
            alerts.forEach { alert ->
                if (alert.condition.needsCandleClose) {
                    val tf = timeframes.firstOrNull { it.id == alert.timeframeId }
                        ?: Timeframe.DEFAULTS.first { it.id == "15m" }
                    val (baseInterval, _) = CandleAggregator.pickBaseInterval(tf, endpointMode)
                    streams.add(
                        BinanceSocketManager.bybitKlineTopic(
                            alert.symbol,
                            BybitPayloadParser.toBybitInterval(baseInterval)
                        )
                    )
                } else {
                    streams.add(BinanceSocketManager.bybitTickerTopic(alert.symbol))
                }
            }
            return streams
        }

        if (alerts.any { it.priceSource == PriceSource.LAST && !it.condition.needsCandleClose }) {
            streams.add("!ticker@arr")
        }
        if (alerts.any { (it.priceSource == PriceSource.MARK || it.priceSource == PriceSource.INDEX) && !it.condition.needsCandleClose }) {
            streams.add("!markPrice@arr@1s")
        }
        alerts.filter { it.condition.needsCandleClose }.forEach { alert ->
            val tf = timeframes.firstOrNull { it.id == alert.timeframeId }
                ?: Timeframe.DEFAULTS.first { it.id == "15m" }
            val (baseInterval, _) = CandleAggregator.pickBaseInterval(tf, endpointMode)
            streams.add(
                BinanceSocketManager.klineStreamName(
                    symbol = alert.symbol,
                    pair = alert.symbol,
                    priceSource = alert.priceSource,
                    nativeInterval = baseInterval
                )
            )
        }
        return streams
    }

    private fun updateSubscriptions(socketManager: BinanceSocketManager, desired: Set<String>) {
        val toUnsub = subscribedStreams - desired
        val toSub = desired - subscribedStreams
        if (toUnsub.isNotEmpty()) {
            socketManager.unsubscribe(toUnsub)
            subscribedStreams.removeAll(toUnsub)
        }
        if (toSub.isNotEmpty()) {
            socketManager.subscribe(toSub)
            subscribedStreams.addAll(toSub)
        }
    }

    private fun clearSubscriptions(socketManager: BinanceSocketManager) {
        if (subscribedStreams.isNotEmpty()) {
            socketManager.unsubscribe(subscribedStreams.toList())
            subscribedStreams.clear()
        }
    }

    override fun onDestroy() {
        isRunning = false
        val socketManager = AppContainer.get(applicationContext).socketManager
        clearSubscriptions(socketManager)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
