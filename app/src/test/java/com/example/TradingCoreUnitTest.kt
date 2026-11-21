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
import com.example.domain.model.FuturesContract
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
            override suspend fun getLinearInstruments(category: String, limit: Int) =
                """{"retCode":0,"result":{"list":[{"symbol":"BTCUSDT","baseCoin":"BTC","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"Trading","launchTime":"1585526400000","priceScale":"1","priceFilter":{"tickSize":"0.1"}},{"symbol":"NEWCOINUSDT","baseCoin":"NEWCOIN","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"PreLaunch","launchTime":"4102444800000","priceScale":"4","priceFilter":{"tickSize":"0.0001"}}]}}"""
                    .toResponseBody(null)

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
        assertEquals(2, repo.contracts.value.size)
        val newContract = repo.contracts.value.first { it.symbol == "NEWCOINUSDT" }
        assertTrue(newContract.isUpcomingListing())
        assertEquals(4102444800000L, newContract.onboardDate)

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

    @Test
    fun `market cap parser normalizes scaled futures symbols and preserves highest rank`() {
        val geckoJson = """
            [
              {"id":"bitcoin","symbol":"btc","market_cap":1800000000000,"market_cap_rank":1},
              {"id":"pepe","symbol":"pepe","market_cap":8500000000,"market_cap_rank":21},
              {"id":"fake-btc","symbol":"btc","market_cap":1000,"market_cap_rank":999},
              {"id":"1inch","symbol":"1inch","market_cap":450000000,"market_cap_rank":140}
            ]
        """.trimIndent()
        val parsed = com.example.data.remote.MarketCapPayloadParser.parseCoinGeckoMarkets(geckoJson)
        assertEquals(1, parsed["BTC"]?.rank)
        assertEquals(21, parsed["PEPE"]?.rank)
        assertEquals(140, parsed["1INCH"]?.rank)

        assertEquals("PEPE", com.example.data.remote.MarketCapPayloadParser.normalizeBaseAsset("1000PEPE"))
        assertEquals("BABYDOGE", com.example.data.remote.MarketCapPayloadParser.normalizeBaseAsset("1MBABYDOGE"))
        assertEquals("1INCH", com.example.data.remote.MarketCapPayloadParser.normalizeBaseAsset("1INCH"))

        val pepeMatch = com.example.data.remote.MarketCapPayloadParser.lookupForBaseAsset("1000PEPE", "1000PEPEUSDT", parsed)
        assertNotNull(pepeMatch)
        assertEquals(21, pepeMatch!!.rank)
    }

    @Test
    fun `chart viewmodel cycles previous and next symbols within active category context`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer.get(ctx)
        val vm = com.example.ui.chart.ChartViewModel(
            appContext = ctx,
            marketRepository = container.marketRepository,
            chartStateRepository = container.chartStateRepository
        )

        val gainersList = listOf("SOLUSDT", "ETHUSDT", "AVAXUSDT")
        vm.openSymbol("ETHUSDT", contextSymbols = gainersList, contextLabel = "Gainers")
        assertEquals("ETHUSDT", vm.symbolState.value)
        assertEquals(gainersList, vm.navigationContextSymbolsState.value)

        vm.selectNextSymbol()
        assertEquals("AVAXUSDT", vm.symbolState.value)

        // Wraps cleanly around to start of active category
        vm.selectNextSymbol()
        assertEquals("SOLUSDT", vm.symbolState.value)

        // Previous wraps cleanly back to end of active category
        vm.selectPreviousSymbol()
        assertEquals("AVAXUSDT", vm.symbolState.value)
    }

    @Test
    fun `futures contract detects upcoming and recently listed symbols and formats listing schedule`() {
        val now = 1_750_000_000_000L
        val upcomingByStatus = FuturesContract(
            symbol = "SOONUSDT",
            pair = "SOONUSDT",
            baseAsset = "SOON",
            quoteAsset = "USDT",
            pricePrecision = 4,
            quantityPrecision = 1,
            tickSize = 0.0001,
            contractType = "PERPETUAL",
            status = "PENDING_TRADING",
            onboardDate = now + 2L * 86_400_000L + 3L * 3_600_000L
        )
        val recentCoin = FuturesContract(
            symbol = "FRESHUSDT",
            pair = "FRESHUSDT",
            baseAsset = "FRESH",
            quoteAsset = "USDT",
            pricePrecision = 3,
            quantityPrecision = 1,
            tickSize = 0.001,
            contractType = "PERPETUAL",
            status = "TRADING",
            onboardDate = now - 3L * 86_400_000L
        )
        val oldCoin = FuturesContract(
            symbol = "BTCUSDT",
            pair = "BTCUSDT",
            baseAsset = "BTC",
            quoteAsset = "USDT",
            pricePrecision = 1,
            quantityPrecision = 3,
            tickSize = 0.1,
            contractType = "PERPETUAL",
            status = "TRADING",
            onboardDate = now - 400L * 86_400_000L
        )

        assertTrue(upcomingByStatus.isUpcomingListing(now))
        assertTrue(upcomingByStatus.isRecentlyListed(now))
        assertFalse(recentCoin.isUpcomingListing(now))
        assertTrue(recentCoin.isRecentlyListed(now, windowDays = 30))
        assertFalse(oldCoin.isRecentlyListed(now, windowDays = 30))

        val upcomingText = com.example.ui.chart.ChartNumberFormatter.formatListingSubtext(
            upcomingByStatus.onboardDate,
            upcomingByStatus.status,
            now
        )
        assertNotNull(upcomingText)
        assertTrue(upcomingText!!.contains("Opens") && upcomingText.contains("in 2d 3h"))

        val recentText = com.example.ui.chart.ChartNumberFormatter.formatListingSubtext(
            recentCoin.onboardDate,
            recentCoin.status,
            now
        )
        assertNotNull(recentText)
        assertTrue(recentText!!.contains("Listed") && recentText.contains("3d ago"))
    }

    @Test
    fun `tbt smc engine handles insufficient candles and detects demand zone power shift 3cr and bias`() {
        val step = 900_000L
        val baseTime = 1_750_000_000_000L

        fun bar(idx: Int, o: Double, h: Double, l: Double, c: Double) = Candle(
            openTime = baseTime + idx * step,
            closeTime = baseTime + idx * step + step - 1L,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = 1000.0,
            isClosed = true
        )

        // 1. Less than 25 candles -> Waiting for data
        val shortList = (0 until 20).map { i -> bar(i, 100.0, 101.0, 99.0, 100.5) }
        val shortOut = com.example.domain.indicator.TbtSmcEngine.calculate(shortList)
        assertTrue(shortOut.markers.isEmpty())
        assertTrue(shortOut.priceLines.isEmpty())
        assertEquals(com.example.domain.indicator.TbtMarketBias.SIDEWAYS, shortOut.summary.bias)
        assertEquals("Waiting for data...", shortOut.summary.lastSignal)

        // 2. Construct deterministic sequence >= 28 candles:
        // Bars 0..15: tight range around 100 (avg body ~0.6)
        val candles = mutableListOf<Candle>()
        for (i in 0..15) {
            val o = 100.0
            val c = if (i % 2 == 0) 100.6 else 99.4
            candles.add(bar(i, o, 101.0, 99.0, c))
        }
        // Bar 16: small bearish base candle (body = 0.2 < avgBody*0.9)
        candles.add(bar(16, 100.0, 100.2, 99.5, 99.8))
        // Bar 17: huge bullish impulse (body = 4.2 > avgBody*1.3, close 104.3 > c1.high 100.2) -> DBR Demand (top=100.0, bottom=99.5)
        candles.add(bar(17, 100.1, 104.3, 100.1, 104.3))
        // Bar 18..20: drift above zone
        candles.add(bar(18, 104.0, 104.4, 103.0, 103.5))
        candles.add(bar(19, 103.5, 103.8, 102.0, 102.2))
        // Bar 20: taps demand zone (low 99.6 <= 100.0 and close 101.0 > bottom 99.5) -> sets activeBullPower = 99.6
        candles.add(bar(20, 102.0, 102.2, 99.6, 101.0))
        // Bar 21..24: 3CR BUY setup (bar 21 bearish, bar 22 sweeps low, bar 23 closes > max(c1.high, c2.high) opening cleanly above base top) & rally for BULLISH bias
        candles.add(bar(21, 101.5, 102.0, 100.5, 100.6))
        candles.add(bar(22, 100.6, 101.5, 100.1, 101.2))
        candles.add(bar(23, 101.3, 105.5, 101.3, 105.0))
        candles.add(bar(24, 104.8, 107.5, 101.4, 107.0))
        candles.add(bar(25, 107.0, 109.5, 105.2, 109.2))

        val fullOut = com.example.domain.indicator.TbtSmcEngine.calculate(candles)
        assertTrue(fullOut.zones.any { it.type == com.example.domain.indicator.TbtZoneType.DEMAND && it.tapped })
        assertTrue(fullOut.priceLines.any { it.title.contains("Demand") && it.title.contains("Tapped") })
        assertTrue(fullOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.POWER_SHIFT })
        assertEquals(99.6, fullOut.summary.activeBullPower!!, 0.0001)
        assertTrue(fullOut.markers.any { it.text == "3CR BUY" })
        assertTrue(fullOut.markers.none { it.text == "BOS" || it.text == "CHoCH" })
        assertEquals(com.example.domain.indicator.TbtMarketBias.BULLISH, fullOut.summary.bias)

        // 3. Respects all layer toggles (including Volume Imbalance line and Zone tags)
        val filteredOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles,
            com.example.domain.indicator.TbtSmcConfig(
                showZones = false,
                showPowerShift = false,
                showOverlap = false,
                showSignals = false,
                showHud = false,
                showVolImbalanceLine = false,
                showZoneTags = false
            )
        )
        assertTrue(filteredOut.priceLines.isEmpty())
        assertTrue(filteredOut.markers.isEmpty())

        // 4. Enforces single marker per candle timestamp (sorted ascending)
        val markerTimes = fullOut.markers.map { it.timeMillis }
        assertEquals(markerTimes.distinct().sorted(), markerTimes)

        // 5. Default recommended fetch limit is tuned for fast initial chart loading
        val m15 = Timeframe.DEFAULTS.first { it.id == "15m" }
        assertEquals(200, CandleAggregator.recommendedFetchLimit(m15))
    }

    @Test
    fun `pure tbt smc htf api bias auto hide vol imbalance buy setup and backtest report work accurately`() {
        // 1. Auto-Hide on HTF (>15m) logic on ChartUiState
        val tf15m = Timeframe.DEFAULTS.first { it.id == "15m" }
        val tf30m = Timeframe.DEFAULTS.first { it.id == "30m" }
        val tf1h = Timeframe.DEFAULTS.first { it.id == "1H" }
        val prefsAutoHide = com.example.domain.model.ChartPreferences(showTbtSmc = true, tbtAutoHideOnHtf = true)
        assertTrue(com.example.ui.chart.ChartUiState(activeTimeframe = tf15m, preferences = prefsAutoHide).isTbtEffectivelyVisible)
        assertFalse(com.example.ui.chart.ChartUiState(activeTimeframe = tf30m, preferences = prefsAutoHide).isTbtEffectivelyVisible)
        assertFalse(com.example.ui.chart.ChartUiState(activeTimeframe = tf1h, preferences = prefsAutoHide).isTbtEffectivelyVisible)
        assertTrue(
            com.example.ui.chart.ChartUiState(
                activeTimeframe = tf1h,
                preferences = prefsAutoHide.copy(tbtAutoHideOnHtf = false)
            ).isTbtEffectivelyVisible
        )

        // 2. Live HTF API candle analysis detects BULLISH trend bias
        val utcDayBase = 1_750_032_000_000L
        val htfCandles = (0 until 25).map { idx ->
            val base = 95.0 + idx * 0.8
            Candle(
                openTime = utcDayBase + idx * 3_600_000L,
                closeTime = utcDayBase + (idx + 1) * 3_600_000L - 1L,
                open = base,
                high = base + 1.2,
                low = base - 0.5,
                close = base + 0.9,
                volume = 5000.0,
                isClosed = true
            )
        }
        assertEquals(
            com.example.domain.indicator.TbtMarketBias.BULLISH,
            com.example.domain.indicator.TbtSmcEngine.analyzeHigherTimeframe(htfCandles)
        )

        // 3. Single Volume Imbalance line + RBR/DBR tags + Backtest winRate/netR (no ICT session restriction!)
        val step = 300_000L // 5m
        val asiaStart = utcDayBase + 2 * 3_600_000L // 02:00 UTC (works 24/7 — no ICT Killzone gating)
        fun bar(idx: Int, o: Double, h: Double, l: Double, c: Double, vol: Double = 1200.0) = Candle(
            openTime = asiaStart + idx * step,
            closeTime = asiaStart + idx * step + step - 1L,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = vol,
            isClosed = true
        )

        val candles = mutableListOf<Candle>()
        for (i in 0..15) {
            val c = if (i % 2 == 0) 100.5 else 99.5
            candles.add(bar(i, 100.0, 101.0, 99.0, c, vol = 1000.0))
        }
        // Bar 16 small base, Bar 17 huge bull impulse -> DBR tag
        candles.add(bar(16, 100.0, 100.2, 99.6, 99.8, vol = 900.0))
        candles.add(bar(17, 100.1, 104.2, 100.1, 104.0, vol = 1800.0))
        // Bar 18..20: 3CR BUY (SL around ~100.9, entry ~105.0, risk ~4.1, TP at 1:2 = ~113.2)
        candles.add(bar(18, 104.0, 104.2, 101.8, 102.0, vol = 1100.0))
        candles.add(bar(19, 102.0, 102.5, 101.0, 102.2, vol = 1200.0))
        candles.add(bar(20, 102.3, 105.2, 102.2, 105.0, vol = 2200.0))
        // Bar 21..25: rally to hit 1:2 TP
        candles.add(bar(21, 105.1, 109.0, 104.9, 108.8, vol = 1600.0))
        candles.add(bar(22, 108.9, 114.5, 108.5, 114.0, vol = 1700.0))
        candles.add(bar(23, 114.0, 118.0, 113.5, 117.5, vol = 1500.0))
        candles.add(bar(24, 117.5, 119.0, 116.8, 118.2, vol = 1400.0))
        candles.add(bar(25, 118.2, 120.0, 117.9, 119.5, vol = 1400.0))

        // Check active BUY setup right at bar 20 (before TP hits!)
        val liveSetupOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = candles.subList(0, 21) + bar(21, 105.0, 106.0, 104.5, 105.5, vol = 1300.0) +
                bar(22, 105.5, 106.2, 105.0, 105.8, vol = 1300.0) +
                bar(23, 105.8, 106.5, 105.2, 106.0, vol = 1300.0) +
                bar(24, 106.0, 106.8, 105.5, 106.2, vol = 1300.0),
            config = com.example.domain.indicator.TbtSmcConfig(
                targetRR = 3.0,
                slBufferPct = 0.05,
                showVolImbalanceLine = true,
                showZoneTags = true
            ),
            htfCandles = htfCandles,
            htfLabel = "1H"
        )
        val setup = liveSetupOut.activeSetup
        assertNotNull("Expected active TBT BUY setup after 3CR BUY", setup)
        assertEquals(com.example.domain.indicator.TbtTradeDirection.BUY, setup!!.direction)
        assertEquals("1H", setup.htfLabel)
        assertEquals(com.example.domain.indicator.TbtMarketBias.BULLISH, setup.htfBias)
        assertTrue(setup.stopLoss < setup.entryPrice)
        assertTrue(setup.takeProfit > setup.entryPrice)
        assertEquals(5, setup.checklist.size)
        assertTrue(liveSetupOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_ENTRY })
        assertTrue(liveSetupOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_SL })
        assertTrue(liveSetupOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_TP })

        // Check completed backtest win on full series
        val out = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = candles,
            config = com.example.domain.indicator.TbtSmcConfig(
                targetRR = 2.0,
                slBufferPct = 0.05,
                showVolImbalanceLine = true,
                showZoneTags = true
            ),
            htfCandles = htfCandles,
            htfLabel = "1H"
        )
        assertTrue(out.markers.any { it.text == "DBR" || it.text == "RBR" })
        assertEquals(1, out.priceLines.count { it.category == com.example.domain.indicator.TbtPriceLineCategory.VOL_IMBALANCE })
        assertTrue((out.backtestReport.totalTrades) >= 1)
        assertEquals(100, out.backtestReport.winRate)
        assertTrue((out.backtestReport.netR ?: 0.0) >= 2.0)
    }

    @Test
    fun `custom timeframe 10m sorts immediately before 15m and after 5m and 9m`() {
        val custom10m = Timeframe.createCustom(10, CustomTimeUnit.MINUTES, sortOrder = 99)
        assertTrue(custom10m.isCustom)
        val sortedAll = (Timeframe.DEFAULTS + custom10m)
            .sortedWith(compareBy<Timeframe> { it.durationMillis }.thenBy { it.sortOrder }.thenBy { it.id })
        val idx9m = sortedAll.indexOfFirst { it.id == "9m" }
        val idx10m = sortedAll.indexOfFirst { it.id == custom10m.id }
        val idx15m = sortedAll.indexOfFirst { it.id == "15m" }
        assertEquals(idx9m + 1, idx10m)
        assertEquals(idx10m + 1, idx15m)

        val sortedFavs = sortedAll.filter { it.isFavorite }
        val favIdx5m = sortedFavs.indexOfFirst { it.id == "5m" }
        val favIdx10m = sortedFavs.indexOfFirst { it.id == custom10m.id }
        val favIdx15m = sortedFavs.indexOfFirst { it.id == "15m" }
        assertEquals(favIdx5m + 1, favIdx10m)
        assertEquals(favIdx10m + 1, favIdx15m)
    }

    @Test
    fun `loud alert beep waveform generates strong multi-pulse PCM audio`() {
        val pcm = com.example.alerts.AlertNotifier.synthesizeLoudBeepWaveform()
        assertTrue(pcm.size > 60_000)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("Expected loud peak amplitude > 25000 but was $peak", peak > 25_000)
        // Verify silence gap between pulse 1 (ends 210ms) and pulse 2 (starts 310ms)
        val gapSample = pcm[(44_100 * 260) / 1000]
        assertEquals(0, gapSample.toInt())
    }
}
