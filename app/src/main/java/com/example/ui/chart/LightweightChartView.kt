package com.example.ui.chart

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.Candle
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPoint
import com.example.domain.model.DrawingTool
import com.example.domain.model.DrawingType
import com.example.domain.model.LineStyle
import com.example.domain.model.PriceAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class ChartJavascriptBridge(
    var onTap: (Long, Double, String?, String?) -> Unit,
    var onCrosshair: (Long, Double) -> Unit,
    var onDismissCrosshair: () -> Unit,
    var onDoubleTapReset: () -> Unit,
    var onPriceAxisTap: (Double) -> Unit,
    var onQuickAlert: (Double) -> Unit,
    var onMoveDrawingAnchor: (String, Int, ChartPoint) -> Unit,
    var onMoveWholeDrawing: (String, Long, Double) -> Unit,
    var onMoveAlertPrice: (String, Double) -> Unit,
    var onManualInteraction: () -> Unit,
    var onMultiExchLevelTap: (String) -> Unit,
    var onChartReady: () -> Unit,
    var onLibraryLoadFailed: (String) -> Unit,
    var onErrorReport: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onChartTap(timeSeconds: Double, price: Double) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post { onTap(time, price, null, null) }
    }

    @JavascriptInterface
    fun onChartTapWithHit(timeSeconds: Double, price: Double, drawingId: String, alertId: String) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post {
            onTap(
                time,
                price,
                drawingId.takeIf { it.isNotBlank() },
                alertId.takeIf { it.isNotBlank() }
            )
        }
    }

    @JavascriptInterface
    fun onCrosshair(timeSeconds: Double, price: Double) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post { onCrosshair(time, price) }
    }

    @JavascriptInterface
    fun onDismissCrosshair() {
        mainHandler.post { onDismissCrosshair() }
    }

    @JavascriptInterface
    fun onDoubleTapReset() {
        mainHandler.post { onDoubleTapReset() }
    }

    @JavascriptInterface
    fun onPriceAxisTap(price: Double) {
        mainHandler.post { onPriceAxisTap(price) }
    }

    @JavascriptInterface
    fun onQuickAlert(price: Double) {
        mainHandler.post { onQuickAlert(price) }
    }

    @JavascriptInterface
    fun onMoveDrawingAnchor(drawingId: String, anchorIndex: Int, timeSeconds: Double, price: Double) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post { onMoveDrawingAnchor(drawingId, anchorIndex, ChartPoint(time, price)) }
    }

    @JavascriptInterface
    fun onMoveWholeDrawing(drawingId: String, deltaTimeSeconds: Double, deltaPrice: Double) {
        val dt = (deltaTimeSeconds * 1000.0).toLong()
        mainHandler.post { onMoveWholeDrawing(drawingId, dt, deltaPrice) }
    }

    @JavascriptInterface
    fun onMoveAlertPrice(alertId: String, newPrice: Double) {
        mainHandler.post { onMoveAlertPrice(alertId, newPrice) }
    }

    @JavascriptInterface
    fun onManualInteraction() {
        mainHandler.post { onManualInteraction() }
    }

    @JavascriptInterface
    fun onMultiExchLevelTap(levelId: String) {
        mainHandler.post { onMultiExchLevelTap(levelId) }
    }

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { onChartReady() }
    }

    @JavascriptInterface
    fun onLibraryFailed(message: String) {
        mainHandler.post { onLibraryLoadFailed(message) }
    }

    @JavascriptInterface
    fun onError(message: String) {
        mainHandler.post { onErrorReport(message) }
    }
}

private class ChartSyncTracker {
    var syncedSeriesKey: String? = null
    var syncedFirstTime: Long = -1L
    var syncedCount: Int = 0
    var syncedLastBar: Candle? = null
    var lastOverlayJson: String = ""
}

