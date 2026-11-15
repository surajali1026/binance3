package com.example.ui.chart

import androidx.compose.ui.geometry.Offset
import com.example.domain.model.Candle
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPoint
import com.example.domain.model.DrawingType
import com.example.domain.model.PriceAlert
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ChartNumberFormatter {
    fun formatPrice(price: Double, precision: Int): String {
        if (price.isNaN() || price.isInfinite()) return "--"
        val p = precision.coerceIn(0, 8)
        val pattern = if (p == 0) "#,##0" else "#,##0." + "0".repeat(p)
        return DecimalFormat(pattern).format(price)
    }

    fun formatPercent(pct: Double): String {
        if (pct.isNaN() || pct.isInfinite()) return "0.00%"
        val sign = if (pct > 0) "+" else ""
        return String.format(Locale.US, "%s%.2f%%", sign, pct)
    }

    fun formatCompactVolume(vol: Double): String {
        return when {
            vol >= 1_000_000_000.0 -> String.format(Locale.US, "$%.1fB", vol / 1_000_000_000.0)
            vol >= 100_000_000.0 -> String.format(Locale.US, "$%.0fM", vol / 1_000_000.0)
            vol >= 1_000_000.0 -> String.format(Locale.US, "$%.1fM", vol / 1_000_000.0)
            vol >= 1_000.0 -> String.format(Locale.US, "$%.1fK", vol / 1_000.0)
            else -> String.format(Locale.US, "$%.0f", vol)
        }
    }

    fun formatCountdown(remainingMillis: Long): String {
        val totalSec = (remainingMillis / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return when {
            days > 0 -> String.format(Locale.US, "%dd %02d:%02d", days, hours, mins)
            hours > 0 -> String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
            else -> String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatAxisTime(timeMillis: Long, timeframeDurationMs: Long): String {
        val pattern = when {
            timeframeDurationMs >= 86_400_000L -> "MMM dd"
            timeframeDurationMs >= 3_600_000L -> "MM/dd HH:mm"
            else -> "HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.US).format(Date(timeMillis))
    }

    fun formatCrosshairTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timeMillis))
    }

    fun formatDuration(durationMs: Long): String {
        val absMs = abs(durationMs)
        val mins = absMs / 60_000L
        val hours = mins / 60L
        val days = hours / 24L
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${mins % 60}m"
            else -> "${mins}m"
        }
    }

    fun formatListingSchedule(onboardDateMs: Long): String {
        if (onboardDateMs <= 0L) return "TBA"
        return SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(onboardDateMs))
    }

    fun formatListingSubtext(
        onboardDateMs: Long,
        status: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        val isPendingStatus = status.equals("PENDING_TRADING", ignoreCase = true) ||
            status.equals("PRE_TRADING", ignoreCase = true) ||
            status.equals("PreLaunch", ignoreCase = true)
        if (onboardDateMs <= 0L) {
            return if (isPendingStatus) "Upcoming • Time TBA" else null
        }
        val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(onboardDateMs))
        val diff = onboardDateMs - nowMillis
        if (diff > 0L || isPendingStatus) {
            if (diff <= 0L) return "Listing • $dateStr"
            val totalMins = (diff / 60_000L).coerceAtLeast(1L)
            val days = totalMins / 1440L
            val hours = (totalMins % 1440L) / 60L
            val mins = totalMins % 60L
            val countdown = when {
                days > 0L -> "in ${days}d ${hours}h"
                hours > 0L -> "in ${hours}h ${mins}m"
                else -> "in ${mins}m"
            }
            return "Opens $dateStr • $countdown"
        }
        val elapsed = nowMillis - onboardDateMs
        val totalMins = (elapsed / 60_000L).coerceAtLeast(1L)
        val hours = totalMins / 60L
        val days = hours / 24L
        return when {
            days > 365L -> {
                val withYear = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(onboardDateMs))
                "Listed $withYear"
            }
            days >= 1L -> "Listed $dateStr • ${days}d ago"
            hours >= 1L -> "Listed $dateStr • ${hours}h ago"
            else -> "Listed $dateStr • ${totalMins}m ago"
        }
    }
}

