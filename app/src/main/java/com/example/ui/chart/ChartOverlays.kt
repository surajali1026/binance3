package com.example.ui.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.SocketConnectionState
import com.example.domain.model.ChartDrawing
import com.example.domain.model.DrawingTool
import com.example.domain.model.DrawingType
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.CyanInfo
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.ToolbarSurface

@Composable
fun ChartTopHud(
    state: ChartUiState,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onOpenCoinSwitcher: () -> Unit,
    onSelectPriceSource: (PriceSource) -> Unit,
    onOpenAlerts: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val pct = state.ticker?.priceChangePercent24h ?: 0.0
    val isUp = pct >= 0.0
    val activeAlertCount = state.allActiveAlerts.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CanvasVoid)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chart_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Watchlist",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Quick Coin Switcher Pill (No branding/logo)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenCoinSwitcher)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("quick_coin_switcher"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotColor = when (state.connectionState) {
                    SocketConnectionState.CONNECTED -> BullishGreen
                    SocketConnectionState.CONNECTING,
                    SocketConnectionState.RECONNECTING -> AmberGold
                    SocketConnectionState.DISCONNECTED -> BearishRed
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = state.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ChartNumberFormatter.formatPercent(pct),
                    style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isUp) BullishGreen else BearishRed
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Switch symbol",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Compact Market Price Selector (Last / Mark / Index)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PriceSource.entries.forEach { src ->
                    val selected = src == state.priceSource
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) AmberGold else Color.Transparent)
                            .clickable { onSelectPriceSource(src) }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .testTag("price_source_${src.name.lowercase()}")
                    ) {
                        Text(
                            text = src.shortLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) CanvasVoid else TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            IconButton(
                onClick = onOpenAlerts,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chart_alerts_button")
            ) {
                Icon(
                    imageVector = if (activeAlertCount > 0) Icons.Filled.NotificationsActive else Icons.Outlined.Notifications,
                    contentDescription = "Alerts",
                    tint = if (activeAlertCount > 0) AmberGold else TextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onToggleFullScreen,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chart_fullscreen_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "Full Screen",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chart_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Chart Settings",
                    tint = TextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
fun ChartTimeframeStrip(
    state: ChartUiState,
    onSelectTimeframe: (String) -> Unit,
    onOpenTimeframePicker: () -> Unit,
    onAutoFit: () -> Unit,
    onStartReplay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(CanvasVoid)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val favs = state.favoriteTimeframes
            val showActiveOutsideFavs = favs.none { it.id == state.activeTimeframe.id }
            val visibleList = if (showActiveOutsideFavs) favs + state.activeTimeframe else favs

            visibleList.forEach { tf ->
                val selected = tf.id == state.activeTimeframe.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) ToolbarSurface else Color.Transparent)
                        .clickable { onSelectTimeframe(tf.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("tf_chip_${tf.id}")
                ) {
                    Text(
                        text = tf.label,
                        style = TabularMonoStyle.copy(
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) AmberGold else TextMuted
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ElevatedSurface)
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenTimeframePicker)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("open_timeframe_picker")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "More",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontSize = 10.sp
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "All Timeframes",
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // Quick Price Reset / Auto-Fit & Bar Replay triggers
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (!state.viewport.isAutoFit) AmberGold.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onAutoFit)
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("chart_autofit_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FitScreen,
                contentDescription = "Reset Price & Fit Chart",
                tint = if (!state.viewport.isAutoFit) AmberGold else CyanInfo,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Reset",
                style = MaterialTheme.typography.bodySmall,
                color = if (!state.viewport.isAutoFit) AmberGold else TextMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onStartReplay)
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("chart_replay_button")
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = "Bar Replay",
                tint = if (state.replayState.isActive || state.replayState.isPickingStart) AmberGold else TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun CrosshairOhlcvBar(
    state: ChartUiState,
    onCreateAlertAtCrosshair: (Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val candle = state.crosshair.candle ?: return
    val precision = state.contract?.pricePrecision ?: 2
    val changePct = if (candle.open != 0.0) ((candle.close - candle.open) / candle.open) * 100.0 else 0.0
    val isBull = candle.close >= candle.open
    val valueColor = if (isBull) BullishGreen else BearishRed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedSurface.copy(alpha = 0.92f))
            .border(0.75.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "O ${ChartNumberFormatter.formatPrice(candle.open, precision)}  H ${ChartNumberFormatter.formatPrice(candle.high, precision)}  L ${ChartNumberFormatter.formatPrice(candle.low, precision)}  C ${ChartNumberFormatter.formatPrice(candle.close, precision)} (${ChartNumberFormatter.formatPercent(changePct)})",
            style = TabularMonoStyle.copy(fontSize = 10.sp),
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onCreateAlertAtCrosshair(state.crosshair.price) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AddAlert,
                contentDescription = "Alert at crosshair price",
                tint = AmberGold,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close crosshair",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun FloatingDrawingToolbar(
    expanded: Boolean,
    activeTool: DrawingTool,
    onToggleExpand: () -> Unit,
    onSelectTool: (DrawingTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        // Compact toggle pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ElevatedSurface.copy(alpha = 0.9f))
                .border(
                    width = 0.75.dp,
                    color = if (activeTool != DrawingTool.NONE) AmberGold else BorderSubtle,
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .testTag("drawing_toolbar_toggle")
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "Drawing Tools",
                tint = if (activeTool != DrawingTool.NONE || expanded) AmberGold else TextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut() + slideOutHorizontally { -it / 2 }
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElevatedSurface.copy(alpha = 0.94f))
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DrawingToolOption(
                    icon = Icons.Filled.HorizontalRule,
                    label = "Horizontal",
                    selected = activeTool == DrawingTool.HORIZONTAL_LINE,
                    testTag = "tool_horizontal_line",
                    onClick = { onSelectTool(DrawingTool.HORIZONTAL_LINE) }
                )
                DrawingToolOption(
                    icon = Icons.Filled.CropSquare,
                    label = "Rectangle",
                    selected = activeTool == DrawingTool.RECTANGLE,
                    testTag = "tool_rectangle",
                    onClick = { onSelectTool(DrawingTool.RECTANGLE) }
                )
                DrawingToolOption(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    label = "Trendline",
                    selected = activeTool == DrawingTool.TRENDLINE,
                    testTag = "tool_trendline",
                    onClick = { onSelectTool(DrawingTool.TRENDLINE) }
                )
                DrawingToolOption(
                    icon = Icons.Filled.Straighten,
                    label = "Price Range",
                    selected = activeTool == DrawingTool.PRICE_RANGE,
                    testTag = "tool_price_range",
                    onClick = { onSelectTool(DrawingTool.PRICE_RANGE) }
                )
            }
        }
    }
}

@Composable
private fun DrawingToolOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AmberGold.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) AmberGold else TextPrimary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) AmberGold else TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SelectedDrawingActionPill(
    drawing: ChartDrawing,
    onEdit: () -> Unit,
    onToggleLock: () -> Unit,
    onCreateBreakoutAlert: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ElevatedSurface.copy(alpha = 0.96f))
            .border(0.75.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("selected_drawing_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DrawingActionChip(
            icon = Icons.Filled.Edit,
            label = "Style",
            tint = TextPrimary,
            testTag = "drawing_edit_button",
            onClick = onEdit
        )
        DrawingActionChip(
            icon = if (drawing.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            label = if (drawing.isLocked) "Unlock" else "Lock",
            tint = if (drawing.isLocked) AmberGold else TextPrimary,
            testTag = "drawing_lock_button",
            onClick = onToggleLock
        )
        if (drawing.type == DrawingType.HORIZONTAL_LINE || drawing.type == DrawingType.TRENDLINE) {
            DrawingActionChip(
                icon = Icons.Filled.AddAlert,
                label = "Alert",
                tint = AlertAmber,
                testTag = "drawing_alert_button",
                onClick = onCreateBreakoutAlert
            )
        }
        DrawingActionChip(
            icon = Icons.Filled.Delete,
            label = "Delete",
            tint = BearishRed,
            testTag = "drawing_delete_button",
            onClick = onDelete
        )
    }
}

