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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.indicator.DominanceSide
import com.example.domain.indicator.IntraCandleVolumeLevel
import com.example.domain.indicator.MultiExchangeVolumeOutput
import com.example.domain.indicator.SupportedExchange
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
import kotlinx.coroutines.delay

/**
 * Standalone Multi-Exchange Intra-Candle Buy/Sell Volume & Trap indicator control.
 * Positioned directly underneath the TBT SMC compact tag with independent Show/Hide,
 * CEX & Perp DEX checkboxes, Trap tag toggle, and inactivity auto-hide drawer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiExchangeVolumeControl(
    visible: Boolean,
    showTraps: Boolean,
    selectedExchanges: Set<SupportedExchange>,
    output: MultiExchangeVolumeOutput,
    collapseSignal: Int,
    onToggleVisible: () -> Unit,
    onToggleShowTraps: () -> Unit,
    onToggleExchange: (SupportedExchange) -> Unit,
    onResetDefaultExchanges: () -> Unit,
    modifier: Modifier = Modifier
) {
    var openSettings by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableLongStateOf(0L) }

    // Outside tap on chart collapses drawer cleanly
    LaunchedEffect(collapseSignal) {
        if (collapseSignal > 0 && openSettings) {
            openSettings = false
        }
    }

    // Auto-hide settings drawer after 6 seconds of inactivity to keep mobile chart uncluttered
    LaunchedEffect(openSettings, lastInteractionMs) {
        if (openSettings) {
            delay(6_000L)
            openSettings = false
        }
    }

    fun touchDrawer() {
        lastInteractionMs = System.currentTimeMillis()
    }

    val connectedCount = output.connectedExchanges.size
    val checkedCount = selectedExchanges.size

    Column(modifier = modifier) {
        // Compact Standalone Indicator Tag (directly below TBT SMC)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(ElevatedSurface.copy(alpha = 0.90f))
                .border(
                    0.75.dp,
                    if (visible) CyanInfo.copy(alpha = 0.55f) else BorderSubtle,
                    RoundedCornerShape(7.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("multi_exch_indicator_tag"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Vol Footprint",
                style = MaterialTheme.typography.labelSmall,
                color = if (!visible) TextMuted else CyanInfo,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                textDecoration = if (!visible) TextDecoration.LineThrough else TextDecoration.None
            )

            if (visible) {
                Text(
                    text = "$connectedCount/$checkedCount",
                    style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                    color = if (connectedCount > 0) BullishGreen else AmberGold,
                    modifier = Modifier.testTag("multi_exch_connected_badge")
                )
            }

            // Independent Show / Hide Eye Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onToggleVisible)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("multi_exch_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (visible) "Hide Volume Footprint" else "Show Volume Footprint",
                    tint = if (visible) TextPrimary else TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Settings Gear Button (opens CEX/DEX checkbox drawer with auto-hide)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (openSettings) CyanInfo.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable {
                        openSettings = !openSettings
                        if (openSettings) touchDrawer()
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("multi_exch_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Multi-Exch Vol & Traps Settings",
                    tint = if (openSettings) CyanInfo else TextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Collapsible Auto-Hide Settings Drawer
        AnimatedVisibility(
            visible = openSettings,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .width(268.dp)
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElevatedSurface.copy(alpha = 0.96f))
                    .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("multi_exch_settings_panel"),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Volume Footprint & Traps",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                touchDrawer()
                                onResetDefaultExchanges()
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("multi_exch_reset_defaults")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { openSettings = false }
                            .padding(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Multi-Exch settings",
                            tint = TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Buyer & Seller Trap Tags Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ToolbarSurface)
                        .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .clickable {
                            touchDrawer()
                            onToggleShowTraps()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("multi_exch_traps_toggle"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactCheckSquare(checked = showTraps, accent = AmberGold)
                    Spacer(modifier = Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Buyer / Seller Trap Tags",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = "Highlight trapped aggressive orderflow",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 9.sp
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

                // Top CEXs
                Text(
                    text = "TOP CEXs (PUBLIC STREAMS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SupportedExchange.CEX_LIST.forEach { ex ->
                        val isChecked = selectedExchanges.contains(ex)
                        val isLive = output.connectedExchanges.contains(ex)
                        ExchangeCheckboxChip(
                            exchange = ex,
                            checked = isChecked,
                            isConnected = isLive,
                            onClick = {
                                touchDrawer()
                                onToggleExchange(ex)
                            }
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

                // Top Perp DEXs
                Text(
                    text = "TOP PERP DEXs (PUBLIC APIS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SupportedExchange.DEX_LIST.forEach { ex ->
                        val isChecked = selectedExchanges.contains(ex)
                        val isLive = output.connectedExchanges.contains(ex)
                        ExchangeCheckboxChip(
                            exchange = ex,
                            checked = isChecked,
                            isConnected = isLive,
                            onClick = {
                                touchDrawer()
                                onToggleExchange(ex)
                            }
                        )
                    }
                }

                Text(
                    text = "Auto-hides after 6s • Connects only to checked venues",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun ExchangeCheckboxChip(
    exchange: SupportedExchange,
    checked: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val borderCol = when {
        checked && isConnected -> BullishGreen.copy(alpha = 0.7f)
        checked -> CyanInfo.copy(alpha = 0.7f)
        else -> BorderSubtle
    }
    val bgCol = if (checked) ToolbarSurface else CanvasVoid.copy(alpha = 0.65f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgCol)
            .border(0.75.dp, borderCol, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .testTag("multi_exch_cb_${exchange.id.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        CompactCheckSquare(
            checked = checked,
            accent = if (isConnected) BullishGreen else CyanInfo
        )
        Text(
            text = exchange.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (checked) TextPrimary else TextMuted,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactCheckSquare(
    checked: Boolean,
    accent: Color
) {
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) accent else Color.Transparent)
            .border(1.dp, if (checked) accent else TextMuted, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = CanvasVoid,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/**
 * Compact popup shown when the user taps an intra-candle Green/Red price-level marker.
 * Lists every participating exchange's name, Buy volume, and Sell volume at that price level.
 */
