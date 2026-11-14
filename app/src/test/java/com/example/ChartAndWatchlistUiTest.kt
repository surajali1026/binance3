package com.example

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `chart top hud switches price source and shows symbol without branding`() {
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
        composeTestRule.onNodeWithTag("price_source_mark").performClick()
        assertEquals(PriceSource.MARK, selectedSource)
        composeTestRule.onNodeWithTag("price_source_index").performClick()
        assertEquals(PriceSource.INDEX, selectedSource)
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
}