data class ChartViewportState(
    val barSpacingPx: Float = 24f,
    val rightOffsetBars: Float = 8f,
    val priceMin: Double = 0.0,
    val priceMax: Double = 100.0,
    val isAutoFit: Boolean = true
)

class ChartCoordinateMapper(
    private val candles: List<Candle>,
    private val timeframeDurationMs: Long,
    private val plotWidthPx: Float,
    private val plotHeightPx: Float,
    private val viewport: ChartViewportState
) {
    private val spacing = viewport.barSpacingPx.coerceIn(4f, 120f)
    private val totalBars = candles.size

    fun indexToX(index: Float): Float {
        if (totalBars == 0) return plotWidthPx / 2f
        val barsFromLast = (totalBars - 1) - index + viewport.rightOffsetBars
        return plotWidthPx - barsFromLast * spacing
    }

    fun xToFloatIndex(x: Float): Float {
        if (totalBars == 0) return 0f
        val barsFromRight = (plotWidthPx - x) / spacing
        return (totalBars - 1) + viewport.rightOffsetBars - barsFromRight
    }

    fun timeToX(timeMillis: Long): Float {
        if (candles.isEmpty()) return plotWidthPx / 2f
        val first = candles.first()
        val last = candles.last()
        return when {
            timeMillis <= first.openTime -> {
                val diffBars = (first.openTime - timeMillis).toFloat() / timeframeDurationMs.toFloat()
                indexToX(0f - diffBars)
            }
            timeMillis >= last.openTime -> {
                val diffBars = (timeMillis - last.openTime).toFloat() / timeframeDurationMs.toFloat()
                indexToX((candles.size - 1).toFloat() + diffBars)
            }
            else -> {
                // Binary search closest candle
                var lo = 0
                var hi = candles.lastIndex
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val cTime = candles[mid].openTime
                    if (cTime == timeMillis) return indexToX(mid.toFloat())
                    if (cTime < timeMillis) lo = mid + 1 else hi = mid - 1
                }
                val leftIdx = (lo - 1).coerceIn(0, candles.lastIndex)
                val rightIdx = lo.coerceIn(0, candles.lastIndex)
                if (leftIdx == rightIdx) {
                    indexToX(leftIdx.toFloat())
                } else {
                    val t0 = candles[leftIdx].openTime
                    val t1 = candles[rightIdx].openTime
                    val frac = if (t1 == t0) 0f else ((timeMillis - t0).toFloat() / (t1 - t0).toFloat())
                    indexToX(leftIdx.toFloat() + frac)
                }
            }
        }
    }

    fun xToTime(x: Float): Long {
        if (candles.isEmpty()) return System.currentTimeMillis()
        val idxF = xToFloatIndex(x)
        val idx = idxF.toInt()
        return when {
            idx < 0 -> candles.first().openTime + (idxF * timeframeDurationMs).toLong()
            idx >= candles.lastIndex -> {
                val extra = idxF - candles.lastIndex
                candles.last().openTime + (extra * timeframeDurationMs).toLong()
            }
            else -> {
                val frac = idxF - idx
                val t0 = candles[idx].openTime
                val t1 = candles[idx + 1].openTime
                t0 + ((t1 - t0) * frac).toLong()
            }
        }
    }

    fun priceToY(price: Double): Float {
        val span = (viewport.priceMax - viewport.priceMin).takeIf { it > 0.0 } ?: 1.0
        val norm = (viewport.priceMax - price) / span
        return (norm * plotHeightPx).toFloat()
    }

    fun yToPrice(y: Float): Double {
        val span = (viewport.priceMax - viewport.priceMin).takeIf { it > 0.0 } ?: 1.0
        val norm = (y / plotHeightPx.coerceAtLeast(1f)).toDouble()
        return viewport.priceMax - norm * span
    }

    fun screenToChartPoint(offset: Offset): ChartPoint {
        return ChartPoint(
            timeMillis = xToTime(offset.x),
            price = yToPrice(offset.y)
        )
    }

    fun visibleIndexRange(): IntRange {
        if (candles.isEmpty()) return IntRange.EMPTY
        val start = floor(xToFloatIndex(0f)).toInt().coerceIn(0, candles.lastIndex)
        val end = ceil(xToFloatIndex(plotWidthPx)).toInt().coerceIn(0, candles.lastIndex)
        return if (start <= end) start..end else IntRange.EMPTY
    }

    fun computePriceTicks(targetCount: Int = 7): List<Double> {
        val minP = viewport.priceMin
        val maxP = viewport.priceMax
        val range = maxP - minP
        if (range <= 0.0 || range.isNaN() || range.isInfinite()) return emptyList()
        val roughStep = range / targetCount.coerceAtLeast(2)
        val mag = 10.0.pow(floor(log10(roughStep)))
        val norm = roughStep / mag
        val niceNorm = when {
            norm < 1.5 -> 1.0
            norm < 3.0 -> 2.0
            norm < 7.0 -> 5.0
            else -> 10.0
        }
        val step = niceNorm * mag
        val firstTick = ceil(minP / step) * step
        val ticks = mutableListOf<Double>()
        var current = firstTick
        var guard = 0
        while (current <= maxP && guard < 30) {
            ticks.add(current)
            current += step
            guard++
        }
        return ticks
    }

    fun hitTestAlert(offset: Offset, alerts: List<PriceAlert>, thresholdPx: Float = 28f): PriceAlert? {
        return alerts.firstOrNull { alert ->
            val y = priceToY(alert.targetPrice)
            abs(offset.y - y) <= thresholdPx
        }
    }

    fun hitTestDrawing(offset: Offset, drawings: List<ChartDrawing>, thresholdPx: Float = 30f): ChartDrawing? {
        for (d in drawings.asReversed()) {
            when (d.type) {
                DrawingType.HORIZONTAL_LINE -> {
                    val pt = d.points.firstOrNull() ?: continue
                    if (abs(offset.y - priceToY(pt.price)) <= thresholdPx) return d
                }
                DrawingType.TRENDLINE -> {
                    if (d.points.size < 2) continue
                    val p1 = Offset(timeToX(d.points[0].timeMillis), priceToY(d.points[0].price))
                    val p2 = Offset(timeToX(d.points[1].timeMillis), priceToY(d.points[1].price))
                    if (distancePointToSegment(offset, p1, p2) <= thresholdPx) return d
                }
                DrawingType.RECTANGLE -> {
                    if (d.points.size < 2) continue
                    val x1 = timeToX(d.points[0].timeMillis)
                    val y1 = priceToY(d.points[0].price)
                    val x2 = timeToX(d.points[1].timeMillis)
                    val y2 = priceToY(d.points[1].price)
                    val left = min(x1, x2) - thresholdPx * 0.4f
                    val right = max(x1, x2) + thresholdPx * 0.4f
                    val top = min(y1, y2) - thresholdPx * 0.4f
                    val bottom = max(y1, y2) + thresholdPx * 0.4f
                    if (offset.x in left..right && offset.y in top..bottom) return d
                }
            }
        }
        return null
    }

    fun hitTestDrawingHandle(offset: Offset, drawing: ChartDrawing, thresholdPx: Float = 36f): Int? {
        drawing.points.forEachIndexed { idx, pt ->
            val p = Offset(timeToX(pt.timeMillis), priceToY(pt.price))
            if (hypot(offset.x - p.x, offset.y - p.y) <= thresholdPx) {
                return idx
            }
        }
        return null
    }

    private fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    companion object {
        fun computeAutoFitBounds(
            candles: List<Candle>,
            visibleRange: IntRange
        ): Pair<Double, Double> {
            if (candles.isEmpty() || visibleRange.isEmpty()) return 0.0 to 100.0
            var low = Double.MAX_VALUE
            var high = -Double.MAX_VALUE
            for (i in visibleRange) {
                val c = candles[i]
                if (c.low < low) low = c.low
                if (c.high > high) high = c.high
            }
            if (low == Double.MAX_VALUE || high == -Double.MAX_VALUE) return 0.0 to 100.0
            val span = (high - low).takeIf { it > 0.0 } ?: (high * 0.02).coerceAtLeast(1e-6)
            val topPad = span * 0.10
            val bottomPad = span * 0.22
            return (low - bottomPad) to (high + topPad)
        }
    }
}
