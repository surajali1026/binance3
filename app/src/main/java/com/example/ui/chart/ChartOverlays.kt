package com.example.ui.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.SocketConnectionState
import com.example.domain.indicator.TbtCascadeAction
import com.example.domain.indicator.TbtConditionSummary
import com.example.domain.indicator.TbtMarketBias
import com.example.domain.indicator.TbtMtfCascadeState
import com.example.domain.indicator.TbtMtfHubTab
import com.example.domain.indicator.TbtMtfProfile
import com.example.domain.indicator.TbtSmcConfig
import com.example.domain.model.ChartDrawing
import com.example.domain.model.DrawingTool
import com.example.domain.model.DrawingType
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
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
    onPrevSymbol: () -> Unit = {},
    onNextSymbol: () -> Unit = {},
    onSelectPriceSource: (PriceSource) -> Unit,
    onOpenAlerts: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val pct = state.ticker?.priceChangePercent24h ?: 0.0
    val isUp = pct >= 0.0
    val activeAlertCount = state.allActiveAlerts.size
    val canCycle = state.canCycleSymbols

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CanvasVoid)
            .padding(horizontal = 4.dp, vertical = 2.dp)
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
                        .size(30.dp)
                        .testTag("chart_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Watchlist",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Context-Aware Coin Navigator (< | Symbol % ▾ | >) gets all remaining width
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 22.dp, height = 34.dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            .background(ElevatedSurface)
                            .border(0.75.dp, BorderSubtle, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            .clickable(enabled = canCycle, onClick = onPrevSymbol)
                            .testTag("chart_prev_symbol_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ChevronLeft,
                            contentDescription = "Previous coin in ${state.navigationContextLabel}",
                            tint = if (canCycle) TextPrimary else TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(34.dp)
                            .background(ElevatedSurface)
                            .border(0.75.dp, BorderSubtle)
                            .clickable(onClick = onOpenCoinSwitcher)
                            .padding(horizontal = 6.dp)
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
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.symbol,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = ChartNumberFormatter.formatPercent(pct),
                            style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isUp) BullishGreen else BearishRed,
                            maxLines = 1,
                            softWrap = false
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Switch symbol",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(width = 22.dp, height = 34.dp)
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(ElevatedSurface)
                            .border(0.75.dp, BorderSubtle, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .clickable(enabled = canCycle, onClick = onNextSymbol)
                            .testTag("chart_next_symbol_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Next coin in ${state.navigationContextLabel}",
                            tint = if (canCycle) TextPrimary else TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Interactive Price Type Selector Box + Dropdown (Last / Mark / Index)
            PriceSourceSelectorBox(
                state = state,
                onSelectPriceSource = onSelectPriceSource,
                compact = false,
                modifier = Modifier.padding(end = 2.dp)
            )

            IconButton(
                onClick = onOpenAlerts,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("chart_alerts_button")
            ) {
                Icon(
                    imageVector = if (activeAlertCount > 0) Icons.Filled.NotificationsActive else Icons.Outlined.Notifications,
                    contentDescription = "Alerts",
                    tint = if (activeAlertCount > 0) AmberGold else TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = onToggleFullScreen,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("chart_fullscreen_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "Full Screen",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("chart_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Chart Settings",
                    tint = TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

private fun resolvePriceForSource(state: ChartUiState, source: PriceSource): Double? {
    val ticker = state.ticker
    return when (source) {
        PriceSource.LAST -> {
            if (state.priceSource == PriceSource.LAST) {
                state.currentPrice ?: ticker?.lastPrice ?: state.candles.lastOrNull()?.close
            } else {
                ticker?.lastPrice ?: state.candles.lastOrNull()?.close
            }
        }
        PriceSource.MARK -> {
            if (state.priceSource == PriceSource.MARK) {
                state.currentPrice ?: ticker?.markPrice
            } else {
                ticker?.markPrice
            }
        }
        PriceSource.INDEX -> {
            if (state.priceSource == PriceSource.INDEX) {
                state.currentPrice ?: ticker?.indexPrice
            } else {
                ticker?.indexPrice
            }
        }
    }
}

@Composable
fun PriceSourceSelectorBox(
    state: ChartUiState,
    onSelectPriceSource: (PriceSource) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val precision = state.contract?.pricePrecision ?: 2
    val activePrice = resolvePriceForSource(state, state.priceSource)
    val activePriceText = activePrice?.let { ChartNumberFormatter.formatPrice(it, precision) } ?: "--"
    val corner = if (compact) 6.dp else 8.dp
    val borderColor = if (expanded) AmberGold.copy(alpha = 0.85f) else BorderSubtle

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(corner))
                .background(ElevatedSurface)
                .border(0.75.dp, borderColor, RoundedCornerShape(corner))
                .clickable { expanded = !expanded }
                .padding(
                    horizontal = if (compact) 6.dp else 7.dp,
                    vertical = if (compact) 2.5.dp else 3.dp
                )
                .testTag("price_source_dropdown_button"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = state.priceSource.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberGold,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 8.5.sp else 9.sp,
                    lineHeight = if (compact) 10.sp else 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = activePriceText,
                    style = TabularMonoStyle.copy(
                        fontSize = if (compact) 10.sp else 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = if (compact) 12.sp else 13.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 2.dp else 3.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = "Select price type (${state.priceSource.label})",
                tint = if (expanded) AmberGold else TextMuted,
                modifier = Modifier.size(if (compact) 14.dp else 15.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = ElevatedSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .testTag("price_source_dropdown_menu")
        ) {
            PriceSource.entries.forEach { src ->
                val selected = src == state.priceSource
                val optionPrice = resolvePriceForSource(state, src)
                val optionPriceText = optionPrice?.let { ChartNumberFormatter.formatPrice(it, precision) } ?: "--"
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.widthIn(min = 152.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.padding(end = 12.dp)) {
                                Text(
                                    text = src.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) AmberGold else TextPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = optionPriceText,
                                    style = TabularMonoStyle.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (selected) TextPrimary else TextMuted,
                                    maxLines = 1
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "${src.label} selected",
                                    tint = AmberGold,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectPriceSource(src)
                    },
                    modifier = Modifier
                        .background(if (selected) AmberGold.copy(alpha = 0.14f) else Color.Transparent)
                        .testTag("price_source_${src.name.lowercase()}")
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
    onStartReplay: () -> Unit,
    onToggleTbtSmc: () -> Unit = {},
    onToggleTbtZones: () -> Unit = {},
    onToggleTbtPowerShift: () -> Unit = {},
    onToggleTbtOverlap: () -> Unit = {},
    onToggleTbtSignals: () -> Unit = {},
    onToggleTbtHud: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(CanvasVoid)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val favs = state.favoriteTimeframes
            val showActiveOutsideFavs = favs.none { it.id == state.activeTimeframe.id }
            val visibleList = (if (showActiveOutsideFavs) favs + state.activeTimeframe else favs)
                .sortedWith(compareBy<Timeframe> { it.durationMillis }.thenBy { it.sortOrder })

            visibleList.forEach { tf ->
                val selected = tf.id == state.activeTimeframe.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) ToolbarSurface else Color.Transparent)
                        .clickable { onSelectTimeframe(tf.id) }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
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
                    .padding(horizontal = 7.dp, vertical = 4.dp)
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

        Spacer(modifier = Modifier.width(4.dp))

        // Quick Price Reset / Auto-Fit & Bar Replay triggers
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (!state.viewport.isAutoFit) AmberGold.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onAutoFit)
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .testTag("chart_autofit_button")
        ) {
            Icon(
                imageVector = Icons.Filled.FitScreen,
                contentDescription = "Reset Price & Fit Chart",
                tint = if (!state.viewport.isAutoFit) AmberGold else CyanInfo,
                modifier = Modifier.size(15.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onStartReplay)
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .testTag("chart_replay_button")
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = "Bar Replay",
                tint = if (state.replayState.isActive || state.replayState.isPickingStart) AmberGold else TextMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
fun TbtSmcControls(
    showTbtSmc: Boolean,
    config: TbtSmcConfig,
    onToggleMain: () -> Unit,
    onToggleZones: () -> Unit,
    onTogglePowerShift: () -> Unit,
    onToggleOverlap: () -> Unit,
    onToggleSignals: () -> Unit,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier
) {
    var settingsExpanded by remember { mutableStateOf(false) }
    val activeTint = BullishGreen
    val pillBg = if (showTbtSmc) activeTint.copy(alpha = 0.16f) else ElevatedSurface
    val pillBorder = if (showTbtSmc) activeTint.copy(alpha = 0.72f) else BorderSubtle
    val contentTint = if (showTbtSmc) activeTint else TextMuted

    Box(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(pillBg)
                    .border(0.75.dp, pillBorder, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable(onClick = onToggleMain)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("tbt_smc_toggle_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (showTbtSmc) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (showTbtSmc) "Hide TBT SMC" else "Show TBT SMC",
                    tint = contentTint,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "TBT SMC",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentTint,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(if (settingsExpanded) AmberGold.copy(alpha = 0.16f) else ElevatedSurface)
                    .border(
                        0.75.dp,
                        if (settingsExpanded) AmberGold else pillBorder,
                        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                    )
                    .clickable { settingsExpanded = !settingsExpanded }
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .testTag("tbt_smc_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = "TBT SMC Layer Settings",
                    tint = if (settingsExpanded) AmberGold else contentTint,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        DropdownMenu(
            expanded = settingsExpanded,
            onDismissRequest = { settingsExpanded = false },
            containerColor = ElevatedSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .testTag("tbt_smc_settings_menu")
        ) {
            Text(
                text = "TBT Real SMC Layers",
                style = MaterialTheme.typography.labelMedium,
                color = AmberGold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            TbtLayerMenuItem(
                label = "Zones (Demand / Supply)",
                checked = config.showZones,
                testTag = "tbt_layer_zones",
                onClick = onToggleZones
            )
            TbtLayerMenuItem(
                label = "Power Shift",
                checked = config.showPowerShift,
                testTag = "tbt_layer_power_shift",
                onClick = onTogglePowerShift
            )
            TbtLayerMenuItem(
                label = "Overlapping POI",
                checked = config.showOverlap,
                testTag = "tbt_layer_overlap",
                onClick = onToggleOverlap
            )
            TbtLayerMenuItem(
                label = "Signals (VI / Liq / 3CR)",
                checked = config.showSignals,
                testTag = "tbt_layer_signals",
                onClick = onToggleSignals
            )
            TbtLayerMenuItem(
                label = "Condition HUD",
                checked = config.showHud,
                testTag = "tbt_layer_hud",
                onClick = onToggleHud
            )
        }
    }
}

@Composable
private fun TbtLayerMenuItem(
    label: String,
    checked: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = BullishGreen,
                        checkmarkColor = CanvasVoid,
                        uncheckedColor = TextMuted
                    ),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) TextPrimary else TextMuted,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag)
    )
}

@Composable
fun TbtConditionHudBadge(
    summary: TbtConditionSummary,
    precision: Int,
    mtfCascade: TbtMtfCascadeState? = null,
    activeProfile: TbtMtfProfile = TbtMtfProfile.INTRADAY_1H,
    activeTimeframeId: String = "15m",
    hubTabs: List<TbtMtfHubTab> = TbtMtfHubTab.allTabs(listOf(TbtMtfHubTab.DEFAULT_CUSTOM_30M)),
    activeHubTabId: String = activeProfile.name,
    onSelectMtfProfile: (TbtMtfProfile) -> Unit = {},
    onSelectHubTab: (String) -> Unit = {},
    onAddCustomHubTabClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val biasColor = when (summary.bias) {
        TbtMarketBias.BULLISH -> BullishGreen
        TbtMarketBias.BEARISH -> BearishRed
        TbtMarketBias.SIDEWAYS -> AmberGold
    }

    val cascadeColor = when (mtfCascade?.action) {
        TbtCascadeAction.BUY_READY -> BullishGreen
        TbtCascadeAction.WAIT_BUY_CONFIRM -> CyanInfo
        TbtCascadeAction.SELL_READY -> BearishRed
        TbtCascadeAction.WAIT_SELL_CONFIRM -> AmberGold
        TbtCascadeAction.MIXED_WAIT, null -> TextMuted
    }

    Column(
        modifier = modifier
            .widthIn(min = 160.dp, max = 236.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedSurface.copy(alpha = 0.90f))
            .border(0.75.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .animateContentSize()
            .testTag("tbt_condition_hud"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header Row: TBT HUB + Bias Pill + Add Custom Tab (+) + Collapse Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "TBT HUB",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.5.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(biasColor.copy(alpha = 0.16f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = summary.bias.shortLabel,
                        style = TabularMonoStyle.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Bold),
                        color = biasColor
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AmberGold.copy(alpha = 0.16f))
                        .border(0.5.dp, AmberGold.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onAddCustomHubTabClick)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag("tbt_hub_add_tab_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add Custom MTF Hub Tab",
                            tint = AmberGold,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "Tab",
                            style = TabularMonoStyle.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Bold),
                            color = AmberGold
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse TBT Hub" else "Expand TBT Hub",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { expanded = !expanded }
                        .testTag("tbt_hub_collapse_btn")
                )
            }
        }

        // Hub Tabs Strip (1H, 4H, 30m, and any user custom tabs — switches Hub view only, never switches chart TF)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            hubTabs.forEach { tab ->
                val selected = tab.id == activeHubTabId
                val profileTag = when (tab.id) {
                    TbtMtfProfile.INTRADAY_1H.name -> "tbt_mtf_profile_1h"
                    TbtMtfProfile.SWING_4H.name -> "tbt_mtf_profile_4h"
                    else -> "tbt_hub_tab_${tab.name.lowercase()}"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) AmberGold.copy(alpha = 0.24f) else ToolbarSurface)
                        .border(
                            0.6.dp,
                            if (selected) AmberGold else BorderSubtle,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            onSelectHubTab(tab.id)
                            when (tab.id) {
                                TbtMtfProfile.INTRADAY_1H.name -> onSelectMtfProfile(TbtMtfProfile.INTRADAY_1H)
                                TbtMtfProfile.SWING_4H.name -> onSelectMtfProfile(TbtMtfProfile.SWING_4H)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag(profileTag)
                ) {
                    Text(
                        text = tab.name,
                        style = TabularMonoStyle.copy(
                            fontSize = 8.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                        ),
                        color = if (selected) AmberGold else TextMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Cascade Summary Banner (Bullish / Bearish alignment count)
        if (mtfCascade != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(cascadeColor.copy(alpha = 0.16f))
                    .border(0.5.dp, cascadeColor.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 2.5.dp)
                    .testTag("tbt_mtf_cascade_banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = mtfCascade.headline,
                    style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                    color = cascadeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${mtfCascade.alignedCount}/${mtfCascade.totalCount}",
                    style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.ExtraBold),
                    color = cascadeColor
                )
            }
        }

        if (expanded) {
            // Read-Only Multi-Timeframe Bullish / Bearish Signal Grid (does NOT switch chart timeframe on tap)
            if (mtfCascade != null && mtfCascade.snapshots.isNotEmpty()) {
                val cols = when {
                    mtfCascade.snapshots.size <= 3 -> mtfCascade.snapshots.size
                    mtfCascade.snapshots.size <= 6 -> 3
                    else -> 4
                }
                val rows = mtfCascade.snapshots.chunked(cols)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            rowItems.forEach { snap ->
                                val snapColor = when {
                                    !snap.isLoaded -> TextMuted
                                    snap.bias == TbtMarketBias.BULLISH -> BullishGreen
                                    snap.bias == TbtMarketBias.BEARISH -> BearishRed
                                    else -> AmberGold
                                }
                                val signalText = when {
                                    !snap.isLoaded -> "..."
                                    snap.bias == TbtMarketBias.BULLISH -> "▲ BULL"
                                    snap.bias == TbtMarketBias.BEARISH -> "▼ BEAR"
                                    else -> "FLAT"
                                }
                                val isCurrentTf = snap.tfId.equals(activeTimeframeId, ignoreCase = true)
                                val borderColor = when {
                                    isCurrentTf -> AmberGold.copy(alpha = 0.85f)
                                    snap.isLoaded -> snapColor.copy(alpha = 0.45f)
                                    else -> BorderSubtle
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(snapColor.copy(alpha = if (snap.isLoaded) 0.14f else 0.08f))
                                        .border(if (isCurrentTf) 0.9.dp else 0.5.dp, borderColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                                        .testTag("tbt_hub_tf_${snap.tfId.lowercase()}"),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = snap.label,
                                        style = TabularMonoStyle.copy(
                                            fontSize = 7.5.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isCurrentTf) TextPrimary else TextMuted,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = signalText,
                                        style = TabularMonoStyle.copy(
                                            fontSize = 7.5.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = snapColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Compact Power Shift & Short Signal Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pwrText = when {
                    summary.activeBullPower != null -> "BPWR ${ChartNumberFormatter.formatPrice(summary.activeBullPower, precision)}"
                    summary.activeBearPower != null -> "SPWR ${ChartNumberFormatter.formatPrice(summary.activeBearPower, precision)}"
                    else -> null
                }
                if (pwrText != null) {
                    Text(
                        text = pwrText,
                        style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFFFD600),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = summary.lastSignal,
                    style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
    onStepBackward: () -> Unit = {},
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
        IconButton(onClick = onStepBackward, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Step Previous Bar",
                tint = TextPrimary
            )
        }
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
fun FullScreenTimeframeBar(
    state: ChartUiState,
    onOpenCoinSwitcher: () -> Unit,
    onPrevSymbol: () -> Unit = {},
    onNextSymbol: () -> Unit = {},
    onSelectPriceSource: (PriceSource) -> Unit = {},
    onSelectTimeframe: (String) -> Unit,
    onOpenTimeframePicker: () -> Unit,
    onAutoFit: () -> Unit,
    onStartReplay: () -> Unit,
    onToggleTbtSmc: () -> Unit = {},
    onToggleTbtZones: () -> Unit = {},
    onToggleTbtPowerShift: () -> Unit = {},
    onToggleTbtOverlap: () -> Unit = {},
    onToggleTbtSignals: () -> Unit = {},
    onToggleTbtHud: () -> Unit = {},
    onExitFullScreen: () -> Unit,
    showBackToWatchlist: Boolean = false,
    onBackToWatchlist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pct = state.ticker?.priceChangePercent24h ?: 0.0
    val isUp = pct >= 0.0
    val canCycle = state.canCycleSymbols

    val favs = state.favoriteTimeframes
    val showActiveOutsideFavs = favs.none { it.id == state.activeTimeframe.id }
    val visibleList = (if (showActiveOutsideFavs) favs + state.activeTimeframe else favs)
        .sortedWith(compareBy<Timeframe> { it.durationMillis }.thenBy { it.sortOrder })

    @Composable
    fun CoinNavigatorCluster(modifier: Modifier = Modifier) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .clickable(enabled = canCycle, onClick = onPrevSymbol)
                    .padding(horizontal = 4.dp, vertical = 5.dp)
                    .testTag("fullscreen_prev_symbol_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Previous coin in ${state.navigationContextLabel}",
                    tint = if (canCycle) TextPrimary else TextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle)
                    .clickable(onClick = onOpenCoinSwitcher)
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .testTag("fullscreen_coin_switcher"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.symbol,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = ChartNumberFormatter.formatPercent(pct),
                    style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isUp) BullishGreen else BearishRed,
                    maxLines = 1,
                    softWrap = false
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Switch symbol",
                    tint = TextMuted,
                    modifier = Modifier.size(13.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .clickable(enabled = canCycle, onClick = onNextSymbol)
                    .padding(horizontal = 4.dp, vertical = 5.dp)
                    .testTag("fullscreen_next_symbol_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Next coin in ${state.navigationContextLabel}",
                    tint = if (canCycle) TextPrimary else TextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }

    @Composable
    fun ActionButtonsCluster() {
        Row(verticalAlignment = Alignment.CenterVertically) {

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!state.viewport.isAutoFit) AmberGold.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(onClick = onAutoFit)
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .testTag("chart_autofit_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.FitScreen,
                    contentDescription = "Reset Price & Fit Chart",
                    tint = if (!state.viewport.isAutoFit) AmberGold else CyanInfo,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onStartReplay)
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .testTag("chart_replay_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "Bar Replay",
                    tint = if (state.replayState.isActive || state.replayState.isPickingStart) AmberGold else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(ElevatedSurface)
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(7.dp))
                    .clickable(onClick = onExitFullScreen)
                    .padding(horizontal = 7.dp, vertical = 5.dp)
                    .testTag("exit_fullscreen_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = "Exit Full Screen",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    @Composable
    fun TimeframeChipsAndMoreRow(modifier: Modifier = Modifier) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleList.forEach { tf ->
                    val selected = tf.id == state.activeTimeframe.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) ToolbarSurface else Color.Transparent)
                            .clickable { onSelectTimeframe(tf.id) }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
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
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ElevatedSurface)
                    .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenTimeframePicker)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
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
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(CanvasVoid)
    ) {
        val isWideLandscape = maxWidth >= 520.dp
        if (isWideLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackToWatchlist) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ElevatedSurface)
                            .border(0.75.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .clickable(onClick = onBackToWatchlist)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .testTag("fullscreen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Watchlist",
                            tint = TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                CoinNavigatorCluster()

                Spacer(modifier = Modifier.width(6.dp))

                PriceSourceSelectorBox(
                    state = state,
                    onSelectPriceSource = onSelectPriceSource,
                    compact = true
                )

                Spacer(modifier = Modifier.width(6.dp))

                TimeframeChipsAndMoreRow(modifier = Modifier.weight(1f))

                Spacer(modifier = Modifier.width(4.dp))

                ActionButtonsCluster()
            }
        } else {
            // Compact portrait full-screen layout: preserves full coin name, price selector dropdown, TBT SMC controls, and timeframes without clipping
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBackToWatchlist) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ElevatedSurface)
                                .border(0.75.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                .clickable(onClick = onBackToWatchlist)
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                .testTag("fullscreen_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Watchlist",
                                tint = TextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        CoinNavigatorCluster()
                    }

                    PriceSourceSelectorBox(
                        state = state,
                        onSelectPriceSource = onSelectPriceSource,
                        compact = true,
                        modifier = Modifier.padding(end = 2.dp)
                    )

                    ActionButtonsCluster()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeframeChipsAndMoreRow(
                        modifier = Modifier.weight(1f)
                    )
                }
            }
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
