package com.example.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.remote.MarketEndpointMode
import com.example.data.remote.SocketConnectionState
import com.example.data.repository.ChartStateRepository
import com.example.data.repository.MarketRepository
import com.example.domain.model.WatchlistFilter
import com.example.domain.model.WatchlistItem
import com.example.domain.model.WatchlistSortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val filter: WatchlistFilter = WatchlistFilter.ALL,
    val sortField: WatchlistSortField = WatchlistSortField.VOLUME,
    val sortAscending: Boolean = false,
    val items: List<WatchlistItem> = emptyList(),
    val connectionState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val endpointMode: MarketEndpointMode = MarketEndpointMode.FUTURES_PROD,
    val activeAlertCount: Int = 0
)

class WatchlistViewModel(
    private val marketRepository: MarketRepository,
    private val chartStateRepository: ChartStateRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(WatchlistFilter.ALL)
    private val sortField = MutableStateFlow(WatchlistSortField.VOLUME)
    private val sortAscending = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)

    private var unsubscribeSocket: (() -> Unit)? = null

    private data class QueryParams(
        val query: String,
        val filter: WatchlistFilter,
        val sortField: WatchlistSortField,
        val sortAsc: Boolean
    )

    private val queryParamsFlow = combine(
        searchQuery,
        filter,
        sortField,
        sortAscending
    ) { q, f, sf, asc -> QueryParams(q, f, sf, asc) }

    val uiState: StateFlow<WatchlistUiState> = combine(
        marketRepository.contracts,
        marketRepository.tickers,
        chartStateRepository.favoriteSymbols,
        queryParamsFlow,
        combine(
            isLoading,
            errorMessage,
            marketRepository.connectionState,
            marketRepository.endpointMode,
            chartStateRepository.activeAlerts
        ) { loading, err, conn, mode, alerts -> listOf(loading, err, conn, mode, alerts.size) }
    ) { contracts, tickers, favorites, params, statusList ->
        val loading = statusList[0] as Boolean
        val err = statusList[1] as String?
        val conn = statusList[2] as SocketConnectionState
        val mode = statusList[3] as MarketEndpointMode
        val alertCount = statusList[4] as Int

        val allMapped = contracts.map { contract ->
            val ticker = tickers[contract.symbol]
            WatchlistItem(
                contract = contract,
                ticker = ticker,
                isFavorite = favorites.contains(contract.symbol),
                displayRank = ticker?.marketCapRank
            )
        }

        val maxExplicitRank = allMapped.maxOfOrNull { it.displayRank ?: 0 } ?: 0
        val unrankedSorted = allMapped
            .filter { it.displayRank == null }
            .sortedByDescending { it.ticker?.quoteVolume24h ?: 0.0 }
        val fallbackRankMap = HashMap<String, Int>(unrankedSorted.size)
        var nextRank = maxExplicitRank + 1
        for (item in unrankedSorted) {
            fallbackRankMap[item.contract.symbol] = nextRank++
        }
        val rankedAll = allMapped.map { item ->
            val rank = item.displayRank ?: fallbackRankMap[item.contract.symbol]
            if (rank == item.displayRank) item else item.copy(displayRank = rank)
        }

        val top100Symbols: Set<String>? = if (params.filter == WatchlistFilter.TOP_100) {
            rankedAll
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

        val q = params.query.trim()
        val combined = rankedAll.asSequence()
            .filter { item ->
                if (top100Symbols != null && !top100Symbols.contains(item.contract.symbol)) {
                    false
                } else if (q.isEmpty()) {
                    true
                } else {
                    item.contract.symbol.contains(q, ignoreCase = true) ||
                        item.contract.baseAsset.contains(q, ignoreCase = true)
                }
            }
            .filter { item ->
                when (params.filter) {
                    WatchlistFilter.ALL, WatchlistFilter.TOP_100 -> true
                    WatchlistFilter.FAVORITES -> item.isFavorite
                    WatchlistFilter.GAINERS -> (item.ticker?.priceChangePercent24h ?: 0.0) > 0.0
                    WatchlistFilter.LOSERS -> (item.ticker?.priceChangePercent24h ?: 0.0) < 0.0
                }
            }

        val comparator = when (params.sortField) {
            WatchlistSortField.MARKET_CAP -> compareBy<WatchlistItem> { it.displayRank ?: Int.MAX_VALUE }
                .thenByDescending { it.ticker?.marketCap ?: 0.0 }
                .thenByDescending { it.ticker?.quoteVolume24h ?: 0.0 }
            WatchlistSortField.VOLUME -> compareBy<WatchlistItem> { it.ticker?.quoteVolume24h ?: 0.0 }
            WatchlistSortField.PRICE -> compareBy<WatchlistItem> { it.ticker?.lastPrice ?: 0.0 }
            WatchlistSortField.CHANGE -> compareBy<WatchlistItem> { it.ticker?.priceChangePercent24h ?: 0.0 }
        }
        val sorted = if (params.sortAsc) {
            combined.sortedWith(comparator).toList()
        } else {
            combined.sortedWith(comparator.reversed()).toList()
        }

        WatchlistUiState(
            isLoading = loading && contracts.isEmpty(),
            errorMessage = err,
            searchQuery = params.query,
            filter = params.filter,
            sortField = params.sortField,
            sortAscending = params.sortAsc,
            items = sorted,
            connectionState = conn,
            endpointMode = mode,
            activeAlertCount = alertCount
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WatchlistUiState()
        )

    init {
        refresh()
        unsubscribeSocket = marketRepository.subscribeMarketTickers()
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                if (marketRepository.endpointMode.value == MarketEndpointMode.BYBIT_LINEAR) {
                    marketRepository.refreshMarketOverview()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            val result = marketRepository.refreshMarketOverview()
            isLoading.value = false
            result.onFailure { throwable ->
                errorMessage.value = throwable.localizedMessage ?: "Failed to load Binance Futures markets"
            }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        searchQuery.value = newQuery
    }

    fun onFilterChange(newFilter: WatchlistFilter) {
        val previous = filter.value
        filter.value = newFilter
        if (newFilter == WatchlistFilter.TOP_100) {
            sortField.value = WatchlistSortField.MARKET_CAP
            sortAscending.value = true
        } else if (previous == WatchlistFilter.TOP_100 && sortField.value == WatchlistSortField.MARKET_CAP) {
            sortField.value = WatchlistSortField.VOLUME
            sortAscending.value = false
        }
    }

    fun onSortClick(field: WatchlistSortField) {
        if (sortField.value == field) {
            sortAscending.value = !sortAscending.value
        } else {
            sortField.value = field
            sortAscending.value = field == WatchlistSortField.MARKET_CAP
        }
    }

    fun toggleFavorite(symbol: String) {
        viewModelScope.launch {
            chartStateRepository.toggleFavoriteSymbol(symbol)
        }
    }

    override fun onCleared() {
        unsubscribeSocket?.invoke()
        unsubscribeSocket = null
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WatchlistViewModel(
                        container.marketRepository,
                        container.chartStateRepository
                    ) as T
                }
            }
    }
}
