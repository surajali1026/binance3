package com.example.ui.chart

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private class ChartJavascriptBridge(
    var onTap: (Long, Double) -> Unit,
    var onCrosshair: (Long, Double) -> Unit,
    var onQuickAlert: (Double) -> Unit,
    var onManualInteraction: () -> Unit,
    var onChartReady: () -> Unit,
    var onErrorReport: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onChartTap(timeSeconds: Double, price: Double) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post { onTap(time, price) }
    }

    @JavascriptInterface
    fun onCrosshair(timeSeconds: Double, price: Double) {
        val time = (timeSeconds * 1000.0).toLong()
        mainHandler.post { onCrosshair(time, price) }
    }

    @JavascriptInterface
    fun onQuickAlert(price: Double) {
        mainHandler.post { onQuickAlert(price) }
    }

    @JavascriptInterface
    fun onManualInteraction() {
        mainHandler.post { onManualInteraction() }
    }

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { onChartReady() }
    }

    @JavascriptInterface
    fun onError(message: String) {
        mainHandler.post { onErrorReport(message) }
    }
}

private class ChartSyncTracker {
    var syncedSeriesKey: String? = null
    var lastOverlayJson: String = ""
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
    onQuickAlert: (Double) -> Unit,
    onManualInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestState by rememberUpdatedState(state)
    val latestOnTap by rememberUpdatedState(onChartTap)
    val latestOnCrosshair by rememberUpdatedState(onCrosshairMove)
    val latestOnQuickAlert by rememberUpdatedState(onQuickAlert)
    val latestOnManualInteraction by rememberUpdatedState(onManualInteraction)
    val latestBuildFullHistory by rememberUpdatedState(buildFullHistory)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var chartReady by remember { mutableStateOf(false) }
    val tracker = remember { ChartSyncTracker() }

    val bridge = remember {
        ChartJavascriptBridge(
            onTap = { _, _ -> },
            onCrosshair = { _, _ -> },
            onQuickAlert = { },
            onManualInteraction = { },
            onChartReady = { },
            onErrorReport = { }
        )
    }

    bridge.onTap = { timeMillis, price ->
        val s = latestState
        val candles = s.displayedCandles
        val index = findNearestCandleIndex(candles, timeMillis)
        val priceTol = max(abs(price) * 0.004, (s.contract?.tickSize ?: 0.01) * 8.0)
        val canSelectExisting = s.activeTool == DrawingTool.NONE && !s.replayState.isPickingStart
        val tappedDrawing = if (canSelectExisting) hitTestDrawing(s.drawings, timeMillis, price, priceTol) else null
        val tappedAlert = if (canSelectExisting && tappedDrawing == null) hitTestAlert(s.alertsForSymbol, price, priceTol) else null
        latestOnTap(ChartPoint(timeMillis, price), tappedDrawing, tappedAlert, index)
    }
    bridge.onCrosshair = { timeMillis, price ->
        val candles = latestState.displayedCandles
        val candle = candles.getOrNull(findNearestCandleIndex(candles, timeMillis))
        latestOnCrosshair(timeMillis, price, candle)
    }
    bridge.onQuickAlert = { price -> latestOnQuickAlert(price) }
    bridge.onManualInteraction = { latestOnManualInteraction() }
    bridge.onChartReady = { chartReady = true }

