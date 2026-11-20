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
    fun `tbt smc toggle button and layer settings menu work across portrait and landscape bars`() {
        var mainToggled = 0
        var zonesToggled = 0
        var powerToggled = 0
        var overlapToggled = 0
        var signalsToggled = 0
        var hudToggled = 0

        val state = ChartUiState(
            symbol = "BTCUSDT",
            contract = FuturesContract("BTCUSDT", "BTCUSDT", "BTC", "USDT", 2, 3, 0.1, "PERPETUAL", "TRADING"),
            preferences = com.example.domain.model.ChartPreferences(showTbtSmc = true)
        )

        composeTestRule.setContent {
            BinanceProTheme {
                androidx.compose.foundation.layout.Column {
                    com.example.ui.chart.ChartTimeframeStrip(
                        state = state,
                        onSelectTimeframe = {},
                        onOpenTimeframePicker = {},
                        onAutoFit = {},
                        onStartReplay = {},
                        onToggleTbtSmc = { mainToggled++ },
                        onToggleTbtZones = { zonesToggled++ },
                        onToggleTbtPowerShift = { powerToggled++ },
                        onToggleTbtOverlap = { overlapToggled++ },
                        onToggleTbtSignals = { signalsToggled++ },
                        onToggleTbtHud = { hudToggled++ }
                    )
                    com.example.ui.chart.TbtConditionHudBadge(
                        summary = com.example.domain.indicator.TbtConditionSummary(
                            bias = com.example.domain.indicator.TbtMarketBias.BULLISH,
                            activeBullPower = 91250.5,
                            activeBearPower = null,
                            lastSignal = "3CR BUY Confirmed"
                        ),
                        precision = 2
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Floating HUD displays bias, active power level, and last signal
        composeTestRule.onNodeWithTag("tbt_condition_hud").assertIsDisplayed()
        composeTestRule.onNodeWithText("BULLISH").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bull Pwr: 91,250.50").assertIsDisplayed()
        composeTestRule.onNodeWithText("3CR BUY Confirmed").assertIsDisplayed()

        // 1-click toggle works
        composeTestRule.onNodeWithTag("tbt_smc_toggle_button").assertIsDisplayed().performClick()
        assertEquals(1, mainToggled)

        // Settings popover opens and toggles all 5 original layers
        composeTestRule.onNodeWithTag("tbt_smc_settings_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tbt_smc_settings_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tbt_layer_zones").performClick()
        composeTestRule.onNodeWithTag("tbt_layer_power_shift").performClick()
        composeTestRule.onNodeWithTag("tbt_layer_overlap").performClick()
        composeTestRule.onNodeWithTag("tbt_layer_signals").performClick()
        composeTestRule.onNodeWithTag("tbt_layer_hud").performClick()
        assertEquals(1, zonesToggled)
        assertEquals(1, powerToggled)
        assertEquals(1, overlapToggled)
        assertEquals(1, signalsToggled)
        assertEquals(1, hudToggled)
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land")
    fun `fullscreen landscape bar exposes tbt smc controls`() {
        var toggled = false
        val state = ChartUiState(
            symbol = "ETHUSDT",
            contract = FuturesContract("ETHUSDT", "ETHUSDT", "ETH", "USDT", 2, 3, 0.01, "PERPETUAL", "TRADING"),
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
                    onStartReplay = {},
                    onToggleTbtSmc = { toggled = true },
                    onExitFullScreen = {},
                    modifier = Modifier.size(width = 760.dp, height = 40.dp)
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tbt_smc_toggle_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("tbt_smc_settings_button").assertIsDisplayed()
        assertEquals(true, toggled)
    }

    @Test
    fun `portrait fullscreen bar exposes tbt smc controls`() {
        var toggled = false
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
                    onStartReplay = {},
                    onToggleTbtSmc = { toggled = true },
                    onExitFullScreen = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tbt_smc_toggle_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("tbt_smc_settings_button").assertIsDisplayed()
        assertEquals(true, toggled)
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
}