@Composable
fun MultiExchangeLevelPopup(
    level: IntraCandleVolumeLevel,
    precision: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBuyDom = level.dominantSide == DominanceSide.BUY
    val accent = if (isBuyDom) BullishGreen else BearishRed
    val sideLabel = if (isBuyDom) "BUY DOMINANT" else "SELL DOMINANT"

    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface.copy(alpha = 0.97f))
            .border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("multi_exch_level_popup"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accent.copy(alpha = 0.2f))
                    .border(0.75.dp, accent, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                val badgeText = when {
                    level.isBuyImbalance -> "BUY IMB"
                    level.isSellImbalance -> "SELL IMB"
                    else -> sideLabel
                }
                Text(
                    text = badgeText,
                    style = TabularMonoStyle.copy(fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold),
                    color = accent
                )
            }
            if (level.isPoc) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(AmberGold.copy(alpha = 0.22f))
                        .border(0.75.dp, AmberGold, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("multi_exch_popup_poc_badge")
                ) {
                    Text(
                        text = "POC",
                        style = TabularMonoStyle.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                        color = AmberGold
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(26.dp)
                    .testTag("multi_exch_popup_close")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close footprint breakdown",
                    tint = TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Price Level Band
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cluster: ${ChartNumberFormatter.formatPrice(level.priceLow, precision)} – ${ChartNumberFormatter.formatPrice(level.priceHigh, precision)}",
                style = TabularMonoStyle.copy(fontSize = 10.sp),
                color = TextMuted
            )
        }

        // Sell x Buy & Delta Summary Row
        val deltaSign = if (level.delta >= 0.0) "+" else "-"
        val deltaColor = if (level.delta >= 0.0) BullishGreen else BearishRed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ToolbarSurface)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sell: ${ChartNumberFormatter.formatCompactVolume(level.totalSellVolume)}",
                style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = BearishRed
            )
            Text(
                text = "Buy: ${ChartNumberFormatter.formatCompactVolume(level.totalBuyVolume)}",
                style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = BullishGreen
            )
            Text(
                text = "Δ $deltaSign${ChartNumberFormatter.formatCompactVolume(kotlin.math.abs(level.delta))}",
                style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
                color = deltaColor
            )
        }

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        // Header Row
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Exchange",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 9.5.sp,
                modifier = Modifier.weight(1.2f)
            )
            Text(
                text = "Buy Vol",
                style = MaterialTheme.typography.labelSmall,
                color = BullishGreen,
                fontSize = 9.5.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Sell Vol",
                style = MaterialTheme.typography.labelSmall,
                color = BearishRed,
                fontSize = 9.5.sp,
                modifier = Modifier.weight(1f)
            )
        }

        // Per-Exchange Breakdown Rows
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            level.exchanges.forEach { row ->
                val rowDomColor = if (row.dominantSide == DominanceSide.BUY) BullishGreen else BearishRed
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multi_exch_popup_row_${row.exchange.id.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.exchange.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.2f)
                    )
                    Text(
                        text = ChartNumberFormatter.formatCompactVolume(row.buyVolume),
                        style = TabularMonoStyle.copy(
                            fontSize = 10.sp,
                            fontWeight = if (row.dominantSide == DominanceSide.BUY) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (row.dominantSide == DominanceSide.BUY) rowDomColor else TextPrimary.copy(alpha = 0.78f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ChartNumberFormatter.formatCompactVolume(row.sellVolume),
                        style = TabularMonoStyle.copy(
                            fontSize = 10.sp,
                            fontWeight = if (row.dominantSide == DominanceSide.SELL) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (row.dominantSide == DominanceSide.SELL) rowDomColor else TextPrimary.copy(alpha = 0.78f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
