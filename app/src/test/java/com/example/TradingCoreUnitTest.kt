package com.example

import com.example.alerts.AlertEvaluator
import com.example.data.remote.BinancePayloadParser
import com.example.data.remote.BybitLinearApi
import com.example.data.remote.BybitPayloadParser
import com.example.data.remote.MarketEndpointMode
import com.example.data.repository.CandleAggregator
import com.example.domain.model.AlertCondition
import com.example.domain.model.Candle
import com.example.domain.model.CustomTimeUnit
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
import com.example.ui.chart.ChartCoordinateMapper
import com.example.ui.chart.ChartViewportState
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TradingCoreUnitTest {

    @Test
    fun `candle aggregator handles 9m, 45m, 8h on Bybit, and custom timeframes accurately`() {
        val tf9m = Timeframe.DEFAULTS.first { it.id == "9m" }
        val (base9m, _) = CandleAggregator.pickBaseInterval(tf9m)
        assertEquals("3m", base9m)

        val tf45m = Timeframe.DEFAULTS.first { it.id == "45m" }
        val (base45m, _) = CandleAggregator.pickBaseInterval(tf45m)
        assertEquals("15m", base45m)

        val tf8h = Timeframe.DEFAULTS.first { it.id == "8H" }
        val (base8hBybit, _) = CandleAggregator.pickBaseInterval(tf8h, MarketEndpointMode.BYBIT_LINEAR)
        assertEquals("4h", base8hBybit)

        val custom7m = Timeframe.createCustom(7, CustomTimeUnit.MINUTES, 99)
        assertTrue(custom7m.isCustom)
        assertEquals("7m", custom7m.label)
        assertEquals("1m", CandleAggregator.pickBaseInterval(custom7m).first)

        val alignedStart = (1_700_000_000_000L / 540_000L) * 540_000L
        val threeMin = 180_000L
        val c1 = Candle(alignedStart, alignedStart + threeMin - 1, 100.0, 105.0, 99.0, 103.0, 10.0, true)
        val c2 = Candle(alignedStart + threeMin, alignedStart + 2 * threeMin - 1, 103.0, 108.0, 101.0, 106.0, 15.0, true)
        val c3 = Candle(alignedStart + 2 * threeMin, alignedStart + 3 * threeMin - 1, 106.0, 107.0, 102.0, 104.0, 12.0, true)

        val agg = CandleAggregator.aggregate(listOf(c1, c2, c3), tf9m)
        assertEquals(1, agg.size)
        val bar = agg.first()
        assertEquals(100.0, bar.open, 0.001)
        assertEquals(108.0, bar.high, 0.001)
        assertEquals(99.0, bar.low, 0.001)
        assertEquals(104.0, bar.close, 0.001)
        assertEquals(37.0, bar.volume, 0.001)
        assertTrue(bar.isClosed)
    }

    @Test
    fun `alert evaluator handles crosses, candle closes, breakout, and trendline touch`() {
        val crossAbove = PriceAlert(
            id = "a1",
            symbol = "BTCUSDT",
            condition = AlertCondition.CROSSES_ABOVE,
            targetPrice = 95000.0,
            lastEvaluatedPrice = 94800.0
        )
        assertFalse(AlertEvaluator.evaluateTick(crossAbove, 94950.0).triggered)
        assertTrue(AlertEvaluator.evaluateTick(crossAbove, 95050.0).triggered)

        val closeBelow = PriceAlert(
            id = "a2",
            symbol = "BTCUSDT",
            condition = AlertCondition.CANDLE_CLOSE_BELOW,
            targetPrice = 94000.0
        )
        val openCandle = Candle(0, 59999, 94200.0, 94300.0, 93800.0, 93900.0, 1.0, isClosed = false)
        assertFalse(AlertEvaluator.evaluateClosedCandle(closeBelow, openCandle).triggered)
        val closedCandle = openCandle.copy(isClosed = true)
        assertTrue(AlertEvaluator.evaluateClosedCandle(closeBelow, closedCandle).triggered)

        val trendAlert = PriceAlert(
            id = "a3",
            symbol = "ETHUSDT",
            condition = AlertCondition.TRENDLINE_TOUCH,
            targetPrice = 150.0,
            anchorTime1 = 1000L,
            anchorPrice1 = 100.0,
            anchorTime2 = 2000L,
            anchorPrice2 = 200.0,
            lastEvaluatedPrice = 148.0
        )
        assertTrue(AlertEvaluator.evaluateTick(trendAlert, 150.5, timestampMillis = 1500L).triggered)
    }

    @Test
    fun `coordinate mapper projects prices and times accurately`() {
        val candles = (0 until 20).map { i ->
            val t = 1_700_000_000_000L + i * 60_000L
            Candle(t, t + 59_999L, 100.0 + i, 102.0 + i, 98.0 + i, 101.0 + i, 50.0, true)
        }
        val vp = ChartViewportState(barSpacingPx = 20f, rightOffsetBars = 4f, priceMin = 90.0, priceMax = 130.0)
        val mapper = ChartCoordinateMapper(candles, 60_000L, 800f, 400f, vp)

        val y = mapper.priceToY(110.0)
        assertEquals(200f, y, 0.5f)
        assertEquals(110.0, mapper.yToPrice(y), 0.01)

        val x = mapper.timeToX(candles[10].openTime)
        val rtTime = mapper.xToTime(x)
        assertEquals(candles[10].openTime.toDouble(), rtTime.toDouble(), 1000.0)
    }

    @Test
    fun `binance and bybit payload parsers decode REST and WS feeds cleanly`() {
        val klineJson = """[[1700000000000,"91000.5","91500.0","90800.0","91250.5","123.4",1700000059999]]"""
        val parsed = BinancePayloadParser.parseKlineArray(klineJson)
        assertEquals(1, parsed.size)
        assertEquals(91250.5, parsed[0].close, 0.001)

        val wsJson = """
            {
              "stream":"btcusdt@markPriceKline_15m",
              "data":{
                "e":"markPriceKline",
                "s":"BTCUSDT",
                "k":{"t":1700000000000,"T":1700000899999,"s":"BTCUSDT","i":"15m","o":"91000","c":"91200","h":"91300","l":"90900","v":"0","x":false}
              }
            }
        """.trimIndent()
        var capturedSource: PriceSource? = null
        var capturedClose: Double? = null
        BinancePayloadParser.parseSocketMessage(
            rawText = wsJson,
            onTickers = {},
            onMarkPrices = {},
            onKline = {
                capturedSource = it.priceSource
                capturedClose = it.candle.close
            }
        )
        assertEquals(PriceSource.MARK, capturedSource)
        assertNotNull(capturedClose)
        assertEquals(91200.0, capturedClose!!, 0.001)

        // Bybit REST kline (newest first -> sorted oldest first)
        val bybitKlineJson = """
            {
              "retCode":0,
              "result":{
                "list":[
                  ["1700000900000","91200","91400","91100","91350","45.0","4000000"],
                  ["1700000000000","91000","91250","90900","91200","30.0","2700000"]
                ]
              }
            }
        """.trimIndent()
        val bybitCandles = BybitPayloadParser.parseKlineResponse(bybitKlineJson, 900_000L)
        assertEquals(2, bybitCandles.size)
        assertEquals(1700000000000L, bybitCandles[0].openTime)
        assertEquals(91350.0, bybitCandles[1].close, 0.001)

        // Bybit partial delta tickers WS keeps unrelated fields null instead of overwriting with 0
        var partialMark: Double? = null
        var partialIndex: Double? = 99.0
        BybitPayloadParser.parseSocketMessage(
            rawText = """{"topic":"tickers.BTCUSDT","type":"delta","data":{"symbol":"BTCUSDT","markPrice":"91420.5"}}""",
            onTickers = {},
            onMarkPrices = { list ->
                partialMark = list.first().markPrice
                partialIndex = list.first().indexPrice
            },
            onKline = {}
        )
        assertEquals(91420.5, partialMark!!, 0.001)
        assertNull(partialIndex)

        // Binance aggTrade and Bybit publicTrade fast live tick parsing + server clock sync
        var binanceTradePrice: Double? = null
        var binanceServerTs = 0L
        BinancePayloadParser.parseSocketMessage(
            rawText = """{"stream":"btcusdt@aggTrade","data":{"e":"aggTrade","E":1700000123456,"s":"BTCUSDT","p":"91499.8","q":"0.25"}}""",
            onTickers = {},
            onMarkPrices = {},
            onKline = {},
            onTrade = { binanceTradePrice = it.price },
            onServerTimestamp = { binanceServerTs = it }
        )
        assertEquals(91499.8, binanceTradePrice!!, 0.001)
        assertEquals(1700000123456L, binanceServerTs)

        var bybitTradePrice: Double? = null
        var bybitServerTs = 0L
        BybitPayloadParser.parseSocketMessage(
            rawText = """{"topic":"publicTrade.BTCUSDT","ts":1700000123999,"data":[{"s":"BTCUSDT","p":"91512.3","T":1700000123990}]}""",
            onTickers = {},
            onMarkPrices = {},
            onKline = {},
            onTrade = { bybitTradePrice = it.price },
            onServerTimestamp = { bybitServerTs = it }
        )
        assertEquals(91512.3, bybitTradePrice!!, 0.001)
        assertEquals(1700000123999L, bybitServerTs)
    }

    @Test
    fun `market repository falls back to bybit linear on http 451 and supports last mark index`() = kotlinx.coroutines.test.runTest {
        val err451 = retrofit2.HttpException(
            retrofit2.Response.error<Any>(
                451,
                "Restricted location".toResponseBody(null)
            )
        )
        val failingFuturesApi = object : com.example.data.remote.BinanceFuturesApi {
            override suspend fun getExchangeInfo(): com.example.data.remote.ExchangeInfoResponse = throw err451
            override suspend fun getTickers24hr(): List<com.example.data.remote.Ticker24hrDto> = throw err451
            override suspend fun getAllPremiumIndex(): List<com.example.data.remote.PremiumIndexDto> = throw err451
            override suspend fun getPremiumIndex(symbol: String): com.example.data.remote.PremiumIndexDto = throw err451
            override suspend fun getLastPriceKlines(symbol: String, interval: String, limit: Int, startTime: Long?, endTime: Long?) = throw err451
            override suspend fun getMarkPriceKlines(symbol: String, interval: String, limit: Int, startTime: Long?, endTime: Long?) = throw err451
            override suspend fun getIndexPriceKlines(pair: String, interval: String, limit: Int, startTime: Long?, endTime: Long?) = throw err451
        }
        val fakeBybitApi = object : BybitLinearApi {
            override suspend fun getLinearTickers(category: String) =
                """{"retCode":0,"result":{"list":[{"symbol":"BTCUSDT","lastPrice":"92000.5","markPrice":"92010.0","indexPrice":"91995.0","price24hPcnt":"0.025","turnover24h":"500000000","highPrice24h":"93000","lowPrice24h":"90000"}]}}"""
                    .toResponseBody(null)

            override suspend fun getLastPriceKlines(category: String, symbol: String, interval: String, limit: Int, end: Long?) =
                """{"retCode":0,"result":{"list":[["1700000000000","91900.0","92100.0","91800.0","92000.5","50.0"]]}}"""
                    .toResponseBody(null)

            override suspend fun getMarkPriceKlines(category: String, symbol: String, interval: String, limit: Int, end: Long?) =
                """{"retCode":0,"result":{"list":[["1700000000000","91910.0","92110.0","91810.0","92010.0"]]}}"""
                    .toResponseBody(null)

            override suspend fun getIndexPriceKlines(category: String, symbol: String, interval: String, limit: Int, end: Long?) =
                """{"retCode":0,"result":{"list":[["1700000000000","91890.0","92090.0","91790.0","91995.0"]]}}"""
                    .toResponseBody(null)
        }
        val fakeVisionApi = object : com.example.data.remote.BinanceVisionApi {
            override suspend fun getExchangeInfo() = com.example.data.remote.ExchangeInfoResponse()
            override suspend fun getTickers24hr() = emptyList<com.example.data.remote.Ticker24hrDto>()
            override suspend fun getKlines(symbol: String, interval: String, limit: Int, startTime: Long?, endTime: Long?) =
                "[]".toResponseBody(null)
        }
        val socketManager = com.example.data.remote.BinanceSocketManager(okhttp3.OkHttpClient())
        val repo = com.example.data.repository.MarketRepository(
            prodApi = failingFuturesApi,
            demoApi = failingFuturesApi,
            bybitApi = fakeBybitApi,
            visionApi = fakeVisionApi,
            socketManager = socketManager
        )

        val overviewRes = repo.refreshMarketOverview()
        assertTrue(overviewRes.isSuccess)
        assertEquals(MarketEndpointMode.BYBIT_LINEAR, repo.endpointMode.value)
        assertEquals(1, repo.contracts.value.size)

        val lastKlines = repo.fetchKlines("BTCUSDT", "BTCUSDT", PriceSource.LAST, Timeframe.DEFAULTS[4])
        assertEquals(92000.5, lastKlines.getOrThrow().first().close, 0.001)

        val markKlines = repo.fetchKlines("BTCUSDT", "BTCUSDT", PriceSource.MARK, Timeframe.DEFAULTS[4])
        assertEquals(92010.0, markKlines.getOrThrow().first().close, 0.001)

        val indexKlines = repo.fetchKlines("BTCUSDT", "BTCUSDT", PriceSource.INDEX, Timeframe.DEFAULTS[4])
        assertEquals(91995.0, indexKlines.getOrThrow().first().close, 0.001)
    }

    @Test
    fun `chart viewmodel preserves price scale on timeframe switch and supports 2d pan plus reset`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.get(ctx)
        val vm = com.example.ui.chart.ChartViewModel(
            appContext = ctx,
            marketRepository = container.marketRepository,
            chartStateRepository = container.chartStateRepository
        )

        // Manually scale price axis (zoomed range)
        vm.scalePriceAxis(-80f)
        val initialMin = vm.viewportState.value.priceMin
        val initialMax = vm.viewportState.value.priceMax
        val initialSpacing = vm.viewportState.value.barSpacingPx

        // Switching 1H -> 30m must preserve the exact price scale & bar spacing
        vm.selectTimeframe("1H")
        vm.selectTimeframe("30m")
        assertEquals(initialMin, vm.viewportState.value.priceMin, 0.0001)
        assertEquals(initialMax, vm.viewportState.value.priceMax, 0.0001)
        assertEquals(initialSpacing, vm.viewportState.value.barSpacingPx, 0.0001f)

        // Free 2D pan moves vertically even if autoFit was true
        vm.resetPriceAutoFitOnly()
        assertTrue(vm.viewportState.value.isAutoFit)
        vm.panChart(deltaXPx = 5f, deltaYPx = 40f, plotHeightPx = 400f)
        assertFalse(vm.viewportState.value.isAutoFit)

        // Explicit Price Reset restores autoFit
        vm.triggerAutoFit()
        assertTrue(vm.viewportState.value.isAutoFit)
    }

    @Test
    fun `tradingview gesture math symmetric axis scale focal pinch and double tap`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.get(ctx)
        val vm = com.example.ui.chart.ChartViewModel(
            appContext = ctx,
            marketRepository = container.marketRepository,
            chartStateRepository = container.chartStateRepository
        )
        vm.updatePlotWidth(1000f)

        // 1. Exponential price & time axis scaling is symmetric (+delta followed by -delta returns to baseline)
        val baseSpan = vm.viewportState.value.priceMax - vm.viewportState.value.priceMin
        val baseSpacing = vm.viewportState.value.barSpacingPx
        vm.scalePriceAxis(50f)
        vm.scalePriceAxis(-50f)
        val afterPriceSpan = vm.viewportState.value.priceMax - vm.viewportState.value.priceMin
        assertEquals(baseSpan, afterPriceSpan, 1e-6)

        vm.scaleTimeAxis(40f)
        vm.scaleTimeAxis(-40f)
        assertEquals(baseSpacing, vm.viewportState.value.barSpacingPx, 0.01f)

        // 2. Focal-point vertical pinch preserves the anchor price at focusYPx
        vm.scalePriceAxis(20f) // ensure isAutoFit = false
        val vpBeforePinch = vm.viewportState.value
        val plotHeight = 500f
        val focusY = 125f // normY = 0.25
        val anchorBefore = vpBeforePinch.priceMax - (focusY / plotHeight) * (vpBeforePinch.priceMax - vpBeforePinch.priceMin)

        vm.pinchZoom(
            zoomX = 1.25f,
            zoomY = 1.3f,
            focusXPx = 500f,
            focusYPx = focusY,
            plotHeightPx = plotHeight
        )
        val vpAfterPinch = vm.viewportState.value
        val anchorAfter = vpAfterPinch.priceMax - (focusY / plotHeight) * (vpAfterPinch.priceMax - vpAfterPinch.priceMin)
        assertEquals(anchorBefore, anchorAfter, 1e-6)
        assertFalse(vpAfterPinch.isAutoFit)

        // 3. Verify horizontal pan direction & double-tap reset
        val offsetBeforeDrag = vm.viewportState.value.rightOffsetBars
        val spacing = vm.viewportState.value.barSpacingPx
        // Dragging left (deltaXPx < 0) increases rightOffsetBars so candles shift left on screen
        vm.panChart(deltaXPx = -90f, deltaYPx = 0f, plotHeightPx = plotHeight)
        val offsetAfterLeftDrag = vm.viewportState.value.rightOffsetBars
        assertEquals(offsetBeforeDrag + (90f / spacing), offsetAfterLeftDrag, 0.001f)
        // Dragging right (deltaXPx > 0) decreases rightOffsetBars so candles shift right on screen
        vm.panChart(deltaXPx = 90f, deltaYPx = 0f, plotHeightPx = plotHeight)
        val offsetAfterRightDrag = vm.viewportState.value.rightOffsetBars
        assertEquals(offsetBeforeDrag, offsetAfterRightDrag, 0.001f)

        vm.onPlotDoubleTap()
        assertTrue(vm.viewportState.value.isAutoFit)
        assertEquals(offsetAfterRightDrag, vm.viewportState.value.rightOffsetBars, 0.001f)

        vm.onPlotDoubleTap()
        assertTrue(vm.viewportState.value.isAutoFit)
        assertEquals(8f, vm.viewportState.value.rightOffsetBars, 0.001f)
    }
}
