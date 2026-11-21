package com.example.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppVersionConfig
import com.example.data.remote.UpdateCheckState
import com.example.domain.model.AlertCondition
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPreferences
import com.example.domain.model.CustomTimeUnit
import com.example.domain.model.DrawingType
import com.example.domain.model.LineStyle
import com.example.domain.model.Timeframe
import com.example.domain.model.WatchlistFilter
import com.example.domain.model.WatchlistItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCoinSwitcherSheet(
    items: List<WatchlistItem>,
    currentSymbol: String,
    onSelectSymbol: (String) -> Unit,
    onSelectSymbolWithContext: ((String, List<String>, String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var queryValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val query = queryValue.text
    var activeFilter by remember { mutableStateOf(WatchlistFilter.ALL) }
    val filtered = remember(items, query, activeFilter) {
        val nowMs = System.currentTimeMillis()
        val q = query.trim()
        val top100Symbols = if (activeFilter == WatchlistFilter.TOP_100) {
            items
                .sortedWith(
                    compareBy<WatchlistItem> { it.displayRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.ticker?.marketCap ?: 0.0 }
                        .thenByDescending { it.ticker?.quoteVolume24h ?: 0.0 }
                )
                .take(100)
                .map { it.contract.symbol }
                .toHashSet()
        } else {
            null
        }
        val newListingSymbols = if (activeFilter == WatchlistFilter.NEW_LISTINGS) {
            val upcomingAndRecent = items
                .filter { it.contract.isUpcomingListing(nowMs) || it.contract.isRecentlyListed(nowMs, 30) }
                .map { it.contract.symbol }
                .toMutableSet()
            if (upcomingAndRecent.size < 25) {
                items
                    .filter { it.contract.onboardDate > 0L && !upcomingAndRecent.contains(it.contract.symbol) }
                    .sortedByDescending { it.contract.onboardDate }
                    .take(25 - upcomingAndRecent.size)
                    .forEach { upcomingAndRecent.add(it.contract.symbol) }
            }
            upcomingAndRecent
        } else {
            null
        }
        val searched = items.filter { item ->
            val matchesTop = top100Symbols == null || top100Symbols.contains(item.contract.symbol)
            val matchesNew = newListingSymbols == null || newListingSymbols.contains(item.contract.symbol)
            val matchesQuery = q.isEmpty() ||
                item.contract.symbol.contains(q, ignoreCase = true) ||
                item.contract.baseAsset.contains(q, ignoreCase = true)
            matchesTop && matchesNew && matchesQuery
        }
        when (activeFilter) {
            WatchlistFilter.ALL -> searched
            WatchlistFilter.TOP_100 -> searched.sortedWith(
                compareBy<WatchlistItem> { it.displayRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.ticker?.marketCap ?: 0.0 }
                    .thenByDescending { it.ticker?.quoteVolume24h ?: 0.0 }
            )
            WatchlistFilter.NEW_LISTINGS -> searched.sortedWith(
                Comparator { a, b ->
                    val aUpcoming = a.contract.isUpcomingListing(nowMs)
                    val bUpcoming = b.contract.isUpcomingListing(nowMs)
                    if (aUpcoming != bUpcoming) return@Comparator if (aUpcoming) -1 else 1
                    if (aUpcoming) {
                        val aTime = a.contract.onboardDate.takeIf { it > 0L } ?: Long.MAX_VALUE
                        val bTime = b.contract.onboardDate.takeIf { it > 0L } ?: Long.MAX_VALUE
                        val cmp = aTime.compareTo(bTime)
                        if (cmp != 0) return@Comparator cmp
                    } else {
                        val cmp = b.contract.onboardDate.compareTo(a.contract.onboardDate)
                        if (cmp != 0) return@Comparator cmp
                    }
                    (b.ticker?.quoteVolume24h ?: 0.0).compareTo(a.ticker?.quoteVolume24h ?: 0.0)
                }
            )
            WatchlistFilter.FAVORITES -> searched.filter { it.isFavorite }
            WatchlistFilter.GAINERS -> searched
                .filter { (it.ticker?.priceChangePercent24h ?: 0.0) > 0.0 }
                .sortedByDescending { it.ticker?.priceChangePercent24h ?: 0.0 }
            WatchlistFilter.LOSERS -> searched
                .filter { (it.ticker?.priceChangePercent24h ?: 0.0) < 0.0 }
                .sortedBy { it.ticker?.priceChangePercent24h ?: 0.0 }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Switch Contract",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = queryValue,
                onValueChange = { queryValue = it },
                placeholder = { Text("Search symbol...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search
                ),
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { queryValue = TextFieldValue("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear Search",
                                tint = TextMuted
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("coin_switcher_search_input")
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filterChips = listOf(
                    WatchlistFilter.ALL to "All",
                    WatchlistFilter.TOP_100 to "Top 100",
                    WatchlistFilter.NEW_LISTINGS to "New",
                    WatchlistFilter.FAVORITES to "Favorites",
                    WatchlistFilter.GAINERS to "Top Gainers",
                    WatchlistFilter.LOSERS to "Top Losers"
                )
                filterChips.forEach { (filter, label) ->
                    val selected = filter == activeFilter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberGold.copy(alpha = 0.16f) else ToolbarSurface)
                            .border(
                                0.75.dp,
                                if (selected) AmberGold else BorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { activeFilter = filter }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("coin_switcher_filter_${filter.name.lowercase()}")
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) AmberGold else TextMuted
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (activeFilter == WatchlistFilter.FAVORITES) {
                            "No starred contracts yet"
                        } else {
                            "No matching contracts"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .testTag("coin_switcher_list")
                ) {
                items(filtered, key = { it.contract.symbol }) { item ->
                    val nowMs = System.currentTimeMillis()
                    val isUpcoming = item.contract.isUpcomingListing(nowMs)
                    val pct = item.ticker?.priceChangePercent24h ?: 0.0
                    val isUp = pct >= 0.0
                    val isSelected = item.contract.symbol.equals(currentSymbol, ignoreCase = true)
                    val rank = item.displayRank ?: item.ticker?.marketCapRank
                    val rankPrefix = rank?.let { "#$it • " } ?: ""
                    val volText = item.ticker?.takeIf { it.quoteVolume24h > 0.0 }?.let {
                        ChartNumberFormatter.formatCompactVolume(it.quoteVolume24h)
                    } ?: "--"
                    val listingSubtext = if (activeFilter == WatchlistFilter.NEW_LISTINGS || isUpcoming) {
                        ChartNumberFormatter.formatListingSubtext(item.contract.onboardDate, item.contract.status, nowMs)
                    } else {
                        null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ToolbarSurface else Color.Transparent)
                            .clickable {
                                if (onSelectSymbolWithContext != null) {
                                    onSelectSymbolWithContext(
                                        item.contract.symbol,
                                        filtered.map { it.contract.symbol },
                                        activeFilter.label
                                    )
                                } else {
                                    onSelectSymbol(item.contract.symbol)
                                }
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.isFavorite) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = AmberGold,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.contract.symbol,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "${rankPrefix}Vol: $volText",
                                style = TabularMonoStyle.copy(fontSize = 11.sp),
                                color = TextMuted,
                                maxLines = 1
                            )
                            if (listingSubtext != null) {
                                Text(
                                    text = listingSubtext,
                                    style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    color = if (isUpcoming) AmberGold else CyanInfo,
                                    maxLines = 1
                                )
                            }
                        }
                        val hasPrice = item.ticker != null && item.ticker.lastPrice > 0.0
                        Text(
                            text = if (hasPrice) {
                                ChartNumberFormatter.formatPrice(item.ticker!!.lastPrice, item.contract.pricePrecision)
                            } else if (isUpcoming) {
                                "Pre-Launch"
                            } else {
                                "--"
                            },
                            style = TabularMonoStyle.copy(fontSize = if (!hasPrice && isUpcoming) 11.sp else 13.sp),
                            color = if (!hasPrice && isUpcoming) AmberGold else TextPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (!hasPrice && isUpcoming) "SOON" else ChartNumberFormatter.formatPercent(pct),
                            style = TabularMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = when {
                                !hasPrice && isUpcoming -> AmberGold
                                isUp -> BullishGreen
                                else -> BearishRed
                            }
                        )
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimeframePickerSheet(
    allTimeframes: List<Timeframe>,
    activeTimeframeId: String,
    onSelectTimeframe: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onCreateCustom: (Int, CustomTimeUnit) -> Unit,
    onDeleteCustom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Timeframes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showCustomDialog = true }) {
                    Text("+ Custom", color = AmberGold, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                text = "Tap a timeframe to switch. Tap star to pin to quick bar.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allTimeframes.forEach { tf ->
                    val selected = tf.id == activeTimeframeId
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberGold.copy(alpha = 0.16f) else ToolbarSurface)
                            .border(
                                0.75.dp,
                                if (selected) AmberGold else BorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                onSelectTimeframe(tf.id)
                                onDismiss()
                            }
                            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tf.label,
                            style = TabularMonoStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = if (selected) AmberGold else TextPrimary
                        )
                        IconButton(
                            onClick = { onToggleFavorite(tf.id, !tf.isFavorite) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (tf.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Pin timeframe",
                                tint = if (tf.isFavorite) AmberGold else TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        if (tf.isCustom) {
                            IconButton(
                                onClick = { onDeleteCustom(tf.id) },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete custom timeframe",
                                    tint = BearishRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomTimeframeDialog(
            onCreate = { amount, unit ->
                onCreateCustom(amount, unit)
                showCustomDialog = false
                onDismiss()
            },
            onDismiss = { showCustomDialog = false }
        )
    }
}

@Composable
private fun CustomTimeframeDialog(
    onCreate: (Int, CustomTimeUnit) -> Unit,
    onDismiss: () -> Unit
) {
    var valueStr by remember { mutableStateOf("7") }
    var unit by remember { mutableStateOf(CustomTimeUnit.MINUTES) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text("Create Custom Timeframe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = valueStr,
                    onValueChange = { if (it.length <= 3) valueStr = it.filter(Char::isDigit) },
                    label = { Text("Interval value") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomTimeUnit.entries.forEach { u ->
                        val selected = u == unit
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AmberGold else ToolbarSurface)
                                .clickable { unit = u }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = u.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) CanvasVoid else TextPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = valueStr.toIntOrNull()?.coerceIn(1, 365) ?: 1
                    onCreate(amount, unit)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = CanvasVoid)
            ) {
                Text("Add & Apply", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAlertSheet(
    symbol: String,
    initialPrice: Double,
    currentPrice: Double?,
    precision: Int,
    linkedDrawing: ChartDrawing?,
    onCreate: (Double, AlertCondition, ChartDrawing?) -> Unit,
    onDismiss: () -> Unit
) {
    var priceText by remember(initialPrice) {
        mutableStateOf(String.format(Locale.US, "%.${precision.coerceIn(1, 6)}f", initialPrice))
    }
    val defaultCondition = remember(linkedDrawing, initialPrice, currentPrice) {
        when (linkedDrawing?.type) {
            DrawingType.TRENDLINE -> AlertCondition.TRENDLINE_TOUCH
            DrawingType.HORIZONTAL_LINE -> AlertCondition.HORIZ_BREAKOUT
            else -> if (currentPrice != null && initialPrice >= currentPrice) {
                AlertCondition.CROSSES_ABOVE
            } else {
                AlertCondition.CROSSES_BELOW
            }
        }
    }
    var selectedCondition by remember { mutableStateOf(defaultCondition) }

    val availableConditions = remember(linkedDrawing) {
        when (linkedDrawing?.type) {
            DrawingType.TRENDLINE -> listOf(AlertCondition.TRENDLINE_TOUCH)
            DrawingType.HORIZONTAL_LINE -> listOf(
                AlertCondition.HORIZ_BREAKOUT,
                AlertCondition.CROSSES_ABOVE,
                AlertCondition.CROSSES_BELOW,
                AlertCondition.CANDLE_CLOSE_ABOVE,
                AlertCondition.CANDLE_CLOSE_BELOW,
                AlertCondition.REACHES
            )
            else -> listOf(
                AlertCondition.REACHES,
                AlertCondition.CROSSES_ABOVE,
                AlertCondition.CROSSES_BELOW,
                AlertCondition.CANDLE_CLOSE_ABOVE,
                AlertCondition.CANDLE_CLOSE_BELOW,
                AlertCondition.HORIZ_BREAKOUT
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Create Alert • $symbol",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (linkedDrawing?.type != DrawingType.TRENDLINE) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Trigger Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "Tracks dynamic interpolated price along the selected trendline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertAmber
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                availableConditions.forEach { cond ->
                    val selected = cond == selectedCondition
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberGold.copy(alpha = 0.16f) else ToolbarSurface)
                            .border(
                                0.75.dp,
                                if (selected) AmberGold else BorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedCondition = cond }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cond.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) AmberGold else TextPrimary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val parsedPrice = priceText.toDoubleOrNull() ?: initialPrice
                    onCreate(parsedPrice, selectedCondition, linkedDrawing)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = CanvasVoid),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("confirm_create_alert_button")
            ) {
                Text("Set Alert", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsManagerSheet(
    state: ChartUiState,
    onCreateNewAlertClick: () -> Unit,
    onToggleAlertActive: (String, Boolean) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onClearHistory: () -> Unit,
    onTestAlertSound: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.US) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Price Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onTestAlertSound,
                    modifier = Modifier.testTag("alerts_test_beep_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Test Loud Alert Beep",
                        tint = AmberGold
                    )
                }
                Button(
                    onClick = {
                        onDismiss()
                        onCreateNewAlertClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = CanvasVoid)
                ) {
                    Text("+ New Alert", fontWeight = FontWeight.Bold)
                }
            }

            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = AmberGold
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Active (${state.allActiveAlerts.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History (${state.alertHistory.size})") })
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (tab == 0) {
                if (state.allActiveAlerts.isEmpty()) {
                    Text(
                        text = "No active alerts. Tap anywhere on the chart price axis and press + to set one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.allActiveAlerts, key = { it.id }) { alert ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${alert.symbol} (${alert.priceSource.shortLabel})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${alert.condition.label} • ${ChartNumberFormatter.formatPrice(alert.targetPrice, 4)}",
                                        style = TabularMonoStyle.copy(fontSize = 12.sp),
                                        color = AlertAmber
                                    )
                                }
                                Switch(
                                    checked = alert.isActive,
                                    onCheckedChange = { onToggleAlertActive(alert.id, it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = CanvasVoid, checkedTrackColor = AmberGold)
                                )
                                IconButton(onClick = { onDeleteAlert(alert.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete alert", tint = BearishRed)
                                }
                            }
                            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.5f))
                        }
                    }
                }
            } else {
                if (state.alertHistory.isEmpty()) {
                    Text(
                        text = "No triggered alerts yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClearHistory) {
                            Text("Clear History", color = BearishRed)
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.alertHistory, key = { it.id }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = entry.symbol,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = BullishGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = dateFmt.format(Date(entry.triggeredAt)),
                                        style = TabularMonoStyle.copy(fontSize = 10.sp),
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDrawingSheet(
    drawing: ChartDrawing,
    onUpdateStyle: (Long, Float, LineStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = listOf(0xFFF0B90BL, 0xFF38BDF8L, 0xFF0ECB81L, 0xFFF6465DL, 0xFFEAECEFL, 0xFFA855F7L)
    var colorHex by remember(drawing) { mutableStateOf(drawing.colorHex) }
    var strokeWidth by remember(drawing) { mutableStateOf(drawing.strokeWidth) }
    var lineStyle by remember(drawing) { mutableStateOf(drawing.lineStyle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Edit Drawing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text("Color", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                palette.forEach { hex ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(
                                width = if (colorHex == hex) 2.5.dp else 0.5.dp,
                                color = if (colorHex == hex) Color.White else BorderSubtle,
                                shape = CircleShape
                            )
                            .clickable {
                                colorHex = hex
                                onUpdateStyle(colorHex, strokeWidth, lineStyle)
                            }
                    )
                }
            }

            Text("Line Style", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineStyle.entries.forEach { st ->
                    val selected = st == lineStyle
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberGold else ToolbarSurface)
                            .clickable {
                                lineStyle = st
                                onUpdateStyle(colorHex, strokeWidth, lineStyle)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = st.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) CanvasVoid else TextPrimary
                        )
                    }
                }
            }

            Text("Thickness", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.5f, 2.5f, 4f).forEach { w ->
                    val selected = strokeWidth == w
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberGold else ToolbarSurface)
                            .clickable {
                                strokeWidth = w
                                onUpdateStyle(colorHex, strokeWidth, lineStyle)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${w}px",
                            style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = if (selected) CanvasVoid else TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartSettingsSheet(
    preferences: ChartPreferences,
    onUpdatePreferences: ((ChartPreferences) -> ChartPreferences) -> Unit,
    onTestAlertSound: () -> Unit = {},
    updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val bullChoices = listOf(0xFF0ECB81L, 0xFF22C55EL, 0xFF38BDF8L)
    val bearChoices = listOf(0xFFF6465DL, 0xFFEF4444L, 0xFFF59E0BL)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ElevatedSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Chart & Alert Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bullish Candle Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bullChoices.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    if (preferences.bullColorHex == hex) 2.5.dp else 0.dp,
                                    Color.White,
                                    CircleShape
                                )
                                .clickable { onUpdatePreferences { it.copy(bullColorHex = hex) } }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bearish Candle Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bearChoices.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    if (preferences.bearColorHex == hex) 2.5.dp else 0.dp,
                                    Color.White,
                                    CircleShape
                                )
                                .clickable { onUpdatePreferences { it.copy(bearColorHex = hex) } }
                        )
                    }
                }
            }

            SettingToggleRow("Show Grid Lines", preferences.showGrid) { v ->
                onUpdatePreferences { it.copy(showGrid = v) }
            }
            SettingToggleRow("Bar Countdown Timer", preferences.showCountdown) { v ->
                onUpdatePreferences { it.copy(showCountdown = v) }
            }
            SettingToggleRow("Auto-Fit Price Scale Default", preferences.autoFitDefault) { v ->
                onUpdatePreferences { it.copy(autoFitDefault = v) }
            }
            SettingToggleRow("Background Live Alert Service", preferences.backgroundMonitorEnabled) { v ->
                onUpdatePreferences { it.copy(backgroundMonitorEnabled = v) }
            }
            SettingToggleRow("Loud Beep Alert Sound", preferences.loudSoundEnabled) { v ->
                onUpdatePreferences { it.copy(loudSoundEnabled = v) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Preview Loud Beep Sound",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onTestAlertSound,
                    modifier = Modifier.testTag("test_alert_sound_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Test Loud Beep",
                        tint = AmberGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Beep", color = AmberGold, fontWeight = FontWeight.Bold)
                }
            }
            SettingToggleRow("Alert Vibration", preferences.vibrationEnabled) { v ->
                onUpdatePreferences { it.copy(vibrationEnabled = v) }
            }

            HorizontalDivider(color = BorderSubtle, thickness = 0.75.dp)

            // App Version & GitHub Auto-Update Checker (surajali1026/binance3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Binance Pro v${AppVersionConfig.CURRENT_VERSION}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    val statusText = when (updateCheckState) {
                        is UpdateCheckState.Checking -> "Checking GitHub releases..."
                        is UpdateCheckState.UpToDate -> "Up to date (${AppVersionConfig.GITHUB_OWNER}/${AppVersionConfig.GITHUB_REPO})"
                        is UpdateCheckState.UpdateAvailable -> "New v${updateCheckState.release.version} available"
                        is UpdateCheckState.Error -> updateCheckState.message
                        UpdateCheckState.Idle -> "GitHub: ${AppVersionConfig.GITHUB_OWNER}/${AppVersionConfig.GITHUB_REPO}"
                    }
                    val statusColor = when (updateCheckState) {
                        is UpdateCheckState.UpdateAvailable -> BullishGreen
                        is UpdateCheckState.Error -> BearishRed
                        else -> TextMuted
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontSize = 11.sp
                    )
                }
                TextButton(
                    onClick = onCheckForUpdates,
                    enabled = updateCheckState !is UpdateCheckState.Checking,
                    modifier = Modifier.testTag("check_updates_button")
                ) {
                    Text(
                        text = if (updateCheckState is UpdateCheckState.Checking) "Checking..." else "Check for Updates",
                        color = AmberGold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = CanvasVoid, checkedTrackColor = AmberGold)
        )
    }
}
