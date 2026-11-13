package com.example.alerts

import com.example.domain.model.AlertCondition
import com.example.domain.model.Candle
import com.example.domain.model.PriceAlert
import kotlin.math.abs

data class AlertEvaluationResult(
    val triggered: Boolean,
    val triggeredPrice: Double,
    val message: String,
    val nextEvaluatedPrice: Double
)

object AlertEvaluator {

    fun evaluateTick(
        alert: PriceAlert,
        currentPrice: Double,
        timestampMillis: Long = System.currentTimeMillis()
    ): AlertEvaluationResult {
        val prev = alert.lastEvaluatedPrice ?: currentPrice
        val target = alert.targetPrice
        val tolerance = (target * 0.0001).coerceAtLeast(1e-8)

        val matched = when (alert.condition) {
            AlertCondition.REACHES -> {
                (prev < target && currentPrice >= target) ||
                    (prev > target && currentPrice <= target) ||
                    abs(currentPrice - target) <= tolerance
            }
            AlertCondition.CROSSES_ABOVE -> {
                prev < target && currentPrice >= target
            }
            AlertCondition.CROSSES_BELOW -> {
                prev > target && currentPrice <= target
            }
            AlertCondition.HORIZ_BREAKOUT -> {
                (prev <= target && currentPrice > target) ||
                    (prev >= target && currentPrice < target)
            }
            AlertCondition.TRENDLINE_TOUCH -> {
                val tlPrice = alert.trendlinePriceAt(timestampMillis)
                if (tlPrice != null) {
                    val tlTol = (tlPrice * 0.0005).coerceAtLeast(1e-8)
                    (prev < tlPrice && currentPrice >= tlPrice) ||
                        (prev > tlPrice && currentPrice <= tlPrice) ||
                        abs(currentPrice - tlPrice) <= tlTol
                } else {
                    false
                }
            }
            AlertCondition.CANDLE_CLOSE_ABOVE,
            AlertCondition.CANDLE_CLOSE_BELOW -> false
        }

        return AlertEvaluationResult(
            triggered = matched,
            triggeredPrice = currentPrice,
            message = "${alert.symbol} ${alert.condition.label} at $currentPrice",
            nextEvaluatedPrice = currentPrice
        )
    }

    fun evaluateClosedCandle(
        alert: PriceAlert,
        closedCandle: Candle
    ): AlertEvaluationResult {
        if (!closedCandle.isClosed) {
            return AlertEvaluationResult(false, closedCandle.close, "", closedCandle.close)
        }
        val target = alert.targetPrice
        val matched = when (alert.condition) {
            AlertCondition.CANDLE_CLOSE_ABOVE -> closedCandle.close > target
            AlertCondition.CANDLE_CLOSE_BELOW -> closedCandle.close < target
            AlertCondition.TRENDLINE_TOUCH -> {
                val tlPrice = alert.trendlinePriceAt(closedCandle.closeTime)
                tlPrice != null && closedCandle.low <= tlPrice && closedCandle.high >= tlPrice
            }
            else -> false
        }
        return AlertEvaluationResult(
            triggered = matched,
            triggeredPrice = closedCandle.close,
            message = "${alert.symbol} ${alert.condition.label} (${closedCandle.close})",
            nextEvaluatedPrice = closedCandle.close
        )
    }
}
