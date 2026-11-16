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
        candles.add(bar(24, 105.0, 108.0, 104.8, 107.8))
        candles.add(bar(25, 107.8, 110.0, 107.5, 109.5))

        val fullOut = com.example.domain.indicator.TbtSmcEngine.calculate(candles)
        assertTrue(fullOut.zones.any { it.type == com.example.domain.indicator.TbtZoneType.DEMAND && it.tapped })
        assertTrue(fullOut.priceLines.any { it.title.contains("Demand") && it.title.contains("Tapped") })
        assertTrue(fullOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.POWER_SHIFT })
        assertEquals(99.6, fullOut.summary.activeBullPower!!, 0.0001)
        assertTrue(fullOut.markers.any { it.text == "3CR BUY" })
        assertEquals(com.example.domain.indicator.TbtMarketBias.BULLISH, fullOut.summary.bias)

        // 3. Respects layer toggles (zones, signals, and trade setup hidden)
        val filteredOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles,
            com.example.domain.indicator.TbtSmcConfig(
                showZones = false,
                showPowerShift = false,
                showOverlap = false,
                showSignals = false,
                showTradeSetup = false,
                showVolumeProfile = false,
                showLiquidityTraps = false
            )
        )
        assertTrue(filteredOut.priceLines.isEmpty())
        assertTrue(filteredOut.markers.isEmpty())
    }

    @Test
    fun `tbt mtf timeframe resolver selects appropriate htf and macro tiers`() {
        val m5 = Timeframe.DEFAULTS.first { it.id == "5m" }
        val m15 = Timeframe.DEFAULTS.first { it.id == "15m" }
        val h1 = Timeframe.DEFAULTS.first { it.id == "1H" }
        val h4 = Timeframe.DEFAULTS.first { it.id == "4H" }

        val p5 = com.example.domain.indicator.TbtMtfTimeframeResolver.resolve(m5, Timeframe.DEFAULTS)
        assertNotNull(p5)
        assertEquals("1H", p5!!.htf.id)
        assertEquals("4H", p5.macro.id)

        val p15 = com.example.domain.indicator.TbtMtfTimeframeResolver.resolve(m15, Timeframe.DEFAULTS)
        assertNotNull(p15)
        assertEquals("1H", p15!!.htf.id)
        assertEquals("4H", p15.macro.id)

        val p1h = com.example.domain.indicator.TbtMtfTimeframeResolver.resolve(h1, Timeframe.DEFAULTS)
        assertNotNull(p1h)
        assertEquals("4H", p1h!!.htf.id)
        assertEquals("1D", p1h.macro.id)

        val p4h = com.example.domain.indicator.TbtMtfTimeframeResolver.resolve(h4, Timeframe.DEFAULTS)
        assertNotNull(p4h)
        assertEquals("1D", p4h!!.htf.id)
        assertEquals("1W", p4h.macro.id)

        val custom = Timeframe(id = "9m", label = "9m", durationMillis = 9L * Timeframe.MINUTE_MS, nativeInterval = null, isCustom = true)
        val pCustom = com.example.domain.indicator.TbtMtfTimeframeResolver.resolve(custom, Timeframe.DEFAULTS)
        assertNotNull(pCustom)
        assertEquals("1H", pCustom!!.htf.id)
        assertEquals("4H", pCustom.macro.id)
    }

    @Test
    fun `tbt smc mtf buy and sell setups compute entry sl tp1 tp2 with valid structural bounds and rr`() {
        val baseTime = 1_750_000_000_000L

        fun seq(count: Int, stepMs: Long, startPrice: Double, delta: Double): List<Candle> {
            return (0 until count).map { i ->
                val o = startPrice + i * delta
                val c = o + delta * 0.9
                val h = maxOf(o, c) + kotlin.math.abs(delta) * 0.6 + 0.5
                val l = minOf(o, c) - kotlin.math.abs(delta) * 0.6 - 0.5
                Candle(
                    openTime = baseTime + i * stepMs,
                    closeTime = baseTime + i * stepMs + stepMs - 1L,
                    open = o,
                    high = h,
                    low = l,
                    close = c,
                    volume = 1000.0,
                    isClosed = true
                )
            }
        }

        // Bullish MTF alignment: steady uptrend on 1H & 4H + LTF DBR demand + tap + 3CR BUY
        val ltfStep = 900_000L
        val ltfBull = mutableListOf<Candle>()
        fun bar(idx: Int, o: Double, h: Double, l: Double, c: Double) = Candle(
            openTime = baseTime + idx * ltfStep,
            closeTime = baseTime + idx * ltfStep + ltfStep - 1L,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = 1000.0,
            isClosed = true
        )
        for (i in 0..15) {
            val c = if (i % 2 == 0) 100.6 else 99.4
            ltfBull.add(bar(i, 100.0, 101.0, 99.0, c))
        }
        ltfBull.add(bar(16, 100.0, 100.2, 99.5, 99.8))
        ltfBull.add(bar(17, 100.1, 104.3, 100.1, 104.3))
        ltfBull.add(bar(18, 104.0, 104.4, 103.0, 103.5))
        ltfBull.add(bar(19, 103.5, 103.8, 102.0, 102.2))
        ltfBull.add(bar(20, 102.0, 102.2, 99.6, 101.0))
        ltfBull.add(bar(21, 101.5, 102.0, 100.5, 100.6))
        ltfBull.add(bar(22, 100.6, 101.5, 100.1, 101.2))
        ltfBull.add(bar(23, 101.3, 105.5, 101.3, 105.0))
        ltfBull.add(bar(24, 105.0, 108.0, 104.8, 107.8))
        ltfBull.add(bar(25, 107.8, 110.0, 107.5, 109.5))

        val htfBull = seq(32, 3_600_000L, 90.0, 0.7)
        val macroBull = seq(32, 14_400_000L, 80.0, 1.0)

        val buyOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = ltfBull,
            config = com.example.domain.indicator.TbtSmcConfig(showTradeSetup = true, useMultiTimeframe = true),
            mtfInput = com.example.domain.indicator.TbtMtfInput(
                currentTfLabel = "15m",
                htfLabel = "1H",
                htfCandles = htfBull,
                macroLabel = "4H",
                macroCandles = macroBull
            )
        )

        val buySetup = buyOut.summary.tradeSetup
        assertEquals(com.example.domain.indicator.TbtTradeDirection.BUY, buySetup.direction)
        assertTrue(buySetup.stopLoss < buySetup.entryPrice)
        assertTrue(buySetup.entryPrice < buySetup.takeProfit1)
        assertTrue(buySetup.takeProfit1 < buySetup.takeProfit2)
        assertTrue(buySetup.riskReward1 >= 1.5)
        assertTrue(buySetup.riskReward2 > buySetup.riskReward1)
        assertEquals(3, buyOut.summary.mtfBiases.size)
        assertTrue(buyOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_ENTRY })
        assertTrue(buyOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_SL })
        assertTrue(buyOut.priceLines.none { it.category == com.example.domain.indicator.TbtPriceLineCategory.TRADE_TP })
        assertTrue(buyOut.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.VOLUME_PROFILE })

        // Bearish MTF alignment: steady downtrend on 1H & 4H + LTF RBD supply + tap + drop
        val ltfBear = mutableListOf<Candle>()
        for (i in 0..15) {
            val c = if (i % 2 == 0) 199.4 else 200.6
            ltfBear.add(bar(i, 200.0, 201.0, 199.0, c))
        }
        // Small bullish base then strong bearish drop -> Supply zone
        ltfBear.add(bar(16, 200.0, 200.5, 199.8, 200.2))
        ltfBear.add(bar(17, 200.0, 200.1, 195.2, 195.5))
        ltfBear.add(bar(18, 195.6, 197.0, 195.0, 196.4))
        ltfBear.add(bar(19, 196.4, 198.2, 196.0, 197.8))
        // Tap supply (high 200.3 >= 200.0, close 198.8 < 200.5)
        ltfBear.add(bar(20, 197.8, 200.3, 197.5, 198.8))
        // Bearish 3CR
        ltfBear.add(bar(21, 198.5, 199.5, 198.0, 199.4))
        ltfBear.add(bar(22, 199.4, 199.9, 198.5, 198.8))
        ltfBear.add(bar(23, 198.7, 198.8, 194.2, 194.5))
        ltfBear.add(bar(24, 194.5, 194.8, 191.8, 192.0))
        ltfBear.add(bar(25, 192.0, 192.4, 189.5, 190.2))

        val htfBear = seq(32, 3_600_000L, 230.0, -0.8)
        val macroBear = seq(32, 14_400_000L, 250.0, -1.0)

        val sellOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = ltfBear,
            config = com.example.domain.indicator.TbtSmcConfig(showTradeSetup = true, useMultiTimeframe = true),
            mtfInput = com.example.domain.indicator.TbtMtfInput(
                currentTfLabel = "15m",
                htfLabel = "1H",
                htfCandles = htfBear,
                macroLabel = "4H",
                macroCandles = macroBear
            )
        )

        val sellSetup = sellOut.summary.tradeSetup
        assertEquals(com.example.domain.indicator.TbtTradeDirection.SELL, sellSetup.direction)
        assertTrue(sellSetup.stopLoss > sellSetup.entryPrice)
        assertTrue(sellSetup.entryPrice > sellSetup.takeProfit1)
        assertTrue(sellSetup.takeProfit1 > sellSetup.takeProfit2)
        assertTrue(sellSetup.takeProfit2 > 0.0)
        assertTrue(sellSetup.riskReward1 >= 1.5)
    }

    @Test
    fun `tbt smc binance futures and spot volume profile identifies retracement entry and respects toggle`() {
        val baseTime = 1_750_000_000_000L
        val ltfStep = 900_000L
        fun bar(idx: Int, o: Double, h: Double, l: Double, c: Double, vol: Double) = Candle(
            openTime = baseTime + idx * ltfStep,
            closeTime = baseTime + idx * ltfStep + ltfStep - 1L,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = vol,
            isClosed = true
        )

        val ltf = mutableListOf<Candle>()
        val spot = mutableListOf<Candle>()
        for (i in 0..15) {
            val c = if (i % 2 == 0) 100.6 else 99.4
            ltf.add(bar(i, 100.0, 101.0, 99.0, c, 500.0))
            spot.add(bar(i, 100.0, 101.0, 99.0, c, 450.0))
        }
        // DBR Demand base + tap with massive institutional volume concentrated near 101.0 - 102.0
        ltf.add(bar(16, 100.0, 100.2, 99.5, 99.8, 1200.0))
        ltf.add(bar(17, 100.1, 104.3, 100.1, 104.3, 4500.0))
        ltf.add(bar(18, 104.0, 104.4, 103.0, 103.5, 800.0))
        ltf.add(bar(19, 103.5, 103.8, 102.0, 102.2, 900.0))
        // High-volume retracement & demand tap cluster around 101.2
        ltf.add(bar(20, 102.0, 102.2, 99.6, 101.0, 18000.0))
        spot.add(bar(16, 100.0, 100.2, 99.5, 99.8, 1000.0))
        spot.add(bar(17, 100.1, 104.3, 100.1, 104.3, 4200.0))
        spot.add(bar(18, 104.0, 104.4, 103.0, 103.5, 700.0))
        spot.add(bar(19, 103.5, 103.8, 102.0, 102.2, 850.0))
        spot.add(bar(20, 102.0, 102.2, 99.6, 101.0, 22000.0))

        ltf.add(bar(21, 101.5, 102.0, 100.5, 100.6, 12000.0))
        ltf.add(bar(22, 100.6, 101.5, 100.1, 101.2, 14000.0))
        ltf.add(bar(23, 101.3, 105.5, 101.3, 105.0, 9000.0))
        ltf.add(bar(24, 105.0, 108.0, 104.8, 107.8, 1500.0))
        ltf.add(bar(25, 107.8, 110.0, 107.5, 109.5, 1400.0))

        spot.add(bar(21, 101.5, 102.0, 100.5, 100.6, 10000.0))
        spot.add(bar(22, 100.6, 101.5, 100.1, 101.2, 11000.0))
        spot.add(bar(23, 101.3, 105.5, 101.3, 105.0, 7000.0))
        spot.add(bar(24, 105.0, 108.0, 104.8, 107.8, 1300.0))
        spot.add(bar(25, 107.8, 110.0, 107.5, 109.5, 1200.0))

        val withVp = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = ltf,
            config = com.example.domain.indicator.TbtSmcConfig(showVolumeProfile = true),
            mtfInput = com.example.domain.indicator.TbtMtfInput(spotCandles = spot)
        )
        assertEquals("Futures+Spot", withVp.summary.volumeSourcesLabel)
        val retrace = withVp.summary.tradeSetup.volumeRetraceEntry
        assertNotNull(retrace)
        assertTrue(retrace!! >= withVp.summary.tradeSetup.stopLoss)
        assertTrue(retrace <= ltf.last().close)
        assertTrue(withVp.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.VOLUME_PROFILE })

        val hiddenVp = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = ltf,
            config = com.example.domain.indicator.TbtSmcConfig(showVolumeProfile = false),
            mtfInput = com.example.domain.indicator.TbtMtfInput(spotCandles = spot)
        )
        assertTrue(hiddenVp.priceLines.none { it.category == com.example.domain.indicator.TbtPriceLineCategory.VOLUME_PROFILE })
    }

    @Test
    fun `tbt smc phase 2 detects eql eqh traps breaker flip zones idm sweep and htf overlap`() {
        val baseTime = 1_750_000_000_000L
        val step = 900_000L
        fun bar(idx: Int, o: Double, h: Double, l: Double, c: Double, vol: Double = 1000.0) = Candle(
            openTime = baseTime + idx * step,
            closeTime = baseTime + idx * step + step - 1L,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = vol,
            isClosed = true
        )

        // Build sequence with:
        // 1) An RBD Supply zone created around bar 17 (top ~102.0, bottom ~100.8)
        // 2) Two Equal Lows near 98.0 (indices 18 and 25, separated by >= 3 bars with swingLen=5)
        // 3) Bar 31 sweeps below EQL 98.0 (low 97.4) and closes above 98.0 -> EQL Trap
        // 4) Bar 32 breaks strongly above Supply top 102.0 -> Bullish Flip (Breaker)
        val candles = mutableListOf<Candle>()
        for (i in 0..15) {
            candles.add(bar(i, 100.0, 101.2, 99.0, if (i % 2 == 0) 100.4 else 99.6))
        }
        candles.add(bar(16, 101.0, 102.0, 100.8, 101.2)) // base
        candles.add(bar(17, 101.0, 101.1, 98.2, 98.4, 2500.0)) // RBD Supply drop (top 102.0, bottom 100.8)
        candles.add(bar(18, 98.5, 99.0, 98.0, 98.8)) // 1st Swing Low @ 98.0
        candles.add(bar(19, 98.8, 99.4, 98.4, 99.2))
        candles.add(bar(20, 99.2, 100.0, 98.9, 99.8))
        candles.add(bar(21, 99.8, 100.3, 99.2, 99.5))
        candles.add(bar(22, 99.5, 100.2, 99.0, 99.4))
        candles.add(bar(23, 99.4, 100.1, 98.8, 99.1))
        candles.add(bar(24, 99.1, 99.5, 98.5, 98.6))
        candles.add(bar(25, 98.6, 99.2, 98.05, 98.9)) // 2nd Swing Low ~98.05 -> forms EQL Pool
        candles.add(bar(26, 98.9, 99.8, 98.6, 99.6))
        candles.add(bar(27, 99.6, 100.2, 99.2, 99.9))
        candles.add(bar(28, 99.9, 100.5, 99.5, 100.1))
        candles.add(bar(29, 100.1, 100.4, 99.6, 99.8))
        candles.add(bar(30, 99.8, 100.0, 98.8, 99.0))
        // Bar 31: sweeps below EQL 98.0 -> low 97.4, closes 99.2 -> EQL Trap!
        candles.add(bar(31, 99.0, 99.4, 97.4, 99.2, 4200.0))
        // Bar 32..34: impulse rally breaking Supply (top 102.0) -> Bullish Flip (Breaker)
        candles.add(bar(32, 99.2, 102.9, 99.1, 102.8, 5200.0))
        candles.add(bar(33, 102.8, 103.4, 101.6, 102.3, 3000.0)) // taps Bullish Flip zone

        // HTF Bullish Demand overlapping near 100.5..102.5
        val htfCandles = mutableListOf<Candle>()
        for (i in 0..15) {
            htfCandles.add(
                Candle(
                    openTime = baseTime + i * 3_600_000L,
                    closeTime = baseTime + i * 3_600_000L + 3_599_999L,
                    open = 100.0,
                    high = 101.2,
                    low = 99.0,
                    close = if (i % 2 == 0) 100.5 else 99.5,
                    volume = 2000.0,
                    isClosed = true
                )
            )
        }
        htfCandles.add(Candle(baseTime + 16 * 3_600_000L, baseTime + 17 * 3_600_000L - 1L, 101.2, 101.9, 100.8, 101.1, 1500.0, true))
        htfCandles.add(Candle(baseTime + 17 * 3_600_000L, baseTime + 18 * 3_600_000L - 1L, 101.1, 105.5, 101.0, 105.2, 6000.0, true))
        for (i in 18..25) {
            val o = 102.0 + (i - 18) * 0.4
            htfCandles.add(Candle(baseTime + i * 3_600_000L, baseTime + (i + 1) * 3_600_000L - 1L, o, o + 1.0, o - 0.5, o + 0.6, 2200.0, true))
        }

        val out = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = candles,
            config = com.example.domain.indicator.TbtSmcConfig(
                showLiquidityTraps = true,
                showOverlap = true,
                showTradeSetup = true,
                useMultiTimeframe = true
            ),
            mtfInput = com.example.domain.indicator.TbtMtfInput(
                currentTfLabel = "15m",
                htfLabel = "1H",
                htfCandles = htfCandles,
                macroLabel = "4H",
                macroCandles = htfCandles
            )
        )

        assertTrue(out.markers.any { it.text == "EQL Trap" })
        assertTrue(out.markers.any { it.text == "Bull Flip" })
        assertTrue(out.priceLines.any { it.category == com.example.domain.indicator.TbtPriceLineCategory.BREAKER_FLIP })
        assertTrue(out.priceLines.any { it.title == "HTF+LTF Overlap (BUY)" })
        assertEquals(com.example.domain.indicator.TbtTradeDirection.BUY, out.summary.tradeSetup.direction)
        assertTrue(out.summary.tradeSetup.confluenceReasons.any { it.contains("EQL Trap") || it.contains("Breaker Flip") || it.contains("Overlapping") })

        // Verify showLiquidityTraps = false hides BREAKER_FLIP & LIQUIDITY_POOL price lines
        val noTrapsOut = com.example.domain.indicator.TbtSmcEngine.calculate(
            candles = candles,
            config = com.example.domain.indicator.TbtSmcConfig(showLiquidityTraps = false)
        )
        assertTrue(noTrapsOut.priceLines.none { it.category == com.example.domain.indicator.TbtPriceLineCategory.BREAKER_FLIP })
        assertTrue(noTrapsOut.priceLines.none { it.category == com.example.domain.indicator.TbtPriceLineCategory.LIQUIDITY_POOL })
    }
}
