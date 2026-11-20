package com.example.domain.model

enum class PriceSource(val label: String, val shortLabel: String) {
    LAST("Last Price", "Last"),
    MARK("Mark Price", "Mark"),
    INDEX("Index Price", "Index")
}

enum class WatchlistFilter(val label: String) {
    ALL("All"),
    TOP_100("Top 100"),
    NEW_LISTINGS("New"),
    FAVORITES("Favorites"),
    GAINERS("Gainers"),
    LOSERS("Losers")
}

enum class WatchlistSortField(val label: String) {
    MARKET_CAP("MCap"),
    VOLUME("Vol"),
    PRICE("Price"),
    CHANGE("24h %"),
    LISTING_TIME("Listed")
}

data class FuturesContract(
    val symbol: String,
    val pair: String,
    val baseAsset: String,
    val quoteAsset: String,
    val pricePrecision: Int,
    val quantityPrecision: Int,
    val tickSize: Double,
    val contractType: String,
    val status: String,
    val onboardDate: Long = 0L
) {
    fun isUpcomingListing(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return status.equals("PENDING_TRADING", ignoreCase = true) ||
            status.equals("PRE_TRADING", ignoreCase = true) ||
            status.equals("PreLaunch", ignoreCase = true) ||
            (onboardDate > nowMillis && onboardDate > 0L)
    }

    fun isRecentlyListed(nowMillis: Long = System.currentTimeMillis(), windowDays: Int = 30): Boolean {
        if (onboardDate <= 0L) return false
        if (isUpcomingListing(nowMillis)) return true
        val windowMs = windowDays.toLong() * 86_400_000L
        return (nowMillis - onboardDate) in 0L..windowMs
    }
}

data class TickerSnapshot(
    val symbol: String,
    val lastPrice: Double,
    val markPrice: Double = lastPrice,
    val indexPrice: Double = lastPrice,
    val priceChangePercent24h: Double,
    val quoteVolume24h: Double,
    val high24h: Double,
    val low24h: Double,
    val marketCap: Double? = null,
    val marketCapRank: Int? = null
)

data class WatchlistItem(
    val contract: FuturesContract,
    val ticker: TickerSnapshot?,
    val isFavorite: Boolean,
    val displayRank: Int? = ticker?.marketCapRank
)

data class Candle(
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val isClosed: Boolean
)

data class Timeframe(
    val id: String,
    val label: String,
    val durationMillis: Long,
    val nativeInterval: String?,
    val isCustom: Boolean = false,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0
) {
    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val WEEK_MS = 604_800_000L
        const val MONTH_MS = 2_592_000_000L

        val NATIVE_INTERVALS: List<Pair<String, Long>> = listOf(
            "1m" to 1 * MINUTE_MS,
            "3m" to 3 * MINUTE_MS,
            "5m" to 5 * MINUTE_MS,
            "15m" to 15 * MINUTE_MS,
            "30m" to 30 * MINUTE_MS,
            "1h" to 1 * HOUR_MS,
            "2h" to 2 * HOUR_MS,
            "4h" to 4 * HOUR_MS,
            "6h" to 6 * HOUR_MS,
            "8h" to 8 * HOUR_MS,
            "12h" to 12 * HOUR_MS,
            "1d" to 1 * DAY_MS,
            "3d" to 3 * DAY_MS,
            "1w" to 1 * WEEK_MS,
            "1M" to 1 * MONTH_MS
        )

        val DEFAULTS: List<Timeframe> = listOf(
            Timeframe("1m", "1m", 1 * MINUTE_MS, "1m", isCustom = false, isFavorite = true, sortOrder = 0),
            Timeframe("3m", "3m", 3 * MINUTE_MS, "3m", isCustom = false, isFavorite = false, sortOrder = 1),
            Timeframe("5m", "5m", 5 * MINUTE_MS, "5m", isCustom = false, isFavorite = true, sortOrder = 2),
            Timeframe("9m", "9m", 9 * MINUTE_MS, null, isCustom = false, isFavorite = false, sortOrder = 3),
            Timeframe("15m", "15m", 15 * MINUTE_MS, "15m", isCustom = false, isFavorite = true, sortOrder = 4),
            Timeframe("30m", "30m", 30 * MINUTE_MS, "30m", isCustom = false, isFavorite = false, sortOrder = 5),
            Timeframe("45m", "45m", 45 * MINUTE_MS, null, isCustom = false, isFavorite = false, sortOrder = 6),
            Timeframe("1H", "1H", 1 * HOUR_MS, "1h", isCustom = false, isFavorite = true, sortOrder = 7),
            Timeframe("2H", "2H", 2 * HOUR_MS, "2h", isCustom = false, isFavorite = false, sortOrder = 8),
            Timeframe("4H", "4H", 4 * HOUR_MS, "4h", isCustom = false, isFavorite = true, sortOrder = 9),
            Timeframe("6H", "6H", 6 * HOUR_MS, "6h", isCustom = false, isFavorite = false, sortOrder = 10),
            Timeframe("8H", "8H", 8 * HOUR_MS, "8h", isCustom = false, isFavorite = false, sortOrder = 11),
            Timeframe("12H", "12H", 12 * HOUR_MS, "12h", isCustom = false, isFavorite = false, sortOrder = 12),
            Timeframe("1D", "1D", 1 * DAY_MS, "1d", isCustom = false, isFavorite = true, sortOrder = 13),
            Timeframe("1W", "1W", 1 * WEEK_MS, "1w", isCustom = false, isFavorite = false, sortOrder = 14),
            Timeframe("1M", "1M", 1 * MONTH_MS, "1M", isCustom = false, isFavorite = false, sortOrder = 15)
        )

        fun createCustom(amount: Int, unit: CustomTimeUnit, sortOrder: Int): Timeframe {
            val safeAmount = amount.coerceAtLeast(1)
            val duration = safeAmount * unit.unitMillis
            val label = "$safeAmount${unit.suffix}"
            val nativeMatch = NATIVE_INTERVALS.firstOrNull { it.second == duration }?.first
            return Timeframe(
                id = "custom_$label",
                label = label,
                durationMillis = duration,
                nativeInterval = nativeMatch,
                isCustom = true,
                isFavorite = true,
                sortOrder = sortOrder
            )
        }
    }
}

enum class CustomTimeUnit(val label: String, val suffix: String, val unitMillis: Long) {
    MINUTES("Minutes", "m", Timeframe.MINUTE_MS),
    HOURS("Hours", "H", Timeframe.HOUR_MS),
    DAYS("Days", "D", Timeframe.DAY_MS)
}
