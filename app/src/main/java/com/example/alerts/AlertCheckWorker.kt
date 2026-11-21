package com.example.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.AppContainer
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
import kotlinx.coroutines.flow.first

class AlertCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        val stateRepo = container.chartStateRepository
        val marketRepo = container.marketRepository

        val activeAlerts = stateRepo.getActiveAlertsOnce()
        if (activeAlerts.isEmpty()) return Result.success()

        val prefs = stateRepo.getPreferencesOnce()
        val refreshResult = marketRepo.refreshMarketOverview()
        val tickers = marketRepo.tickers.value
        val now = System.currentTimeMillis()
        val timeframes = stateRepo.timeframes.first()
        var evaluatedAny = false

        for (alert in activeAlerts) {
            if (!alert.condition.needsCandleClose) {
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
                    evaluatedAny = true
                    val eval = AlertEvaluator.evaluateTick(alert, price, now)
                    if (eval.triggered) {
                        stateRepo.recordAlertTriggered(alert, eval.triggeredPrice, eval.message)
                        AlertNotifier.notifyAlertTriggered(
                            context = applicationContext,
                            symbol = alert.symbol,
                            title = "${alert.symbol} Alert Triggered",
                            body = eval.message,
                            loudSoundEnabled = prefs.loudSoundEnabled,
                            vibrationEnabled = prefs.vibrationEnabled
                        )
                    } else if (alert.lastEvaluatedPrice != eval.nextEvaluatedPrice) {
                        stateRepo.updateAlertLastEvaluatedPrice(alert.id, eval.nextEvaluatedPrice)
                    }
                }
            } else {
                val tf = timeframes.firstOrNull { it.id == alert.timeframeId }
                    ?: Timeframe.DEFAULTS.first { it.id == "15m" }
                val candles = marketRepo.fetchKlines(
                    symbol = alert.symbol,
                    pair = alert.symbol,
                    priceSource = alert.priceSource,
                    timeframe = tf
                ).getOrNull() ?: continue
                val lastClosed = candles.lastOrNull { it.isClosed } ?: continue
                evaluatedAny = true
                val eval = AlertEvaluator.evaluateClosedCandle(alert, lastClosed)
                if (eval.triggered) {
                    stateRepo.recordAlertTriggered(alert, eval.triggeredPrice, eval.message)
                    AlertNotifier.notifyAlertTriggered(
                        context = applicationContext,
                        symbol = alert.symbol,
                        title = "${alert.symbol} Alert Triggered",
                        body = eval.message,
                        loudSoundEnabled = prefs.loudSoundEnabled,
                        vibrationEnabled = prefs.vibrationEnabled
                    )
                }
            }
        }

        val remainingAlerts = stateRepo.getActiveAlertsOnce()
        if (remainingAlerts.isNotEmpty() && prefs.backgroundMonitorEnabled && !AlertMonitorService.isRunning) {
            // Keep fast background fallback alive if OS stopped the foreground service while app is closed
            AlertScheduler.scheduleImmediateFallbackCheck(applicationContext, delaySeconds = 45L)
        }

        return if (!evaluatedAny && refreshResult.isFailure) Result.retry() else Result.success()
    }
}