private fun isAndroidEmulator(): Boolean {
    return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
        Build.FINGERPRINT.contains("cf_x86", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
        Build.HARDWARE.contains("cutf", ignoreCase = true) ||
        Build.HARDWARE.contains("vsoc", ignoreCase = true) ||
        Build.MODEL.contains("google_sdk", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.MODEL.contains("Cuttlefish", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk", ignoreCase = true) ||
        Build.PRODUCT.contains("emulator", ignoreCase = true) ||
        Build.PRODUCT.contains("simulator", ignoreCase = true) ||
        Build.PRODUCT.contains("vsoc", ignoreCase = true)
}

private fun Candle.toJsonObject(): JSONObject = JSONObject().apply {
    put("time", openTime / 1000L)
    put("open", open)
    put("high", high)
    put("low", low)
    put("close", close)
    put("volume", volume)
}

private fun findNearestCandleIndex(candles: List<Candle>, timeMillis: Long): Int {
    if (candles.isEmpty()) return 0
    var bestIdx = 0
    var bestDiff = abs(candles[0].openTime - timeMillis)
    for (i in 1 until candles.size) {
        val diff = abs(candles[i].openTime - timeMillis)
        if (diff < bestDiff) {
            bestDiff = diff
            bestIdx = i
        }
    }
    return bestIdx
}

private fun hitTestAlert(alerts: List<PriceAlert>, price: Double, priceTolerance: Double): PriceAlert? {
    return alerts.firstOrNull { it.isActive && abs(it.targetPrice - price) <= priceTolerance }
}

private fun hitTestDrawing(
    drawings: List<ChartDrawing>,
    timeMillis: Long,
    price: Double,
    priceTolerance: Double
): ChartDrawing? {
    for (d in drawings.asReversed()) {
        when (d.type) {
            DrawingType.HORIZONTAL_LINE -> {
                val p = d.points.firstOrNull()?.price ?: continue
                if (abs(p - price) <= priceTolerance) return d
            }
            DrawingType.TRENDLINE -> {
                if (d.points.size < 2) continue
                val p1 = d.points[0]
                val p2 = d.points[1]
                val minT = min(p1.timeMillis, p2.timeMillis)
                val maxT = max(p1.timeMillis, p2.timeMillis)
                if (timeMillis in minT..maxT && maxT > minT) {
                    val ratio = (timeMillis - p1.timeMillis).toDouble() / (p2.timeMillis - p1.timeMillis).toDouble()
                    val linePrice = p1.price + ratio * (p2.price - p1.price)
                    if (abs(linePrice - price) <= priceTolerance * 1.5) return d
                }
            }
            DrawingType.RECTANGLE -> {
                if (d.points.size < 2) continue
                val minT = min(d.points[0].timeMillis, d.points[1].timeMillis)
                val maxT = max(d.points[0].timeMillis, d.points[1].timeMillis)
                val minP = min(d.points[0].price, d.points[1].price)
                val maxP = max(d.points[0].price, d.points[1].price)
                if (timeMillis in minT..maxT && price in (minP - priceTolerance)..(maxP + priceTolerance)) {
                    return d
                }
            }
        }
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
@Composable
fun LightweightChartView(
    state: ChartUiState,
    webUpdates: SharedFlow<ChartWebUpdate>,
    buildFullHistory: () -> ChartWebUpdate.FullHistory?,
    onChartTap: (ChartPoint, ChartDrawing?, PriceAlert?, Int) -> Unit,
    onCrosshairMove: (Long, Double, Candle?) -> Unit,
    onDismissCrosshair: () -> Unit = {},
    onDoubleTapReset: () -> Unit = {},
    onPriceAxisTap: (Double) -> Unit = {},
    onQuickAlert: (Double) -> Unit,
    onMoveDrawingAnchor: (String, Int, ChartPoint) -> Unit = { _, _, _ -> },
    onMoveWholeDrawing: (String, Long, Double) -> Unit = { _, _, _ -> },
    onMoveAlertPrice: (String, Double) -> Unit = { _, _ -> },
    onManualInteraction: () -> Unit,
    onMultiExchLevelTap: (String) -> Unit = {},
    isImmersive: Boolean = false,
    fallbackCanvas: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val latestState by rememberUpdatedState(state)
    val latestOnTap by rememberUpdatedState(onChartTap)
    val latestOnCrosshair by rememberUpdatedState(onCrosshairMove)
    val latestOnDismissCrosshair by rememberUpdatedState(onDismissCrosshair)
    val latestOnDoubleTapReset by rememberUpdatedState(onDoubleTapReset)
    val latestOnPriceAxisTap by rememberUpdatedState(onPriceAxisTap)
    val latestOnQuickAlert by rememberUpdatedState(onQuickAlert)
    val latestOnMoveDrawingAnchor by rememberUpdatedState(onMoveDrawingAnchor)
    val latestOnMoveWholeDrawing by rememberUpdatedState(onMoveWholeDrawing)
    val latestOnMoveAlertPrice by rememberUpdatedState(onMoveAlertPrice)
    val latestOnManualInteraction by rememberUpdatedState(onManualInteraction)
    val latestOnMultiExchLevelTap by rememberUpdatedState(onMultiExchLevelTap)
    val latestBuildFullHistory by rememberUpdatedState(buildFullHistory)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var chartReady by remember { mutableStateOf(false) }
    var useFallbackCanvas by remember { mutableStateOf(false) }
    val tracker = remember { ChartSyncTracker() }

    val bridge = remember {
        ChartJavascriptBridge(
            onTap = { _, _, _, _ -> },
            onCrosshair = { _, _ -> },
            onDismissCrosshair = { },
            onDoubleTapReset = { },
            onPriceAxisTap = { },
            onQuickAlert = { },
            onMoveDrawingAnchor = { _, _, _ -> },
            onMoveWholeDrawing = { _, _, _ -> },
            onMoveAlertPrice = { _, _ -> },
            onManualInteraction = { },
            onMultiExchLevelTap = { },
            onChartReady = { },
            onLibraryLoadFailed = { },
            onErrorReport = { }
        )
    }

    bridge.onTap = { timeMillis, price, hitDrawingId, hitAlertId ->
        val s = latestState
        val candles = s.displayedCandles
        val index = findNearestCandleIndex(candles, timeMillis)
        val priceTol = max(abs(price) * 0.004, (s.contract?.tickSize ?: 0.01) * 8.0)
        val canSelectExisting = s.activeTool == DrawingTool.NONE && !s.replayState.isPickingStart
        val tappedDrawing = if (canSelectExisting) {
            if (hitDrawingId != null) s.drawings.firstOrNull { it.id == hitDrawingId }
            else hitTestDrawing(s.drawings, timeMillis, price, priceTol)
        } else null
        val tappedAlert = if (canSelectExisting && tappedDrawing == null) {
            if (hitAlertId != null) s.alertsForSymbol.firstOrNull { it.id == hitAlertId && it.isActive }
            else hitTestAlert(s.alertsForSymbol, price, priceTol)
        } else null
        latestOnTap(ChartPoint(timeMillis, price), tappedDrawing, tappedAlert, index)
    }
    bridge.onCrosshair = { timeMillis, price ->
        val candles = latestState.displayedCandles
        val candle = candles.getOrNull(findNearestCandleIndex(candles, timeMillis))
        latestOnCrosshair(timeMillis, price, candle)
    }
    bridge.onDismissCrosshair = { latestOnDismissCrosshair() }
    bridge.onDoubleTapReset = { latestOnDoubleTapReset() }
    bridge.onPriceAxisTap = { price -> latestOnPriceAxisTap(price) }
    bridge.onQuickAlert = { price -> latestOnQuickAlert(price) }
    bridge.onMoveDrawingAnchor = { id, idx, pt -> latestOnMoveDrawingAnchor(id, idx, pt) }
    bridge.onMoveWholeDrawing = { id, dt, dp -> latestOnMoveWholeDrawing(id, dt, dp) }
    bridge.onMoveAlertPrice = { id, p -> latestOnMoveAlertPrice(id, p) }
    bridge.onManualInteraction = { latestOnManualInteraction() }
    bridge.onMultiExchLevelTap = { levelId -> latestOnMultiExchLevelTap(levelId) }
    bridge.onChartReady = {
        useFallbackCanvas = false
        chartReady = true
    }
    bridge.onLibraryLoadFailed = { msg ->
        Log.w("LightweightChartView", "CDN load failed, switching to ChartCanvas fallback: $msg")
        if (fallbackCanvas != null) {
            useFallbackCanvas = true
        }
    }
    bridge.onErrorReport = { msg ->
        Log.e("LightweightChartView", "Chart JS error: $msg")
    }

    val overlayJson = remember(
        state.alertsForSymbol,
        state.drawings,
        state.selectedDrawingId,
        state.selectedAlertId,
        state.pendingPoints,
        state.rangeMeasurement,
        state.activeTool,
        state.replayState.isPickingStart,
        state.axisQuickAlertPrice,
        state.isTbtEffectivelyVisible,
        state.tbtSmc,
        state.preferences.showMultiExchVol,
        state.multiExchVol,
        state.selectedMultiExchLevelId
    ) {
        val root = JSONObject()
        val lines = JSONArray()
        val alertsArray = JSONArray()
        state.alertsForSymbol.filter { it.isActive }.forEach { alert ->
            val isSelected = alert.id == state.selectedAlertId
            alertsArray.put(JSONObject().apply {
                put("id", alert.id)
                put("price", alert.targetPrice)
                put("selected", isSelected)
            })
        }
        state.drawings.filter { it.type == DrawingType.HORIZONTAL_LINE }.forEach { drawing ->
            drawing.points.firstOrNull()?.let { point ->
                val styleKey = when (drawing.lineStyle) {
                    LineStyle.SOLID -> "solid"
                    LineStyle.DASHED -> "dashed"
                    LineStyle.DOTTED -> "dotted"
                }
                lines.put(JSONObject().apply {
                    put("price", point.price)
                    put("title", "")
                    put("color", String.format("#%06X", 0xFFFFFF and drawing.colorHex.toInt()))
                    put("lineStyle", styleKey)
                    put("lineWidth", drawing.strokeWidth.roundToInt().coerceIn(1, 4))
                    put("dashed", drawing.lineStyle != LineStyle.SOLID)
                    put("axisLabelVisible", true)
                    put("selected", drawing.id == state.selectedDrawingId)
                })
            }
        }
        root.put("lines", lines)
        root.put("alerts", alertsArray)

        val drawingArray = JSONArray()
        state.drawings.forEach { drawing ->
            val points = JSONArray()
            drawing.points.forEach { point ->
                points.put(JSONObject().apply {
                    put("time", point.timeMillis / 1000L)
                    put("price", point.price)
                })
            }
            val styleKey = when (drawing.lineStyle) {
                LineStyle.SOLID -> "solid"
                LineStyle.DASHED -> "dashed"
                LineStyle.DOTTED -> "dotted"
            }
            drawingArray.put(JSONObject().apply {
                put("id", drawing.id)
                put("type", drawing.type.name)
                put("locked", drawing.isLocked)
                put("color", String.format("#%06X", 0xFFFFFF and drawing.colorHex.toInt()))
                put("lineStyle", styleKey)
                put("strokeWidth", drawing.strokeWidth.toDouble())
                put("dashed", drawing.lineStyle != LineStyle.SOLID)
                put("selected", drawing.id == state.selectedDrawingId)
                put("points", points)
            })
        }
        root.put("drawings", drawingArray)

        val pendingArr = JSONArray()
        state.pendingPoints.forEach { pt ->
            pendingArr.put(JSONObject().apply {
                put("time", pt.timeMillis / 1000L)
                put("price", pt.price)
            })
        }
        root.put("pendingPoints", pendingArr)
        root.put("activeTool", state.activeTool.name)
        root.put("isPickingReplay", state.replayState.isPickingStart)
        root.put("quickAlertPrice", state.axisQuickAlertPrice ?: JSONObject.NULL)

        state.rangeMeasurement?.let { range ->
            val pct = if (range.start.price != 0.0) {
                ((range.end.price - range.start.price) / range.start.price) * 100.0
            } else 0.0
            val diff = range.end.price - range.start.price
            val precision = state.contract?.pricePrecision ?: 2
            val duration = ChartNumberFormatter.formatDuration(range.end.timeMillis - range.start.timeMillis)
            val label = "${ChartNumberFormatter.formatPrice(diff, precision)} (${ChartNumberFormatter.formatPercent(pct)}) • ${range.barCount} bars • $duration"
            root.put("rangeMeasurement", JSONObject().apply {
                put("start", JSONObject().apply {
                    put("time", range.start.timeMillis / 1000L)
                    put("price", range.start.price)
                })
                put("end", JSONObject().apply {
                    put("time", range.end.timeMillis / 1000L)
                    put("price", range.end.price)
                })
                put("label", label)
            })
        }
        val tbtVisible = state.isTbtEffectivelyVisible
        val tbtObj = JSONObject().apply {
            put("enabled", tbtVisible)
            val mArr = JSONArray()
            if (tbtVisible) {
                state.tbtSmc.markers.forEach { m ->
                    mArr.put(JSONObject().apply {
                        put("time", m.timeMillis / 1000L)
                        put("position", m.position)
                        put("color", m.colorHex)
                        put("shape", m.shape)
                        put("text", m.text)
                    })
                }
            }
            put("markers", mArr)
            val pArr = JSONArray()
            if (tbtVisible) {
                state.tbtSmc.priceLines.forEach { pl ->
                    pArr.put(JSONObject().apply {
                        put("price", pl.price)
                        put("title", pl.title)
                        put("color", pl.colorHex)
                        put("lineStyle", pl.lineStyle)
                        put("lineWidth", pl.lineWidth)
                    })
                }
            }
            put("priceLines", pArr)
            put("zones", JSONArray())
        }
        root.put("tbtSmc", tbtObj)

        val multiExchEnabled = state.preferences.showMultiExchVol
        val meObj = JSONObject().apply {
            put("enabled", multiExchEnabled)
            put("selectedId", state.selectedMultiExchLevelId ?: "")
            val lvlArr = JSONArray()
            val candleArr = JSONArray()
            val trapArr = JSONArray()
            if (multiExchEnabled) {
                state.multiExchVol.levels.forEach { lvl ->
                    lvlArr.put(JSONObject().apply {
                        put("id", lvl.id)
                        put("time", lvl.candleTimeMillis / 1000L)
                        put("priceLow", lvl.priceLow)
                        put("priceHigh", lvl.priceHigh)
                        put("price", lvl.priceMid)
                        put("buyVol", lvl.totalBuyVolume)
                        put("sellVol", lvl.totalSellVolume)
                        put("delta", lvl.delta)
                        put("isPoc", lvl.isPoc)
                        put("buyImb", lvl.isBuyImbalance)
                        put("sellImb", lvl.isSellImbalance)
                        put("volRatio", lvl.volumeRatio)
                        put("side", lvl.dominantSide.name)
                        put("count", lvl.dominantExchangeCount)
                    })
                }
                state.multiExchVol.candleSummaries.forEach { summary ->
                    candleArr.put(JSONObject().apply {
                        put("time", summary.candleTimeMillis / 1000L)
                        put("low", summary.candleLow)
                        put("high", summary.candleHigh)
                        put("buyVol", summary.totalBuyVolume)
                        put("sellVol", summary.totalSellVolume)
                        put("delta", summary.delta)
                        put("totalVol", summary.totalVolume)
                        put("poc", summary.pocPriceMid)
                    })
                }
                state.multiExchVol.traps.forEach { trap ->
                    trapArr.put(JSONObject().apply {
                        put("id", trap.id)
                        put("time", trap.candleTimeMillis / 1000L)
                        put("price", trap.price)
                        put("type", trap.type.name)
                    })
                }
            }
            put("levels", lvlArr)
            put("candles", candleArr)
            put("traps", trapArr)
        }
        root.put("multiExchVol", meObj)
        root.toString()
    }
    val latestOverlayJson by rememberUpdatedState(overlayJson)

    fun appendCandleJson(sb: StringBuilder, c: Candle) {
        sb.append("{\"time\":").append(c.openTime / 1000L)
            .append(",\"open\":").append(c.open)
            .append(",\"high\":").append(c.high)
            .append(",\"low\":").append(c.low)
            .append(",\"close\":").append(c.close)
            .append(",\"volume\":").append(c.volume)
            .append('}')
    }

    fun clearWebSeries(view: WebView) {
        tracker.syncedSeriesKey = null
        tracker.syncedFirstTime = -1L
        tracker.syncedCount = 0
        tracker.syncedLastBar = null
        view.evaluateJavascript("window.clearChartSeries && window.clearChartSeries()", null)
    }

    fun pushFullHistory(view: WebView, update: ChartWebUpdate.FullHistory, includeOverlay: Boolean) {
        if (update.candles.isEmpty()) return
        val currentOverlay = latestOverlayJson
        val sb = StringBuilder(update.candles.size * 96 + if (includeOverlay) currentOverlay.length + 64 else 64)
        sb.append("{\"seriesKey\":").append(JSONObject.quote(update.seriesKey))
            .append(",\"fit\":").append(update.fit)
            .append(",\"candles\":[")
        update.candles.forEachIndexed { idx, candle ->
            if (idx > 0) sb.append(',')
            appendCandleJson(sb, candle)
        }
        sb.append(']')
        if (includeOverlay && currentOverlay.length > 2) {
            sb.append(',')
            sb.append(currentOverlay, 1, currentOverlay.length - 1)
            tracker.lastOverlayJson = currentOverlay
        }
        sb.append('}')
        val payload = sb.toString()
        tracker.syncedSeriesKey = update.seriesKey
        tracker.syncedFirstTime = update.candles.first().openTime
        tracker.syncedCount = update.candles.size
        tracker.syncedLastBar = update.candles.lastOrNull()
        view.evaluateJavascript(
            "window.setMarketData && window.setMarketData(${JSONObject.quote(payload)})",
            null
        )
    }

    val currentCandles = state.displayedCandles
    val currentSeriesKey = "${state.symbol}_${state.activeTimeframe.id}_${state.priceSource.name}"
    val candlesMatchActiveSeries = state.candlesSeriesKey == currentSeriesKey
    val currentFirstTime = if (candlesMatchActiveSeries) currentCandles.firstOrNull()?.openTime ?: -1L else -1L
    val currentCount = if (candlesMatchActiveSeries) currentCandles.size else 0
    val currentLastBar = if (candlesMatchActiveSeries) currentCandles.lastOrNull() else null

    fun pushDeltaBars(view: WebView, seriesKey: String, deltaBars: List<Candle>, totalCount: Int) {
        if (deltaBars.isEmpty()) return
        tracker.syncedCount = totalCount
        tracker.syncedLastBar = deltaBars.last()
        val sb = StringBuilder(deltaBars.size * 96 + 64)
        sb.append("{\"seriesKey\":").append(JSONObject.quote(seriesKey))
            .append(",\"totalCount\":").append(totalCount)
            .append(",\"bars\":[")
        deltaBars.forEachIndexed { idx, candle ->
            if (idx > 0) sb.append(',')
            appendCandleJson(sb, candle)
        }
        sb.append("]}")
        view.evaluateJavascript(
            "window.updateLastCandle && window.updateLastCandle(${JSONObject.quote(sb.toString())})",
            null
        )
    }

    // Guarantee initial history (and symbol/tf/pagination/replay changes) always reach WebView
    // ONLY when candles belong to the active seriesKey, clearing immediately on empty switches.
    LaunchedEffect(chartReady, useFallbackCanvas, currentSeriesKey, state.candlesSeriesKey, currentFirstTime, currentCount) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        if (!candlesMatchActiveSeries) return@LaunchedEffect
        if (currentCandles.isEmpty()) {
            if (tracker.syncedSeriesKey != null) {
                clearWebSeries(view)
            }
            return@LaunchedEffect
        }
        val needsFull =
            tracker.syncedSeriesKey != currentSeriesKey ||
                tracker.syncedFirstTime != currentFirstTime ||
                currentCount < tracker.syncedCount ||
                currentCount > tracker.syncedCount + 1
        if (needsFull) {
            pushFullHistory(
                view,
                ChartWebUpdate.FullHistory(
                    seriesKey = currentSeriesKey,
                    candles = currentCandles,
                    fit = state.viewport.isAutoFit
                ),
                includeOverlay = true
            )
        }
    }

    // Catch-up live bar sync if state changed while collector was starting or delta arrived out-of-band
    LaunchedEffect(chartReady, useFallbackCanvas, currentSeriesKey, state.candlesSeriesKey, currentLastBar) {
        if (useFallbackCanvas || !chartReady || !candlesMatchActiveSeries || currentLastBar == null) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        if (tracker.syncedSeriesKey == currentSeriesKey &&
            tracker.syncedLastBar != currentLastBar &&
            currentCount in tracker.syncedCount..(tracker.syncedCount + 1)
        ) {
            val prevSyncedTime = tracker.syncedLastBar?.openTime ?: -1L
            val barsToSend = if (currentLastBar.openTime > prevSyncedTime && currentCandles.size >= 2) {
                listOf(currentCandles[currentCandles.lastIndex - 1], currentLastBar)
            } else {
                listOf(currentLastBar)
            }
            pushDeltaBars(view, currentSeriesKey, barsToSend, currentCount)
        }
    }

    // Collect live candle delta updates without resending full historical candle list
    LaunchedEffect(chartReady, useFallbackCanvas, webUpdates) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        webUpdates.collect { update ->
            val view = webView ?: return@collect
            val activeKey = "${latestState.symbol}_${latestState.activeTimeframe.id}_${latestState.priceSource.name}"
            when (update) {
                is ChartWebUpdate.ClearSeries -> {
                    if (update.seriesKey == activeKey) {
                        clearWebSeries(view)
                    }
                }
                is ChartWebUpdate.FullHistory -> {
                    if (update.seriesKey != activeKey) return@collect
                    val alreadySynced =
                        tracker.syncedSeriesKey == update.seriesKey &&
                            tracker.syncedFirstTime == (update.candles.firstOrNull()?.openTime ?: -1L) &&
                            tracker.syncedCount == update.candles.size &&
                            tracker.syncedLastBar == update.candles.lastOrNull()
                    if (!alreadySynced) {
                        pushFullHistory(view, update, includeOverlay = tracker.syncedSeriesKey != update.seriesKey)
                    }
                }
                is ChartWebUpdate.LiveCandleDelta -> {
                    if (update.seriesKey != activeKey) return@collect
                    if (tracker.syncedSeriesKey != update.seriesKey) {
                        latestBuildFullHistory()?.takeIf { it.seriesKey == activeKey }?.let { full ->
                            pushFullHistory(view, full, includeOverlay = true)
                        }
                    } else if (update.candles.isNotEmpty() && update.candles.last() != tracker.syncedLastBar) {
                        pushDeltaBars(view, update.seriesKey, update.candles, update.totalCount)
                    }
                }
                ChartWebUpdate.ResetScroll -> {
                    view.evaluateJavascript("window.scrollToLiveEdge && window.scrollToLiveEdge()", null)
                }
                ChartWebUpdate.ResetPriceScale -> {
                    view.evaluateJavascript("window.resetPriceAutoFitOnly && window.resetPriceAutoFitOnly()", null)
                }
            }
        }
    }

    // Sync alerts / drawings / range measurement independently from candle ticks
    LaunchedEffect(chartReady, useFallbackCanvas, overlayJson) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        if (overlayJson != tracker.lastOverlayJson) {
            tracker.lastOverlayJson = overlayJson
            view.evaluateJavascript(
                "window.setOverlayData && window.setOverlayData(${JSONObject.quote(overlayJson)})",
                null
            )
        }
    }

    // Explicitly purge all TBT SMC lines & markers in WebView the instant the indicator is hidden or auto-hidden on HTF
    LaunchedEffect(chartReady, useFallbackCanvas, state.isTbtEffectivelyVisible) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        if (!state.isTbtEffectivelyVisible) {
            webView?.evaluateJavascript("window.clearTbtSmc && window.clearTbtSmc()", null)
        }
    }

    // Explicitly purge all Multi-Exch Vol & Traps markers/traps without touching TBT SMC when hidden
    LaunchedEffect(chartReady, useFallbackCanvas, state.preferences.showMultiExchVol) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        if (!state.preferences.showMultiExchVol) {
            webView?.evaluateJavascript("window.clearMultiExchVol && window.clearMultiExchVol()", null)
        }
    }

    // Clear HTML crosshair lines whenever Compose dismisses crosshair
    LaunchedEffect(chartReady, useFallbackCanvas, state.crosshair.isActive) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        if (!state.crosshair.isActive) {
            webView?.evaluateJavascript("window.clearCrosshair && window.clearCrosshair()", null)
        }
    }

    // Sync bar countdown directly underneath the live price tag on the right price axis
    val showCountdown = state.preferences.showCountdown && !state.replayState.isActive && state.countdownMillis > 0L && currentCandles.isNotEmpty()
    val countdownLabel = if (showCountdown) ChartNumberFormatter.formatCountdown(state.countdownMillis) else ""
    val lastBar = currentCandles.lastOrNull()
    val isBullBar = if (lastBar != null) lastBar.close >= lastBar.open else true
    LaunchedEffect(chartReady, useFallbackCanvas, showCountdown, countdownLabel, isBullBar) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.setBarCountdown && window.setBarCountdown(${JSONObject.quote(countdownLabel)}, $isBullBar, $showCountdown)",
            null
        )
    }

    LaunchedEffect(chartReady, useFallbackCanvas, isImmersive) {
        if (useFallbackCanvas || !chartReady) return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.setImmersiveMode && window.setImmersiveMode($isImmersive)",
            null
        )
    }

    // Watchdog: if candles are loaded but WebView fails to signal chartReady within 3.5s
    // (e.g. blocked CDN or broken WebView renderer), fall back to ChartCanvas so candles always render.
    LaunchedEffect(useFallbackCanvas, chartReady, currentCandles.isNotEmpty()) {
        if (!useFallbackCanvas && !chartReady && currentCandles.isNotEmpty() && fallbackCanvas != null) {
            delay(3500L)
            if (!chartReady) {
                Log.w("LightweightChartView", "WebView readiness timeout; activating ChartCanvas fallback")
                useFallbackCanvas = true
            }
        }
    }

    if (useFallbackCanvas && fallbackCanvas != null) {
        Box(modifier = modifier.testTag("chart_fallback_canvas")) {
            fallbackCanvas()
        }
    } else {
        AndroidView(
            modifier = modifier.testTag("chart_webview_container"),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    if (isAndroidEmulator()) {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
                    setBackgroundColor(android.graphics.Color.rgb(9, 13, 18))
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webChromeClient = object : WebChromeClient() {
                        private var customView: View? = null
                        private var customViewCallback: CustomViewCallback? = null

                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            (context as? android.app.Activity)?.let { act ->
                                (act.window?.decorView as? ViewGroup)?.addView(
                                    view,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }

                        override fun onHideCustomView() {
                            (context as? android.app.Activity)?.let { act ->
                                customView?.let { (act.window?.decorView as? ViewGroup)?.removeView(it) }
                            }
                            customView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null
                        }
                    }
                    addJavascriptInterface(bridge, "AndroidChart")
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.url?.toString()?.contains("lightweight-charts") == true) {
                                Log.w("LightweightChartView", "WebView resource error: ${error.description}")
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            Log.e("LightweightChartView", "WebView render process gone; switching to ChartCanvas")
                            if (fallbackCanvas != null) {
                                useFallbackCanvas = true
                            }
                            return true
                        }
                    }
                    webView = this
                    loadUrl("file:///android_asset/lightweight_chart.html")
                }
            },
            update = { view ->
                if (webView !== view) {
                    webView = view
                }
            },
            onRelease = { view ->
                chartReady = false
                webView = null
                tracker.syncedSeriesKey = null
                tracker.syncedFirstTime = -1L
                tracker.syncedCount = 0
                tracker.syncedLastBar = null
                tracker.lastOverlayJson = ""
                view.removeJavascriptInterface("AndroidChart")
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            }
        )
    }
}
