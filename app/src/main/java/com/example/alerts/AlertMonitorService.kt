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
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AlertMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val subscribedStreams = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
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
        if (monitorJob == null) {
            startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        val container = AppContainer.get(applicationContext)
        val stateRepo = container.chartStateRepository
        val marketRepo = container.marketRepository
        val socketManager = container.socketManager

        monitorJob = serviceScope.launch {
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

                    if (alerts.isEmpty() || !prefs.backgroundMonitorEnabled) {
                        clearSubscriptions(socketManager)
                        stopSelf()
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
                            evaluateAndDispatchTick(alert, lastPrice, now, stateRepo, prefs.loudSoundEnabled, prefs.vibrationEnabled)
                        }
                    }
                }
            }

            // Evaluate Mark & Index tick alerts
            launch {
                socketManager.markPriceUpdates.collect { updates ->
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
                                evaluateAndDispatchTick(alert, price, now, stateRepo, prefs.loudSoundEnabled, prefs.vibrationEnabled)
                            }
                        }
                    }
                }
            }

            // Evaluate closed-candle & trendline-touch candle alerts
            launch {
                socketManager.klineUpdates.collect { update ->
                    if (!update.candle.isClosed) return@collect
                    val alerts = stateRepo.getActiveAlertsOnce()
                    if (alerts.isEmpty()) return@collect
                    val prefs = stateRepo.getPreferencesOnce()
                    val matching = alerts.filter {
                        it.symbol.equals(update.symbol, ignoreCase = true) &&
                            it.priceSource == update.priceSource &&
                            (it.condition.needsCandleClose || it.condition == com.example.domain.model.AlertCondition.TRENDLINE_TOUCH)
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
        val socketManager = AppContainer.get(applicationContext).socketManager
        clearSubscriptions(socketManager)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
