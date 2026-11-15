package com.example.data.remote

import com.example.domain.model.Candle
import com.example.domain.model.FuturesContract
import com.example.domain.model.PriceSource
import com.example.domain.model.TickerSnapshot
import com.squareup.moshi.JsonClass
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

@JsonClass(generateAdapter = true)
data class ExchangeInfoResponse(
    val symbols: List<SymbolInfoDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SymbolInfoDto(
    val symbol: String,
    val pair: String = "",
    val baseAsset: String = "",
    val quoteAsset: String = "",
    val contractType: String = "",
    val status: String = "",
    val pricePrecision: Int = 2,
    val quantityPrecision: Int = 3,
    val filters: List<FilterDto> = emptyList()
) {
    fun toDomain(): FuturesContract {
        val tickFilter = filters.firstOrNull { it.filterType == "PRICE_FILTER" }
        val tickSize = tickFilter?.tickSize?.toDoubleOrNull() ?: 0.01
        val derivedPrecision = derivePrecision(tickFilter?.tickSize, pricePrecision)
        return FuturesContract(
            symbol = symbol,
            pair = pair.ifBlank { symbol },
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            pricePrecision = derivedPrecision,
            quantityPrecision = quantityPrecision,
            tickSize = tickSize,
            contractType = contractType,
            status = status
        )
    }

    private fun derivePrecision(tickSizeStr: String?, fallback: Int): Int {
        if (tickSizeStr.isNullOrBlank()) return fallback
        val trimmed = tickSizeStr.trimEnd('0')
        val dotIdx = trimmed.indexOf('.')
        return if (dotIdx >= 0) max(0, trimmed.length - dotIdx - 1) else 0
    }
}

@JsonClass(generateAdapter = true)
data class FilterDto(
    val filterType: String = "",
    val tickSize: String? = null
)

@JsonClass(generateAdapter = true)
data class Ticker24hrDto(
    val symbol: String,
    val priceChangePercent: String = "0",
    val lastPrice: String = "0",
    val highPrice: String = "0",
    val lowPrice: String = "0",
    val quoteVolume: String = "0"
) {
    fun toDomain(markPrice: Double? = null, indexPrice: Double? = null): TickerSnapshot {
        val last = lastPrice.toDoubleOrNull() ?: 0.0
        return TickerSnapshot(
            symbol = symbol,
            lastPrice = last,
            markPrice = markPrice ?: last,
            indexPrice = indexPrice ?: last,
            priceChangePercent24h = priceChangePercent.toDoubleOrNull() ?: 0.0,
            quoteVolume24h = quoteVolume.toDoubleOrNull() ?: 0.0,
            high24h = highPrice.toDoubleOrNull() ?: last,
            low24h = lowPrice.toDoubleOrNull() ?: last
        )
    }
}

@JsonClass(generateAdapter = true)
data class PremiumIndexDto(
    val symbol: String,
    val markPrice: String = "0",
    val indexPrice: String = "0"
)

data class WsKlineUpdate(
    val stream: String,
    val symbol: String,
    val priceSource: PriceSource,
    val interval: String,
    val candle: Candle
)

data class WsTickerUpdate(
    val symbol: String,
    val lastPrice: Double?,
    val priceChangePercent24h: Double?,
    val quoteVolume24h: Double?,
    val high24h: Double?,
    val low24h: Double?
)

data class WsMarkPriceUpdate(
    val symbol: String,
    val markPrice: Double?,
    val indexPrice: Double?
)

data class WsTradeUpdate(
    val symbol: String,
    val price: Double,
    val timestampMs: Long
)

object BinancePayloadParser {
    fun parseKlineArray(jsonText: String): List<Candle> {
        val root = JSONArray(jsonText)
        val result = ArrayList<Candle>(root.length())
        for (i in 0 until root.length()) {
            val row = root.getJSONArray(i)
            val openTime = row.getLong(0)
            val open = row.getString(1).toDouble()
            val high = row.getString(2).toDouble()
            val low = row.getString(3).toDouble()
            val close = row.getString(4).toDouble()
            val volume = row.optString(5, "0").toDoubleOrNull() ?: 0.0
            val closeTime = row.getLong(6)
            result.add(
                Candle(
                    openTime = openTime,
                    closeTime = closeTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    isClosed = System.currentTimeMillis() > closeTime
                )
            )
        }
        return result
    }

    fun parseSocketMessage(
        rawText: String,
        onTickers: (List<WsTickerUpdate>) -> Unit,
        onMarkPrices: (List<WsMarkPriceUpdate>) -> Unit,
        onKline: (WsKlineUpdate) -> Unit,
        onTrade: (WsTradeUpdate) -> Unit = {},
        onServerTimestamp: (Long) -> Unit = {}
    ) {
        val root = JSONObject(rawText)
        if (!root.has("stream") || !root.has("data")) return
        val stream = root.getString("stream")
        when {
            stream == "!ticker@arr" -> {
                val arr = root.getJSONArray("data")
                val list = ArrayList<WsTickerUpdate>(arr.length())
                var latestEventTs = 0L
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val ev = item.optLong("E", 0L)
                    if (ev > latestEventTs) latestEventTs = ev
                    list.add(
                        WsTickerUpdate(
                            symbol = item.getString("s"),
                            lastPrice = item.optString("c").toDoubleOrNull(),
                            priceChangePercent24h = item.optString("P").toDoubleOrNull(),
                            quoteVolume24h = item.optString("q").toDoubleOrNull(),
                            high24h = item.optString("h").toDoubleOrNull(),
                            low24h = item.optString("l").toDoubleOrNull()
                        )
                    )
                }
                if (latestEventTs > 1_500_000_000_000L) onServerTimestamp(latestEventTs)
                onTickers(list)
            }
            stream == "!markPrice@arr@1s" -> {
                val arr = root.getJSONArray("data")
                val list = ArrayList<WsMarkPriceUpdate>(arr.length())
                var latestEventTs = 0L
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val ev = item.optLong("E", 0L)
                    if (ev > latestEventTs) latestEventTs = ev
                    val mark = item.optString("p").toDoubleOrNull()
                    val index = item.optString("i").toDoubleOrNull()
                    if (mark != null || index != null) {
                        list.add(
                            WsMarkPriceUpdate(
                                symbol = item.getString("s"),
                                markPrice = mark,
                                indexPrice = index
                            )
                        )
                    }
                }
                if (latestEventTs > 1_500_000_000_000L) onServerTimestamp(latestEventTs)
                onMarkPrices(list)
            }
            stream.endsWith("@aggTrade") || stream.endsWith("@trade") -> {
                val data = root.getJSONObject("data")
                val symbol = data.optString("s", stream.substringBefore("@")).uppercase()
                val price = data.optString("p").toDoubleOrNull()
                val ev = data.optLong("E", data.optLong("T", 0L))
                if (ev > 1_500_000_000_000L) onServerTimestamp(ev)
                if (symbol.isNotBlank() && price != null && price > 0.0) {
                    onTrade(
                        WsTradeUpdate(
                            symbol = symbol,
                            price = price,
                            timestampMs = if (ev > 0L) ev else System.currentTimeMillis()
                        )
                    )
                }
            }
            stream.contains("@kline_") || stream.contains("@markPriceKline_") || stream.contains("@indexPriceKline_") -> {
                val data = root.getJSONObject("data")
                val ev = data.optLong("E", 0L)
                if (ev > 1_500_000_000_000L) onServerTimestamp(ev)
                val k = data.optJSONObject("k") ?: return
                val symbol = data.optString("s", data.optString("ps", k.optString("s", ""))).uppercase()
                val priceSource = when {
                    stream.contains("@markPriceKline_") -> PriceSource.MARK
                    stream.contains("@indexPriceKline_") -> PriceSource.INDEX
                    else -> PriceSource.LAST
                }
                val interval = k.getString("i")
                val candle = Candle(
                    openTime = k.getLong("t"),
                    closeTime = k.getLong("T"),
                    open = k.getString("o").toDouble(),
                    high = k.getString("h").toDouble(),
                    low = k.getString("l").toDouble(),
                    close = k.getString("c").toDouble(),
                    volume = k.optString("v", "0").toDoubleOrNull() ?: 0.0,
                    isClosed = k.optBoolean("x", false)
                )
                onKline(
                    WsKlineUpdate(
                        stream = stream,
                        symbol = symbol,
                        priceSource = priceSource,
                        interval = interval,
                        candle = candle
                    )
                )
            }
        }
    }
}

