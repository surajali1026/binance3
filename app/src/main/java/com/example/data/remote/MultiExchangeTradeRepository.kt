package com.example.data.remote

import com.example.domain.indicator.ExchangeCandleFlow
import com.example.domain.indicator.ExchangeTradeTick
import com.example.domain.indicator.ExchangeVolumeSnapshot
import com.example.domain.indicator.SupportedExchange
import com.example.domain.model.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Multi-exchange public trade & volume aggregator (NO API keys required).
 *
 * Connects ONLY to the user's checked [SupportedExchange] venues. Uses a dedicated
 * low-timeout OkHttpClient derived from [baseClient] with LRU caching and throttled
 * emissions so mobile charts remain smooth across all timeframes (1m..1D & custom).
 */
class MultiExchangeTradeRepository(
    baseClient: OkHttpClient
) {
    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private data class CacheEntry(
        val trades: List<ExchangeTradeTick>,
        val flows: List<ExchangeCandleFlow>,
        val fetchedAtMs: Long,
        val connected: Boolean
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        private const val CACHE_TTL_MS = 10_000L
        private const val POLL_INTERVAL_MS = 12_000L
        private const val WS_EMIT_THROTTLE_MS = 900L
        private const val MAX_TICKS_PER_EXCHANGE = 650
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Observes real public trade & kline volume strictly for [selectedExchanges].
     * Cancelling the returned Flow immediately closes any open exchange WebSockets.
     */
    fun observeExchangeVolumes(
        symbol: String,
        timeframe: Timeframe,
        selectedExchanges: Set<SupportedExchange>
    ): Flow<ExchangeVolumeSnapshot> = channelFlow {
        if (symbol.isBlank() || selectedExchanges.isEmpty()) {
            send(ExchangeVolumeSnapshot(symbol = symbol, timeframeId = timeframe.id))
            return@channelFlow
        }

        val cleanSymbol = symbol.uppercase()
        val liveTicks = ConcurrentHashMap<SupportedExchange, ArrayList<ExchangeTradeTick>>()
        val liveFlows = ConcurrentHashMap<SupportedExchange, List<ExchangeCandleFlow>>()
        val connectedSet = ConcurrentHashMap.newKeySet<SupportedExchange>()
        val openSockets = ArrayList<WebSocket>()
        val stateLock = Any()

        fun buildSnapshot(): ExchangeVolumeSnapshot {
            val tradesCopy = LinkedHashMap<SupportedExchange, List<ExchangeTradeTick>>()
            val flowsCopy = LinkedHashMap<SupportedExchange, List<ExchangeCandleFlow>>()
            synchronized(stateLock) {
                for (ex in selectedExchanges) {
                    liveTicks[ex]?.let { if (it.isNotEmpty()) tradesCopy[ex] = it.toList() }
                    liveFlows[ex]?.let { if (it.isNotEmpty()) flowsCopy[ex] = it }
                }
            }
            return ExchangeVolumeSnapshot(
                symbol = cleanSymbol,
                timeframeId = timeframe.id,
                tradesByExchange = tradesCopy,
                flowsByExchange = flowsCopy,
                connectedExchanges = connectedSet.intersect(selectedExchanges),
                updatedAtMillis = System.currentTimeMillis()
            )
        }

        fun appendLiveTick(tick: ExchangeTradeTick) {
            if (tick.price <= 0.0 || tick.quoteQty <= 0.0) return
            synchronized(stateLock) {
                val list = liveTicks.getOrPut(tick.exchange) { ArrayList(128) }
                list.add(tick)
                if (list.size > MAX_TICKS_PER_EXCHANGE) {
                    val drop = list.size - MAX_TICKS_PER_EXCHANGE
                    list.subList(0, drop).clear()
                }
            }
            connectedSet.add(tick.exchange)
        }

        // 1) Immediately seed from valid cache so timeframe switches feel instant
        val now = System.currentTimeMillis()
        var hadCached = false
        for (ex in selectedExchanges) {
            val entry = cache[cacheKey(cleanSymbol, timeframe.id, ex)]
            if (entry != null && now - entry.fetchedAtMs < 45_000L) {
                synchronized(stateLock) {
                    if (entry.trades.isNotEmpty()) liveTicks[ex] = ArrayList(entry.trades)
                    if (entry.flows.isNotEmpty()) liveFlows[ex] = entry.flows
                }
                if (entry.connected) connectedSet.add(ex)
                hadCached = true
            }
        }
        if (hadCached) {
            trySend(buildSnapshot())
        }

        // 2) Open lightweight public WebSockets ONLY for checked exchanges that support clean public streams
        val wsEmitJob = launch(Dispatchers.Default) {
            var lastEmittedCount = -1
            while (isActive) {
                delay(WS_EMIT_THROTTLE_MS)
                val count = synchronized(stateLock) {
                    liveTicks.values.sumOf { it.size }
                }
                if (count > 0 && count != lastEmittedCount) {
                    lastEmittedCount = count
                    trySend(buildSnapshot())
                }
            }
        }

        openCheckedWebSockets(
            symbol = cleanSymbol,
            selectedExchanges = selectedExchanges,
            onTick = ::appendLiveTick,
            sinkSockets = openSockets
        )

        // 3) Parallel throttled public REST fetch across ONLY checked exchanges (recent trades + klines)
        val pollJob = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val fetched = coroutineScope {
                        selectedExchanges.map { ex ->
                            async {
                                fetchExchangeData(cleanSymbol, timeframe, ex)
                            }
                        }.awaitAll()
                    }

                    val ts = System.currentTimeMillis()
                    for ((ex, result) in fetched) {
                        if (result.connected) {
                            connectedSet.add(ex)
                        }
                        synchronized(stateLock) {
                            if (result.trades.isNotEmpty()) {
                                val existing = liveTicks[ex].orEmpty()
                                val merged = mergeTicks(existing, result.trades)
                                liveTicks[ex] = ArrayList(merged)
                            }
                            if (result.flows.isNotEmpty()) {
                                liveFlows[ex] = result.flows
                            }
                        }
                        cache[cacheKey(cleanSymbol, timeframe.id, ex)] = CacheEntry(
                            trades = result.trades,
                            flows = result.flows,
                            fetchedAtMs = ts,
                            connected = result.connected
                        )
                    }
                    trySend(buildSnapshot())
                    delay(POLL_INTERVAL_MS)
                }
            } catch (_: CancellationException) {
                // Normal lifecycle cancellation
            }
        }

        try {
            pollJob.join()
        } finally {
            wsEmitJob.cancel()
            pollJob.cancel()
            openSockets.forEach { ws -> runCatching { ws.close(1000, "closed") } }
            openSockets.clear()
        }
    }

    private fun cacheKey(symbol: String, tfId: String, ex: SupportedExchange): String =
        "${symbol}_${tfId}_${ex.id}"

    private fun mergeTicks(
        existing: List<ExchangeTradeTick>,
        incoming: List<ExchangeTradeTick>
    ): List<ExchangeTradeTick> {
        if (existing.isEmpty()) return incoming.takeLast(MAX_TICKS_PER_EXCHANGE)
        if (incoming.isEmpty()) return existing.takeLast(MAX_TICKS_PER_EXCHANGE)
        val map = LinkedHashMap<String, ExchangeTradeTick>(existing.size + incoming.size)
        for (t in existing) {
            map["${t.timestampMillis}_${t.price}_${t.isBuyAggressor}_${t.baseQty}"] = t
        }
        for (t in incoming) {
            map["${t.timestampMillis}_${t.price}_${t.isBuyAggressor}_${t.baseQty}"] = t
        }
        return map.values.sortedBy { it.timestampMillis }.takeLast(MAX_TICKS_PER_EXCHANGE)
    }

    private fun extractBaseAsset(symbol: String): String {
        val clean = symbol.uppercase()
            .removeSuffix("USDT")
            .removeSuffix("USDC")
            .removeSuffix("BUSD")
            .removeSuffix("USD")
            .ifBlank { "BTC" }
        return clean
    }

    private fun openCheckedWebSockets(
        symbol: String,
        selectedExchanges: Set<SupportedExchange>,
        onTick: (ExchangeTradeTick) -> Unit,
        sinkSockets: MutableList<WebSocket>
    ) {
        val base = extractBaseAsset(symbol)

        if (selectedExchanges.contains(SupportedExchange.BINANCE)) {
            val lower = symbol.lowercase()
            val req = Request.Builder()
                .url("wss://fstream.binance.com/ws/${lower}@aggTrade")
                .build()
            sinkSockets.add(httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        val p = o.optString("p").toDoubleOrNull() ?: return
                        val q = o.optString("q").toDoubleOrNull() ?: return
                        val isBuyerMaker = o.optBoolean("m", false)
                        val ts = o.optLong("T", System.currentTimeMillis())
                        onTick(
                            ExchangeTradeTick(
                                exchange = SupportedExchange.BINANCE,
                                price = p,
                                baseQty = q,
                                quoteQty = p * q,
                                isBuyAggressor = !isBuyerMaker,
                                timestampMillis = ts
                            )
                        )
                    }
                }
            }))
        }

        if (selectedExchanges.contains(SupportedExchange.BYBIT)) {
            val req = Request.Builder()
                .url("wss://stream.bybit.com/v5/public/linear")
                .build()
            sinkSockets.add(httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"op":"subscribe","args":["publicTrade.$symbol"]}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        val arr = o.optJSONArray("data") ?: return
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val p = item.optString("p").toDoubleOrNull() ?: continue
                            val q = item.optString("v").toDoubleOrNull() ?: continue
                            val side = item.optString("S", "")
                            val ts = item.optLong("T", System.currentTimeMillis())
                            onTick(
                                ExchangeTradeTick(
                                    exchange = SupportedExchange.BYBIT,
                                    price = p,
                                    baseQty = q,
                                    quoteQty = p * q,
                                    isBuyAggressor = side.equals("Buy", ignoreCase = true),
                                    timestampMillis = ts
                                )
                            )
                        }
                    }
                }
            }))
        }

        if (selectedExchanges.contains(SupportedExchange.OKX)) {
            val instId = "$base-USDT-SWAP"
            val req = Request.Builder()
                .url("wss://ws.okx.com:8443/ws/v5/public")
                .build()
            sinkSockets.add(httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"op":"subscribe","args":[{"channel":"trades","instId":"$instId"}]}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        val arr = o.optJSONArray("data") ?: return
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val p = item.optString("px").toDoubleOrNull() ?: continue
                            val q = item.optString("sz").toDoubleOrNull() ?: continue
                            val side = item.optString("side", "")
                            val ts = item.optString("ts").toLongOrNull() ?: System.currentTimeMillis()
                            onTick(
                                ExchangeTradeTick(
                                    exchange = SupportedExchange.OKX,
                                    price = p,
                                    baseQty = q,
                                    quoteQty = p * q,
                                    isBuyAggressor = side.equals("buy", ignoreCase = true),
                                    timestampMillis = ts
                                )
                            )
                        }
                    }
                }
            }))
        }

        if (selectedExchanges.contains(SupportedExchange.HYPERLIQUID)) {
            val req = Request.Builder()
                .url("wss://api.hyperliquid.xyz/ws")
                .build()
            sinkSockets.add(httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"method":"subscribe","subscription":{"type":"trades","coin":"$base"}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        if (o.optString("channel") != "trades") return
                        val arr = o.optJSONArray("data") ?: return
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val p = item.optString("px").toDoubleOrNull() ?: continue
                            val q = item.optString("sz").toDoubleOrNull() ?: continue
                            val side = item.optString("side", "")
                            val ts = item.optLong("time", System.currentTimeMillis())
                            onTick(
                                ExchangeTradeTick(
                                    exchange = SupportedExchange.HYPERLIQUID,
                                    price = p,
                                    baseQty = q,
                                    quoteQty = p * q,
                                    isBuyAggressor = side.equals("B", ignoreCase = true),
                                    timestampMillis = ts
                                )
                            )
                        }
                    }
                }
            }))
        }
    }

    private data class ExchangeFetchResult(
        val trades: List<ExchangeTradeTick> = emptyList(),
        val flows: List<ExchangeCandleFlow> = emptyList(),
        val connected: Boolean = false
    )

    private suspend fun fetchExchangeData(
        symbol: String,
        timeframe: Timeframe,
        exchange: SupportedExchange
    ): Pair<SupportedExchange, ExchangeFetchResult> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cache[cacheKey(symbol, timeframe.id, exchange)]
        if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS && cached.connected) {
            return@withContext exchange to ExchangeFetchResult(cached.trades, cached.flows, true)
        }

        val base = extractBaseAsset(symbol)
        val result = runCatching {
            when (exchange) {
                SupportedExchange.BINANCE -> fetchBinance(symbol, timeframe)
                SupportedExchange.BYBIT -> fetchBybit(symbol, timeframe)
                SupportedExchange.OKX -> fetchOkx(base, timeframe)
                SupportedExchange.BITGET -> fetchBitget(symbol, timeframe)
                SupportedExchange.COINBASE -> fetchCoinbase(base)
                SupportedExchange.KRAKEN -> fetchKraken(base)
                SupportedExchange.GATEIO -> fetchGateIo(base, timeframe)
                SupportedExchange.KUCOIN -> fetchKuCoin(base)
                SupportedExchange.HYPERLIQUID -> fetchHyperliquid(base, timeframe)
                SupportedExchange.DYDX -> fetchDydx(base, timeframe)
                SupportedExchange.VERTEX -> fetchVertex(base)
                SupportedExchange.DRIFT -> fetchDrift(base)
                SupportedExchange.PARADEX -> fetchParadex(base)
                SupportedExchange.GMX -> fetchGmx(base)
            }
        }.getOrDefault(ExchangeFetchResult())

        exchange to result
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "BinancePro-Android")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun httpPostJson(url: String, jsonBody: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "BinancePro-Android")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    private fun mapBinanceInterval(tf: Timeframe): String = when {
        tf.durationMillis <= 60_000L -> "1m"
        tf.durationMillis <= 3 * 60_000L -> "3m"
        tf.durationMillis <= 5 * 60_000L -> "5m"
        tf.durationMillis <= 15 * 60_000L -> "15m"
        tf.durationMillis <= 30 * 60_000L -> "30m"
        tf.durationMillis <= 60 * 60_000L -> "1h"
        tf.durationMillis <= 4 * 60 * 60_000L -> "4h"
        else -> "1d"
    }

    private fun fetchBinance(symbol: String, tf: Timeframe): ExchangeFetchResult {
        val interval = mapBinanceInterval(tf)
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        // Try Binance Futures first, fallback to Binance Vision spot mirror
        val tradesBody = httpGet("https://fapi.binance.com/fapi/v1/aggTrades?symbol=$symbol&limit=300")
            ?: httpGet("https://data-api.binance.vision/api/v3/aggTrades?symbol=$symbol&limit=300")
        if (!tradesBody.isNullOrBlank()) {
            val arr = JSONArray(tradesBody)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p").toDoubleOrNull() ?: continue
                val q = o.optString("q").toDoubleOrNull() ?: continue
                val isBuyerMaker = o.optBoolean("m", false)
                val ts = o.optLong("T", 0L)
                if (ts > 0L) {
                    trades.add(ExchangeTradeTick(SupportedExchange.BINANCE, p, q, p * q, !isBuyerMaker, ts))
                }
            }
        }

        val klinesBody = httpGet("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=40")
            ?: httpGet("https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=$interval&limit=40")
        if (!klinesBody.isNullOrBlank()) {
            val arr = JSONArray(klinesBody)
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 11) continue
                val openT = row.optLong(0)
                val open = row.optString(1).toDoubleOrNull() ?: continue
                val high = row.optString(2).toDoubleOrNull() ?: continue
                val low = row.optString(3).toDoubleOrNull() ?: continue
                val close = row.optString(4).toDoubleOrNull() ?: continue
                val closeT = row.optLong(6)
                val totalQuote = row.optString(7).toDoubleOrNull() ?: 0.0
                val takerBuyQuote = row.optString(10).toDoubleOrNull() ?: (totalQuote * 0.5)
                val takerSellQuote = (totalQuote - takerBuyQuote).coerceAtLeast(0.0)
                flows.add(
                    ExchangeCandleFlow(
                        exchange = SupportedExchange.BINANCE,
                        openTimeMillis = openT,
                        closeTimeMillis = closeT,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        buyQuoteVolume = takerBuyQuote,
                        sellQuoteVolume = takerSellQuote
                    )
                )
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchBybit(symbol: String, tf: Timeframe): ExchangeFetchResult {
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        val tradesBody = httpGet("https://api.bybit.com/v5/market/recent-trade?category=linear&symbol=$symbol&limit=250")
        if (!tradesBody.isNullOrBlank()) {
            val list = JSONObject(tradesBody).optJSONObject("result")?.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val p = o.optString("price").toDoubleOrNull() ?: continue
                    val q = o.optString("size").toDoubleOrNull() ?: continue
                    val side = o.optString("side", "")
                    val ts = o.optString("time").toLongOrNull() ?: continue
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.BYBIT,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("Buy", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }

        val bybitInterval = when {
            tf.durationMillis <= 60_000L -> "1"
            tf.durationMillis <= 3 * 60_000L -> "3"
            tf.durationMillis <= 5 * 60_000L -> "5"
            tf.durationMillis <= 15 * 60_000L -> "15"
            tf.durationMillis <= 30 * 60_000L -> "30"
            tf.durationMillis <= 60 * 60_000L -> "60"
            tf.durationMillis <= 4 * 60 * 60_000L -> "240"
            else -> "D"
        }
        val klineBody = httpGet("https://api.bybit.com/v5/market/kline?category=linear&symbol=$symbol&interval=$bybitInterval&limit=40")
        if (!klineBody.isNullOrBlank()) {
            val list = JSONObject(klineBody).optJSONObject("result")?.optJSONArray("list")
            if (list != null) {
                val durMs = tf.durationMillis.coerceAtLeast(60_000L)
                for (i in 0 until list.length()) {
                    val row = list.optJSONArray(i) ?: continue
                    if (row.length() < 7) continue
                    val openT = row.optString(0).toLongOrNull() ?: continue
                    val open = row.optString(1).toDoubleOrNull() ?: continue
                    val high = row.optString(2).toDoubleOrNull() ?: continue
                    val low = row.optString(3).toDoubleOrNull() ?: continue
                    val close = row.optString(4).toDoubleOrNull() ?: continue
                    val turnover = row.optString(6).toDoubleOrNull() ?: 0.0
                    val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, turnover)
                    flows.add(
                        ExchangeCandleFlow(
                            exchange = SupportedExchange.BYBIT,
                            openTimeMillis = openT,
                            closeTimeMillis = openT + durMs - 1L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            buyQuoteVolume = buyVol,
                            sellQuoteVolume = sellVol
                        )
                    )
                }
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchOkx(base: String, tf: Timeframe): ExchangeFetchResult {
        val instId = "$base-USDT-SWAP"
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        val tradesBody = httpGet("https://www.okx.com/api/v5/market/trades?instId=$instId&limit=250")
        if (!tradesBody.isNullOrBlank()) {
            val data = JSONObject(tradesBody).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val p = o.optString("px").toDoubleOrNull() ?: continue
                    val q = o.optString("sz").toDoubleOrNull() ?: continue
                    val side = o.optString("side", "")
                    val ts = o.optString("ts").toLongOrNull() ?: continue
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.OKX,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("buy", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }

        val okxBar = when {
            tf.durationMillis <= 60_000L -> "1m"
            tf.durationMillis <= 3 * 60_000L -> "3m"
            tf.durationMillis <= 5 * 60_000L -> "5m"
            tf.durationMillis <= 15 * 60_000L -> "15m"
            tf.durationMillis <= 30 * 60_000L -> "30m"
            tf.durationMillis <= 60 * 60_000L -> "1H"
            tf.durationMillis <= 4 * 60 * 60_000L -> "4H"
            else -> "1D"
        }
        val klineBody = httpGet("https://www.okx.com/api/v5/market/candles?instId=$instId&bar=$okxBar&limit=40")
        if (!klineBody.isNullOrBlank()) {
            val data = JSONObject(klineBody).optJSONArray("data")
            if (data != null) {
                val durMs = tf.durationMillis.coerceAtLeast(60_000L)
                for (i in 0 until data.length()) {
                    val row = data.optJSONArray(i) ?: continue
                    if (row.length() < 8) continue
                    val openT = row.optString(0).toLongOrNull() ?: continue
                    val open = row.optString(1).toDoubleOrNull() ?: continue
                    val high = row.optString(2).toDoubleOrNull() ?: continue
                    val low = row.optString(3).toDoubleOrNull() ?: continue
                    val close = row.optString(4).toDoubleOrNull() ?: continue
                    val quoteVol = row.optString(7).toDoubleOrNull() ?: 0.0
                    val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, quoteVol)
                    flows.add(
                        ExchangeCandleFlow(
                            exchange = SupportedExchange.OKX,
                            openTimeMillis = openT,
                            closeTimeMillis = openT + durMs - 1L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            buyQuoteVolume = buyVol,
                            sellQuoteVolume = sellVol
                        )
                    )
                }
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchBitget(symbol: String, tf: Timeframe): ExchangeFetchResult {
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        val body = httpGet("https://api.bitget.com/api/v2/mix/market/fills?symbol=$symbol&productType=USDT-FUTURES&limit=100")
        if (!body.isNullOrBlank()) {
            val data = JSONObject(body).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val p = o.optString("price").toDoubleOrNull() ?: continue
                    val q = o.optString("size").toDoubleOrNull() ?: continue
                    val side = o.optString("side", "")
                    val ts = o.optString("ts").toLongOrNull() ?: continue
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.BITGET,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("buy", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }

        val gran = when {
            tf.durationMillis <= 60_000L -> "1m"
            tf.durationMillis <= 3 * 60_000L -> "3m"
            tf.durationMillis <= 5 * 60_000L -> "5m"
            tf.durationMillis <= 15 * 60_000L -> "15m"
            tf.durationMillis <= 30 * 60_000L -> "30m"
            tf.durationMillis <= 60 * 60_000L -> "1H"
            tf.durationMillis <= 4 * 60 * 60_000L -> "4H"
            else -> "1D"
        }
        val klineBody = httpGet("https://api.bitget.com/api/v2/mix/market/candles?symbol=$symbol&productType=USDT-FUTURES&granularity=$gran&limit=40")
        if (!klineBody.isNullOrBlank()) {
            val data = JSONObject(klineBody).optJSONArray("data")
            if (data != null) {
                val durMs = tf.durationMillis.coerceAtLeast(60_000L)
                for (i in 0 until data.length()) {
                    val row = data.optJSONArray(i) ?: continue
                    if (row.length() < 7) continue
                    val openT = row.optString(0).toLongOrNull() ?: continue
                    val open = row.optString(1).toDoubleOrNull() ?: continue
                    val high = row.optString(2).toDoubleOrNull() ?: continue
                    val low = row.optString(3).toDoubleOrNull() ?: continue
                    val close = row.optString(4).toDoubleOrNull() ?: continue
                    val quoteVol = row.optString(6).toDoubleOrNull() ?: 0.0
                    val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, quoteVol)
                    flows.add(
                        ExchangeCandleFlow(
                            exchange = SupportedExchange.BITGET,
                            openTimeMillis = openT,
                            closeTimeMillis = openT + durMs - 1L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            buyQuoteVolume = buyVol,
                            sellQuoteVolume = sellVol
                        )
                    )
                }
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchCoinbase(base: String): ExchangeFetchResult {
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://api.exchange.coinbase.com/products/$base-USD/trades?limit=100")
        if (!body.isNullOrBlank()) {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("price").toDoubleOrNull() ?: continue
                val q = o.optString("size").toDoubleOrNull() ?: continue
                // Coinbase "side" indicates maker side: "sell" means market buyer lifted ask
                val side = o.optString("side", "")
                val ts = parseIsoTimestampMillis(o.optString("time", "")) ?: System.currentTimeMillis()
                trades.add(
                    ExchangeTradeTick(
                        exchange = SupportedExchange.COINBASE,
                        price = p,
                        baseQty = q,
                        quoteQty = p * q,
                        isBuyAggressor = side.equals("sell", ignoreCase = true),
                        timestampMillis = ts
                    )
                )
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchKraken(base: String): ExchangeFetchResult {
        val krakenBase = if (base == "BTC") "XBT" else base
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://futures.kraken.com/derivatives/api/v3/history?symbol=PF_${krakenBase}USD")
        if (!body.isNullOrBlank()) {
            val arr = JSONObject(body).optJSONArray("history")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optDouble("price", Double.NaN)
                    val q = o.optDouble("size", Double.NaN)
                    if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q <= 0.0) continue
                    val side = o.optString("side", "")
                    val ts = parseIsoTimestampMillis(o.optString("time", "")) ?: System.currentTimeMillis()
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.KRAKEN,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("buy", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchGateIo(base: String, tf: Timeframe): ExchangeFetchResult {
        val contract = "${base}_USDT"
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()
        val body = httpGet("https://api.gateio.ws/api/v4/futures/usdt/trades?contract=$contract&limit=150")
        if (!body.isNullOrBlank()) {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("price").toDoubleOrNull() ?: continue
                val size = o.optDouble("size", 0.0)
                if (size == 0.0) continue
                val ts = o.optLong("create_time_ms", o.optLong("create_time", 0L) * 1000L)
                if (ts <= 0L) continue
                val absQty = abs(size)
                trades.add(
                    ExchangeTradeTick(
                        exchange = SupportedExchange.GATEIO,
                        price = p,
                        baseQty = absQty,
                        quoteQty = p * absQty,
                        isBuyAggressor = size > 0.0,
                        timestampMillis = ts
                    )
                )
            }
        }
        val gateInterval = mapBinanceInterval(tf)
        val klineBody = httpGet("https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=$contract&interval=$gateInterval&limit=35")
        if (!klineBody.isNullOrBlank()) {
            val arr = JSONArray(klineBody)
            val durMs = tf.durationMillis.coerceAtLeast(60_000L)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val openT = o.optLong("t", 0L) * 1000L
                val open = o.optString("o").toDoubleOrNull() ?: continue
                val high = o.optString("h").toDoubleOrNull() ?: continue
                val low = o.optString("l").toDoubleOrNull() ?: continue
                val close = o.optString("c").toDoubleOrNull() ?: continue
                val quoteVol = o.optString("sum").toDoubleOrNull() ?: 0.0
                val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, quoteVol)
                flows.add(
                    ExchangeCandleFlow(
                        exchange = SupportedExchange.GATEIO,
                        openTimeMillis = openT,
                        closeTimeMillis = openT + durMs - 1L,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        buyQuoteVolume = buyVol,
                        sellQuoteVolume = sellVol
                    )
                )
            }
        }
        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchKuCoin(base: String): ExchangeFetchResult {
        val kcBase = if (base == "BTC") "XBT" else base
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://api-futures.kucoin.com/api/v1/trade/history?symbol=${kcBase}USDTM")
        if (!body.isNullOrBlank()) {
            val data = JSONObject(body).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val p = o.optString("price").toDoubleOrNull() ?: continue
                    val q = o.optDouble("size", 0.0)
                    if (q <= 0.0) continue
                    val side = o.optString("side", "")
                    val rawTs = o.optLong("ts", 0L)
                    val ts = if (rawTs > 1_000_000_000_000_000L) rawTs / 1_000_000L else rawTs
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.KUCOIN,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("buy", ignoreCase = true),
                            timestampMillis = if (ts > 0L) ts else System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchHyperliquid(base: String, tf: Timeframe): ExchangeFetchResult {
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        val tradesBody = httpPostJson(
            "https://api.hyperliquid.xyz/info",
            """{"type":"recentTrades","coin":"$base"}"""
        )
        if (!tradesBody.isNullOrBlank()) {
            val arr = JSONArray(tradesBody)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("px").toDoubleOrNull() ?: continue
                val q = o.optString("sz").toDoubleOrNull() ?: continue
                val side = o.optString("side", "")
                val ts = o.optLong("time", 0L)
                if (ts > 0L) {
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.HYPERLIQUID,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("B", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }

        val interval = mapBinanceInterval(tf)
        val endTime = System.currentTimeMillis()
        val startTime = endTime - tf.durationMillis.coerceAtLeast(60_000L) * 40L
        val candleBody = httpPostJson(
            "https://api.hyperliquid.xyz/info",
            """{"type":"candleSnapshot","req":{"coin":"$base","interval":"$interval","startTime":$startTime,"endTime":$endTime}}"""
        )
        if (!candleBody.isNullOrBlank()) {
            val arr = JSONArray(candleBody)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val openT = o.optLong("t", 0L)
                val closeT = o.optLong("T", openT + tf.durationMillis - 1L)
                val open = o.optString("o").toDoubleOrNull() ?: continue
                val high = o.optString("h").toDoubleOrNull() ?: continue
                val low = o.optString("l").toDoubleOrNull() ?: continue
                val close = o.optString("c").toDoubleOrNull() ?: continue
                val baseVol = o.optString("v").toDoubleOrNull() ?: 0.0
                val quoteVol = baseVol * close
                val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, quoteVol)
                flows.add(
                    ExchangeCandleFlow(
                        exchange = SupportedExchange.HYPERLIQUID,
                        openTimeMillis = openT,
                        closeTimeMillis = closeT,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        buyQuoteVolume = buyVol,
                        sellQuoteVolume = sellVol
                    )
                )
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchDydx(base: String, tf: Timeframe): ExchangeFetchResult {
        val market = "$base-USD"
        val trades = ArrayList<ExchangeTradeTick>()
        val flows = ArrayList<ExchangeCandleFlow>()

        val body = httpGet("https://indexer.dydx.trade/v4/trades/perpetualMarket/$market?limit=120")
        if (!body.isNullOrBlank()) {
            val arr = JSONObject(body).optJSONArray("trades")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("price").toDoubleOrNull() ?: continue
                    val q = o.optString("size").toDoubleOrNull() ?: continue
                    val side = o.optString("side", "")
                    val ts = parseIsoTimestampMillis(o.optString("createdAt", "")) ?: System.currentTimeMillis()
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.DYDX,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("BUY", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }

        val resolution = when {
            tf.durationMillis <= 60_000L -> "1MIN"
            tf.durationMillis <= 5 * 60_000L -> "5MINS"
            tf.durationMillis <= 15 * 60_000L -> "15MINS"
            tf.durationMillis <= 30 * 60_000L -> "30MINS"
            tf.durationMillis <= 60 * 60_000L -> "1HOUR"
            tf.durationMillis <= 4 * 60 * 60_000L -> "4HOURS"
            else -> "1DAY"
        }
        val candleBody = httpGet("https://indexer.dydx.trade/v4/candles/perpetualMarkets/$market?resolution=$resolution&limit=35")
        if (!candleBody.isNullOrBlank()) {
            val arr = JSONObject(candleBody).optJSONArray("candles")
            if (arr != null) {
                val durMs = tf.durationMillis.coerceAtLeast(60_000L)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val openT = parseIsoTimestampMillis(o.optString("startedAt", "")) ?: continue
                    val open = o.optString("open").toDoubleOrNull() ?: continue
                    val high = o.optString("high").toDoubleOrNull() ?: continue
                    val low = o.optString("low").toDoubleOrNull() ?: continue
                    val close = o.optString("close").toDoubleOrNull() ?: continue
                    val quoteVol = o.optString("usdVolume").toDoubleOrNull() ?: 0.0
                    val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, quoteVol)
                    flows.add(
                        ExchangeCandleFlow(
                            exchange = SupportedExchange.DYDX,
                            openTimeMillis = openT,
                            closeTimeMillis = openT + durMs - 1L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            buyQuoteVolume = buyVol,
                            sellQuoteVolume = sellVol
                        )
                    )
                }
            }
        }

        return ExchangeFetchResult(trades, flows, trades.isNotEmpty() || flows.isNotEmpty())
    }

    private fun fetchVertex(base: String): ExchangeFetchResult {
        val productId = when (base) {
            "BTC" -> 2
            "ETH" -> 4
            "ARB" -> 6
            "BNB" -> 8
            "XRP" -> 10
            "SOL" -> 12
            else -> return ExchangeFetchResult()
        }
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpPostJson(
            "https://archive.prod.vertexprotocol.com/v1",
            """{"matches":{"product_ids":[$productId],"limit":80}}"""
        )
        if (!body.isNullOrBlank()) {
            val arr = JSONObject(body).optJSONArray("matches")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val baseFilled = (o.optString("base_filled").toDoubleOrNull() ?: 0.0) / 1e18
                    val quoteFilled = (o.optString("quote_filled").toDoubleOrNull() ?: 0.0) / 1e18
                    val absBase = abs(baseFilled)
                    val absQuote = abs(quoteFilled)
                    if (absBase <= 0.0 || absQuote <= 0.0) continue
                    val price = absQuote / absBase
                    val ts = o.optLong("timestamp", System.currentTimeMillis() / 1000L) * 1000L
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.VERTEX,
                            price = price,
                            baseQty = absBase,
                            quoteQty = absQuote,
                            isBuyAggressor = baseFilled > 0.0,
                            timestampMillis = ts
                        )
                    )
                }
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchDrift(base: String): ExchangeFetchResult {
        val marketName = "$base-PERP"
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://data.api.drift.trade/market/$marketName/trades")
        if (!body.isNullOrBlank()) {
            val arr = runCatching { JSONObject(body).optJSONArray("records") }.getOrNull()
                ?: runCatching { JSONArray(body) }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val baseAmt = o.optString("baseAssetAmountFilled").toDoubleOrNull() ?: continue
                    val quoteAmt = o.optString("quoteAssetAmountFilled").toDoubleOrNull() ?: continue
                    if (baseAmt <= 0.0 || quoteAmt <= 0.0) continue
                    val p = quoteAmt / baseAmt
                    val dir = o.optString("takerOrderDirection", "")
                    val tsSec = o.optLong("ts", System.currentTimeMillis() / 1000L)
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.DRIFT,
                            price = p,
                            baseQty = baseAmt,
                            quoteQty = quoteAmt,
                            isBuyAggressor = dir.equals("long", ignoreCase = true) || dir.equals("buy", ignoreCase = true),
                            timestampMillis = if (tsSec < 10_000_000_000L) tsSec * 1000L else tsSec
                        )
                    )
                }
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchParadex(base: String): ExchangeFetchResult {
        val market = "$base-USD-PERP"
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://api.prod.paradex.trade/v1/trades?market=$market")
        if (!body.isNullOrBlank()) {
            val arr = JSONObject(body).optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("price").toDoubleOrNull() ?: continue
                    val q = o.optString("size").toDoubleOrNull() ?: continue
                    val side = o.optString("side", "")
                    val ts = o.optLong("created_at", System.currentTimeMillis())
                    trades.add(
                        ExchangeTradeTick(
                            exchange = SupportedExchange.PARADEX,
                            price = p,
                            baseQty = q,
                            quoteQty = p * q,
                            isBuyAggressor = side.equals("BUY", ignoreCase = true),
                            timestampMillis = ts
                        )
                    )
                }
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    private fun fetchGmx(base: String): ExchangeFetchResult {
        val trades = ArrayList<ExchangeTradeTick>()
        val body = httpGet("https://arbitrum-api.gmxinfra.io/prices/candles?tokenSymbol=$base&period=5m")
        if (!body.isNullOrBlank()) {
            val arr = JSONObject(body).optJSONArray("candles")
            if (arr != null && arr.length() > 0) {
                val flows = ArrayList<ExchangeCandleFlow>()
                for (i in 0 until arr.length().coerceAtMost(35)) {
                    val row = arr.optJSONArray(i) ?: continue
                    if (row.length() < 5) continue
                    val openT = row.optLong(0) * 1000L
                    val open = row.optDouble(1)
                    val high = row.optDouble(2)
                    val low = row.optDouble(3)
                    val close = row.optDouble(4)
                    if (!close.isFinite() || close <= 0.0) continue
                    val approxQuoteVol = (abs(high - low) / close).coerceIn(0.0005, 0.05) * 2_000_000.0
                    val (buyVol, sellVol) = splitCandleVolumeByPriceAction(open, high, low, close, approxQuoteVol)
                    flows.add(
                        ExchangeCandleFlow(
                            exchange = SupportedExchange.GMX,
                            openTimeMillis = openT,
                            closeTimeMillis = openT + 5 * 60_000L - 1L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            buyQuoteVolume = buyVol,
                            sellQuoteVolume = sellVol
                        )
                    )
                }
                return ExchangeFetchResult(trades, flows, flows.isNotEmpty())
            }
        }
        return ExchangeFetchResult(trades, emptyList(), trades.isNotEmpty())
    }

    /**
     * Estimates buy/sell split from a real exchange's kline OHLC & quote volume when that
     * venue's kline endpoint does not return explicit takerBuyQuoteVolume.
     */
    private fun splitCandleVolumeByPriceAction(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        totalQuoteVolume: Double
    ): Pair<Double, Double> {
        if (totalQuoteVolume <= 0.0) return 0.0 to 0.0
        val range = (high - low).coerceAtLeast(1e-12)
        val closePosition = ((close - low) / range).coerceIn(0.0, 1.0)
        val bodyBias = ((close - open) / range).coerceIn(-1.0, 1.0)
        val buyShare = (0.50 + (closePosition - 0.50) * 0.42 + bodyBias * 0.22).coerceIn(0.18, 0.82)
        val buyVol = totalQuoteVolume * buyShare
        val sellVol = (totalQuoteVolume - buyVol).coerceAtLeast(0.0)
        return buyVol to sellVol
    }

    private fun parseIsoTimestampMillis(iso: String): Long? {
        if (iso.isBlank()) return null
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrNull()
    }
}
