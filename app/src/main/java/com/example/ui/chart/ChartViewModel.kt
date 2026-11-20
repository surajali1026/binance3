package com.example.ui.chart

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.alerts.AlertEvaluator
import com.example.alerts.AlertNotifier
import com.example.data.remote.SocketConnectionState
import com.example.data.repository.ChartStateRepository
import com.example.data.repository.MarketRepository
import com.example.domain.model.AlertCondition
import com.example.domain.model.AlertHistoryEntry
import com.example.domain.model.BarReplayState
import com.example.domain.model.Candle
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPoint
import com.example.domain.model.ChartPreferences
import com.example.domain.model.CustomTimeUnit
import com.example.domain.model.DrawingTool
import com.example.domain.model.DrawingType
import com.example.domain.model.FuturesContract
import com.example.domain.model.LineStyle
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceRangeMeasurement
import com.example.domain.model.PriceSource
import com.example.domain.model.TickerSnapshot
import com.example.domain.model.Timeframe
import com.example.domain.indicator.TbtSmcEngine
import com.example.domain.indicator.TbtSmcOutput
import com.example.domain.model.WatchlistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

sealed interface ChartWebUpdate {
    data class FullHistory(
        val seriesKey: String,
        val candles: List<Candle>,
        val fit: Boolean
    ) : ChartWebUpdate

    data class LiveCandleDelta(
        val seriesKey: String,
        val candles: List<Candle>,
        val totalCount: Int
    ) : ChartWebUpdate

    data class ClearSeries(
        val seriesKey: String
    ) : ChartWebUpdate

    data object ResetScroll : ChartWebUpdate

    data object ResetPriceScale : ChartWebUpdate
}

data class CrosshairState(
    val isActive: Boolean = false,
    val timeMillis: Long = 0L,
    val price: Double = 0.0,
    val candle: Candle? = null
)

data class ChartUiState(
    val symbol: String = "BTCUSDT",
    val contract: FuturesContract? = null,
    val ticker: TickerSnapshot? = null,
    val priceSource: PriceSource = PriceSource.LAST,
    val activeTimeframe: Timeframe = Timeframe.DEFAULTS[4],
    val allTimeframes: List<Timeframe> = Timeframe.DEFAULTS,
    val favoriteTimeframes: List<Timeframe> = Timeframe.DEFAULTS.filter { it.isFavorite },
    val candles: List<Candle> = emptyList(),
    val candlesSeriesKey: String = "",
    val isLoadingHistory: Boolean = true,
    val isPaginatingBack: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val connectionState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val countdownMillis: Long = 0L,
    val viewport: ChartViewportState = ChartViewportState(),
    val activeTool: DrawingTool = DrawingTool.NONE,
    val drawingToolbarExpanded: Boolean = false,
    val pendingPoints: List<ChartPoint> = emptyList(),
    val drawings: List<ChartDrawing> = emptyList(),
    val selectedDrawingId: String? = null,
    val alertsForSymbol: List<PriceAlert> = emptyList(),
    val allActiveAlerts: List<PriceAlert> = emptyList(),
    val alertHistory: List<AlertHistoryEntry> = emptyList(),
    val selectedAlertId: String? = null,
    val crosshair: CrosshairState = CrosshairState(),
    val axisQuickAlertPrice: Double? = null,
    val rangeMeasurement: PriceRangeMeasurement? = null,
    val replayState: BarReplayState = BarReplayState(),
    val preferences: ChartPreferences = ChartPreferences(),
    val tbtSmc: TbtSmcOutput = TbtSmcOutput.EMPTY,
    val isFullScreen: Boolean = false,
    val switcherItems: List<WatchlistItem> = emptyList(),
    val navigationSymbols: List<String> = emptyList(),
    val navigationContextLabel: String = "All"
) {
    val canCycleSymbols: Boolean
        get() = navigationSymbols.size > 1

    val displayedCandles: List<Candle>
        get() = if (replayState.isActive && replayState.cutoffIndex in candles.indices) {
            candles.subList(0, replayState.cutoffIndex + 1)
        } else {
            candles
        }

    val currentPrice: Double?
        get() = displayedCandles.lastOrNull()?.close ?: when (priceSource) {
            PriceSource.LAST -> ticker?.lastPrice
            PriceSource.MARK -> ticker?.markPrice
            PriceSource.INDEX -> ticker?.indexPrice
        }?.takeIf { it.isFinite() && it > 0.0 }
}