object BybitPayloadParser {
    fun toBybitInterval(binanceInterval: String): String {
        return when (binanceInterval) {
            "1m" -> "1"
            "3m" -> "3"
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h" -> "60"
            "2h" -> "120"
            "4h", "8h" -> "240"
            "6h" -> "360"
            "12h" -> "720"
            "1d" -> "D"
            "1w" -> "W"
            "1M" -> "M"
            else -> "15"
        }
    }

    fun fromBybitIntervalMs(bybitInterval: String): Long {
        return when (bybitInterval) {
            "1" -> 60_000L
            "3" -> 180_000L
            "5" -> 300_000L
            "15" -> 900_000L
            "30" -> 1_800_000L
            "60" -> 3_600_000L
            "120" -> 7_200_000L
            "240" -> 14_400_000L
            "360" -> 21_600_000L
            "720" -> 43_200_000L
            "D" -> 86_400_000L
            "W" -> 604_800_000L
            "M" -> 2_592_000_000L
            else -> 900_000L
        }
    }

    fun parseTickersResponse(jsonText: String): Pair<List<FuturesContract>, Map<String, TickerSnapshot>> {
        val root = JSONObject(jsonText)
        val list = root.optJSONObject("result")?.optJSONArray("list") ?: return emptyList<FuturesContract>() to emptyMap()
        val contracts = ArrayList<FuturesContract>(list.length())
        val tickers = HashMap<String, TickerSnapshot>(list.length())

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val symbol = item.optString("symbol")
            if (!symbol.endsWith("USDT")) continue

            val lastStr = item.optString("lastPrice", "0")
            val last = lastStr.toDoubleOrNull() ?: continue
            if (last <= 0.0) continue

            val mark = item.optString("markPrice").toDoubleOrNull() ?: last
            val index = item.optString("indexPrice").toDoubleOrNull() ?: last
            val pct = (item.optString("price24hPcnt", "0").toDoubleOrNull() ?: 0.0) * 100.0
            val vol = item.optString("turnover24h", "0").toDoubleOrNull() ?: 0.0
            val high = item.optString("highPrice24h").toDoubleOrNull() ?: last
            val low = item.optString("lowPrice24h").toDoubleOrNull() ?: last

            val trimmed = lastStr.trimEnd('0')
            val dotIdx = trimmed.indexOf('.')
            val precision = if (dotIdx >= 0) (trimmed.length - dotIdx - 1).coerceIn(2, 8) else 2
            val tick = Math.pow(10.0, -precision.toDouble())

            contracts.add(
                FuturesContract(
                    symbol = symbol,
                    pair = symbol,
                    baseAsset = symbol.removeSuffix("USDT"),
                    quoteAsset = "USDT",
                    pricePrecision = precision,
                    quantityPrecision = 3,
                    tickSize = tick,
                    contractType = "PERPETUAL",
                    status = "TRADING"
                )
            )
            tickers[symbol] = TickerSnapshot(
                symbol = symbol,
                lastPrice = last,
                markPrice = mark,
                indexPrice = index,
                priceChangePercent24h = pct,
                quoteVolume24h = vol,
                high24h = high,
                low24h = low
            )
        }
        return contracts.sortedBy { it.symbol } to tickers
    }

    fun parseKlineResponse(jsonText: String, intervalMs: Long): List<Candle> {
        val root = JSONObject(jsonText)
        val list = root.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        val candles = ArrayList<Candle>(list.length())
        val now = System.currentTimeMillis()
        for (i in 0 until list.length()) {
            val row = list.getJSONArray(i)
            val openTime = row.getString(0).toLong()
            val closeTime = openTime + intervalMs - 1L
            val open = row.getString(1).toDouble()
            val high = row.getString(2).toDouble()
            val low = row.getString(3).toDouble()
            val close = row.getString(4).toDouble()
            val vol = if (row.length() > 5) row.optString(5, "0").toDoubleOrNull() ?: 0.0 else 0.0
            candles.add(
                Candle(
                    openTime = openTime,
                    closeTime = closeTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    isClosed = now > closeTime
                )
            )
        }
        candles.sortBy { it.openTime }
        return candles
    }

    fun parseSocketMessage(
        rawText: String,
        onTickers: (List<WsTickerUpdate>) -> Unit,
        onMarkPrices: (List<WsMarkPriceUpdate>) -> Unit,
        onKline: (WsKlineUpdate) -> Unit,
        onTrade: (WsTradeUpdate) -> Unit = {},
        onServerTimestamp: (Long) -> Unit = {}
    ) {
        val root = JSONObject(rawText)
        val serverTs = root.optLong("ts", 0L)
        if (serverTs > 1_500_000_000_000L) onServerTimestamp(serverTs)
        val topic = root.optString("topic", "")
        if (topic.isEmpty()) return

        when {
            topic.startsWith("kline.") -> {
                val parts = topic.split(".")
                if (parts.size < 3) return
                val interval = parts[1]
                val symbol = parts[2]
                val dataArr = root.optJSONArray("data") ?: return
                for (i in 0 until dataArr.length()) {
                    val d = dataArr.getJSONObject(i)
                    val candle = Candle(
                        openTime = d.getLong("start"),
                        closeTime = d.getLong("end"),
                        open = d.getString("open").toDouble(),
                        high = d.getString("high").toDouble(),
                        low = d.getString("low").toDouble(),
                        close = d.getString("close").toDouble(),
                        volume = d.optString("volume", "0").toDoubleOrNull() ?: 0.0,
                        isClosed = d.optBoolean("confirm", false)
                    )
                    onKline(
                        WsKlineUpdate(
                            stream = topic,
                            symbol = symbol,
                            priceSource = PriceSource.LAST,
                            interval = interval,
                            candle = candle
                        )
                    )
                }
            }
            topic.startsWith("publicTrade.") -> {
                val fallbackSymbol = topic.removePrefix("publicTrade.")
                val dataArr = root.optJSONArray("data") ?: return
                if (dataArr.length() == 0) return
                val latest = dataArr.getJSONObject(dataArr.length() - 1)
                val symbol = latest.optString("s", fallbackSymbol).ifBlank { fallbackSymbol }.uppercase()
                val price = latest.optString("p").toDoubleOrNull() ?: return
                val tradeTs = latest.optLong("T", serverTs)
                if (price > 0.0) {
                    onTrade(
                        WsTradeUpdate(
                            symbol = symbol,
                            price = price,
                            timestampMs = if (tradeTs > 0L) tradeTs else System.currentTimeMillis()
                        )
                    )
                }
            }
            topic.startsWith("tickers.") -> {
                val d = root.optJSONObject("data") ?: return
                val symbol = d.optString("symbol", topic.removePrefix("tickers.")).ifBlank {
                    topic.removePrefix("tickers.")
                }
                val last = d.optString("lastPrice").toDoubleOrNull()
                val pct = d.optString("price24hPcnt").toDoubleOrNull()?.times(100.0)
                val vol = d.optString("turnover24h").toDoubleOrNull()
                val high = d.optString("highPrice24h").toDoubleOrNull()
                val low = d.optString("lowPrice24h").toDoubleOrNull()
                val mark = d.optString("markPrice").toDoubleOrNull()
                val index = d.optString("indexPrice").toDoubleOrNull()
                if (last != null || pct != null || vol != null || high != null || low != null) {
                    onTickers(listOf(WsTickerUpdate(symbol, last, pct, vol, high, low)))
                }
                if (mark != null || index != null) {
                    onMarkPrices(listOf(WsMarkPriceUpdate(symbol, mark, index)))
                }
            }
        }
    }
}