@Composable
private fun DrawingActionChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun SelectedAlertActionPill(
    alert: PriceAlert,
    precision: Int,
    onDelete: () -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ElevatedSurface.copy(alpha = 0.95f))
            .border(0.75.dp, AlertAmber.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("selected_alert_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${alert.condition.label}: ${ChartNumberFormatter.formatPrice(alert.targetPrice, precision)}",
            style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = AlertAmber
        )
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(onClick = onOpenManager, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Manage Alerts", tint = TextPrimary, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete Alert", tint = BearishRed, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun BarReplayControllerBar(
    state: ChartUiState,
    onStepForward: () -> Unit,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val replay = state.replayState
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ElevatedSurface.copy(alpha = 0.96f))
            .border(0.75.dp, CyanInfo.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("bar_replay_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "REPLAY",
            style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = CyanInfo,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (replay.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (replay.isPlaying) "Pause Replay" else "Play Replay",
                tint = TextPrimary
            )
        }
        IconButton(onClick = onStepForward, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Step Next Bar",
                tint = TextPrimary
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ToolbarSurface)
                .clickable(onClick = onCycleSpeed)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = "${replay.speedMultiplier}x",
                style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = AmberGold
            )
        }
        IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Exit Bar Replay",
                tint = BearishRed,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun FullScreenExitChip(
    onExitFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface.copy(alpha = 0.85f))
            .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onExitFullScreen)
            .padding(8.dp)
            .testTag("exit_fullscreen_button")
    ) {
        Icon(
            imageVector = Icons.Filled.FullscreenExit,
            contentDescription = "Exit Full Screen",
            tint = TextPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}
