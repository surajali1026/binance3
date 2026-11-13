package com.example.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.AppContainer
import com.example.domain.model.PriceSource
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
        if (refreshResult.isFailure) return Result.retry()

        val tickers = marketRepo.tickers.value
        val now = System.currentTimeMillis()
        val timeframes = stateRepo.timeframes.first()

        for (alert in activeAlerts) {
            val ticker = tickers[alert.symbol]
            if (!alert.condition.needsCandleClose && ticker != null) {
                val price = when (alert.priceSource) {
                    PriceSource.LAST -> ticker.lastPrice
                    PriceSource.MARK -> ticker.markPrice
                    PriceSource.INDEX -> ticker.indexPrice
                }
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
                } else {
                    stateRepo.updateAlertLastEvaluatedPrice(alert.id, eval.nextEvaluatedPrice)
                }
            } else if (alert.condition.needsCandleClose) {
                val tf = timeframes.firstOrNull { it.id == alert.timeframeId }
                    ?: com.example.domain.model.Timeframe.DEFAULTS.first { it.id == "15m" }
                val candles = marketRepo.fetchKlines(
                    symbol = alert.symbol,
                    pair = alert.symbol,
                    priceSource = alert.priceSource,
                    timeframe = tf
                ).getOrNull() ?: continue
                val lastClosed = candles.lastOrNull { it.isClosed } ?: continue
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
        return Result.success()
    }
}
