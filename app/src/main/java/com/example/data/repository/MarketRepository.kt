package com.example.data.repository

import com.example.data.remote.BinanceFuturesApi
import com.example.data.remote.BinancePayloadParser
import com.example.data.remote.BinanceSocketManager
import com.example.data.remote.BinanceVisionApi
import com.example.data.remote.BybitLinearApi
import com.example.data.remote.BybitPayloadParser
import com.example.data.remote.CoinGeckoApi
import com.example.data.remote.CoinPaprikaApi
import com.example.data.remote.MarketCapInfo
import com.example.data.remote.MarketCapPayloadParser
import com.example.data.remote.MarketEndpointMode
import com.example.data.remote.SocketConnectionState
import com.example.domain.model.Candle
import com.example.domain.model.FuturesContract
import com.example.domain.model.PriceSource
import com.example.domain.model.TickerSnapshot
import com.example.domain.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

data class CandleCacheKey(
    val symbol: String,
    val priceSource: PriceSource,
    val timeframeId: String,
    val endpointMode: MarketEndpointMode
)

class MarketRepository(
    private val prodApi: BinanceFuturesApi,
    private val demoApi: BinanceFuturesApi,
    private val bybitApi: BybitLinearApi,
    private val visionApi: BinanceVisionApi,
    private val socketManager: BinanceSocketManager,
    private val coinGeckoApi: CoinGeckoApi? = null,
    private val coinPaprikaApi: CoinPaprikaApi? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _contracts = MutableStateFlow<List<FuturesContract>>(emptyList())
    val contracts: StateFlow<List<FuturesContract>> = _contracts.asStateFlow()

    private val _tickers = MutableStateFlow<Map<String, TickerSnapshot>>(emptyMap())
    val tickers: StateFlow<Map<String, TickerSnapshot>> = _tickers.asStateFlow()

    private val _endpointMode = MutableStateFlow(MarketEndpointMode.FUTURES_PROD)
    val endpointMode: StateFlow<MarketEndpointMode> = _endpointMode.asStateFlow()

    val connectionState: StateFlow<SocketConnectionState> = socketManager.connectionState

    private val baseCandleCache = ConcurrentHashMap<CandleCacheKey, LinkedHashMap<Long, Candle>>()
    private val tickerSubscriberCount = AtomicInteger(0)
    private var activeBybitWatchlistTopics: List<String> = emptyList()

    @Volatile
    private var cachedMarketCaps: Map<String, MarketCapInfo> = emptyMap()
    private val lastMarketCapFetchMs = AtomicLong(0L)

    init {
        scope.launch {
            socketManager.tickerUpdates.collect { updates ->
                if (updates.isEmpty()) return@collect
                _tickers.update { current ->
                    val mutable = current.toMutableMap()
                    for (u in updates) {
                        val prev = mutable[u.symbol]
                        val last = u.lastPrice ?: prev?.lastPrice ?: continue
                        mutable[u.symbol] = TickerSnapshot(
                            symbol = u.symbol,
                            lastPrice = last,
                            markPrice = prev?.markPrice ?: last,
                            indexPrice = prev?.indexPrice ?: last,
                            priceChangePercent24h = u.priceChangePercent24h ?: prev?.priceChangePercent24h ?: 0.0,
                            quoteVolume24h = u.quoteVolume24h ?: prev?.quoteVolume24h ?: 0.0,
                            high24h = u.high24h ?: prev?.high24h ?: last,
                            low24h = u.low24h ?: prev?.low24h ?: last,
                            marketCap = prev?.marketCap,
                            marketCapRank = prev?.marketCapRank
                        )
                    }
                    mutable
                }
            }
        }
        scope.launch {
            socketManager.markPriceUpdates.collect { updates ->
                if (updates.isEmpty()) return@collect
                _tickers.update { current ->
                    val mutable = current.toMutableMap()
                    for (u in updates) {
                        val prev = mutable[u.symbol] ?: continue
                        mutable[u.symbol] = prev.copy(
                            markPrice = u.markPrice ?: prev.markPrice,
                            indexPrice = u.indexPrice ?: prev.indexPrice
                        )
                    }
                    mutable
                }
            }
        }
    }

    private fun switchEndpointMode(mode: MarketEndpointMode) {
        if (_endpointMode.value != mode) {
            _endpointMode.value = mode
            socketManager.setStreamBaseUrl(mode.socketUrl)
            syncBybitWatchlistSubscriptions()
        }
    }

    private fun isGeoBlocked(t: Throwable): Boolean {
        return t is HttpException && (t.code() == 451 || t.code() == 403)
    }

    suspend fun refreshMarketOverview(): Result<Unit> = withContext(Dispatchers.IO) {
        val mcapJob = scope.launch { refreshMarketCapDirectoryIfNeeded() }
        runCatching {
            when (_endpointMode.value) {
                MarketEndpointMode.FUTURES_PROD -> {
                    try {
                        loadFromFuturesApi(prodApi, MarketEndpointMode.FUTURES_PROD)
                    } catch (t: Throwable) {
                        if (isGeoBlocked(t)) {
                            try {
                                loadFromFuturesApi(demoApi, MarketEndpointMode.FUTURES_DEMO)
                            } catch (demoErr: Throwable) {
                                if (isGeoBlocked(demoErr)) {
                                    loadBybitOrVisionFallback()
                                } else {
                                    throw demoErr
                                }
                            }
                        } else {
                            throw t
                        }
                    }
                }
                MarketEndpointMode.FUTURES_DEMO -> {
                    try {
                        loadFromFuturesApi(demoApi, MarketEndpointMode.FUTURES_DEMO)
                    } catch (demoErr: Throwable) {
                        if (isGeoBlocked(demoErr)) {
                            loadBybitOrVisionFallback()
                        } else {
                            throw demoErr
                        }
                    }
                }
                MarketEndpointMode.BYBIT_LINEAR -> {
                    loadBybitOrVisionFallback()
                }
                MarketEndpointMode.VISION_MIRROR -> {
                    loadFromVisionMirror()
                }
            }
        }.also {
            if (!mcapJob.isCompleted) {
                // Re-apply once market cap fetch completes in background if it finished after exchange load
                mcapJob.invokeOnCompletion {
                    val currentContracts = _contracts.value
                    if (currentContracts.isNotEmpty() && cachedMarketCaps.isNotEmpty()) {
                        _tickers.update { current -> enrichTickerMapWithMarketCap(currentContracts, current) }
                    }
                }
            }
        }
    }

    private suspend fun refreshMarketCapDirectoryIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedMarketCaps.isNotEmpty() && now - lastMarketCapFetchMs.get() < 300_000L) {
            return
        }
        val fromGecko = runCatching {
            val raw = coinGeckoApi?.getTopMarkets()?.string() ?: return@runCatching emptyMap()
            MarketCapPayloadParser.parseCoinGeckoMarkets(raw)
        }.getOrDefault(emptyMap())

        val resolved = if (fromGecko.isNotEmpty()) {
            fromGecko
        } else {
            runCatching {
                val raw = coinPaprikaApi?.getTickers()?.string() ?: return@runCatching emptyMap()
                MarketCapPayloadParser.parseCoinPaprikaTickers(raw)
            }.getOrDefault(emptyMap())
        }

        if (resolved.isNotEmpty()) {
            cachedMarketCaps = resolved
            lastMarketCapFetchMs.set(System.currentTimeMillis())
        }
    }

    private fun enrichTickerMapWithMarketCap(
        contracts: List<FuturesContract>,
        rawTickers: Map<String, TickerSnapshot>
    ): Map<String, TickerSnapshot> {
        val mcapMap = cachedMarketCaps
        val mutable = rawTickers.toMutableMap()
        val unmatched = mutableListOf<FuturesContract>()
        var maxMatchedRank = 0

        for (contract in contracts) {
            val snap = mutable[contract.symbol] ?: continue
            val info = MarketCapPayloadParser.lookupForBaseAsset(
                baseAsset = contract.baseAsset,
                symbol = contract.symbol,
                marketCaps = mcapMap
            )
            if (info != null) {
                if (info.rank > maxMatchedRank) maxMatchedRank = info.rank
                mutable[contract.symbol] = snap.copy(
                    marketCap = info.marketCap,
                    marketCapRank = info.rank
                )
            } else {
                unmatched.add(contract)
            }
        }

        // Assign deterministic fallback sequential ranks (ordered by 24h volume) for any contract not in top directory
        val sortedUnmatched = unmatched.sortedByDescending { mutable[it.symbol]?.quoteVolume24h ?: 0.0 }
        var nextRank = maxMatchedRank + 1
        for (contract in sortedUnmatched) {
            val snap = mutable[contract.symbol] ?: continue
            mutable[contract.symbol] = snap.copy(
                marketCap = snap.marketCap,
                marketCapRank = nextRank++
            )
        }
        return mutable
    }

    private suspend fun loadBybitOrVisionFallback() {
        try {
            loadFromBybitLinear()
        } catch (bybitErr: Throwable) {
            try {
                loadFromVisionMirror()
            } catch (_: Throwable) {
                throw bybitErr
            }
        }
    }

    private suspend fun loadFromFuturesApi(api: BinanceFuturesApi, mode: MarketEndpointMode) {
        val loadedContracts = api.getExchangeInfo().symbols
            .filter { it.status == "TRADING" && (it.quoteAsset == "USDT" || it.quoteAsset == "USDC") }
            .map { it.toDomain() }
            .sortedBy { it.symbol }

        val ticker24hrList = api.getTickers24hr()
        val premiumMap = runCatching {
            api.getAllPremiumIndex().associateBy { it.symbol }
        }.getOrDefault(emptyMap())

        val tickerMap = HashMap<String, TickerSnapshot>(ticker24hrList.size)
        for (dto in ticker24hrList) {
            val prem = premiumMap[dto.symbol]
            tickerMap[dto.symbol] = dto.toDomain(
                markPrice = prem?.markPrice?.toDoubleOrNull(),
                indexPrice = prem?.indexPrice?.toDoubleOrNull()
            )
        }
        switchEndpointMode(mode)
        _contracts.value = loadedContracts
        _tickers.value = enrichTickerMapWithMarketCap(loadedContracts, tickerMap)
    }

    private suspend fun loadFromBybitLinear() {
        val raw = bybitApi.getLinearTickers("linear").string()
        val (loadedContracts, tickerMap) = BybitPayloadParser.parseTickersResponse(raw)
        if (loadedContracts.isEmpty()) {
            throw IllegalStateException("Bybit returned empty linear futures list")
        }
        _contracts.value = loadedContracts
        _tickers.value = enrichTickerMapWithMarketCap(loadedContracts, tickerMap)
        switchEndpointMode(MarketEndpointMode.BYBIT_LINEAR)
        syncBybitWatchlistSubscriptions()
    }

    private suspend fun loadFromVisionMirror() {
        val ticker24hrList = visionApi.getTickers24hr()
            .filter {
                it.symbol.endsWith("USDT") &&
                    !it.symbol.endsWith("UPUSDT") &&
                    !it.symbol.endsWith("DOWNUSDT") &&
                    !it.symbol.endsWith("BULLUSDT") &&
                    !it.symbol.endsWith("BEARUSDT") &&
                    (it.quoteVolume.toDoubleOrNull() ?: 0.0) > 0.0
            }

        val loadedContracts = ArrayList<FuturesContract>(ticker24hrList.size)
        val tickerMap = HashMap<String, TickerSnapshot>(ticker24hrList.size)

        for (dto in ticker24hrList) {
            val base = dto.symbol.removeSuffix("USDT")
            val trimmedPrice = dto.lastPrice.trimEnd('0')
            val dotIdx = trimmedPrice.indexOf('.')
            val precision = if (dotIdx >= 0) {
                (trimmedPrice.length - dotIdx - 1).coerceIn(2, 8)
            } else {
                2
            }
            val tick = Math.pow(10.0, -precision.toDouble())
            loadedContracts.add(
                FuturesContract(
                    symbol = dto.symbol,
                    pair = dto.symbol,
                    baseAsset = base,
                    quoteAsset = "USDT",
                    pricePrecision = precision,
                    quantityPrecision = 3,
                    tickSize = tick,
                    contractType = "SPOT MIRROR",
                    status = "TRADING"
                )
            )
            tickerMap[dto.symbol] = dto.toDomain()
        }

        val sortedContracts = loadedContracts.sortedBy { it.symbol }
        switchEndpointMode(MarketEndpointMode.VISION_MIRROR)
        _contracts.value = sortedContracts
        _tickers.value = enrichTickerMapWithMarketCap(sortedContracts, tickerMap)
    }

    fun getContract(symbol: String): FuturesContract? {
        return _contracts.value.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
    }

    fun subscribeMarketTickers(): () -> Unit {
        val count = tickerSubscriberCount.incrementAndGet()
        val binanceStreams = listOf("!ticker@arr", "!markPrice@arr@1s")
        socketManager.subscribe(binanceStreams)
        if (count == 1) {
            syncBybitWatchlistSubscriptions()
        }
        return {
            socketManager.unsubscribe(binanceStreams)
            if (tickerSubscriberCount.decrementAndGet() <= 0) {
                tickerSubscriberCount.set(0)
                synchronized(this) {
                    if (activeBybitWatchlistTopics.isNotEmpty()) {
                        socketManager.unsubscribe(activeBybitWatchlistTopics)
                        activeBybitWatchlistTopics = emptyList()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun syncBybitWatchlistSubscriptions() {
        if (_endpointMode.value != MarketEndpointMode.BYBIT_LINEAR || tickerSubscriberCount.get() <= 0) {
            if (activeBybitWatchlistTopics.isNotEmpty()) {
                socketManager.unsubscribe(activeBybitWatchlistTopics)
                activeBybitWatchlistTopics = emptyList()
            }
            return
        }
        val topSymbols = _tickers.value.values
            .sortedByDescending { it.quoteVolume24h }
            .take(20)
            .map { BinanceSocketManager.bybitTickerTopic(it.symbol) }
        if (topSymbols.isEmpty()) return
        val toUnsub = activeBybitWatchlistTopics - topSymbols.toSet()
        val toSub = topSymbols - activeBybitWatchlistTopics.toSet()
        if (toUnsub.isNotEmpty()) socketManager.unsubscribe(toUnsub)
        if (toSub.isNotEmpty()) socketManager.subscribe(toSub)
        activeBybitWatchlistTopics = topSymbols
    }

    fun currentExchangeTimeMillis(): Long = socketManager.currentExchangeTimeMillis()

    suspend fun fetchKlines(
        symbol: String,
        pair: String,
        priceSource: PriceSource,
        timeframe: Timeframe,
        endTime: Long? = null
    ): Result<List<Candle>> = withContext(Dispatchers.IO) {
        runCatching {
            val (parsedBase, resolvedMode) = fetchKlinesWithFallback(
                symbol = symbol,
                pair = pair,
                priceSource = priceSource,
                timeframe = timeframe,
                endTime = endTime
            )

            if (endTime == null && parsedBase.isNotEmpty()) {
                val latest = parsedBase.last()
                val localNow = System.currentTimeMillis()
                if (localNow < latest.openTime || localNow > latest.closeTime) {
                    val span = (latest.closeTime - latest.openTime).coerceAtLeast(1_000L)
                    socketManager.seedServerOffsetIfUnsynced(latest.openTime + min(span / 2L, 15_000L))
                }
            }

            val cacheKey = CandleCacheKey(symbol, priceSource, timeframe.id, resolvedMode)
            val cache = baseCandleCache.getOrPut(cacheKey) { LinkedHashMap() }
            synchronized(cache) {
                parsedBase.forEach { c -> cache[c.openTime] = c }
                val allBase = cache.values.toList()
                CandleAggregator.aggregate(allBase, timeframe, resolvedMode)
            }
        }
    }

    private suspend fun fetchKlinesWithFallback(
        symbol: String,
        pair: String,
        priceSource: PriceSource,
        timeframe: Timeframe,
        endTime: Long?
    ): Pair<List<Candle>, MarketEndpointMode> {
        suspend fun queryFutures(api: BinanceFuturesApi, mode: MarketEndpointMode): List<Candle> {
            val (baseInterval, _) = CandleAggregator.pickBaseInterval(timeframe, mode)
            val limit = CandleAggregator.recommendedFetchLimit(timeframe, endpointMode = mode)
            val body = when (priceSource) {
                PriceSource.LAST -> api.getLastPriceKlines(symbol, baseInterval, limit, endTime = endTime)
                PriceSource.MARK -> api.getMarkPriceKlines(symbol, baseInterval, limit, endTime = endTime)
                PriceSource.INDEX -> api.getIndexPriceKlines(pair.ifBlank { symbol }, baseInterval, limit, endTime = endTime)
            }
            return BinancePayloadParser.parseKlineArray(body.string())
        }

        suspend fun queryBybit(): List<Candle> {
            val mode = MarketEndpointMode.BYBIT_LINEAR
            val (baseInterval, _) = CandleAggregator.pickBaseInterval(timeframe, mode)
            val bybitInterval = BybitPayloadParser.toBybitInterval(baseInterval)
            val baseIntervalMs = BybitPayloadParser.fromBybitIntervalMs(bybitInterval)
            val limit = CandleAggregator.recommendedFetchLimit(timeframe, endpointMode = mode).coerceIn(1, 1000)
            val body = when (priceSource) {
                PriceSource.LAST -> bybitApi.getLastPriceKlines(
                    symbol = symbol,
                    interval = bybitInterval,
                    limit = limit,
                    end = endTime
                )
                PriceSource.MARK -> bybitApi.getMarkPriceKlines(
                    symbol = symbol,
                    interval = bybitInterval,
                    limit = limit,
                    end = endTime
                )
                PriceSource.INDEX -> bybitApi.getIndexPriceKlines(
                    symbol = symbol,
                    interval = bybitInterval,
                    limit = limit,
                    end = endTime
                )
            }
            return BybitPayloadParser.parseKlineResponse(body.string(), baseIntervalMs)
        }

        suspend fun queryVision(): List<Candle> {
            if (priceSource != PriceSource.LAST) {
                throw IllegalStateException(
                    "${priceSource.label} klines are unavailable in Spot Vision fallback mode."
                )
            }
            val mode = MarketEndpointMode.VISION_MIRROR
            val (baseInterval, _) = CandleAggregator.pickBaseInterval(timeframe, mode)
            val limit = CandleAggregator.recommendedFetchLimit(timeframe, endpointMode = mode)
            val raw = visionApi.getKlines(symbol, baseInterval, limit, endTime = endTime).string()
            return BinancePayloadParser.parseKlineArray(raw)
        }

        suspend fun tryBybitThenVision(): Pair<List<Candle>, MarketEndpointMode> {
            return try {
                val candles = queryBybit()
                switchEndpointMode(MarketEndpointMode.BYBIT_LINEAR)
                candles to MarketEndpointMode.BYBIT_LINEAR
            } catch (bybitErr: Throwable) {
                if (priceSource == PriceSource.LAST) {
                    val candles = queryVision()
                    switchEndpointMode(MarketEndpointMode.VISION_MIRROR)
                    candles to MarketEndpointMode.VISION_MIRROR
                } else {
                    throw bybitErr
                }
            }
        }

        return when (_endpointMode.value) {
            MarketEndpointMode.FUTURES_PROD -> {
                try {
                    queryFutures(prodApi, MarketEndpointMode.FUTURES_PROD) to MarketEndpointMode.FUTURES_PROD
                } catch (t: Throwable) {
                    if (isGeoBlocked(t)) {
                        try {
                            val candles = queryFutures(demoApi, MarketEndpointMode.FUTURES_DEMO)
                            switchEndpointMode(MarketEndpointMode.FUTURES_DEMO)
                            candles to MarketEndpointMode.FUTURES_DEMO
                        } catch (demoErr: Throwable) {
                            if (isGeoBlocked(demoErr)) {
                                tryBybitThenVision()
                            } else {
                                throw demoErr
                            }
                        }
                    } else {
                        throw t
                    }
                }
            }
            MarketEndpointMode.FUTURES_DEMO -> {
                try {
                    queryFutures(demoApi, MarketEndpointMode.FUTURES_DEMO) to MarketEndpointMode.FUTURES_DEMO
                } catch (demoErr: Throwable) {
                    if (isGeoBlocked(demoErr)) {
                        tryBybitThenVision()
                    } else {
                        throw demoErr
                    }
                }
            }
            MarketEndpointMode.BYBIT_LINEAR -> tryBybitThenVision()
            MarketEndpointMode.VISION_MIRROR -> queryVision() to MarketEndpointMode.VISION_MIRROR
        }
    }

    fun getCachedCandles(
        symbol: String,
        priceSource: PriceSource,
        timeframe: Timeframe
    ): List<Candle> {
        val mode = _endpointMode.value
        val cache = baseCandleCache[CandleCacheKey(symbol, priceSource, timeframe.id, mode)] ?: return emptyList()
        return synchronized(cache) {
            CandleAggregator.aggregate(cache.values.toList(), timeframe, mode)
        }
    }

    fun observeLiveKlineUpdates(
        symbol: String,
        pair: String,
        priceSource: PriceSource,
        timeframe: Timeframe
    ): Flow<Candle> = callbackFlow {
        val mode = _endpointMode.value
        if (mode == MarketEndpointMode.VISION_MIRROR && priceSource != PriceSource.LAST) {
            awaitClose { }
            return@callbackFlow
        }

        val cacheKey = CandleCacheKey(symbol, priceSource, timeframe.id, mode)
        val (baseInterval, baseMs) = CandleAggregator.pickBaseInterval(timeframe, mode)
        val lastKlineWsMillis = AtomicLong(0L)
        val lastTradeEmitMillis = AtomicLong(0L)

        fun aggregateIfNeeded(cache: LinkedHashMap<Long, Candle>, baseCandle: Candle): Candle {
            return if (CandleAggregator.isNativeSupported(timeframe, mode)) {
                baseCandle
            } else {
                val bucketMs = timeframe.durationMillis
                val bucketStart = (baseCandle.openTime / bucketMs) * bucketMs
                val inBucket = cache.values.filter {
                    it.openTime >= bucketStart && it.openTime < bucketStart + bucketMs
                }
                CandleAggregator.aggregateBucket(bucketStart, bucketMs, inBucket)
            }
        }

        fun applyTickToBaseCache(tickPrice: Double, serverNowMs: Long = currentExchangeTimeMillis()): Candle {
            val effectiveNow = if (serverNowMs > 1_500_000_000_000L) serverNowMs else currentExchangeTimeMillis()
            val cache = baseCandleCache.getOrPut(cacheKey) { LinkedHashMap() }
            return synchronized(cache) {
                val lastExisting = cache.values.lastOrNull()
                val rawBucketStart = (effectiveNow / baseMs) * baseMs
                val bucketStart = if (lastExisting != null) max(rawBucketStart, lastExisting.openTime) else rawBucketStart
                val bucketClose = bucketStart + baseMs - 1L
                val existing = cache[bucketStart]
                val updatedBase = if (existing != null) {
                    existing.copy(
                        high = max(existing.high, tickPrice),
                        low = min(existing.low, tickPrice),
                        close = tickPrice,
                        isClosed = false
                    )
                } else {
                    if (lastExisting != null && !lastExisting.isClosed && lastExisting.openTime < bucketStart) {
                        cache[lastExisting.openTime] = lastExisting.copy(isClosed = true)
                    }
                    Candle(
                        openTime = bucketStart,
                        closeTime = bucketClose,
                        open = tickPrice,
                        high = tickPrice,
                        low = tickPrice,
                        close = tickPrice,
                        volume = 0.0,
                        isClosed = false
                    )
                }
                cache[bucketStart] = updatedBase
                aggregateIfNeeded(cache, updatedBase)
            }
        }

        val topics: List<String> = when (mode) {
            MarketEndpointMode.BYBIT_LINEAR -> {
                val bybitInterval = BybitPayloadParser.toBybitInterval(baseInterval)
                buildList {
                    add(BinanceSocketManager.bybitKlineTopic(symbol, bybitInterval))
                    add(BinanceSocketManager.bybitTickerTopic(symbol))
                    if (priceSource == PriceSource.LAST) {
                        add(BinanceSocketManager.bybitTradeTopic(symbol))
                    }
                }
            }
            else -> {
                val klineStream = BinanceSocketManager.klineStreamName(
                    symbol = symbol,
                    pair = pair.ifBlank { symbol },
                    priceSource = priceSource,
                    nativeInterval = baseInterval
                )
                buildList {
                    add(klineStream)
                    if (priceSource == PriceSource.LAST) {
                        add(BinanceSocketManager.tradeStreamName(symbol, mode == MarketEndpointMode.VISION_MIRROR))
                    }
                }
            }
        }
        val targetKlineTopic = topics.first()
        socketManager.subscribe(topics)

        var lastEmittedCandle: Candle? = null
        val emitLock = Any()
        fun emitIfChanged(candle: Candle) {
            val shouldEmit = synchronized(emitLock) {
                if (lastEmittedCandle == candle) {
                    false
                } else {
                    lastEmittedCandle = candle
                    true
                }
            }
            if (shouldEmit) {
                trySend(candle)
            }
        }

        val klineJob = launch(Dispatchers.IO) {
            socketManager.klineUpdates.collect { update ->
                if (!update.stream.equals(targetKlineTopic, ignoreCase = true)) return@collect
                if (mode == MarketEndpointMode.BYBIT_LINEAR && priceSource != PriceSource.LAST) return@collect
                lastKlineWsMillis.set(System.currentTimeMillis())
                val baseCandle = update.candle
                val cache = baseCandleCache.getOrPut(cacheKey) { LinkedHashMap() }
                val emittedCandle = synchronized(cache) {
                    val existingLast = cache.values.lastOrNull()
                    if (existingLast != null && existingLast.openTime < baseCandle.openTime && !existingLast.isClosed) {
                        cache[existingLast.openTime] = existingLast.copy(isClosed = true)
                    }
                    cache[baseCandle.openTime] = baseCandle
                    aggregateIfNeeded(cache, baseCandle)
                }
                emitIfChanged(emittedCandle)
            }
        }

        val tradeJob = launch(Dispatchers.IO) {
            if (priceSource != PriceSource.LAST) return@launch
            socketManager.tradeUpdates.collect { trade ->
                if (!trade.symbol.equals(symbol, ignoreCase = true)) return@collect
                lastKlineWsMillis.set(System.currentTimeMillis())
                val updated = applyTickToBaseCache(trade.price, trade.timestampMs)
                val nowLocal = System.currentTimeMillis()
                val prevEmit = lastTradeEmitMillis.get()
                if (nowLocal - prevEmit >= 65L) {
                    lastTradeEmitMillis.set(nowLocal)
                    emitIfChanged(updated)
                }
            }
        }

        val tickerCandleJob = launch(Dispatchers.IO) {
            if (priceSource == PriceSource.LAST) {
                socketManager.tickerUpdates.collect { updates ->
                    val tick = updates.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: return@collect
                    val price = tick.lastPrice ?: return@collect
                    if (price <= 0.0) return@collect
                    emitIfChanged(applyTickToBaseCache(price))
                }
            } else {
                socketManager.markPriceUpdates.collect { updates ->
                    val tick = updates.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: return@collect
                    val price = if (priceSource == PriceSource.MARK) tick.markPrice else tick.indexPrice
                    if (price == null || price <= 0.0) return@collect
                    if (mode == MarketEndpointMode.BYBIT_LINEAR ||
                        System.currentTimeMillis() - lastKlineWsMillis.get() > 900L
                    ) {
                        emitIfChanged(applyTickToBaseCache(price))
                    }
                }
            }
        }

        val restSyncFallbackJob = launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L)
                val staleWs = System.currentTimeMillis() - lastKlineWsMillis.get() > 2500L
                if (staleWs || socketManager.connectionState.value != SocketConnectionState.CONNECTED) {
                    runCatching {
                        val (parsed, resolvedMode) = fetchKlinesWithFallback(
                            symbol = symbol,
                            pair = pair,
                            priceSource = priceSource,
                            timeframe = timeframe,
                            endTime = null
                        )
                        if (parsed.isNotEmpty() && resolvedMode == mode) {
                            val cache = baseCandleCache.getOrPut(cacheKey) { LinkedHashMap() }
                            val toEmit = synchronized(cache) {
                                val prevLastTime = cache.values.lastOrNull()?.openTime ?: 0L
                                parsed.takeLast(5).forEach { c -> cache[c.openTime] = c }
                                val list = mutableListOf<Candle>()
                                if (parsed.size >= 2 && parsed.last().openTime > prevLastTime) {
                                    list.add(aggregateIfNeeded(cache, parsed[parsed.lastIndex - 1]))
                                }
                                cache.values.lastOrNull()?.let { tail ->
                                    list.add(aggregateIfNeeded(cache, tail))
                                }
                                list
                            }
                            for (c in toEmit) {
                                emitIfChanged(c)
                            }
                        }
                    }
                }
            }
        }

        awaitClose {
            klineJob.cancel()
            tradeJob.cancel()
            tickerCandleJob.cancel()
            restSyncFallbackJob.cancel()
            socketManager.unsubscribe(topics)
        }
    }

    fun reconnectSocket() {
        socketManager.forceReconnect()
    }
}
