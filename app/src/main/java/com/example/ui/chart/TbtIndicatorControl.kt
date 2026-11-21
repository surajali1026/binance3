package com.example.ui.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.indicator.TbtMarketBias
import com.example.domain.indicator.TbtTradeDirection
import com.example.domain.indicator.TbtTradeSetup
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
import java.util.Locale

@Composable
fun TbtIndicatorControl(
    state: ChartUiState,
    onToggleVisible: () -> Unit,
    onToggleAutoHideOnHtf: () -> Unit,
    onToggleVolImbalanceLine: () -> Unit,
    onToggleZoneTags: () -> Unit,
    onToggleSignals: () -> Unit,
    onToggleZones: () -> Unit,
    onTogglePowerShift: () -> Unit,
    onToggleOverlap: () -> Unit,
    onToggleHud: () -> Unit,
    onUpdateTargetRR: (Double) -> Unit,
    onUpdateSlBufferPct: (Double) -> Unit,
    onToggleReplayMode: (Boolean) -> Unit,
    onReplayStepBackward: () -> Unit,
    onReplayTogglePlay: () -> Unit,
    onReplayStepForward: () -> Unit,
    onReplaySetSpeed: (Int) -> Unit,
    onReplayReset: () -> Unit,
    onCreateEntryAlert: (TbtTradeSetup, Boolean) -> Unit = { _, _ -> },
    onCreateSlTpAlerts: (TbtTradeSetup) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var openSettings by remember { mutableStateOf(false) }
    var expandTradeCard by remember { mutableStateOf(false) }

    val prefs = state.preferences
    val isHtf = state.isHtfTimeframe
    val isEffectivelyHidden = !state.isTbtEffectivelyVisible
    val report = state.tbtSmc.backtestReport
    val activeSetup = state.tbtSmc.activeSetup
    val replay = state.replayState
    val precision = state.contract?.pricePrecision ?: 2

    Column(modifier = modifier) {
        // Compact TradingView-style Indicator Tag (Pure TBT SMC — No ICT)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(ElevatedSurface.copy(alpha = 0.90f))
                .border(0.75.dp, BorderSubtle, RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("tbt_indicator_tag"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "TBT SMC",
                style = MaterialTheme.typography.labelSmall,
                color = if (isEffectivelyHidden) TextMuted else BullishGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                textDecoration = if (isEffectivelyHidden) TextDecoration.LineThrough else TextDecoration.None
            )

            if (prefs.tbtAutoHideOnHtf && isHtf) {
                Text(
                    text = "(Auto-Hidden)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberGold,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("tbt_auto_hidden_badge")
                )
            }

            if (!isEffectivelyHidden && report.winRate != null && report.netR != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.testTag("tbt_backtest_stats")
                ) {
                    Text(
                        text = "WR:",
                        style = TabularMonoStyle.copy(fontSize = 9.5.sp),
                        color = TextMuted
                    )
                    Text(
                        text = "${report.winRate}%",
                        style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "|",
                        style = TabularMonoStyle.copy(fontSize = 9.5.sp),
                        color = TextMuted
                    )
                    val netRFormatted = if (report.netR >= 0.0) {
                        "+${String.format(Locale.US, "%.1f", report.netR)}R"
                    } else {
                        "${String.format(Locale.US, "%.1f", report.netR)}R"
                    }
                    Text(
                        text = netRFormatted,
                        style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                        color = if (report.netR >= 0.0) BullishGreen else BearishRed
                    )
                }
            }

            // Show / Hide Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onToggleVisible)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("tbt_smc_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (prefs.showTbtSmc) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (prefs.showTbtSmc) "Hide Indicator" else "Show Indicator",
                    tint = if (prefs.showTbtSmc) TextPrimary else TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Settings Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (openSettings) BullishGreen.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable {
                        openSettings = !openSettings
                        if (openSettings) expandTradeCard = false
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("tbt_smc_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Indicator Settings",
                    tint = if (openSettings) BullishGreen else TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Actionable TBT SMC BUY / SELL Setup Pill + Expandable Trade Plan Card
        if (!isEffectivelyHidden && !openSettings && prefs.tbtShowSignals && activeSetup != null) {
            TbtBuySellSetupOverlay(
                setup = activeSetup,
                precision = precision,
                expanded = expandTradeCard,
                onToggleExpand = { expandTradeCard = !expandTradeCard },
                onCreateEntryAlert = onCreateEntryAlert,
                onCreateSlTpAlerts = onCreateSlTpAlerts,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        // Collapsible Settings Panel (Replay + Display & Auto-Hide + Risk & Target)
        AnimatedVisibility(
            visible = openSettings,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(284.dp)
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CanvasVoid.copy(alpha = 0.96f))
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("tbt_smc_settings_panel"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TBT SMC Settings",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { openSettings = false }
                            .padding(4.dp)
                            .testTag("tbt_settings_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Settings",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle, thickness = 0.75.dp)

                // 1. Bar Replay Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElevatedSurface)
                        .border(0.75.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleReplayMode(!replay.isActive) }
                            .testTag("tbt_replay_mode_toggle"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bar Replay Mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = BullishGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Checkbox(
                            checked = replay.isActive,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = BullishGreen,
                                checkmarkColor = CanvasVoid,
                                uncheckedColor = TextMuted
                            ),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (replay.isActive) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ReplayStepButton(
                                label = "Prev",
                                icon = Icons.Filled.SkipPrevious,
                                iconFirst = true,
                                containerColor = ToolbarSurface,
                                contentColor = TextPrimary,
                                testTag = "tbt_replay_prev_btn",
                                onClick = onReplayStepBackward,
                                modifier = Modifier.weight(1f)
                            )
                            ReplayStepButton(
                                label = if (replay.isPlaying) "Pause" else "Play",
                                icon = if (replay.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                iconFirst = true,
                                containerColor = BullishGreen,
                                contentColor = CanvasVoid,
                                testTag = "tbt_replay_play_btn",
                                onClick = onReplayTogglePlay,
                                modifier = Modifier.weight(1f)
                            )
                            ReplayStepButton(
                                label = "Next",
                                icon = Icons.Filled.SkipNext,
                                iconFirst = false,
                                containerColor = ToolbarSurface,
                                contentColor = TextPrimary,
                                testTag = "tbt_replay_next_btn",
                                onClick = onReplayStepForward,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Speed:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )
                            listOf(1 to "1x", 2 to "2x", 5 to "5x").forEach { (mult, label) ->
                                val selected = replay.speedMultiplier == mult
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (selected) BullishGreen.copy(alpha = 0.2f) else ToolbarSurface)
                                        .border(
                                            0.75.dp,
                                            if (selected) BullishGreen else BorderSubtle,
                                            RoundedCornerShape(5.dp)
                                        )
                                        .clickable { onReplaySetSpeed(mult) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                        .testTag("tbt_replay_speed_${mult}x")
                                ) {
                                    Text(
                                        text = label,
                                        style = TabularMonoStyle.copy(
                                            fontSize = 10.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (selected) BullishGreen else TextPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberGold,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = onReplayReset)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("tbt_replay_reset_btn")
                            )
                        }
                    }
                }

                // 2. Display & Auto-Hide Settings
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TbtCheckboxRow(
                        label = "Auto-Hide on HTF (>15m)",
                        checked = prefs.tbtAutoHideOnHtf,
                        testTag = "tbt_toggle_auto_hide_htf",
                        onClick = onToggleAutoHideOnHtf
                    )
                    TbtCheckboxRow(
                        label = "Buy / Sell Setup & 3CR Signals",
                        checked = prefs.tbtShowSignals,
                        testTag = "tbt_toggle_buy_sell_signals",
                        onClick = onToggleSignals
                    )
                    TbtCheckboxRow(
                        label = "Volume Imbalance Line",
                        checked = prefs.tbtShowVolImbalanceLine,
                        testTag = "tbt_toggle_vol_imbalance",
                        onClick = onToggleVolImbalanceLine
                    )
                    TbtCheckboxRow(
                        label = "RBR / DBD / DBR / RBD Tags",
                        checked = prefs.tbtShowZoneTags,
                        testTag = "tbt_toggle_zone_tags",
                        onClick = onToggleZoneTags
                    )
                }

                HorizontalDivider(color = BorderSubtle, thickness = 0.75.dp)

                // Core TBT SMC Layers
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TbtCheckboxRow(
                        label = "Demand / Supply Lines",
                        checked = prefs.tbtShowZones,
                        testTag = "tbt_layer_zones",
                        onClick = onToggleZones
                    )
                    TbtCheckboxRow(
                        label = "Power Shift",
                        checked = prefs.tbtShowPowerShift,
                        testTag = "tbt_layer_power_shift",
                        onClick = onTogglePowerShift
                    )
                    TbtCheckboxRow(
                        label = "Overlapping POI",
                        checked = prefs.tbtShowOverlap,
                        testTag = "tbt_layer_overlap",
                        onClick = onToggleOverlap
                    )
                    TbtCheckboxRow(
                        label = "Condition HUD",
                        checked = prefs.tbtShowHud,
                        testTag = "tbt_layer_hud",
                        onClick = onToggleHud
                    )
                }

                HorizontalDivider(color = BorderSubtle, thickness = 0.75.dp)

                // 3. Risk & Target Settings (Target R:R & SL Buffer %)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumericStepperInput(
                        label = "Target R:R",
                        value = prefs.tbtTargetRR,
                        step = 0.5,
                        minValue = 1.0,
                        maxValue = 20.0,
                        decimals = 1,
                        testTag = "tbt_input_target_rr",
                        onValueChange = onUpdateTargetRR,
                        modifier = Modifier.weight(1f)
                    )
                    NumericStepperInput(
                        label = "SL Buffer (%)",
                        value = prefs.tbtSlBufferPct,
                        step = 0.01,
                        minValue = 0.0,
                        maxValue = 5.0,
                        decimals = 2,
                        testTag = "tbt_input_sl_buffer",
                        onValueChange = onUpdateSlBufferPct,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TbtBuySellSetupOverlay(
    setup: TbtTradeSetup,
    precision: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCreateEntryAlert: (TbtTradeSetup, Boolean) -> Unit,
    onCreateSlTpAlerts: (TbtTradeSetup) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBuy = setup.direction == TbtTradeDirection.BUY
    val accent = if (isBuy) BullishGreen else BearishRed
    val rrLabel = if (setup.riskReward % 1.0 == 0.0) {
        "1:${setup.riskReward.toInt()}"
    } else {
        "1:${String.format(Locale.US, "%.1f", setup.riskReward)}"
    }

    Column(modifier = modifier) {
        // Compact BUY / SELL Signal Pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(CanvasVoid.copy(alpha = 0.92f))
                .border(0.75.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(7.dp))
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("tbt_trade_setup_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = setup.direction.label,
                    style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold),
                    color = CanvasVoid
                )
            }

            Text(
                text = setup.grade,
                style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                color = if (setup.grade == "A+") AmberGold else TextPrimary
            )

            Text(
                text = setup.triggerName,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )

            if (setup.htfLabel != null && setup.htfBias != null) {
                val htfColor = when (setup.htfBias) {
                    TbtMarketBias.BULLISH -> BullishGreen
                    TbtMarketBias.BEARISH -> BearishRed
                    TbtMarketBias.SIDEWAYS -> AmberGold
                }
                val htfShort = when (setup.htfBias) {
                    TbtMarketBias.BULLISH -> "BULL"
                    TbtMarketBias.BEARISH -> "BEAR"
                    TbtMarketBias.SIDEWAYS -> "RANGE"
                }
                Text(
                    text = "${setup.htfLabel}:$htfShort",
                    style = TabularMonoStyle.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = htfColor
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse trade plan" else "Expand trade plan",
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }

        // Expandable TBT SMC Buy/Sell Trade Plan Card
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .width(268.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(CanvasVoid.copy(alpha = 0.96f))
                    .border(0.75.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                    .padding(9.dp)
                    .testTag("tbt_trade_setup_card"),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // Header: Direction + Reason
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBuy) "TBT BUY LONG SETUP" else "TBT SELL SHORT SETUP",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "$rrLabel • ${setup.score}/5",
                            style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = AmberGold
                        )
                    }
                    Text(
                        text = setup.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Levels Grid (Entry, optional VI Limit, SL, TP)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(ElevatedSurface)
                        .border(0.5.dp, BorderSubtle, RoundedCornerShape(7.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TradeLevelRow(
                        label = "Entry (Market)",
                        value = ChartNumberFormatter.formatPrice(setup.entryPrice, precision),
                        valueColor = accent
                    )
                    setup.retracementEntryPrice?.let { viPrice ->
                        TradeLevelRow(
                            label = "VI Retrace Limit",
                            value = ChartNumberFormatter.formatPrice(viPrice, precision),
                            valueColor = CyanInfo
                        )
                    }
                    TradeLevelRow(
                        label = "Stop Loss",
                        value = "${ChartNumberFormatter.formatPrice(setup.stopLoss, precision)} (-${String.format(Locale.US, "%.2f", setup.riskPercent)}%)",
                        valueColor = BearishRed
                    )
                    TradeLevelRow(
                        label = "Take Profit ($rrLabel)",
                        value = ChartNumberFormatter.formatPrice(setup.takeProfit, precision),
                        valueColor = BullishGreen
                    )
                }

                // TBT SMC Confluence Checklist
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    setup.checklist.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(if (item.met) BullishGreen.copy(alpha = 0.22f) else ToolbarSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.met) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = BullishGreen,
                                        modifier = Modifier.size(9.dp)
                                    )
                                }
                            }
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.met) TextPrimary else TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 1-Tap Buy / Sell Execution Alert Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val hasVi = setup.retracementEntryPrice != null
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.18f))
                            .border(0.75.dp, accent.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                            .clickable { onCreateEntryAlert(setup, hasVi) }
                            .padding(vertical = 6.dp, horizontal = 6.dp)
                            .testTag("tbt_setup_alert_entry_btn"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAlert,
                            contentDescription = "Set Entry Alert",
                            tint = accent,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (hasVi) "Alert VI Entry" else "Alert Entry",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ToolbarSurface)
                            .border(0.75.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .clickable { onCreateSlTpAlerts(setup) }
                            .padding(vertical = 6.dp, horizontal = 6.dp)
                            .testTag("tbt_setup_alert_sltp_btn"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAlert,
                            contentDescription = "Set SL and TP Alerts",
                            tint = AmberGold,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Alert SL / TP",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeLevelRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = 10.sp
        )
        Text(
            text = value,
            style = TabularMonoStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
            color = valueColor
        )
    }
}

@Composable
private fun ReplayStepButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconFirst: Boolean,
    containerColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconFirst) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp
        )
        if (!iconFirst) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun TbtCheckboxRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 2.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) TextPrimary else TextMuted,
            fontSize = 11.5.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal
        )
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
    }
}

