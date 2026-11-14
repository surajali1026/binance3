package com.example.ui.chart

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.ChartUiState
import com.example.domain.model.DrawingType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private class ChartJavascriptBridge(
    var onTap: (Long, Double) -> Unit,
    var onCrosshair: (Long, Double) -> Unit,
    var onQuickAlert: (Double) -> Unit
) {
    @JavascriptInterface fun onChartTap(timeSeconds: Double, price: Double) {
        onTap((timeSeconds * 1000.0).toLong(), price)
    }
    @JavascriptInterface fun onCrosshair(timeSeconds: Double, price: Double) {
        onCrosshair((timeSeconds * 1000.0).toLong(), price)
    }
    @JavascriptInterface fun onQuickAlert(price: Double) { onQuickAlert(price) }
    @JavascriptInterface fun onReady() = Unit
    @JavascriptInterface fun onError(message: String) = Unit
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LightweightChartView(
    state: ChartUiState,
    onChartTap: (Long, Double, Int) -> Unit,
    onCrosshairMove: (Long, Double) -> Unit,
    onQuickAlert: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    val bridge = remember {
        ChartJavascriptBridge({ _, _ -> }, { _, _ -> }, { })
    }
    bridge.onTap = { time, price ->
        val candles = state.displayedCandles
        val index = candles.indices.minByOrNull { abs(candles[it].openTime - time) } ?: 0
        onChartTap(time, price, index)
    }
    bridge.onCrosshair = onCrosshairMove
    bridge.onQuickAlert = onQuickAlert

    val payload = remember(state.displayedCandles, state.alertsForSymbol, state.drawings) {
        val root = JSONObject()
        val data = JSONArray()
        state.displayedCandles.forEach { candle ->
            data.put(JSONObject().apply {
                put("time", candle.openTime / 1000L)
                put("open", candle.open)
                put("high", candle.high)
                put("low", candle.low)
                put("close", candle.close)
                put("volume", candle.volume)
            })
        }
        root.put("candles", data)
        val lines = JSONArray()
        state.alertsForSymbol.filter { it.isActive }.forEach { alert ->
            lines.put(JSONObject().apply {
                put("price", alert.targetPrice)
                put("title", "Alert")
                put("color", "#f0b90b")
                put("dashed", true)
            })
        }
        state.drawings.filter { it.type == DrawingType.HORIZONTAL_LINE }.forEach { drawing ->
            drawing.points.firstOrNull()?.let { point ->
                lines.put(JSONObject().apply {
                    put("price", point.price)
                    put("title", "Drawing")
                    put("color", String.format("#%06X", 0xFFFFFF and drawing.colorHex.toInt()))
                })
            }
        }
        root.put("lines", lines)
        root.toString()
    }

    LaunchedEffect(payload, pageLoaded) {
        if (pageLoaded) {
            webView?.evaluateJavascript(
                "window.setMarketData && window.setMarketData(${JSONObject.quote(payload)})",
                null
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(9, 13, 18))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webChromeClient = WebChromeClient()
                addJavascriptInterface(bridge, "AndroidChart")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                        view.evaluateJavascript(
                            "window.setMarketData && window.setMarketData(${JSONObject.quote(payload)})",
                            null
                        )
                    }
                }
                webView = this
                loadUrl("file:///android_asset/lightweight_chart.html")
            }
        },
        update = { view -> webView = view },
        onRelease = { view ->
            pageLoaded = false
            webView = null
            view.removeJavascriptInterface("AndroidChart")
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    )
}
