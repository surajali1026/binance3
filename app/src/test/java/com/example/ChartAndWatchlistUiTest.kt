package com.example

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.example.domain.model.Candle
import com.example.domain.model.DrawingTool
import com.example.domain.model.FuturesContract
import com.example.domain.model.PriceSource
import com.example.domain.model.TickerSnapshot
import com.example.ui.chart.ChartCanvas
import com.example.ui.chart.ChartTopHud
import com.example.ui.chart.ChartUiState
import com.example.ui.chart.FloatingDrawingToolbar
import com.example.ui.theme.BinanceProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChartAndWatchlistUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `chart top hud switches price source via dropdown and shows coin name and live price`() {
        var selectedSource = PriceSource.LAST
        val state = ChartUiState(
            symbol = "BTCUSDT",
            contract = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 1, 3, 0.1, "PERPETUAL", "TRADING"),
            ticker = TickerSnapshot("BTCUSDT", 92500.0, 92510.0, 92490.0, 2.45, 1_500_000_000.0, 93000.0, 90000.0),
            priceSource = PriceSource.LAST
        )

        composeTestRule.setContent {
            BinanceProTheme {
                ChartTopHud(
                    state = state,
                    showBackButton = true,
                    onBackClick = {},
                    onOpenCoinSwitcher = {},
                    onSelectPriceSource = { selectedSource = it },
                    onOpenAlerts = {},
                    onToggleFullScreen = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last").assertIsDisplayed()
        composeTestRule.onNodeWithText("92,500.0").assertIsDisplayed()

        // Open dropdown and select Mark Price
        composeTestRule.onNodeWithTag("price_source_dropdown_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Last Price").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark Price").assertIsDisplayed()
        composeTestRule.onNodeWithText("Index Price").assertIsDisplayed()
        composeTestRule.onNodeWithTag("price_source_mark").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PriceSource.MARK, selectedSource)
        composeTestRule.onNodeWithTag("price_source_dropdown_menu").assertDoesNotExist()

        // Re-open dropdown and select Index Price
        composeTestRule.onNodeWithTag("price_source_dropdown_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("price_source_index").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PriceSource.INDEX, selectedSource)
        composeTestRule.onNodeWithTag("price_source_dropdown_menu").assertDoesNotExist()
    }

    @Test
    fun `drawing toolbar expands and auto-selects tool`() {
        var expanded = false
        var selectedTool = DrawingTool.NONE

        composeTestRule.setContent {
            BinanceProTheme {
                FloatingDrawingToolbar(
                    expanded = true,
                    activeTool = DrawingTool.NONE,
                    onToggleExpand = { expanded = !expanded },
                    onSelectTool = { selectedTool = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tool_horizontal_line").assertIsDisplayed().performClick()
        assertEquals(DrawingTool.HORIZONTAL_LINE, selectedTool)
        composeTestRule.onNodeWithTag("tool_rectangle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool_trendline").assertIsDisplayed()
    }

    @Test
    fun `chart canvas renders candlesticks and price axis`() {
        val now = 1_700_000_000_000L
        val candles = (0 until 30).map { i ->
            val t = now + i * 900_000L
            Candle(t, t + 899_999L, 90000.0 + i * 20, 90100.0 + i * 20, 89900.0 + i * 20, 90050.0 + i * 20, 100.0, true)
        }
        val state = ChartUiState(
            symbol = "BTCUSDT",
            candles = candles,
            isLoadingHistory = false
        )

        composeTestRule.setContent {
            BinanceProTheme {
                ChartCanvas(
                    state = state,
                    onPlotWidthChanged = {},
                    onPan = { _, _, _ -> },
                    onPinchZoom = { _, _, _, _, _ -> },
                    onScalePriceAxis = {},
                    onResetPriceAutoFit = {},
                    onScaleTimeAxis = {},
                    onCrosshairMove = { _, _, _ -> },
                    onChartTap = { _, _, _, _ -> },
                    onMoveDrawingAnchor = { _, _, _ -> },
                    onMoveWholeDrawing = { _, _, _ -> },
                    onMoveAlertPrice = { _, _ -> },
                    onPriceAxisTap = {},
                    onQuickAlertPlusClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chart_plot_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chart_price_axis").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chart_time_axis").assertIsDisplayed()
    }

    @Test
    fun `selected drawing pill exposes lock and delete and floating plus alert button shows`() {
        var lockedToggled = false
        var deleted = false
        var quickAlertClickedPrice: Double? = null

        val drawing = com.example.domain.model.ChartDrawing(
            id = "d1",
            symbol = "BTCUSDT",
            type = com.example.domain.model.DrawingType.HORIZONTAL_LINE,
            points = listOf(com.example.domain.model.ChartPoint(1_700_000_000_000L, 90050.0))
        )
        val state = ChartUiState(
            symbol = "BTCUSDT",
            candles = listOf(
                Candle(1_700_000_000_000L, 1_700_000_899_999L, 90000.0, 90200.0, 89900.0, 90100.0, 10.0, true)
            ),
            axisQuickAlertPrice = 90080.0,
            isLoadingHistory = false
        )

        composeTestRule.setContent {
            BinanceProTheme {
                androidx.compose.foundation.layout.Box {
                    ChartCanvas(
                        state = state,
                        onPlotWidthChanged = {},
                        onPan = { _, _, _ -> },
                        onPinchZoom = { _, _, _, _, _ -> },
                        onScalePriceAxis = {},
                        onResetPriceAutoFit = {},
                        onScaleTimeAxis = {},
                        onCrosshairMove = { _, _, _ -> },
                        onChartTap = { _, _, _, _ -> },
                        onMoveDrawingAnchor = { _, _, _ -> },
                        onMoveWholeDrawing = { _, _, _ -> },
                        onMoveAlertPrice = { _, _ -> },
                        onPriceAxisTap = {},
                        onQuickAlertPlusClick = { quickAlertClickedPrice = it }
                    )
                    com.example.ui.chart.SelectedDrawingActionPill(
                        drawing = drawing,
                        onEdit = {},
                        onToggleLock = { lockedToggled = true },
                        onCreateBreakoutAlert = {},
                        onDelete = { deleted = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("drawing_lock_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("drawing_delete_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("floating_plus_alert_button").assertIsDisplayed().performClick()

        assertEquals(true, lockedToggled)
        assertEquals(true, deleted)
        assertEquals(90080.0, quickAlertClickedPrice!!, 0.001)
    }

    @Test
    fun `chart canvas pointerInput handles drag to scroll and pinch to zoom`() {
        val now = 1_700_000_000_000L
        val candles = (0 until 40).map { i ->
            val t = now + i * 900_000L
            Candle(t, t + 899_999L, 90000.0 + i * 15, 90120.0 + i * 15, 89880.0 + i * 15, 90040.0 + i * 15, 100.0, true)
        }
        val state = ChartUiState(
            symbol = "BTCUSDT",
            candles = candles,
            isLoadingHistory = false
        )
        var totalPanX = 0f
        var pinchCount = 0
        var lastZoomX = 1f

        composeTestRule.setContent {
            BinanceProTheme {
                ChartCanvas(
                    state = state,
                    onPlotWidthChanged = {},
                    onPan = { dx, _, _ -> totalPanX += dx },
                    onPinchZoom = { zx, _, _, _, _ ->
                        pinchCount++
                        lastZoomX = zx
                    },
                    onScalePriceAxis = {},
                    onResetPriceAutoFit = {},
                    onScaleTimeAxis = {},
                    onCrosshairMove = { _, _, _ -> },
                    onChartTap = { _, _, _, _ -> },
                    onMoveDrawingAnchor = { _, _, _ -> },
                    onMoveWholeDrawing = { _, _, _ -> },
                    onMoveAlertPrice = { _, _ -> },
                    onPriceAxisTap = {},
                    onQuickAlertPlusClick = {},
                    modifier = Modifier.size(400.dp, 300.dp)
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chart_plot_canvas").performTouchInput {
            swipeRight(startX = width * 0.25f, endX = width * 0.75f, durationMillis = 120L)
        }
        composeTestRule.waitForIdle()
        assertEquals(true, totalPanX > 20f)

        composeTestRule.onNodeWithTag("chart_plot_canvas").performTouchInput {
            pinch(
                start0 = Offset(width * 0.4f, height * 0.5f),
                end0 = Offset(width * 0.15f, height * 0.5f),
                start1 = Offset(width * 0.6f, height * 0.5f),
                end1 = Offset(width * 0.85f, height * 0.5f),
                durationMillis = 150L
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(true, pinchCount > 0)
        assertEquals(true, lastZoomX > 1.0f)
    }

    @Test
    fun `chart coin switcher exposes all top gainers and top losers filters`() {
        val btc = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val eth = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val sol = FuturesContract("SOLUSDT", "SOLUSDT", "SOL", "USDT", 2, 3, 0.01, "PERPETUAL", "TRADING")
        val items = listOf(
            com.example.domain.model.WatchlistItem(
                btc,
                TickerSnapshot("BTCUSDT", 92000.0, 92000.0, 92000.0, 4.5, 1_000_000.0, 93000.0, 88000.0),
                isFavorite = true
            ),
            com.example.domain.model.WatchlistItem(
                eth,
                TickerSnapshot("ETHUSDT", 3200.0, 3200.0, 3200.0, -3.2, 800_000.0, 3350.0, 3150.0),
                isFavorite = false
            ),
            com.example.domain.model.WatchlistItem(
                sol,
                TickerSnapshot("SOLUSDT", 210.0, 210.0, 210.0, 8.1, 600_000.0, 215.0, 190.0),
                isFavorite = false
            )
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.QuickCoinSwitcherSheet(
                    items = items,
                    currentSymbol = "BTCUSDT",
                    onSelectSymbol = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("coin_switcher_filter_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("coin_switcher_filter_favorites").assertIsDisplayed()
        composeTestRule.onNodeWithTag("coin_switcher_filter_gainers").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SOLUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("ETHUSDT").assertDoesNotExist()

        composeTestRule.onNodeWithTag("coin_switcher_filter_losers").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ETHUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("SOLUSDT").assertDoesNotExist()

        composeTestRule.onNodeWithTag("coin_switcher_filter_all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("BTCUSDT").assertIsDisplayed()
    }

    @Test
    fun `fullscreen bar displays full coin name price selector timeframes picker and exit button`() {
        var selectedTf = ""
        var openedPicker = false
        var exitedFullScreen = false
        var selectedSource = PriceSource.LAST
        val state = ChartUiState(
            symbol = "BTCUSDT",
            contract = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 1, 3, 0.1, "PERPETUAL", "TRADING"),
            ticker = TickerSnapshot("BTCUSDT", 92500.0, 92510.0, 92490.0, 2.45, 1_500_000_000.0, 93000.0, 90000.0),
            priceSource = PriceSource.LAST,
            isFullScreen = true
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.FullScreenTimeframeBar(
                    state = state,
                    onOpenCoinSwitcher = {},
                    onSelectPriceSource = { selectedSource = it },
                    onSelectTimeframe = { selectedTf = it },
                    onOpenTimeframePicker = { openedPicker = true },
                    onAutoFit = {},
                    onStartReplay = {},
                    onExitFullScreen = { exitedFullScreen = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("price_source_dropdown_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("price_source_mark").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(PriceSource.MARK, selectedSource)

        composeTestRule.onNodeWithTag("tf_chip_15m").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("open_timeframe_picker").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("exit_fullscreen_button").assertIsDisplayed().performClick()

        assertEquals("15m", selectedTf)
        assertEquals(true, openedPicker)
        assertEquals(true, exitedFullScreen)
    }

    @Test
    fun `landscape fullscreen bar shows single row controls with full coin name and price dropdown`() {
        var selectedSource = PriceSource.MARK
        val state = ChartUiState(
            symbol = "ETHUSDT",
            contract = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.01, "PERPETUAL", "TRADING"),
            ticker = TickerSnapshot("ETHUSDT", 3200.0, 3205.5, 3198.25, 1.85, 900_000_000.0, 3250.0, 3100.0),
            priceSource = PriceSource.MARK,
            isFullScreen = true
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.FullScreenTimeframeBar(
                    state = state,
                    onOpenCoinSwitcher = {},
                    onSelectPriceSource = { selectedSource = it },
                    onSelectTimeframe = {},
                    onOpenTimeframePicker = {},
                    onAutoFit = {},
                    onStartReplay = {},
                    onExitFullScreen = {},
                    modifier = Modifier.size(width = 760.dp, height = 40.dp)
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("ETHUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark").assertIsDisplayed()
        composeTestRule.onNodeWithText("3,205.50").assertIsDisplayed()
        composeTestRule.onNodeWithTag("price_source_dropdown_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("price_source_index").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(PriceSource.INDEX, selectedSource)
    }

    @Test
    fun `chart top hud triggers prev and next symbol callbacks`() {
        var prevClicks = 0
        var nextClicks = 0
        val state = ChartUiState(
            symbol = "BTCUSDT",
            navigationSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
            navigationContextLabel = "Top 100"
        )

        composeTestRule.setContent {
            BinanceProTheme {
                ChartTopHud(
                    state = state,
                    showBackButton = false,
                    onBackClick = {},
                    onOpenCoinSwitcher = {},
                    onPrevSymbol = { prevClicks++ },
                    onNextSymbol = { nextClicks++ },
                    onSelectPriceSource = {},
                    onOpenAlerts = {},
                    onToggleFullScreen = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chart_prev_symbol_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("chart_next_symbol_button").assertIsDisplayed().performClick()

        assertEquals(1, prevClicks)
        assertEquals(1, nextClicks)
    }

    @Test
    fun `quick coin switcher exposes top 100 tab and shows rank and volume metadata`() {
        val btc = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val eth = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val items = listOf(
            com.example.domain.model.WatchlistItem(
                btc,
                TickerSnapshot("BTCUSDT", 92000.0, 92000.0, 92000.0, 4.5, 1_500_000_000.0, 93000.0, 88000.0, marketCap = 1.8e12, marketCapRank = 1),
                isFavorite = true,
                displayRank = 1
            ),
            com.example.domain.model.WatchlistItem(
                eth,
                TickerSnapshot("ETHUSDT", 3200.0, 3200.0, 3200.0, -3.2, 450_000_000.0, 3350.0, 3150.0, marketCap = 3.8e11, marketCapRank = 2),
                isFavorite = false,
                displayRank = 2
            )
        )

        var selectedWithCtx: Triple<String, List<String>, String>? = null
        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.QuickCoinSwitcherSheet(
                    items = items,
                    currentSymbol = "BTCUSDT",
                    onSelectSymbol = {},
                    onSelectSymbolWithContext = { sym, list, label -> selectedWithCtx = Triple(sym, list, label) },
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("coin_switcher_filter_top_100").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("#1 • Vol: \$1.5B").assertIsDisplayed()
        composeTestRule.onNodeWithTag("coin_switcher_list").performScrollToIndex(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("#2 • Vol: \$450M").assertIsDisplayed()
        composeTestRule.onNodeWithText("ETHUSDT").performClick()

        assertEquals("ETHUSDT", selectedWithCtx?.first)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), selectedWithCtx?.second)
        assertEquals("Top 100", selectedWithCtx?.third)
    }

    @Test
    fun `quick coin switcher exposes new listings tab with upcoming time and listed age`() {
        val now = System.currentTimeMillis()
        val upcoming = FuturesContract(
            symbol = "FUTUREUSDT",
            pair = "FUTUREUSDT",
            baseAsset = "FUTURE",
            quoteAsset = "USDT",
            pricePrecision = 4,
            quantityPrecision = 1,
            tickSize = 0.0001,
            contractType = "PERPETUAL",
            status = "PENDING_TRADING",
            onboardDate = now + 26L * 3_600_000L
        )
        val recent = FuturesContract(
            symbol = "RECENTUSDT",
            pair = "RECENTUSDT",
            baseAsset = "RECENT",
            quoteAsset = "USDT",
            pricePrecision = 3,
            quantityPrecision = 1,
            tickSize = 0.001,
            contractType = "PERPETUAL",
            status = "TRADING",
            onboardDate = now - 2L * 86_400_000L
        )
        val items = listOf(
            com.example.domain.model.WatchlistItem(upcoming, null, isFavorite = false, displayRank = null),
            com.example.domain.model.WatchlistItem(
                recent,
                TickerSnapshot("RECENTUSDT", 1.25, 1.25, 1.25, 12.4, 80_000_000.0, 1.30, 1.05, marketCapRank = 180),
                isFavorite = false,
                displayRank = 180
            )
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.QuickCoinSwitcherSheet(
                    items = items,
                    currentSymbol = "RECENTUSDT",
                    onSelectSymbol = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("coin_switcher_filter_new_listings").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("FUTUREUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pre-Launch").assertIsDisplayed()
        composeTestRule.onNodeWithText("SOON").assertIsDisplayed()
        composeTestRule.onNodeWithTag("coin_switcher_list").performScrollToIndex(1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("RECENTUSDT").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `pure tbt smc control shows buy setup card alerts replay and settings without ict`() {
        var mainToggled = 0
        var autoHideToggled = 0
        var volImbToggled = 0
        var zoneTagsToggled = 0
        var signalsToggled = 0
        var replayToggledTo: Boolean? = null
        var prevSteps = 0
        var nextSteps = 0
        var resetClicks = 0
        var selectedSpeed = 0
        var entryAlertClicks = 0
        var slTpAlertClicks = 0
        var selectedProfile: com.example.domain.indicator.TbtMtfProfile? = null
        var jumpedTfId: String? = null

        val sampleCascade = com.example.domain.indicator.TbtMtfCascadeState(
            profile = com.example.domain.indicator.TbtMtfProfile.INTRADAY_1H,
            snapshots = listOf(
                com.example.domain.indicator.TbtTimeframeSnapshot("1H", "1H", com.example.domain.indicator.TbtMarketBias.BULLISH, "DEM*"),
                com.example.domain.indicator.TbtTimeframeSnapshot("30m", "30m", com.example.domain.indicator.TbtMarketBias.BULLISH, "OVL+"),
                com.example.domain.indicator.TbtTimeframeSnapshot("15m", "15m", com.example.domain.indicator.TbtMarketBias.BULLISH, "BPWR"),
                com.example.domain.indicator.TbtTimeframeSnapshot("10m", "10m", com.example.domain.indicator.TbtMarketBias.BULLISH, "BULL"),
                com.example.domain.indicator.TbtTimeframeSnapshot("5m", "5m", com.example.domain.indicator.TbtMarketBias.BULLISH, "3CR+", hasBuyConfirm = true),
                com.example.domain.indicator.TbtTimeframeSnapshot("3m", "3m", com.example.domain.indicator.TbtMarketBias.BULLISH, "3CR+", hasBuyConfirm = true)
            ),
            action = com.example.domain.indicator.TbtCascadeAction.BUY_READY,
            headline = "BUY READY • 3m/5m OK",
            alignedCount = 6,
            totalCount = 6,
            htfBias = com.example.domain.indicator.TbtMarketBias.BULLISH
        )

        val sampleSetup = com.example.domain.indicator.TbtTradeSetup(
            direction = com.example.domain.indicator.TbtTradeDirection.BUY,
            triggerName = "3CR BUY",
            reason = "DEM/OVL + SPWR + 3CR BUY",
            grade = "A+",
            score = 5,
            entryPrice = 91500.0,
            retracementEntryPrice = 91320.0,
            stopLoss = 91100.0,
            takeProfit = 92700.0,
            riskReward = 3.0,
            riskPercent = 0.44,
            htfLabel = "1H",
            htfBias = com.example.domain.indicator.TbtMarketBias.BULLISH,
            cascadeAction = com.example.domain.indicator.TbtCascadeAction.BUY_READY,
            executionTf = "3m",
            volumeConfirmed = true,
            checklist = listOf(
                com.example.domain.indicator.TbtChecklistItem("1H MTF (6/6) BUY READY", true),
                com.example.domain.indicator.TbtChecklistItem("DEM/SUP or OVL POI", true),
                com.example.domain.indicator.TbtChecklistItem("BPWR / SPWR Shift", true),
                com.example.domain.indicator.TbtChecklistItem("Trigger: 3CR BUY", true),
                com.example.domain.indicator.TbtChecklistItem("Volume Conf", true)
            )
        )

        val state = ChartUiState(
            symbol = "BTCUSDT",
            contract = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING"),
            activeTimeframe = com.example.domain.model.Timeframe.DEFAULTS.first { it.id == "15m" },
            replayState = com.example.domain.model.BarReplayState(isActive = true, cutoffIndex = 40, isPlaying = false, speedMultiplier = 2),
            preferences = com.example.domain.model.ChartPreferences(showTbtSmc = true, tbtAutoHideOnHtf = false, tbtShowSignals = true),
            tbtSmc = com.example.domain.indicator.TbtSmcOutput(
                summary = com.example.domain.indicator.TbtConditionSummary(
                    bias = com.example.domain.indicator.TbtMarketBias.BULLISH,
                    htfLabel = "1H",
                    htfBias = com.example.domain.indicator.TbtMarketBias.BULLISH,
                    activeBullPower = 91250.5,
                    activeBearPower = null,
                    lastSignal = "3CR BUY"
                ),
                backtestReport = com.example.domain.indicator.TbtBacktestReport(
                    totalTrades = 6,
                    wins = 4,
                    losses = 2,
                    winRate = 67,
                    netR = 10.0
                ),
                activeSetup = sampleSetup,
                mtfCascade = sampleCascade
            )
        )

        composeTestRule.setContent {
            BinanceProTheme {
                androidx.compose.foundation.layout.Column {
                    com.example.ui.chart.TbtIndicatorControl(
                        state = state,
                        onToggleVisible = { mainToggled++ },
                        onToggleAutoHideOnHtf = { autoHideToggled++ },
                        onToggleVolImbalanceLine = { volImbToggled++ },
                        onToggleZoneTags = { zoneTagsToggled++ },
                        onToggleSignals = { signalsToggled++ },
                        onToggleZones = {},
                        onTogglePowerShift = {},
                        onToggleOverlap = {},
                        onToggleHud = {},
                        onUpdateTargetRR = {},
                        onUpdateSlBufferPct = {},
                        onToggleReplayMode = { replayToggledTo = it },
                        onReplayStepBackward = { prevSteps++ },
                        onReplayTogglePlay = {},
                        onReplayStepForward = { nextSteps++ },
                        onReplaySetSpeed = { selectedSpeed = it },
                        onReplayReset = { resetClicks++ },
                        onSelectMtfProfile = { selectedProfile = it },
                        onCreateEntryAlert = { _, _ -> entryAlertClicks++ },
                        onCreateSlTpAlerts = { slTpAlertClicks++ }
                    )
                    com.example.ui.chart.TbtConditionHudBadge(
                        summary = state.tbtSmc.summary,
                        precision = 2,
                        mtfCascade = state.tbtSmc.mtfCascade,
                        activeProfile = state.preferences.tbtMtfProfile,
                        activeTimeframeId = "15m",
                        onSelectMtfProfile = { selectedProfile = it },
                        onSelectTimeframe = { jumpedTfId = it }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Pure TBT SMC tag (zero ICT!) & backtest stats
        composeTestRule.onNodeWithTag("tbt_indicator_tag").assertIsDisplayed()
        composeTestRule.onNodeWithText("TBT SMC+ICT").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tbt_backtest_stats").assertIsDisplayed()
        composeTestRule.onNodeWithText("67%").assertIsDisplayed()
        composeTestRule.onNodeWithText("+10.0R").assertIsDisplayed()

        // Actionable BUY Setup Pill expands into full Trade Plan Card with 1-tap Alert buttons
        composeTestRule.onNodeWithTag("tbt_trade_setup_pill").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tbt_trade_setup_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("TBT BUY LONG SETUP").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tbt_setup_alert_entry_btn").performClick()
        composeTestRule.onNodeWithTag("tbt_setup_alert_sltp_btn").performClick()
        assertEquals(1, entryAlertClicks)
        assertEquals(1, slTpAlertClicks)

        // Multi-Timeframe TBT HUB displays Cascade Banner, 1H/4H switcher, tappable TF chips, and short BPWR label
        composeTestRule.onNodeWithTag("tbt_condition_hud").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tbt_mtf_cascade_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("BUY READY • 3m/5m OK").assertIsDisplayed()
        composeTestRule.onNodeWithText("BPWR 91,250.50").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tbt_hub_tf_3m").assertIsDisplayed().performClick()
        assertEquals("3m", jumpedTfId)
        composeTestRule.onNodeWithTag("tbt_mtf_profile_4h").assertIsDisplayed().performClick()
        assertEquals(com.example.domain.indicator.TbtMtfProfile.SWING_4H, selectedProfile)

        // Eye toggle works
        composeTestRule.onNodeWithTag("tbt_smc_toggle_button").assertIsDisplayed().performClick()
        assertEquals(1, mainToggled)

        // Settings panel opens with Replay, MTF Setup Switcher, & pure TBT SMC controls (no ICT Killzone)
        composeTestRule.onNodeWithTag("tbt_smc_settings_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tbt_smc_settings_panel").assertIsDisplayed()
        composeTestRule.onNodeWithText("ICT Killzone Filter").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tbt_settings_profile_1h").assertIsDisplayed().performClick()
        assertEquals(com.example.domain.indicator.TbtMtfProfile.INTRADAY_1H, selectedProfile)
        composeTestRule.onNodeWithTag("tbt_replay_prev_btn").performClick()
        composeTestRule.onNodeWithTag("tbt_replay_next_btn").performClick()
        composeTestRule.onNodeWithTag("tbt_replay_speed_5x").performClick()
        composeTestRule.onNodeWithTag("tbt_replay_reset_btn").performClick()
        composeTestRule.onNodeWithTag("tbt_replay_mode_toggle").performClick()
        composeTestRule.onNodeWithTag("tbt_toggle_auto_hide_htf").performClick()
        composeTestRule.onNodeWithTag("tbt_toggle_buy_sell_signals").performClick()
        composeTestRule.onNodeWithTag("tbt_toggle_vol_imbalance").performClick()
        composeTestRule.onNodeWithTag("tbt_toggle_zone_tags").performClick()

        assertEquals(1, prevSteps)
        assertEquals(1, nextSteps)
        assertEquals(5, selectedSpeed)
        assertEquals(1, resetClicks)
        assertEquals(false, replayToggledTo)
        assertEquals(1, autoHideToggled)
        assertEquals(1, signalsToggled)
        assertEquals(1, volImbToggled)
        assertEquals(1, zoneTagsToggled)
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land")
    fun `tbt indicator control shows auto hidden badge on htf timeframes above 15m`() {
        val state = ChartUiState(
            symbol = "ETHUSDT",
            contract = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.01, "PERPETUAL", "TRADING"),
            activeTimeframe = com.example.domain.model.Timeframe.DEFAULTS.first { it.id == "1H" },
            preferences = com.example.domain.model.ChartPreferences(showTbtSmc = true, tbtAutoHideOnHtf = true),
            tbtSmc = com.example.domain.indicator.TbtSmcOutput(
                backtestReport = com.example.domain.indicator.TbtBacktestReport(
                    totalTrades = 5,
                    wins = 3,
                    losses = 2,
                    winRate = 60,
                    netR = 7.0
                )
            )
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.TbtIndicatorControl(
                    state = state,
                    onToggleVisible = {},
                    onToggleAutoHideOnHtf = {},
                    onToggleVolImbalanceLine = {},
                    onToggleZoneTags = {},
                    onToggleSignals = {},
                    onToggleZones = {},
                    onTogglePowerShift = {},
                    onToggleOverlap = {},
                    onToggleHud = {},
                    onUpdateTargetRR = {},
                    onUpdateSlBufferPct = {},
                    onToggleReplayMode = {},
                    onReplayStepBackward = {},
                    onReplayTogglePlay = {},
                    onReplayStepForward = {},
                    onReplaySetSpeed = {},
                    onReplayReset = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tbt_auto_hidden_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tbt_backtest_stats").assertDoesNotExist()
    }

    @Test
    fun `portrait fullscreen bar exposes coin switcher price selector replay and exit cleanly`() {
        var replayClicked = false
        val state = ChartUiState(
            symbol = "BTCUSDT",
            contract = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING"),
            isFullScreen = true
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.FullScreenTimeframeBar(
                    state = state,
                    onOpenCoinSwitcher = {},
                    onSelectPriceSource = {},
                    onSelectTimeframe = {},
                    onOpenTimeframePicker = {},
                    onAutoFit = {},
                    onStartReplay = { replayClicked = true },
                    onExitFullScreen = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("fullscreen_coin_switcher").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chart_replay_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("exit_fullscreen_button").assertIsDisplayed()
        assertEquals(true, replayClicked)
    }

    @Test
    fun `coin switcher search retains typed characters across live ticker updates`() {
        val btc = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val eth = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING")
        val liveTick = androidx.compose.runtime.mutableStateOf(
            listOf(
                com.example.domain.model.WatchlistItem(
                    btc,
                    TickerSnapshot("BTCUSDT", 92000.0, 92000.0, 92000.0, 4.5, 1_500_000_000.0, 93000.0, 88000.0),
                    isFavorite = true
                ),
                com.example.domain.model.WatchlistItem(
                    eth,
                    TickerSnapshot("ETHUSDT", 3200.0, 3200.0, 3200.0, -3.2, 450_000_000.0, 3350.0, 3150.0),
                    isFavorite = false
                )
            )
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.chart.QuickCoinSwitcherSheet(
                    items = liveTick.value,
                    currentSymbol = "BTCUSDT",
                    onSelectSymbol = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("coin_switcher_search_input").performTextInput("ETH")
        composeTestRule.waitForIdle()

        // Simulate live WebSocket ticker update while user has typed "ETH"
        liveTick.value = listOf(
            com.example.domain.model.WatchlistItem(
                btc,
                TickerSnapshot("BTCUSDT", 92150.0, 92150.0, 92150.0, 4.7, 1_510_000_000.0, 93000.0, 88000.0),
                isFavorite = true
            ),
            com.example.domain.model.WatchlistItem(
                eth,
                TickerSnapshot("ETHUSDT", 3215.0, 3215.0, 3215.0, -3.0, 460_000_000.0, 3350.0, 3150.0),
                isFavorite = false
            )
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("ETHUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("BTCUSDT").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `multi exchange volume control exposes cex dex checkboxes trap toggle and tap breakdown popup`() {
        var visibilityToggled = 0
        var trapsToggled = 0
        var toggledExchange: com.example.domain.indicator.SupportedExchange? = null
        var popupDismissed = false

        val level = com.example.domain.indicator.IntraCandleVolumeLevel(
            id = "lvl_1",
            candleTimeMillis = 1_750_000_000_000L,
            bucketIndex = 3,
            bucketCount = 4,
            priceLow = 91800.0,
            priceHigh = 92000.0,
            priceMid = 91900.0,
            dominantSide = com.example.domain.indicator.DominanceSide.BUY,
            dominantExchangeCount = 3,
            activeExchangeCount = 3,
            totalBuyVolume = 1_500_000.0,
            totalSellVolume = 350_000.0,
            exchanges = listOf(
                com.example.domain.indicator.ExchangeBucketBreakdown(
                    com.example.domain.indicator.SupportedExchange.BINANCE,
                    800_000.0,
                    150_000.0
                ),
                com.example.domain.indicator.ExchangeBucketBreakdown(
                    com.example.domain.indicator.SupportedExchange.BYBIT,
                    450_000.0,
                    120_000.0
                ),
                com.example.domain.indicator.ExchangeBucketBreakdown(
                    com.example.domain.indicator.SupportedExchange.HYPERLIQUID,
                    250_000.0,
                    80_000.0
                )
            )
        )

        val output = com.example.domain.indicator.MultiExchangeVolumeOutput(
            levels = listOf(level),
            connectedExchanges = setOf(
                com.example.domain.indicator.SupportedExchange.BINANCE,
                com.example.domain.indicator.SupportedExchange.BYBIT,
                com.example.domain.indicator.SupportedExchange.HYPERLIQUID
            ),
            checkedExchanges = com.example.domain.indicator.SupportedExchange.DEFAULT_SELECTED
        )

        composeTestRule.setContent {
            BinanceProTheme {
                androidx.compose.foundation.layout.Column {
                    com.example.ui.chart.MultiExchangeVolumeControl(
                        visible = true,
                        showTraps = true,
                        selectedExchanges = com.example.domain.indicator.SupportedExchange.DEFAULT_SELECTED,
                        output = output,
                        collapseSignal = 0,
                        onToggleVisible = { visibilityToggled++ },
                        onToggleShowTraps = { trapsToggled++ },
                        onToggleExchange = { toggledExchange = it },
                        onResetDefaultExchanges = {}
                    )
                    com.example.ui.chart.MultiExchangeLevelPopup(
                        level = level,
                        precision = 2,
                        onDismiss = { popupDismissed = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("multi_exch_indicator_tag").assertIsDisplayed()
        composeTestRule.onNodeWithText("Multi-Exch Vol & Traps").assertIsDisplayed()
        composeTestRule.onNodeWithText("3/6").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_toggle_button").performClick()
        assertEquals(1, visibilityToggled)

        // Open settings drawer and verify CEX, Perp DEX checkboxes & Trap toggle
        composeTestRule.onNodeWithTag("multi_exch_settings_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("multi_exch_settings_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_traps_toggle").performClick()
        assertEquals(1, trapsToggled)
        composeTestRule.onNodeWithTag("multi_exch_cb_coinbase").assertIsDisplayed().performClick()
        assertEquals(com.example.domain.indicator.SupportedExchange.COINBASE, toggledExchange)

        // Verify Tap Popup lists exchange names & Buy/Sell volumes
        composeTestRule.onNodeWithTag("multi_exch_level_popup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_popup_row_binance").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_popup_row_bybit").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_popup_row_hyperliquid").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multi_exch_popup_close").performClick()
        assertEquals(true, popupDismissed)
    }

    @Test
    fun `github update prompt banner shows new version notes download and dismiss controls`() {
        var dismissed = false
        var downloaded = false
        val release = com.example.data.remote.GitHubReleaseInfo(
            tagName = "v2.0.0",
            version = "2.0.0",
            releaseName = "Binance Pro v2.0.0",
            notesSummary = "Multi-Exchange Volume & Traps + Auto-Updater",
            downloadUrl = "https://github.com/surajali1026/binance3/releases/download/v2.0.0/app-release.apk",
            htmlUrl = "https://github.com/surajali1026/binance3/releases/tag/v2.0.0"
        )

        composeTestRule.setContent {
            BinanceProTheme {
                com.example.ui.components.UpdatePromptBanner(
                    release = release,
                    onDismiss = { dismissed = true },
                    onDownloadClick = { downloaded = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("github_update_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Binance Pro v2.0.0 Available").assertIsDisplayed()
        composeTestRule.onNodeWithText("Multi-Exchange Volume & Traps + Auto-Updater").assertIsDisplayed()
        composeTestRule.onNodeWithTag("github_update_download_btn").performClick()
        composeTestRule.onNodeWithTag("github_update_dismiss_btn").performClick()

        assertEquals(true, downloaded)
        assertEquals(true, dismissed)
    }
}