data class MarketCapInfo(
    val rank: Int,
    val marketCap: Double
)

object MarketCapPayloadParser {
    private val STABLECOIN_BASES = setOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "DAI", "USDE")

    fun normalizeBaseAsset(rawBase: String): String {
        val upper = rawBase.trim().uppercase()
        return when {
            upper.startsWith("1000000") && upper.length > 7 -> upper.removePrefix("1000000")
            upper.startsWith("1000") && upper.length > 4 -> upper.removePrefix("1000")
            upper.startsWith("1M") && upper.length > 3 && upper[2].isLetter() -> upper.removePrefix("1M")
            upper.endsWith("1000") && upper.length > 4 -> upper.removeSuffix("1000")
            else -> upper
        }
    }

    fun parseCoinGeckoMarkets(jsonText: String): Map<String, MarketCapInfo> {
        val arr = JSONArray(jsonText)
        val result = HashMap<String, MarketCapInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val sym = obj.optString("symbol").trim().uppercase()
            if (sym.isBlank()) continue
            val rank = obj.optInt("market_cap_rank", 0)
            val mcap = obj.optDouble("market_cap", 0.0)
            if (rank <= 0 || !mcap.isFinite() || mcap <= 0.0) continue
            val existing = result[sym]
            if (existing == null || rank < existing.rank) {
                result[sym] = MarketCapInfo(rank = rank, marketCap = mcap)
            }
        }
        return result
    }

    fun parseCoinPaprikaTickers(jsonText: String): Map<String, MarketCapInfo> {
        val arr = JSONArray(jsonText)
        val result = HashMap<String, MarketCapInfo>(512)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val sym = obj.optString("symbol").trim().uppercase()
            if (sym.isBlank()) continue
            val rank = obj.optInt("rank", 0)
            val usd = obj.optJSONObject("quotes")?.optJSONObject("USD") ?: continue
            val mcap = usd.optDouble("market_cap", 0.0)
            if (rank <= 0 || !mcap.isFinite() || mcap <= 0.0) continue
            val existing = result[sym]
            if (existing == null || rank < existing.rank) {
                result[sym] = MarketCapInfo(rank = rank, marketCap = mcap)
            }
        }
        return result
    }

    fun lookupForBaseAsset(
        baseAsset: String,
        symbol: String,
        marketCaps: Map<String, MarketCapInfo>
    ): MarketCapInfo? {
        if (marketCaps.isEmpty()) return null
        val rawUpper = baseAsset.trim().uppercase()
        if (rawUpper in STABLECOIN_BASES) {
            return marketCaps[rawUpper]
        }
        marketCaps[rawUpper]?.let { return it }
        val normalized = normalizeBaseAsset(rawUpper)
        if (normalized != rawUpper) {
            marketCaps[normalized]?.let { return it }
        }
        val symNoQuote = symbol.uppercase().removeSuffix("USDT").removeSuffix("USDC")
        marketCaps[symNoQuote]?.let { return it }
        val normSym = normalizeBaseAsset(symNoQuote)
        if (normSym != symNoQuote) {
            marketCaps[normSym]?.let { return it }
        }
        return null
    }
}