@Composable
private fun NumericStepperInput(
    label: String,
    value: Double,
    step: Double,
    minValue: Double,
    maxValue: Double,
    decimals: Int,
    testTag: String,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val formatted = remember(value, decimals) {
        String.format(Locale.US, "%.${decimals}f", value)
    }
    var textValue by remember { mutableStateOf(formatted) }
    LaunchedEffect(formatted) {
        if (textValue.toDoubleOrNull() != value) {
            textValue = formatted
        }
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ElevatedSurface)
                .border(0.75.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onValueChange((value - step).coerceIn(minValue, maxValue))
                    }
                    .padding(3.dp)
                    .testTag("${testTag}_minus")
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrease $label",
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }

            BasicTextField(
                value = textValue,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it == '.' }
                    textValue = filtered
                    filtered.toDoubleOrNull()?.let { parsed ->
                        if (parsed in minValue..maxValue) {
                            onValueChange(parsed)
                        }
                    }
                },
                singleLine = true,
                textStyle = TabularMonoStyle.copy(
                    color = TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                cursorBrush = SolidColor(BullishGreen),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .testTag(testTag)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onValueChange((value + step).coerceIn(minValue, maxValue))
                    }
                    .padding(3.dp)
                    .testTag("${testTag}_plus")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increase $label",
                    tint = BullishGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
