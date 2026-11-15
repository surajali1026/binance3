package com.example.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.remote.MarketEndpointMode
import com.example.data.remote.SocketConnectionState
import com.example.domain.model.WatchlistFilter
import com.example.domain.model.WatchlistItem
import com.example.domain.model.WatchlistSortField
import com.example.ui.chart.ChartNumberFormatter
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BearishContainer
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BullishContainer
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.CyanInfo
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.ToolbarSurface

@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    selectedSymbol: String? = null,
    onSelectSymbol: (String) -> Unit,
    onSelectSymbolWithContext: ((String, List<String>, String) -> Unit)? = null,
    onOpenAlerts: () -> Unit,
    applyBottomInset: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasVoid)
            .windowInsetsPadding(WindowInsets.statusBars)
            .let { if (applyBottomInset) it.windowInsetsPadding(WindowInsets.navigationBars) else it }
    ) {
        WatchlistTopBar(
            connectionState = state.connectionState,
            endpointMode = state.endpointMode,
            activeAlertCount = state.activeAlertCount,
            onRefresh = viewModel::refresh,
            onOpenAlerts = onOpenAlerts
        )

        SearchBarRow(
            query = state.searchQuery,
            onQueryChange = viewModel::onSearchQueryChange
        )

        FilterChipsRow(
            activeFilter = state.filter,
            onSelectFilter = viewModel::onFilterChange
        )

        SortHeaderRow(
            activeFilter = state.filter,
            sortField = state.sortField,
            sortAscending = state.sortAscending,
            onSortClick = viewModel::onSortClick
        )

        HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AmberGold, strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading USD-M Futures markets...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.errorMessage ?: "Connection unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BearishRed,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = viewModel::refresh,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = CanvasVoid),
                            modifier = Modifier.testTag("watchlist_retry_button")
                        ) {
                            Text("Retry", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            state.filter == WatchlistFilter.FAVORITES ->
                                "No starred pairs yet. Tap the star icon next to any contract."
                            state.filter == WatchlistFilter.NEW_LISTINGS && state.endpointMode == MarketEndpointMode.VISION_MIRROR ->
                                "Spot mirror fallback does not include listing timestamps. Binance or Bybit Futures API is required for New & Upcoming listings."
                            state.filter == WatchlistFilter.NEW_LISTINGS ->
                                "No recent or upcoming Binance/Bybit listings found."
                            else ->
                                "No USD-M Futures pairs match \"${state.searchQuery}\""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("watchlist_list"),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(
                        items = state.items,
                        key = { it.contract.symbol }
                    ) { item ->
                        WatchlistRow(
                            item = item,
                            isSelected = item.contract.symbol.equals(selectedSymbol, ignoreCase = true),
                            showListingTime = state.filter == WatchlistFilter.NEW_LISTINGS,
                            onClick = {
                                if (onSelectSymbolWithContext != null) {
                                    onSelectSymbolWithContext(
                                        item.contract.symbol,
                                        state.items.map { it.contract.symbol },
                                        state.filter.label
                                    )
                                } else {
                                    onSelectSymbol(item.contract.symbol)
                                }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(item.contract.symbol) }
                        )
                        HorizontalDivider(color = BorderSubtle.copy(alpha = 0.4f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistTopBar(
    connectionState: SocketConnectionState,
    endpointMode: MarketEndpointMode,
    activeAlertCount: Int,
    onRefresh: () -> Unit,
    onOpenAlerts: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "USD-M Futures",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                val statusColor = when (connectionState) {
                    SocketConnectionState.CONNECTED -> BullishGreen
                    SocketConnectionState.CONNECTING,
                    SocketConnectionState.RECONNECTING -> AmberGold
                    SocketConnectionState.DISCONNECTED -> BearishRed
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
            Text(
                text = when (connectionState) {
                    SocketConnectionState.CONNECTED -> endpointMode.label
                    SocketConnectionState.CONNECTING -> "Connecting (${endpointMode.label})..."
                    SocketConnectionState.RECONNECTING -> "Reconnecting..."
                    SocketConnectionState.DISCONNECTED -> "Offline"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }

        IconButton(
            onClick = onOpenAlerts,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("watchlist_alerts_button")
        ) {
            Box {
                Icon(
                    imageVector = if (activeAlertCount > 0) Icons.Filled.NotificationsActive else Icons.Outlined.Notifications,
                    contentDescription = "Manage Alerts",
                    tint = if (activeAlertCount > 0) AmberGold else TextMuted
                )
                if (activeAlertCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(AmberGold),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = activeAlertCount.coerceAtMost(99).toString(),
                            style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = CanvasVoid
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("watchlist_refresh_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh Markets",
                tint = TextMuted
            )
        }
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(0.75.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search symbol (e.g. BTCUSDT, ETH, SOL)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(AmberGold),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watchlist_search_input")
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear Search",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    activeFilter: WatchlistFilter,
    onSelectFilter: (WatchlistFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WatchlistFilter.entries.forEach { filter ->
            val selected = filter == activeFilter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) AmberGold.copy(alpha = 0.16f) else ToolbarSurface)
                    .border(
                        width = 0.75.dp,
                        color = if (selected) AmberGold else BorderSubtle,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelectFilter(filter) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag("filter_${filter.name.lowercase()}")
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) AmberGold else TextMuted
                )
            }
        }
    }
}

@Composable
private fun SortHeaderRow(
    activeFilter: WatchlistFilter,
    sortField: WatchlistSortField,
    sortAscending: Boolean,
    onSortClick: (WatchlistSortField) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1.35f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activeFilter == WatchlistFilter.NEW_LISTINGS) {
                SortButton(
                    label = "Listed",
                    active = sortField == WatchlistSortField.LISTING_TIME,
                    ascending = sortAscending,
                    onClick = { onSortClick(WatchlistSortField.LISTING_TIME) },
                    alignment = Alignment.CenterStart
                )
            } else {
                SortButton(
                    label = "Rank",
                    active = sortField == WatchlistSortField.MARKET_CAP,
                    ascending = sortAscending,
                    onClick = { onSortClick(WatchlistSortField.MARKET_CAP) },
                    alignment = Alignment.CenterStart
                )
            }
            Text(
                text = "/",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            SortButton(
                label = "Vol",
                active = sortField == WatchlistSortField.VOLUME,
                ascending = sortAscending,
                onClick = { onSortClick(WatchlistSortField.VOLUME) },
                alignment = Alignment.CenterStart
            )
        }
        SortButton(
            label = "Last Price",
            active = sortField == WatchlistSortField.PRICE,
            ascending = sortAscending,
            onClick = { onSortClick(WatchlistSortField.PRICE) },
            modifier = Modifier.weight(1f),
            alignment = Alignment.CenterEnd
        )
        SortButton(
            label = "24h %",
            active = sortField == WatchlistSortField.CHANGE,
            ascending = sortAscending,
            onClick = { onSortClick(WatchlistSortField.CHANGE) },
            modifier = Modifier.weight(0.85f),
            alignment = Alignment.CenterEnd
        )
    }
}

@Composable
private fun SortButton(
    label: String,
    active: Boolean,
    ascending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) AmberGold else TextMuted,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
            )
            if (active) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = AmberGold,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun WatchlistRow(
    item: WatchlistItem,
    isSelected: Boolean,
    showListingTime: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val ticker = item.ticker
    val nowMs = System.currentTimeMillis()
    val isUpcoming = item.contract.isUpcomingListing(nowMs)
    val isNewRecent = !isUpcoming && item.contract.isRecentlyListed(nowMs, 14)
    val hasActivePrice = ticker != null && ticker.lastPrice > 0.0
    val changePct = ticker?.priceChangePercent24h ?: 0.0
    val isUp = changePct >= 0.0
    val priceText = if (hasActivePrice) {
        ChartNumberFormatter.formatPrice(ticker!!.lastPrice, item.contract.pricePrecision)
    } else if (isUpcoming) {
        "Pre-Launch"
    } else {
        "--"
    }
    val rank = item.displayRank ?: ticker?.marketCapRank
    val rankPrefix = rank?.let { "#$it • " } ?: ""
    val volText = ticker?.takeIf { it.quoteVolume24h > 0.0 }?.let {
        ChartNumberFormatter.formatCompactVolume(it.quoteVolume24h)
    } ?: "--"
    val listingSubtext = if (showListingTime || isUpcoming || isNewRecent) {
        ChartNumberFormatter.formatListingSubtext(item.contract.onboardDate, item.contract.status, nowMs)
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) ToolbarSurface.copy(alpha = 0.65f) else CanvasVoid)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
            .testTag("watchlist_item_${item.contract.symbol}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .size(40.dp)
                .testTag("favorite_toggle_${item.contract.symbol}")
        ) {
            Icon(
                imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "Favorite ${item.contract.symbol}",
                tint = if (item.isFavorite) AmberGold else TextMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1.35f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.contract.baseAsset.ifBlank { item.contract.symbol },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "/${item.contract.quoteAsset}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1
                )
                if (item.contract.contractType == "PERPETUAL") {
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(ToolbarSurface)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "PERP",
                            style = TabularMonoStyle.copy(fontSize = 8.sp),
                            color = TextMuted
                        )
                    }
                }
                if (isUpcoming) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(AmberGold.copy(alpha = 0.18f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "SOON",
                            style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = AmberGold
                        )
                    }
                } else if (isNewRecent) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(CyanInfo.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "NEW",
                            style = TabularMonoStyle.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = CyanInfo
                        )
                    }
                }
            }
            Text(
                text = "${rankPrefix}Vol: $volText",
                style = TabularMonoStyle.copy(fontSize = 11.sp),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (listingSubtext != null) {
                Text(
                    text = listingSubtext,
                    style = TabularMonoStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    color = if (isUpcoming) AmberGold else CyanInfo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = priceText,
            style = TabularMonoStyle.copy(
                fontSize = if (!hasActivePrice && isUpcoming) 11.sp else 14.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (!hasActivePrice && isUpcoming) AmberGold else TextPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .weight(0.85f)
                .padding(start = 8.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            !hasActivePrice && isUpcoming -> AmberGold.copy(alpha = 0.14f)
                            isUp -> BullishContainer
                            else -> BearishContainer
                        }
                    )
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!hasActivePrice && isUpcoming) "SOON" else ChartNumberFormatter.formatPercent(changePct),
                    style = TabularMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = when {
                        !hasActivePrice && isUpcoming -> AmberGold
                        isUp -> BullishGreen
                        else -> BearishRed
                    },
                    maxLines = 1
                )
            }
        }
    }
}