    val overlayJson = remember(
        state.alertsForSymbol,
        state.drawings,
        state.selectedDrawingId,
        state.selectedAlertId,
        state.pendingPoints,
        state.rangeMeasurement
    ) {
        val root = JSONObject()
        val lines = JSONArray()
        state.alertsForSymbol.filter { it.isActive }.forEach { alert ->
            lines.put(JSONObject().apply {
                put("price", alert.targetPrice)
                put("title", "Alert")
                put("color", "#f0b90b")
                put("dashed", true)
                put("selected", alert.id == state.selectedAlertId)
            })
        }
        state.drawings.filter { it.type == DrawingType.HORIZONTAL_LINE }.forEach { drawing ->
            drawing.points.firstOrNull()?.let { point ->
                lines.put(JSONObject().apply {
                    put("price", point.price)
                    put("title", if (drawing.isLocked) "Locked" else "Line")
                    put("color", String.format("#%06X", 0xFFFFFF and drawing.colorHex.toInt()))
                    put("dashed", drawing.lineStyle != LineStyle.SOLID)
                    put("selected", drawing.id == state.selectedDrawingId)
                })
            }
        }
        root.put("lines", lines)

        val drawingArray = JSONArray()
        state.drawings.filter { it.type != DrawingType.HORIZONTAL_LINE }.forEach { drawing ->
            val points = JSONArray()
            drawing.points.forEach { point ->
                points.put(JSONObject().apply {
                    put("time", point.timeMillis / 1000L)
                    put("price", point.price)
                })
            }
            drawingArray.put(JSONObject().apply {
                put("type", drawing.type.name)
                put("color", String.format("#%06X", 0xFFFFFF and drawing.colorHex.toInt()))
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

        state.rangeMeasurement?.let { range ->
            val pct = if (range.start.price != 0.0) {
                ((range.end.price - range.start.price) / range.start.price) * 100.0
            } else 0.0
            val diff = range.end.price - range.start.price
            val precision = state.contract?.pricePrecision ?: 2
            val label = "${ChartNumberFormatter.formatPercent(pct)} (${ChartNumberFormatter.formatPrice(diff, precision)}) · ${range.barCount} bars"
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
        root.toString()
    }

    fun pushFullHistory(view: WebView, update: ChartWebUpdate.FullHistory, includeOverlay: Boolean) {
        if (update.candles.isEmpty()) return
        val payload = JSONObject().apply {
            put("seriesKey", update.seriesKey)
            put("fit", update.fit)
            val data = JSONArray()
            update.candles.forEach { candle -> data.put(candle.toJsonObject()) }
            put("candles", data)
            if (includeOverlay) {
                val ov = JSONObject(overlayJson)
                put("lines", ov.optJSONArray("lines"))
                put("drawings", ov.optJSONArray("drawings"))
                put("pendingPoints", ov.optJSONArray("pendingPoints"))
                if (ov.has("rangeMeasurement")) {
                    put("rangeMeasurement", ov.optJSONObject("rangeMeasurement"))
                }
                tracker.lastOverlayJson = overlayJson
            }
        }.toString()
        tracker.syncedSeriesKey = update.seriesKey
        view.evaluateJavascript(
            "window.setMarketData && window.setMarketData(${JSONObject.quote(payload)})",
            null
        )
    }

    // Seed initial history once when WebView is ready (if history loaded before or right as WebView mounted)
    LaunchedEffect(chartReady) {
        val view = webView ?: return@LaunchedEffect
        if (!chartReady) return@LaunchedEffect
        if (tracker.syncedSeriesKey == null) {
            latestBuildFullHistory()?.let { initial ->
                pushFullHistory(view, initial, includeOverlay = true)
            }
        }
    }

    // Collect incremental updates from ChartViewModel: FullHistory only on init/symbol/tf/replay/pagination;
    // LiveCandleDelta only sends the 1-2 updated/new trailing candles without resending historical list!
    LaunchedEffect(chartReady, webUpdates) {
        if (!chartReady) return@LaunchedEffect
        webUpdates.collect { update ->
            val view = webView ?: return@collect
            when (update) {
                is ChartWebUpdate.FullHistory -> {
                    pushFullHistory(view, update, includeOverlay = tracker.syncedSeriesKey == null)
                }
                is ChartWebUpdate.LiveCandleDelta -> {
                    if (tracker.syncedSeriesKey != update.seriesKey) {
                        latestBuildFullHistory()?.let { full ->
                            pushFullHistory(view, full, includeOverlay = true)
                        }
                    } else if (update.candles.isNotEmpty()) {
                        val deltaPayload = JSONObject().apply {
                            put("seriesKey", update.seriesKey)
                            put("totalCount", update.totalCount)
                            val bars = JSONArray()
                            update.candles.forEach { candle -> bars.put(candle.toJsonObject()) }
                            put("bars", bars)
                        }.toString()
                        view.evaluateJavascript(
                            "window.updateLastCandle && window.updateLastCandle(${JSONObject.quote(deltaPayload)})",
                            null
                        )
                    }
                }
                ChartWebUpdate.ResetScroll -> {
                    view.evaluateJavascript("window.scrollToLiveEdge && window.scrollToLiveEdge()", null)
                }
            }
        }
    }

    // Sync alerts / drawings / range measurement independently from candle ticks
    LaunchedEffect(chartReady, overlayJson) {
        val view = webView ?: return@LaunchedEffect
        if (!chartReady) return@LaunchedEffect
        if (overlayJson != tracker.lastOverlayJson) {
            tracker.lastOverlayJson = overlayJson
            view.evaluateJavascript(
                "window.setOverlayData && window.setOverlayData(${JSONObject.quote(overlayJson)})",
                null
            )
        }
    }

    AndroidView(
        modifier = modifier.testTag("chart_webview_container"),
        factory = { context ->
            WebView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(android.graphics.Color.rgb(9, 13, 18))
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webChromeClient = WebChromeClient()
                addJavascriptInterface(bridge, "AndroidChart")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (url == "about:blank") return
                        tracker.syncedSeriesKey = null
                        tracker.lastOverlayJson = ""
                        chartReady = true
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
            tracker.lastOverlayJson = ""
            view.removeJavascriptInterface("AndroidChart")
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    )
}
