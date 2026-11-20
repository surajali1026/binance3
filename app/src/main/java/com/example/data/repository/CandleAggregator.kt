package com.example.data.repository

import com.example.data.remote.MarketEndpointMode
import com.example.domain.model.Candle
import com.example.domain.model.Timeframe

object CandleAggregator {

    fun isNativeSupported(
        timeframe: Timeframe,
        endpointMode: MarketEndpointMode = MarketEndpointMode.FUTURES_PROD
    ): Boolean {
        val native = timeframe.nativeInterval ?: return false
        if (endpointMode == MarketEndpointMode.BYBIT_LINEAR && native == "8h") {
            return false
        }
        return true
    }

    fun pickBaseInterval(
        timeframe: Timeframe,
        endpointMode: MarketEndpointMode = MarketEndpointMode.FUTURES_PROD
    ): Pair<String, Long> {
        if (isNativeSupported(timeframe, endpointMode) && timeframe.nativeInterval != null) {
            return timeframe.nativeInterval to timeframe.durationMillis
        }
        val targetMs = timeframe.durationMillis
        val candidate = Timeframe.NATIVE_INTERVALS
            .asReversed()
            .firstOrNull { (name, nativeMs) ->
                val allowed = !(endpointMode == MarketEndpointMode.BYBIT_LINEAR && name == "8h")
                allowed && nativeMs <= targetMs && targetMs % nativeMs == 0L && (targetMs / nativeMs) <= 60L
            }
        return candidate ?: ("1m" to Timeframe.MINUTE_MS)
    }

    fun recommendedFetchLimit(
        timeframe: Timeframe,
        desiredBars: Int = 200,
        endpointMode: MarketEndpointMode = MarketEndpointMode.FUTURES_PROD
    ): Int {
        if (isNativeSupported(timeframe, endpointMode)) return desiredBars.coerceIn(100, 1000)
        val (_, baseMs) = pickBaseInterval(timeframe, endpointMode)
        val ratio = (timeframe.durationMillis / baseMs).coerceAtLeast(1L).toInt()
        return (desiredBars * ratio).coerceIn(200, 1000)
    }

    fun aggregate(
        baseCandles: List<Candle>,
        timeframe: Timeframe,
        endpointMode: MarketEndpointMode = MarketEndpointMode.FUTURES_PROD
    ): List<Candle> {
        if (baseCandles.isEmpty()) return emptyList()
        if (isNativeSupported(timeframe, endpointMode)) {
            return baseCandles.sortedBy { it.openTime }.distinctBy { it.openTime }
        }
        val bucketMs = timeframe.durationMillis
        val grouped = LinkedHashMap<Long, MutableList<Candle>>()
        baseCandles.sortedBy { it.openTime }.forEach { candle ->
            val bucketStart = (candle.openTime / bucketMs) * bucketMs
            grouped.getOrPut(bucketStart) { mutableListOf() }.add(candle)
        }
        return grouped.entries.map { (bucketStart, group) ->
            aggregateBucket(bucketStart, bucketMs, group)
        }
    }

    fun aggregateBucket(
        bucketStart: Long,
        bucketMs: Long,
        candlesInBucket: List<Candle>
    ): Candle {
        val sorted = candlesInBucket.sortedBy { it.openTime }
        val first = sorted.first()
        val last = sorted.last()
        var high = first.high
        var low = first.low
        var vol = 0.0
        for (c in sorted) {
            if (c.high > high) high = c.high
            if (c.low < low) low = c.low
            vol += c.volume
        }
        val bucketCloseTime = bucketStart + bucketMs - 1L
        val isClosed = last.isClosed && last.closeTime >= bucketCloseTime - 1000L
        return Candle(
            openTime = bucketStart,
            closeTime = bucketCloseTime,
            open = first.open,
            high = high,
            low = low,
            close = last.close,
            volume = vol,
            isClosed = isClosed
        )
    }
}
