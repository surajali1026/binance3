package com.example.domain.indicator

enum class ExchangeCategory(val label: String) {
    CEX("Top CEXs"),
    DEX("Top Perp DEXs")
}

enum class SupportedExchange(
    val id: String,
    val displayName: String,
    val shortCode: String,
    val category: ExchangeCategory,
    val defaultChecked: Boolean
) {
    // Top CEXs
    BINANCE("BINANCE", "Binance", "BN", ExchangeCategory.CEX, true),
    BYBIT("BYBIT", "Bybit", "BB", ExchangeCategory.CEX, true),
    OKX("OKX", "OKX", "OKX", ExchangeCategory.CEX, true),
    BITGET("BITGET", "Bitget", "BG", ExchangeCategory.CEX, true),
    COINBASE("COINBASE", "Coinbase", "CB", ExchangeCategory.CEX, false),
    KRAKEN("KRAKEN", "Kraken", "KRK", ExchangeCategory.CEX, false),
    GATEIO("GATEIO", "Gate.io", "GT", ExchangeCategory.CEX, false),
    KUCOIN("KUCOIN", "KuCoin", "KC", ExchangeCategory.CEX, false),

    // Top Perp DEXs
    HYPERLIQUID("HYPERLIQUID", "Hyperliquid", "HL", ExchangeCategory.DEX, true),
    DYDX("DYDX", "dYdX", "DY", ExchangeCategory.DEX, true),
    VERTEX("VERTEX", "Vertex", "VTX", ExchangeCategory.DEX, false),
    DRIFT("DRIFT", "Drift", "DRF", ExchangeCategory.DEX, false),
    PARADEX("PARADEX", "Paradex", "PDX", ExchangeCategory.DEX, false),
    GMX("GMX", "GMX", "GMX", ExchangeCategory.DEX, false);

    companion object {
        val DEFAULT_SELECTED: Set<SupportedExchange> =
            entries.filter { it.defaultChecked }.toSet()

        val DEFAULT_CSV: String =
            DEFAULT_SELECTED.joinToString(",") { it.id }

        val CEX_LIST: List<SupportedExchange> =
            entries.filter { it.category == ExchangeCategory.CEX }

        val DEX_LIST: List<SupportedExchange> =
            entries.filter { it.category == ExchangeCategory.DEX }

        fun parseCsv(csv: String?): Set<SupportedExchange> {
            if (csv.isNullOrBlank()) return emptySet()
            val byId = entries.associateBy { it.id }
            return csv.split(',')
                .mapNotNull { byId[it.trim().uppercase()] }
                .toSet()
        }

        fun toCsv(exchanges: Set<SupportedExchange>): String =
            entries.filter { exchanges.contains(it) }.joinToString(",") { it.id }
    }
}

enum class DominanceSide {
    BUY,
    SELL
}

enum class TrapType(val label: String) {
    BUYER_TRAP("Buyer Trap"),
    SELLER_TRAP("Seller Trap")
}

/**
 * Real public trade tick from a single exchange.
 * [isBuyAggressor] is true when the market taker bought (aggressive buy), false when taker sold.
 */
data class ExchangeTradeTick(
    val exchange: SupportedExchange,
    val price: Double,
    val baseQty: Double,
    val quoteQty: Double,
    val isBuyAggressor: Boolean,
    val timestampMillis: Long
)

/**
 * Per-candle public volume summary from an exchange's kline endpoint (used to cover
 * historical candles beyond recent-trade REST windows across all timeframes).
 */
data class ExchangeCandleFlow(
    val exchange: SupportedExchange,
    val openTimeMillis: Long,
    val closeTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val buyQuoteVolume: Double,
    val sellQuoteVolume: Double
)

data class ExchangeVolumeSnapshot(
    val symbol: String,
    val timeframeId: String,
    val tradesByExchange: Map<SupportedExchange, List<ExchangeTradeTick>> = emptyMap(),
    val flowsByExchange: Map<SupportedExchange, List<ExchangeCandleFlow>> = emptyMap(),
    val connectedExchanges: Set<SupportedExchange> = emptySet(),
    val updatedAtMillis: Long = 0L
) {
    companion object {
        val EMPTY = ExchangeVolumeSnapshot(symbol = "", timeframeId = "")
    }
}

data class ExchangeBucketBreakdown(
    val exchange: SupportedExchange,
    val buyVolume: Double,
    val sellVolume: Double
) {
    val totalVolume: Double get() = buyVolume + sellVolume
    val dominantSide: DominanceSide get() = if (buyVolume >= sellVolume) DominanceSide.BUY else DominanceSide.SELL
}

/**
 * Aggregated intra-candle price level/bucket with Green/Red dominance and checked-exchange count.
 */
data class IntraCandleVolumeLevel(
    val id: String,
    val candleTimeMillis: Long,
    val bucketIndex: Int,
    val bucketCount: Int,
    val priceLow: Double,
    val priceHigh: Double,
    val priceMid: Double,
    val dominantSide: DominanceSide,
    val dominantExchangeCount: Int,
    val activeExchangeCount: Int,
    val totalBuyVolume: Double,
    val totalSellVolume: Double,
    val exchanges: List<ExchangeBucketBreakdown>
)

data class CandleTrapTag(
    val id: String,
    val candleTimeMillis: Long,
    val price: Double,
    val type: TrapType,
    val dominantExchangeCount: Int,
    val totalBuyVolume: Double,
    val totalSellVolume: Double
)

data class MultiExchangeVolumeOutput(
    val levels: List<IntraCandleVolumeLevel> = emptyList(),
    val traps: List<CandleTrapTag> = emptyList(),
    val connectedExchanges: Set<SupportedExchange> = emptySet(),
    val checkedExchanges: Set<SupportedExchange> = emptySet(),
    val isLoading: Boolean = false
) {
    companion object {
        val EMPTY = MultiExchangeVolumeOutput()
    }
}