class ChartViewModel(
    private val appContext: Context,
    private val marketRepository: MarketRepository,
    private val chartStateRepository: ChartStateRepository
) : ViewModel() {

    private data class CandleSeriesData(
        val seriesKey: String = "",
        val candles: List<Candle> = emptyList()
    )

    private val _symbol = MutableStateFlow("BTCUSDT")
    val symbolState: StateFlow<String> = _symbol.asStateFlow()
    private val _priceSource = MutableStateFlow(PriceSource.LAST)
    private val _timeframeId = MutableStateFlow("15m")
    private val _candleSeries = MutableStateFlow(CandleSeriesData())
    private val _isLoadingHistory = MutableStateFlow(true)
    private val _isPaginatingBack = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _noticeMessage = MutableStateFlow<String?>(null)
    private val _countdownMillis = MutableStateFlow(0L)
    private val _viewport = MutableStateFlow(ChartViewportState())
    val viewportState: StateFlow<ChartViewportState> = _viewport.asStateFlow()
    private val _activeTool = MutableStateFlow(DrawingTool.NONE)
    private val _drawingToolbarExpanded = MutableStateFlow(false)
    private val _pendingPoints = MutableStateFlow<List<ChartPoint>>(emptyList())
    private val _selectedDrawingId = MutableStateFlow<String?>(null)
    private val _selectedAlertId = MutableStateFlow<String?>(null)
    private val _crosshair = MutableStateFlow(CrosshairState())
    private val _axisQuickAlertPrice = MutableStateFlow<Double?>(null)
    private val _rangeMeasurement = MutableStateFlow<PriceRangeMeasurement?>(null)
    private val _replayState = MutableStateFlow(BarReplayState())
    private val _isFullScreen = MutableStateFlow(false)
    private val _navigationContextSymbols = MutableStateFlow<List<String>>(emptyList())
    val navigationContextSymbolsState: StateFlow<List<String>> = _navigationContextSymbols.asStateFlow()
    private val _navigationContextLabel = MutableStateFlow("All")

    @Volatile
    private var cachedTimeframes: List<Timeframe> = Timeframe.DEFAULTS
    private var klineStreamJob: Job? = null
    private var replayJob: Job? = null
    private var countdownJob: Job? = null
    private var drawingsJob: Job? = null
    private var alertsSymbolJob: Job? = null
    private var persistJob: Job? = null
    private var unsubscribeTickers: (() -> Unit)? = null
    private var lastPlotWidthPx = 900f
    private var hasReachedEarliestHistory = false
    private var shouldAutoFitOnNextLoad = true

    private val _drawingsForSymbol = MutableStateFlow<List<ChartDrawing>>(emptyList())
    private val _alertsForSymbol = MutableStateFlow<List<PriceAlert>>(emptyList())
    private val _webChartUpdates = MutableSharedFlow<ChartWebUpdate>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val webChartUpdates: SharedFlow<ChartWebUpdate> = _webChartUpdates.asSharedFlow()

    fun buildCurrentFullHistoryUpdate(): ChartWebUpdate.FullHistory? {
        val series = _candleSeries.value
        val expectedKey = currentSeriesKey()
        if (series.seriesKey != expectedKey || series.candles.isEmpty()) return null
        val list = if (_replayState.value.isActive && _replayState.value.cutoffIndex in series.candles.indices) {
            series.candles.subList(0, _replayState.value.cutoffIndex + 1)
        } else {
            series.candles
        }
        if (list.isEmpty()) return null
        return ChartWebUpdate.FullHistory(
            seriesKey = expectedKey,
            candles = list,
            fit = _viewport.value.isAutoFit
        )
    }

    private fun currentSeriesKey(
        symbol: String = _symbol.value,
        timeframeId: String = _timeframeId.value,
        priceSource: PriceSource = _priceSource.value
    ): String = "${symbol}_${timeframeId}_${priceSource.name}"

    private data class CoreMarketSlice(
        val symbol: String,
        val source: PriceSource,
        val tfId: String,
        val allTfs: List<Timeframe>,
        val candleSeries: CandleSeriesData,
        val contracts: List<FuturesContract>,
        val tickers: Map<String, TickerSnapshot>,
        val favorites: Set<String>
    )

    private val coreMarketFlow = combine(
        _symbol,
        _priceSource,
        _timeframeId,
        chartStateRepository.timeframes,
        _candleSeries,
        marketRepository.contracts,
        marketRepository.tickers,
        chartStateRepository.favoriteSymbols
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        CoreMarketSlice(
            symbol = arr[0] as String,
            source = arr[1] as PriceSource,
            tfId = arr[2] as String,
            allTfs = arr[3] as List<Timeframe>,
            candleSeries = arr[4] as CandleSeriesData,
            contracts = arr[5] as List<FuturesContract>,
            tickers = arr[6] as Map<String, TickerSnapshot>,
            favorites = arr[7] as Set<String>
        )
    }

    private data class InteractionSlice(
        val viewport: ChartViewportState,
        val activeTool: DrawingTool,
        val toolbarExpanded: Boolean,
        val pendingPoints: List<ChartPoint>,
        val drawings: List<ChartDrawing>,
        val selectedDrawingId: String?,
        val alertsSymbol: List<PriceAlert>,
        val selectedAlertId: String?,
        val crosshair: CrosshairState,
        val quickAlertPrice: Double?,
        val rangeMeasure: PriceRangeMeasurement?,
        val replay: BarReplayState,
        val isFullScreen: Boolean
    )

    private val interactionFlow = combine(
        _viewport,
        _activeTool,
        _drawingToolbarExpanded,
        _pendingPoints,
        _drawingsForSymbol,
        _selectedDrawingId,
        _alertsForSymbol,
        _selectedAlertId,
        _crosshair,
        _axisQuickAlertPrice,
        _rangeMeasurement,
        _replayState,
        _isFullScreen
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        InteractionSlice(
            viewport = arr[0] as ChartViewportState,
            activeTool = arr[1] as DrawingTool,
            toolbarExpanded = arr[2] as Boolean,
            pendingPoints = arr[3] as List<ChartPoint>,
            drawings = arr[4] as List<ChartDrawing>,
            selectedDrawingId = arr[5] as String?,
            alertsSymbol = arr[6] as List<PriceAlert>,
            selectedAlertId = arr[7] as String?,
            crosshair = arr[8] as CrosshairState,
            quickAlertPrice = arr[9] as Double?,
            rangeMeasure = arr[10] as PriceRangeMeasurement?,
            replay = arr[11] as BarReplayState,
            isFullScreen = arr[12] as Boolean
        )
    }

    private val switcherItemsFlow: StateFlow<List<WatchlistItem>> = combine(
        marketRepository.contracts,
        marketRepository.tickers,
        chartStateRepository.favoriteSymbols
    ) { contracts, tickers, favorites ->
        val raw = contracts.map { c ->
            val t = tickers[c.symbol]
            WatchlistItem(c, t, favorites.contains(c.symbol), displayRank = t?.marketCapRank)
        }
        val maxRank = raw.maxOfOrNull { it.displayRank ?: 0 } ?: 0
        var nextRank = maxRank + 1
        val unrankedMap = HashMap<String, Int>()
        for (item in raw.filter { it.displayRank == null }.sortedByDescending { it.ticker?.quoteVolume24h ?: 0.0 }) {
            unrankedMap[item.contract.symbol] = nextRank++
        }
        raw.map { item ->
            val rank = item.displayRank ?: unrankedMap[item.contract.symbol]
            if (rank == item.displayRank) item else item.copy(displayRank = rank)
        }.sortedWith(
            compareByDescending<WatchlistItem> { it.isFavorite }
                .thenByDescending { it.ticker?.quoteVolume24h ?: 0.0 }
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private data class MessageAndNavContext(
        val error: String?,
        val notice: String?,
        val contextSymbols: List<String>,
        val contextLabel: String
    )

    private val tbtSmcFlow: Flow<TbtSmcOutput> = combine(
        _candleSeries,
        _replayState,
        chartStateRepository.preferences
    ) { series, replay, prefs ->
        if (!prefs.showTbtSmc || series.candles.isEmpty()) {
            TbtSmcOutput.EMPTY
        } else {
            val candles = series.candles
            val displayed = if (replay.isActive && replay.cutoffIndex in candles.indices) {
                candles.subList(0, replay.cutoffIndex + 1)
            } else {
                candles
            }
            TbtSmcEngine.calculate(displayed, prefs.toTbtSmcConfig())
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val uiState: StateFlow<ChartUiState> = combine(
        coreMarketFlow,
        interactionFlow,
        _isLoadingHistory,
        _isPaginatingBack,
        combine(
            _errorMessage,
            _noticeMessage,
            _navigationContextSymbols,
            _navigationContextLabel
        ) { err, notice, navSyms, navLabel -> MessageAndNavContext(err, notice, navSyms, navLabel) },
        marketRepository.connectionState,
        _countdownMillis,
        chartStateRepository.activeAlerts,
        chartStateRepository.alertHistory,
        chartStateRepository.preferences,
        switcherItemsFlow,
        tbtSmcFlow
    ) { arr ->
        val market = arr[0] as CoreMarketSlice
        val ui = arr[1] as InteractionSlice
        val loading = arr[2] as Boolean
        val paginating = arr[3] as Boolean
        val msgNav = arr[4] as MessageAndNavContext
        val error = msgNav.error
        val notice = msgNav.notice
        val conn = arr[5] as SocketConnectionState
        val countdown = arr[6] as Long
        @Suppress("UNCHECKED_CAST")
        val allActiveAlerts = arr[7] as List<PriceAlert>
        @Suppress("UNCHECKED_CAST")
        val alertHistory = arr[8] as List<AlertHistoryEntry>
        val prefs = arr[9] as ChartPreferences
        @Suppress("UNCHECKED_CAST")
        val switcherItems = arr[10] as List<WatchlistItem>
        val tbtSmc = arr[11] as TbtSmcOutput

        val activeTf = market.allTfs.firstOrNull { it.id == market.tfId }
            ?: market.allTfs.firstOrNull { it.id == "15m" }
            ?: Timeframe.DEFAULTS[4]
        val favTfs = market.allTfs.filter { it.isFavorite }
        val contract = market.contracts.firstOrNull { it.symbol.equals(market.symbol, ignoreCase = true) }
        val ticker = market.tickers[market.symbol]
        val navSymbols = msgNav.contextSymbols.takeIf { it.size > 1 }
            ?: switcherItems.map { it.contract.symbol }

        ChartUiState(
            symbol = market.symbol,
            contract = contract,
            ticker = ticker,
            priceSource = market.source,
            activeTimeframe = activeTf,
            allTimeframes = market.allTfs,
            favoriteTimeframes = favTfs.ifEmpty { market.allTfs.take(6) },
            candles = market.candleSeries.candles,
            candlesSeriesKey = market.candleSeries.seriesKey,
            isLoadingHistory = loading,
            isPaginatingBack = paginating,
            errorMessage = error,
            noticeMessage = notice,
            connectionState = conn,
            countdownMillis = countdown,
            viewport = ui.viewport,
            activeTool = ui.activeTool,
            drawingToolbarExpanded = ui.toolbarExpanded,
            pendingPoints = ui.pendingPoints,
            drawings = ui.drawings,
            selectedDrawingId = ui.selectedDrawingId,
            alertsForSymbol = ui.alertsSymbol,
            allActiveAlerts = allActiveAlerts,
            alertHistory = alertHistory,
            selectedAlertId = ui.selectedAlertId,
            crosshair = ui.crosshair,
            axisQuickAlertPrice = ui.quickAlertPrice,
            rangeMeasurement = ui.rangeMeasure,
            replayState = ui.replay,
            preferences = prefs,
            tbtSmc = tbtSmc,
            isFullScreen = ui.isFullScreen,
            switcherItems = switcherItems,
            navigationSymbols = navSymbols,
            navigationContextLabel = msgNav.contextLabel
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChartUiState()
    )

    init {
        unsubscribeTickers = marketRepository.subscribeMarketTickers()
        viewModelScope.launch {
            chartStateRepository.timeframes.collect { list ->
                if (list.isNotEmpty()) cachedTimeframes = list
            }
        }
        viewModelScope.launch {
            if (marketRepository.contracts.value.isEmpty()) {
                marketRepository.refreshMarketOverview()
            }
        }
        viewModelScope.launch {
            chartStateRepository.ensureSeeded()
            val prefs = chartStateRepository.getPreferencesOnce()
            _symbol.value = prefs.lastSymbol
            _timeframeId.value = prefs.lastTimeframeId
            _priceSource.value = prefs.lastPriceSource
            _viewport.update {
                it.copy(
                    barSpacingPx = (prefs.lastBarSpacingDp * 2.6f).coerceIn(8f, 90f),
                    rightOffsetBars = prefs.lastRightOffsetBars.coerceIn(-500f, 30f),
                    isAutoFit = prefs.autoFitDefault
                )
            }
            observeSymbolPersistedData(prefs.lastSymbol)
            reloadChartFeed()
            startCountdownTicker()
        }
    }

    fun openSymbol(
        symbol: String,
        contextSymbols: List<String>? = null,
        contextLabel: String? = null
    ) {
        val clean = symbol.uppercase()
        if (!contextSymbols.isNullOrEmpty()) {
            _navigationContextSymbols.value = contextSymbols.map { it.uppercase() }.distinct()
        }
        if (!contextLabel.isNullOrBlank()) {
            _navigationContextLabel.value = contextLabel
        }
        if (_symbol.value == clean && _candleSeries.value.candles.isNotEmpty()) return
        _symbol.value = clean
        _selectedDrawingId.value = null
        _selectedAlertId.value = null
        _axisQuickAlertPrice.value = null
        _pendingPoints.value = emptyList()
        _activeTool.value = DrawingTool.NONE
        _rangeMeasurement.value = null
        shouldAutoFitOnNextLoad = true
        _viewport.update { it.copy(isAutoFit = uiState.value.preferences.autoFitDefault) }
        exitBarReplay(emitFullHistory = false)
        persistChartState()
        observeSymbolPersistedData(clean)
        reloadChartFeed()
    }

    fun selectPreviousSymbol() {
        cycleSymbol(step = -1)
    }

    fun selectNextSymbol() {
        cycleSymbol(step = 1)
    }

    private fun cycleSymbol(step: Int) {
        val list = _navigationContextSymbols.value.takeIf { it.size > 1 }
            ?: switcherItemsFlow.value.map { it.contract.symbol }
        if (list.size <= 1) return
        val current = _symbol.value
        val idx = list.indexOfFirst { it.equals(current, ignoreCase = true) }
        val targetIdx = if (idx < 0) {
            if (step > 0) 0 else list.lastIndex
        } else {
            (idx + step + list.size) % list.size
        }
        val target = list[targetIdx]
        if (!target.equals(current, ignoreCase = true)) {
            openSymbol(target)
        }
    }

    fun selectTimeframe(timeframeId: String) {
        if (_timeframeId.value == timeframeId) return
        _timeframeId.value = timeframeId
        _pendingPoints.value = emptyList()
        _crosshair.value = CrosshairState()
        // Preserve current price zoom/bounds when switching timeframes (e.g., 1H -> 30m)
        shouldAutoFitOnNextLoad = false
        val hasValidPriceRange = _viewport.value.priceMax > _viewport.value.priceMin &&
            !(_viewport.value.priceMin == 0.0 && _viewport.value.priceMax == 100.0)
        if (hasValidPriceRange) {
            _viewport.update { it.copy(isAutoFit = false) }
        }
        exitBarReplay(emitFullHistory = false)
        persistChartState()
        reloadChartFeed()
    }

    fun selectPriceSource(source: PriceSource) {
        if (_priceSource.value == source) return
        if (source != PriceSource.LAST &&
            marketRepository.endpointMode.value == com.example.data.remote.MarketEndpointMode.VISION_MIRROR
        ) {
            _noticeMessage.value = "${source.label} requires Futures API (Spot fallback active in this region)"
            return
        }
        _priceSource.value = source
        _crosshair.value = CrosshairState()
        shouldAutoFitOnNextLoad = false
        exitBarReplay(emitFullHistory = false)
        persistChartState()
        reloadChartFeed()
    }

    fun dismissNotice() {
        _noticeMessage.value = null
    }

    private fun observeSymbolPersistedData(symbol: String) {
        drawingsJob?.cancel()
        drawingsJob = viewModelScope.launch {
            chartStateRepository.observeDrawings(symbol).collect { _drawingsForSymbol.value = it }
        }
        alertsSymbolJob?.cancel()
        alertsSymbolJob = viewModelScope.launch {
            chartStateRepository.observeAlertsForSymbol(symbol).collect { _alertsForSymbol.value = it }
        }
    }

    fun reloadChartFeed() {
        klineStreamJob?.cancel()
        hasReachedEarliestHistory = false
        val sym = _symbol.value
        val source = _priceSource.value
        val tfId = _timeframeId.value
        val seriesKey = currentSeriesKey(sym, tfId, source)
        val tf = cachedTimeframes.firstOrNull { it.id == tfId }
            ?: Timeframe.DEFAULTS.firstOrNull { it.id == tfId }
            ?: Timeframe.DEFAULTS[4]

        // Immediately swap to fresh cached candles OR clear stale previous-timeframe candles synchronously
        val freshCached = marketRepository.getFreshCachedCandles(sym, source, tf)
        val isUninitializedRange = _viewport.value.priceMax <= _viewport.value.priceMin ||
            (_viewport.value.priceMin == 0.0 && _viewport.value.priceMax == 100.0)
        if (freshCached.isNotEmpty()) {
            _candleSeries.value = CandleSeriesData(seriesKey, freshCached)
            _isLoadingHistory.value = false
            if (shouldAutoFitOnNextLoad || _viewport.value.isAutoFit || isUninitializedRange) {
                autoFitIfNeeded(freshCached, force = isUninitializedRange)
                shouldAutoFitOnNextLoad = false
            }
            if (!_replayState.value.isActive) {
                _webChartUpdates.tryEmit(
                    ChartWebUpdate.FullHistory(
                        seriesKey = seriesKey,
                        candles = freshCached,
                        fit = _viewport.value.isAutoFit
                    )
                )
            }
        } else {
            _candleSeries.value = CandleSeriesData(seriesKey, emptyList())
            _isLoadingHistory.value = true
            _webChartUpdates.tryEmit(ChartWebUpdate.ClearSeries(seriesKey))
        }
        _errorMessage.value = null

        klineStreamJob = viewModelScope.launch {
            val resolvedTf = cachedTimeframes.firstOrNull { it.id == tfId }
                ?: chartStateRepository.timeframes.first().firstOrNull { it.id == tfId }
                ?: tf
            val contract = marketRepository.getContract(sym)
            val pair = contract?.pair ?: sym

            var restLoaded = freshCached.isNotEmpty()
            var pendingLiveCandle: Candle? = null

            // Start WebSocket subscription immediately in parallel with REST fetch so live ticks flow with 0ms extra wait
            val liveJob = launch {
                marketRepository.observeLiveKlineUpdates(
                    symbol = sym,
                    pair = pair,
                    priceSource = source,
                    timeframe = resolvedTf
                ).collect { liveCandle ->
                    if (!restLoaded) {
                        pendingLiveCandle = liveCandle
                        return@collect
                    }
                    applyLiveCandle(sym, source, seriesKey, liveCandle)
                }
            }

            val fetchResult = marketRepository.fetchKlines(
                symbol = sym,
                pair = pair,
                priceSource = source,
                timeframe = resolvedTf
            )
            _isLoadingHistory.value = false
            fetchResult.onSuccess { list ->
                if (list.isEmpty() && contract != null && contract.isUpcomingListing()) {
                    _errorMessage.value = if (contract.onboardDate > 0L) {
                        "Upcoming listing • Opens ${ChartNumberFormatter.formatListingSchedule(contract.onboardDate)}"
                    } else {
                        "Upcoming listing • Trading not started yet"
                    }
                    return@onSuccess
                }
                val mergedList = pendingLiveCandle?.let { pending ->
                    if (list.isNotEmpty() && pending.openTime >= list.last().openTime) {
                        mergeCandleWithDelta(list, pending).updatedList
                    } else list
                } ?: list
                pendingLiveCandle = null
                restLoaded = true
                _candleSeries.value = CandleSeriesData(seriesKey, mergedList)
                val stillUninitialized = _viewport.value.priceMax <= _viewport.value.priceMin ||
                    (_viewport.value.priceMin == 0.0 && _viewport.value.priceMax == 100.0)
                if (shouldAutoFitOnNextLoad || _viewport.value.isAutoFit || stillUninitialized) {
                    autoFitIfNeeded(mergedList, force = stillUninitialized)
                    shouldAutoFitOnNextLoad = false
                }
                if (!_replayState.value.isActive) {
                    _webChartUpdates.tryEmit(
                        ChartWebUpdate.FullHistory(
                            seriesKey = seriesKey,
                            candles = mergedList,
                            fit = _viewport.value.isAutoFit
                        )
                    )
                }
            }.onFailure { err ->
                if (contract != null && contract.isUpcomingListing()) {
                    _errorMessage.value = if (contract.onboardDate > 0L) {
                        "Upcoming listing • Trading opens ${ChartNumberFormatter.formatListingSchedule(contract.onboardDate)}"
                    } else {
                        "Upcoming listing • Trading has not started yet"
                    }
                } else if (source != PriceSource.LAST) {
                    liveJob.cancel()
                    _priceSource.value = PriceSource.LAST
                    _noticeMessage.value = "${source.label} unavailable in this region/server; showing Last Price"
                    persistChartState()
                    reloadChartFeed()
                    return@launch
                } else if (_candleSeries.value.candles.isEmpty()) {
                    _errorMessage.value = err.localizedMessage ?: "Unable to load chart candles"
                }
                restLoaded = true
            }

            liveJob.join()
        }
    }

    private suspend fun applyLiveCandle(
        sym: String,
        source: PriceSource,
        seriesKey: String,
        liveCandle: Candle
    ) {
        val currentSeries = _candleSeries.value
        if (currentSeries.seriesKey != seriesKey) return
        val (updatedList, deltaCandles, requiresFullSync) = mergeCandleWithDelta(currentSeries.candles, liveCandle)
        _candleSeries.value = CandleSeriesData(seriesKey, updatedList)
        if (_viewport.value.isAutoFit && !_replayState.value.isActive) {
            autoFitIfNeeded(updatedList)
        }
        if (!_replayState.value.isActive) {
            if (requiresFullSync) {
                _webChartUpdates.tryEmit(
                    ChartWebUpdate.FullHistory(
                        seriesKey = seriesKey,
                        candles = updatedList,
                        fit = false
                    )
                )
            } else if (deltaCandles.isNotEmpty()) {
                _webChartUpdates.tryEmit(
                    ChartWebUpdate.LiveCandleDelta(
                        seriesKey = seriesKey,
                        candles = deltaCandles,
                        totalCount = updatedList.size
                    )
                )
            }
        }
        evaluateForegroundAlerts(sym, source, liveCandle)
    }

    private data class CandleMergeResult(
        val updatedList: List<Candle>,
        val deltaCandles: List<Candle>,
        val requiresFullSync: Boolean
    )

    private fun mergeCandleWithDelta(existing: List<Candle>, update: Candle): CandleMergeResult {
        if (existing.isEmpty()) {
            val single = listOf(update)
            return CandleMergeResult(single, single, requiresFullSync = true)
        }
        val last = existing.last()
        return when {
            update.openTime == last.openTime -> {
                if (last == update) {
                    CandleMergeResult(existing, emptyList(), requiresFullSync = false)
                } else {
                    val next = existing.toMutableList().apply { set(lastIndex, update) }
                    CandleMergeResult(next, listOf(update), requiresFullSync = false)
                }
            }
            update.openTime > last.openTime -> {
                val closedPrev = if (!last.isClosed) last.copy(isClosed = true) else last
                val next = existing.toMutableList().apply {
                    set(lastIndex, closedPrev)
                    add(update)
                }
                CandleMergeResult(next, listOf(closedPrev, update), requiresFullSync = false)
            }
            else -> {
                val idx = existing.indexOfLast { it.openTime == update.openTime }
                if (idx >= 0) {
                    if (existing[idx] == update) {
                        CandleMergeResult(existing, emptyList(), requiresFullSync = false)
                    } else {
                        val next = existing.toMutableList().apply { set(idx, update) }
                        CandleMergeResult(next, listOf(update), requiresFullSync = true)
                    }
                } else {
                    val next = (existing + update).sortedBy { it.openTime }
                    CandleMergeResult(next, listOf(update), requiresFullSync = true)
                }
            }
        }
    }

    private suspend fun evaluateForegroundAlerts(
        symbol: String,
        source: PriceSource,
        candle: Candle
    ) {
        val active = _alertsForSymbol.value.filter { it.isActive && it.priceSource == source }
        if (active.isEmpty()) return
        val prefs = uiState.value.preferences
        val now = System.currentTimeMillis()
        for (alert in active) {
            val res = if (alert.condition.needsCandleClose) {
                if (candle.isClosed) AlertEvaluator.evaluateClosedCandle(alert, candle) else null
            } else {
                AlertEvaluator.evaluateTick(alert, candle.close, now)
            }
            if (res != null && res.triggered) {
                chartStateRepository.recordAlertTriggered(alert, res.triggeredPrice, res.message)
                AlertNotifier.notifyAlertTriggered(
                    context = appContext,
                    symbol = symbol,
                    title = "$symbol Alert Triggered",
                    body = res.message,
                    loudSoundEnabled = prefs.loudSoundEnabled,
                    vibrationEnabled = prefs.vibrationEnabled
                )
            } else if (res != null && alert.lastEvaluatedPrice != res.nextEvaluatedPrice) {
                chartStateRepository.updateAlertLastEvaluatedPrice(alert.id, res.nextEvaluatedPrice)
            }
        }
    }

    fun paginateOlderHistoryIfNeeded() {
        val currentSeries = _candleSeries.value
        if (_isPaginatingBack.value || hasReachedEarliestHistory || currentSeries.candles.isEmpty()) return
        val oldestOpenTime = currentSeries.candles.first().openTime
        val expectedKey = currentSeries.seriesKey
        viewModelScope.launch {
            _isPaginatingBack.value = true
            val sym = _symbol.value
            val source = _priceSource.value
            val tf = uiState.value.activeTimeframe
            val pair = marketRepository.getContract(sym)?.pair ?: sym
            val previousSize = _candleSeries.value.candles.size
            val res = marketRepository.fetchKlines(
                symbol = sym,
                pair = pair,
                priceSource = source,
                timeframe = tf,
                endTime = oldestOpenTime - 1L
            )
            res.onSuccess { combined ->
                if (expectedKey != currentSeriesKey()) return@onSuccess
                if (combined.size <= previousSize) {
                    hasReachedEarliestHistory = true
                } else {
                    _candleSeries.value = CandleSeriesData(expectedKey, combined)
                    if (!_replayState.value.isActive) {
                        _webChartUpdates.tryEmit(
                            ChartWebUpdate.FullHistory(
                                seriesKey = expectedKey,
                                candles = combined,
                                fit = false
                            )
                        )
                    }
                }
            }
            _isPaginatingBack.value = false
        }
    }

    // Viewport controls
    fun updatePlotWidth(widthPx: Float) {
        if (widthPx > 0f && abs(widthPx - lastPlotWidthPx) > 1f) {
            lastPlotWidthPx = widthPx
            if (_viewport.value.isAutoFit) {
                autoFitIfNeeded(uiState.value.displayedCandles)
            }
        }
    }

    fun panChart(deltaXPx: Float, deltaYPx: Float, plotHeightPx: Float) {
        val spacing = _viewport.value.barSpacingPx.coerceAtLeast(4f)
        val deltaBars = deltaXPx / spacing
        val candles = uiState.value.displayedCandles
        val maxOffset = 40f
        val minOffset = -(candles.size.toFloat() - 2f).coerceAtLeast(0f)

        _viewport.update { vp ->
            val newRightOffset = (vp.rightOffsetBars - deltaBars).coerceIn(minOffset, maxOffset)
            var newMin = vp.priceMin
            var newMax = vp.priceMax
            var nextAutoFit = vp.isAutoFit
            val wantsVerticalPan = !vp.isAutoFit || (abs(deltaYPx) >= 0.6f && abs(deltaYPx) >= abs(deltaXPx) * 0.3f)
            if (wantsVerticalPan && plotHeightPx > 1f) {
                nextAutoFit = false
                val priceSpan = (vp.priceMax - vp.priceMin).takeIf { it > 0.0 } ?: 1.0
                val deltaPrice = (deltaYPx / plotHeightPx) * priceSpan
                newMin += deltaPrice
                newMax += deltaPrice
            }
            val updated = vp.copy(
                rightOffsetBars = newRightOffset,
                priceMin = newMin,
                priceMax = newMax,
                isAutoFit = nextAutoFit
            )
            applyAutoFitToViewport(updated, candles)
        }

        val visibleLeftIdx = floor(
            (candles.size - 1) + _viewport.value.rightOffsetBars - (lastPlotWidthPx / spacing)
        ).toInt()
        if (visibleLeftIdx <= 20) {
            paginateOlderHistoryIfNeeded()
        }
        persistChartState()
    }

    fun pinchZoom(
        zoomX: Float,
        zoomY: Float,
        focusXPx: Float,
        focusYPx: Float = -1f,
        plotHeightPx: Float = 0f
    ) {
        val candles = uiState.value.displayedCandles
        _viewport.update { vp ->
            val oldSpacing = vp.barSpacingPx
            val newSpacing = (oldSpacing * zoomX).coerceIn(4f, 110f)
            val barsFromRightOld = (lastPlotWidthPx - focusXPx) / oldSpacing
            val barsFromRightNew = (lastPlotWidthPx - focusXPx) / newSpacing
            val adjustedOffset = (vp.rightOffsetBars + (barsFromRightNew - barsFromRightOld))
                .coerceIn(-(candles.size.toFloat() - 2f).coerceAtLeast(0f), 40f)

            var newMin = vp.priceMin
            var newMax = vp.priceMax
            var nextAutoFit = vp.isAutoFit
            if (zoomY != 1f && abs(zoomY - 1f) > 0.002f) {
                val oldSpan = (vp.priceMax - vp.priceMin).takeIf { it > 0.0 }
                if (oldSpan != null) {
                    if (!vp.isAutoFit || abs(zoomY - 1f) > 0.025f) {
                        nextAutoFit = false
                        val normY = if (focusYPx >= 0f && plotHeightPx > 1f) {
                            (focusYPx / plotHeightPx).toDouble().coerceIn(0.0, 1.0)
                        } else {
                            0.5
                        }
                        val anchorPrice = vp.priceMax - normY * oldSpan
                        val minAllowedSpan = (abs(anchorPrice) * 1e-5).coerceAtLeast(1e-8)
                        val newSpan = (oldSpan / zoomY.coerceIn(0.5f, 2.0f)).coerceAtLeast(minAllowedSpan)
                        newMax = anchorPrice + normY * newSpan
                        newMin = newMax - newSpan
                    }
                }
            }
            val updated = vp.copy(
                barSpacingPx = newSpacing,
                rightOffsetBars = adjustedOffset,
                priceMin = newMin,
                priceMax = newMax,
                isAutoFit = nextAutoFit
            )
            applyAutoFitToViewport(updated, candles)
        }
        persistChartState()
    }

    fun scalePriceAxis(deltaYDp: Float) {
        _viewport.update { vp ->
            val span = (vp.priceMax - vp.priceMin).takeIf { it > 0.0 } ?: return@update vp
            val factor = exp(deltaYDp * 0.0075).coerceIn(0.5, 2.0)
            val mid = (vp.priceMax + vp.priceMin) / 2.0
            val minHalfSpan = (abs(mid) * 5e-6).coerceAtLeast(5e-9)
            val halfSpan = ((span / 2.0) * factor).coerceAtLeast(minHalfSpan)
            vp.copy(
                isAutoFit = false,
                priceMin = (mid - halfSpan),
                priceMax = (mid + halfSpan)
            )
        }
    }

    fun scaleTimeAxis(deltaXDp: Float) {
        val candles = uiState.value.displayedCandles
        _viewport.update { vp ->
            val factor = exp(deltaXDp * 0.0085).toFloat().coerceIn(0.5f, 2.0f)
            val newSpacing = (vp.barSpacingPx * factor).coerceIn(4f, 110f)
            applyAutoFitToViewport(vp.copy(barSpacingPx = newSpacing), candles)
        }
        persistChartState()
    }

    fun triggerAutoFit() {
        val candles = uiState.value.displayedCandles
        _viewport.update { vp ->
            applyAutoFitToViewport(vp.copy(isAutoFit = true, rightOffsetBars = 8f), candles, force = true)
        }
        _webChartUpdates.tryEmit(ChartWebUpdate.ResetScroll)
        persistChartState()
    }

    fun resetPriceAutoFitOnly() {
        val candles = uiState.value.displayedCandles
        _viewport.update { vp ->
            applyAutoFitToViewport(vp.copy(isAutoFit = true), candles, force = true)
        }
        _webChartUpdates.tryEmit(ChartWebUpdate.ResetPriceScale)
    }

    fun onChartManualInteraction() {
        if (_viewport.value.isAutoFit) {
            _viewport.update { it.copy(isAutoFit = false) }
        }
    }

    fun onPlotDoubleTap() {
        _axisQuickAlertPrice.value = null
        if (!_viewport.value.isAutoFit) {
            resetPriceAutoFitOnly()
        } else {
            triggerAutoFit()
        }
    }

    private fun applyAutoFitToViewport(
        vp: ChartViewportState,
        candles: List<Candle>,
        force: Boolean = false
    ): ChartViewportState {
        if (candles.isEmpty()) return vp
        if (!force && !vp.isAutoFit) return vp
        val spacing = vp.barSpacingPx.coerceAtLeast(4f)
        val total = candles.size
        val startIdx = floor((total - 1) + vp.rightOffsetBars - (lastPlotWidthPx / spacing)).toInt()
            .coerceIn(0, candles.lastIndex)
        val endIdx = ceil((total - 1) + vp.rightOffsetBars).toInt()
            .coerceIn(0, candles.lastIndex)
        val range = if (startIdx <= endIdx) startIdx..endIdx else 0..candles.lastIndex
        val (minP, maxP) = ChartCoordinateMapper.computeAutoFitBounds(candles, range)
        return vp.copy(priceMin = minP, priceMax = maxP)
    }

    private fun autoFitIfNeeded(candles: List<Candle>, force: Boolean = false) {
        if (candles.isEmpty()) return
        _viewport.update { vp -> applyAutoFitToViewport(vp, candles, force = force) }
    }

    // Crosshair & Quick Alert "+"
    fun updateCrosshair(timeMillis: Long, price: Double, candle: Candle?) {
        _crosshair.value = CrosshairState(
            isActive = true,
            timeMillis = timeMillis,
            price = price,
            candle = candle
        )
        _axisQuickAlertPrice.value = price
    }

    fun clearCrosshair() {
        _crosshair.value = CrosshairState(isActive = false)
    }

    fun setAxisQuickAlertPrice(price: Double?) {
        _axisQuickAlertPrice.value = price
    }

    // Drawing tools lifecycle (auto-hide toolbar on select, auto-deactivate on completion)
    fun toggleDrawingToolbar() {
        _drawingToolbarExpanded.update { !it }
    }

    fun selectDrawingTool(tool: DrawingTool) {
        _activeTool.value = if (_activeTool.value == tool) DrawingTool.NONE else tool
        _drawingToolbarExpanded.value = false
        _pendingPoints.value = emptyList()
        _selectedDrawingId.value = null
        if (tool != DrawingTool.PRICE_RANGE) {
            _rangeMeasurement.value = null
        }
    }

    fun handleChartTap(point: ChartPoint, tappedDrawing: ChartDrawing?, tappedAlert: PriceAlert?, tappedIndex: Int) {
        if (_replayState.value.isPickingStart) {
            val safeIdx = tappedIndex.coerceIn(0, (_candleSeries.value.candles.size - 1).coerceAtLeast(0))
            _replayState.value = BarReplayState(
                isActive = true,
                isPickingStart = false,
                cutoffIndex = safeIdx,
                isPlaying = false,
                speedMultiplier = 1
            )
            val replayed = uiState.value.displayedCandles
            autoFitIfNeeded(replayed)
            _webChartUpdates.tryEmit(
                ChartWebUpdate.FullHistory(
                    seriesKey = currentSeriesKey(),
                    candles = replayed,
                    fit = false
                )
            )
            return
        }

        val tool = _activeTool.value
        if (tool != DrawingTool.NONE) {
            when (tool) {
                DrawingTool.HORIZONTAL_LINE -> {
                    val drawing = ChartDrawing(
                        id = UUID.randomUUID().toString(),
                        symbol = _symbol.value,
                        type = DrawingType.HORIZONTAL_LINE,
                        points = listOf(point)
                    )
                    viewModelScope.launch { chartStateRepository.saveDrawing(drawing) }
                    _activeTool.value = DrawingTool.NONE
                    _pendingPoints.value = emptyList()
                    _selectedDrawingId.value = drawing.id
                }
                DrawingTool.TRENDLINE,
                DrawingTool.RECTANGLE -> {
                    val existing = _pendingPoints.value
                    if (existing.isEmpty()) {
                        _pendingPoints.value = listOf(point)
                    } else {
                        val type = if (tool == DrawingTool.TRENDLINE) DrawingType.TRENDLINE else DrawingType.RECTANGLE
                        val drawing = ChartDrawing(
                            id = UUID.randomUUID().toString(),
                            symbol = _symbol.value,
                            type = type,
                            points = listOf(existing.first(), point)
                        )
                        viewModelScope.launch { chartStateRepository.saveDrawing(drawing) }
                        _activeTool.value = DrawingTool.NONE
                        _pendingPoints.value = emptyList()
                        _selectedDrawingId.value = drawing.id
                    }
                }
                DrawingTool.PRICE_RANGE -> {
                    val existing = _pendingPoints.value
                    if (existing.isEmpty()) {
                        _pendingPoints.value = listOf(point)
                        _rangeMeasurement.value = null
                    } else {
                        val start = existing.first()
                        val tfMs = uiState.value.activeTimeframe.durationMillis.coerceAtLeast(1L)
                        val bars = abs((point.timeMillis - start.timeMillis) / tfMs).toInt().coerceAtLeast(1)
                        _rangeMeasurement.value = PriceRangeMeasurement(start, point, bars)
                        _activeTool.value = DrawingTool.NONE
                        _pendingPoints.value = emptyList()
                    }
                }
                DrawingTool.NONE -> Unit
            }
            return
        }

        if (_crosshair.value.isActive) {
            clearCrosshair()
            return
        }

        if (_rangeMeasurement.value != null && tappedDrawing == null && tappedAlert == null) {
            _rangeMeasurement.value = null
        }

        if (tappedDrawing != null) {
            _selectedDrawingId.value = tappedDrawing.id
            _selectedAlertId.value = null
            _axisQuickAlertPrice.value = null
        } else if (tappedAlert != null) {
            _selectedAlertId.value = tappedAlert.id
            _selectedDrawingId.value = null
            _axisQuickAlertPrice.value = null
        } else {
            _selectedDrawingId.value = null
            _selectedAlertId.value = null
        }
    }

    fun clearRangeMeasurement() {
        _rangeMeasurement.value = null
    }

    fun moveDrawingAnchor(drawingId: String, anchorIndex: Int, newPoint: ChartPoint) {
        val drawing = _drawingsForSymbol.value.firstOrNull { it.id == drawingId } ?: return
        if (drawing.isLocked || anchorIndex !in drawing.points.indices) return
        val updatedPts = drawing.points.toMutableList().apply { set(anchorIndex, newPoint) }
        val updated = drawing.copy(points = updatedPts)
        updateDrawingLocallyAndPersist(updated)
    }

    fun moveWholeDrawing(drawingId: String, deltaTimeMs: Long, deltaPrice: Double) {
        val drawing = _drawingsForSymbol.value.firstOrNull { it.id == drawingId } ?: return
        if (drawing.isLocked) return
        val updatedPts = drawing.points.map {
            ChartPoint(it.timeMillis + deltaTimeMs, it.price + deltaPrice)
        }
        updateDrawingLocallyAndPersist(drawing.copy(points = updatedPts))
    }

    fun updateDrawingStyle(
        drawingId: String,
        colorHex: Long,
        strokeWidth: Float,
        lineStyle: LineStyle
    ) {
        val drawing = _drawingsForSymbol.value.firstOrNull { it.id == drawingId } ?: return
        updateDrawingLocallyAndPersist(
            drawing.copy(colorHex = colorHex, strokeWidth = strokeWidth, lineStyle = lineStyle)
        )
    }

    fun toggleDrawingLock(drawingId: String) {
        val drawing = _drawingsForSymbol.value.firstOrNull { it.id == drawingId } ?: return
        updateDrawingLocallyAndPersist(drawing.copy(isLocked = !drawing.isLocked))
    }

    fun deleteSelectedDrawing() {
        val id = _selectedDrawingId.value ?: return
        _selectedDrawingId.value = null
        viewModelScope.launch { chartStateRepository.deleteDrawing(id) }
    }

    private fun updateDrawingLocallyAndPersist(updated: ChartDrawing) {
        _drawingsForSymbol.update { list -> list.map { if (it.id == updated.id) updated else it } }
        viewModelScope.launch { chartStateRepository.saveDrawing(updated) }
    }

    // Alerts
    fun createAlert(
        price: Double,
        condition: AlertCondition,
        linkedDrawing: ChartDrawing? = null
    ) {
        val sym = _symbol.value
        val src = _priceSource.value
        val tfId = _timeframeId.value
        val currentPrice = uiState.value.currentPrice
        val alert = PriceAlert(
            id = UUID.randomUUID().toString(),
            symbol = sym,
            priceSource = src,
            condition = condition,
            targetPrice = price,
            anchorTime1 = linkedDrawing?.points?.getOrNull(0)?.timeMillis,
            anchorPrice1 = linkedDrawing?.points?.getOrNull(0)?.price,
            anchorTime2 = linkedDrawing?.points?.getOrNull(1)?.timeMillis,
            anchorPrice2 = linkedDrawing?.points?.getOrNull(1)?.price,
            timeframeId = tfId,
            linkedDrawingId = linkedDrawing?.id,
            isActive = true,
            lastEvaluatedPrice = currentPrice
        )
        viewModelScope.launch { chartStateRepository.saveAlert(alert) }
        _axisQuickAlertPrice.value = null
        _selectedAlertId.value = alert.id
    }

    fun moveAlertPrice(alertId: String, newPrice: Double) {
        _alertsForSymbol.update { list ->
            list.map { if (it.id == alertId) it.copy(targetPrice = newPrice) else it }
        }
        viewModelScope.launch { chartStateRepository.updateAlertTargetPrice(alertId, newPrice) }
    }

    fun toggleAlertActive(alertId: String, active: Boolean) {
        viewModelScope.launch { chartStateRepository.setAlertActive(alertId, active) }
    }

    fun deleteAlert(alertId: String) {
        if (_selectedAlertId.value == alertId) _selectedAlertId.value = null
        viewModelScope.launch { chartStateRepository.deleteAlert(alertId) }
    }

    fun clearAlertHistory() {
        viewModelScope.launch { chartStateRepository.clearAlertHistory() }
    }

    // Timeframes
    fun toggleFavoriteTimeframe(id: String, isFav: Boolean) {
        viewModelScope.launch { chartStateRepository.toggleFavoriteTimeframe(id, isFav) }
    }

    fun createCustomTimeframe(amount: Int, unit: CustomTimeUnit) {
        viewModelScope.launch {
            val created = chartStateRepository.addCustomTimeframe(amount, unit)
            selectTimeframe(created.id)
        }
    }

    fun deleteCustomTimeframe(id: String) {
        viewModelScope.launch {
            if (_timeframeId.value == id) {
                selectTimeframe("15m")
            }
            chartStateRepository.deleteCustomTimeframe(id)
        }
    }

    // Bar Replay
    fun startBarReplayPicker() {
        _activeTool.value = DrawingTool.NONE
        _drawingToolbarExpanded.value = false
        _replayState.value = BarReplayState(isActive = false, isPickingStart = true)
    }

    fun stepReplayForward() {
        val total = _candleSeries.value.candles.size
        if (total == 0) return
        _replayState.update { state ->
            if (!state.isActive) return@update state
            val nextIdx = (state.cutoffIndex + 1).coerceAtMost(total - 1)
            val shouldStop = nextIdx >= total - 1
            state.copy(
                cutoffIndex = nextIdx,
                isPlaying = if (shouldStop) false else state.isPlaying
            )
        }
        val displayed = uiState.value.displayedCandles
        if (_viewport.value.isAutoFit) {
            autoFitIfNeeded(displayed)
        }
        if (displayed.isNotEmpty()) {
            _webChartUpdates.tryEmit(
                ChartWebUpdate.LiveCandleDelta(
                    seriesKey = currentSeriesKey(),
                    candles = listOf(displayed.last()),
                    totalCount = displayed.size
                )
            )
        }
    }

    fun toggleReplayPlayPause() {
        val current = _replayState.value
        if (!current.isActive) return
        val nextPlaying = !current.isPlaying
        _replayState.update { it.copy(isPlaying = nextPlaying) }
        replayJob?.cancel()
        if (nextPlaying) {
            replayJob = viewModelScope.launch {
                while (isActive && _replayState.value.isPlaying) {
                    val speed = _replayState.value.speedMultiplier.coerceIn(1, 10)
                    delay(1000L / speed)
                    stepReplayForward()
                    if (_replayState.value.cutoffIndex >= _candleSeries.value.candles.lastIndex) {
                        _replayState.update { it.copy(isPlaying = false) }
                        break
                    }
                }
            }
        }
    }

    fun cycleReplaySpeed() {
        _replayState.update { state ->
            val nextSpeed = when (state.speedMultiplier) {
                1 -> 2
                2 -> 5
                5 -> 10
                else -> 1
            }
            state.copy(speedMultiplier = nextSpeed)
        }
    }

    fun exitBarReplay(emitFullHistory: Boolean = true) {
        val wasActive = _replayState.value.isActive || _replayState.value.isPickingStart
        replayJob?.cancel()
        if (!wasActive) return
        _replayState.value = BarReplayState()
        if (!emitFullHistory) return
        val series = _candleSeries.value
        val expectedKey = currentSeriesKey()
        if (series.seriesKey != expectedKey || series.candles.isEmpty()) return
        if (_viewport.value.isAutoFit) {
            autoFitIfNeeded(series.candles)
        }
        _webChartUpdates.tryEmit(
            ChartWebUpdate.FullHistory(
                seriesKey = expectedKey,
                candles = series.candles,
                fit = _viewport.value.isAutoFit
            )
        )
    }

    // Full screen & Preferences
    fun toggleFullScreen() {
        _isFullScreen.update { !it }
    }

    fun updatePreferences(transform: (ChartPreferences) -> ChartPreferences) {
        viewModelScope.launch {
            chartStateRepository.updatePreferences(transform)
        }
    }

    fun toggleTbtSmc() {
        updatePreferences { it.copy(showTbtSmc = !it.showTbtSmc) }
    }

    fun toggleTbtZones() {
        updatePreferences { it.copy(tbtShowZones = !it.tbtShowZones) }
    }

    fun toggleTbtPowerShift() {
        updatePreferences { it.copy(tbtShowPowerShift = !it.tbtShowPowerShift) }
    }

    fun toggleTbtOverlap() {
        updatePreferences { it.copy(tbtShowOverlap = !it.tbtShowOverlap) }
    }

    fun toggleTbtSignals() {
        updatePreferences { it.copy(tbtShowSignals = !it.tbtShowSignals) }
    }

    fun toggleTbtHud() {
        updatePreferences { it.copy(tbtShowHud = !it.tbtShowHud) }
    }

    private fun persistChartState() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300L)
            val sym = _symbol.value
            val tfId = _timeframeId.value
            val src = _priceSource.value
            val spacingDp = (_viewport.value.barSpacingPx / 2.6f).coerceIn(3f, 40f)
            val rightBars = _viewport.value.rightOffsetBars
            chartStateRepository.updatePreferences { prefs ->
                prefs.copy(
                    lastSymbol = sym,
                    lastTimeframeId = tfId,
                    lastPriceSource = src,
                    lastBarSpacingDp = spacingDp,
                    lastRightOffsetBars = rightBars
                )
            }
        }
    }

    private fun startCountdownTicker() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                val lastCandle = uiState.value.displayedCandles.lastOrNull()
                if (lastCandle != null && !uiState.value.replayState.isActive) {
                    val rem = (lastCandle.closeTime + 1L - marketRepository.currentExchangeTimeMillis()).coerceAtLeast(0L)
                    _countdownMillis.value = rem
                } else {
                    _countdownMillis.value = 0L
                }
                delay(500L)
            }
        }
    }

    override fun onCleared() {
        unsubscribeTickers?.invoke()
        unsubscribeTickers = null
        klineStreamJob?.cancel()
        replayJob?.cancel()
        countdownJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChartViewModel(
                        context.applicationContext,
                        container.marketRepository,
                        container.chartStateRepository
                    ) as T
                }
            }
    }
}
