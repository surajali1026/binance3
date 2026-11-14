package com.example.ui.chart

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ChartDrawing
import com.example.domain.model.DrawingTool
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.CyanInfo
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

@Composable
fun ChartScreen(
    viewModel: ChartViewModel,
    showBackButton: Boolean,
    onBackToWatchlist: () -> Unit,
    applyBottomInset: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var showCoinSwitcher by remember { mutableStateOf(false) }
    var showTimeframePicker by remember { mutableStateOf(false) }
    var showAlertsManager by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showEditDrawing by remember { mutableStateOf<ChartDrawing?>(null) }
    var createAlertRequest by remember { mutableStateOf<Pair<Double, ChartDrawing?>?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Alert works in-app regardless; notification posts if granted */ }
    )

    fun requestCreateAlert(price: Double, linkedDrawing: ChartDrawing? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        createAlertRequest = price to linkedDrawing
    }

    // Handle immersive full-screen system bars
    LaunchedEffect(state.isFullScreen) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.isFullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? Activity)?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, view)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = state.isFullScreen) {
        viewModel.toggleFullScreen()
    }

    val containerModifier = if (state.isFullScreen) {
        modifier
            .fillMaxSize()
            .background(CanvasVoid)
    } else {
        modifier
            .fillMaxSize()
            .background(CanvasVoid)
            .windowInsetsPadding(WindowInsets.statusBars)
            .let { if (applyBottomInset) it.windowInsetsPadding(WindowInsets.navigationBars) else it }
    }

    Column(modifier = containerModifier) {
        if (!state.isFullScreen) {
            ChartTopHud(
                state = state,
                showBackButton = showBackButton,
                onBackClick = onBackToWatchlist,
                onOpenCoinSwitcher = { showCoinSwitcher = true },
                onSelectPriceSource = viewModel::selectPriceSource,
                onOpenAlerts = { showAlertsManager = true },
                onToggleFullScreen = viewModel::toggleFullScreen,
                onOpenSettings = { showSettings = true }
            )
            ChartTimeframeStrip(
                state = state,
                onSelectTimeframe = viewModel::selectTimeframe,
                onOpenTimeframePicker = { showTimeframePicker = true },
                onAutoFit = viewModel::triggerAutoFit,
                onStartReplay = viewModel::startBarReplayPicker
            )
        }

        // Main Full-Bleed Chart Viewport (single persistent WebView instance)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LightweightChartView(
                state = state,
                webUpdates = viewModel.webChartUpdates,
                buildFullHistory = viewModel::buildCurrentFullHistoryUpdate,
                onChartTap = viewModel::handleChartTap,
                onCrosshairMove = viewModel::updateCrosshair,
                onDismissCrosshair = viewModel::clearCrosshair,
                onDoubleTapReset = viewModel::onPlotDoubleTap,
                onPriceAxisTap = { price -> viewModel.setAxisQuickAlertPrice(price) },
                onQuickAlert = { price -> requestCreateAlert(price) },
                onMoveDrawingAnchor = viewModel::moveDrawingAnchor,
                onMoveWholeDrawing = viewModel::moveWholeDrawing,
                onMoveAlertPrice = viewModel::moveAlertPrice,
                onManualInteraction = viewModel::onChartManualInteraction,
                fallbackCanvas = {
                    ChartCanvas(
                        state = state,
                        onPlotWidthChanged = viewModel::updatePlotWidth,
                        onPan = viewModel::panChart,
                        onPinchZoom = viewModel::pinchZoom,
                        onScalePriceAxis = viewModel::scalePriceAxis,
                        onResetPriceAutoFit = viewModel::resetPriceAutoFitOnly,
                        onPlotDoubleTap = viewModel::onPlotDoubleTap,
                        onDismissCrosshair = viewModel::clearCrosshair,
                        onScaleTimeAxis = viewModel::scaleTimeAxis,
                        onCrosshairMove = viewModel::updateCrosshair,
                        onChartTap = viewModel::handleChartTap,
                        onMoveDrawingAnchor = viewModel::moveDrawingAnchor,
                        onMoveWholeDrawing = viewModel::moveWholeDrawing,
                        onMoveAlertPrice = viewModel::moveAlertPrice,
                        onPriceAxisTap = { price -> viewModel.setAxisQuickAlertPrice(price) },
                        onQuickAlertPlusClick = { price -> requestCreateAlert(price) }
                    )
                },
                modifier = Modifier.fillMaxSize()
            )

            // Side-mounted Floating Drawing Toolbar
            FloatingDrawingToolbar(
                expanded = state.drawingToolbarExpanded,
                activeTool = state.activeTool,
                onToggleExpand = viewModel::toggleDrawingToolbar,
                onSelectTool = viewModel::selectDrawingTool,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            )

            // Floating Crosshair OHLCV HUD (overlaid so chart height never jumps)
            if (state.crosshair.isActive) {
                CrosshairOhlcvBar(
                    state = state,
                    onCreateAlertAtCrosshair = { price -> requestCreateAlert(price) },
                    onDismiss = viewModel::clearCrosshair,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                )
            }

            // Full-screen exit button in corner when full-screen is active
            if (state.isFullScreen) {
                FullScreenExitChip(
                    onExitFullScreen = viewModel::toggleFullScreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 80.dp)
                )
            }

            // Active Tool / Replay Picking Instruction Banner
            val instructionText = when {
                state.replayState.isPickingStart -> "Tap a candle on the chart to start Bar Replay"
                state.activeTool == DrawingTool.HORIZONTAL_LINE -> "Tap chart to place Horizontal Line"
                state.activeTool == DrawingTool.TRENDLINE ->
                    if (state.pendingPoints.isEmpty()) "Tap 1st point for Trendline" else "Tap 2nd point for Trendline"
                state.activeTool == DrawingTool.RECTANGLE ->
                    if (state.pendingPoints.isEmpty()) "Tap 1st corner for Rectangle" else "Tap opposite corner for Rectangle"
                state.activeTool == DrawingTool.PRICE_RANGE ->
                    if (state.pendingPoints.isEmpty()) "Tap start point to measure Price Range" else "Tap end point to measure"
                else -> null
            }

            if (instructionText != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElevatedSurface.copy(alpha = 0.94f))
                        .border(0.75.dp, AmberGold.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = instructionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberGold,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (state.replayState.isPickingStart) viewModel.exitBarReplay()
                            else viewModel.selectDrawingTool(DrawingTool.NONE)
                        },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel",
                            tint = TextMuted
                        )
                    }
                }
            }

            // Selected Drawing Context Pill
            val selectedDrawing = state.drawings.firstOrNull { it.id == state.selectedDrawingId }
            if (selectedDrawing != null && !state.replayState.isActive) {
                SelectedDrawingActionPill(
                    drawing = selectedDrawing,
                    onEdit = { showEditDrawing = selectedDrawing },
                    onToggleLock = { viewModel.toggleDrawingLock(selectedDrawing.id) },
                    onCreateBreakoutAlert = {
                        val price = selectedDrawing.points.firstOrNull()?.price ?: state.currentPrice ?: 0.0
                        requestCreateAlert(price, selectedDrawing)
                    },
                    onDelete = viewModel::deleteSelectedDrawing,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                )
            }

            // Selected Alert Context Pill
            val selectedAlert = state.alertsForSymbol.firstOrNull { it.id == state.selectedAlertId && it.isActive }
            if (selectedAlert != null && selectedDrawing == null && !state.replayState.isActive) {
                SelectedAlertActionPill(
                    alert = selectedAlert,
                    precision = state.contract?.pricePrecision ?: 2,
                    onDelete = { viewModel.deleteAlert(selectedAlert.id) },
                    onOpenManager = { showAlertsManager = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                )
            }

            // Bar Replay Floating Controller
            if (state.replayState.isActive) {
                BarReplayControllerBar(
                    state = state,
                    onStepForward = viewModel::stepReplayForward,
                    onTogglePlay = viewModel::toggleReplayPlayPause,
                    onCycleSpeed = viewModel::cycleReplaySpeed,
                    onExit = viewModel::exitBarReplay,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                )
            }

            // Price Reset Pill (appears whenever user manually zoomed/panned or switched timeframe)
            if (!state.viewport.isAutoFit && selectedDrawing == null && !state.replayState.isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 76.dp, bottom = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElevatedSurface.copy(alpha = 0.94f))
                        .border(0.75.dp, AmberGold.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .clickable(onClick = viewModel::triggerAutoFit)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("chart_price_reset_pill")
                ) {
                    Text(
                        text = "Reset Price",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberGold,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            // Range Measurement Clear Pill
            if (state.rangeMeasurement != null && selectedDrawing == null && !state.replayState.isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElevatedSurface.copy(alpha = 0.9f))
                        .border(0.75.dp, CyanInfo, RoundedCornerShape(8.dp))
                        .clickable(onClick = viewModel::clearRangeMeasurement)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Clear Range",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanInfo,
                        fontSize = 11.sp
                    )
                }
            }

            // Initial History Loading or Error Overlay
            if (state.isLoadingHistory && state.candles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AmberGold, strokeWidth = 2.5.dp)
                }
            } else if (state.errorMessage != null && state.candles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.errorMessage ?: "Failed to load market candles",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BearishRed
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::reloadChartFeed,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = CanvasVoid)
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Bottom Sheets & Dialogs
    if (showCoinSwitcher) {
        QuickCoinSwitcherSheet(
            items = state.switcherItems,
            currentSymbol = state.symbol,
            onSelectSymbol = viewModel::openSymbol,
            onDismiss = { showCoinSwitcher = false }
        )
    }

    if (showTimeframePicker) {
        TimeframePickerSheet(
            allTimeframes = state.allTimeframes,
            activeTimeframeId = state.activeTimeframe.id,
            onSelectTimeframe = viewModel::selectTimeframe,
            onToggleFavorite = viewModel::toggleFavoriteTimeframe,
            onCreateCustom = viewModel::createCustomTimeframe,
            onDeleteCustom = viewModel::deleteCustomTimeframe,
            onDismiss = { showTimeframePicker = false }
        )
    }

    createAlertRequest?.let { (price, drawing) ->
        CreateAlertSheet(
            symbol = state.symbol,
            initialPrice = price,
            currentPrice = state.currentPrice,
            precision = state.contract?.pricePrecision ?: 2,
            linkedDrawing = drawing,
            onCreate = { targetPrice, cond, linked ->
                viewModel.createAlert(targetPrice, cond, linked)
            },
            onDismiss = { createAlertRequest = null }
        )
    }

    if (showAlertsManager) {
        AlertsManagerSheet(
            state = state,
            onCreateNewAlertClick = {
                requestCreateAlert(state.currentPrice ?: 0.0, null)
            },
            onToggleAlertActive = viewModel::toggleAlertActive,
            onDeleteAlert = viewModel::deleteAlert,
            onClearHistory = viewModel::clearAlertHistory,
            onDismiss = { showAlertsManager = false }
        )
    }

    showEditDrawing?.let { drawing ->
        EditDrawingSheet(
            drawing = drawing,
            onUpdateStyle = { colorHex, width, style ->
                viewModel.updateDrawingStyle(drawing.id, colorHex, width, style)
            },
            onDismiss = { showEditDrawing = null }
        )
    }

    if (showSettings) {
        ChartSettingsSheet(
            preferences = state.preferences,
            onUpdatePreferences = viewModel::updatePreferences,
            onDismiss = { showSettings = false }
        )
    }
}
