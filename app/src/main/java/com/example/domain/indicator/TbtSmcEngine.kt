package com.example.domain.indicator

import com.example.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TbtZoneType {
    DEMAND,
    SUPPLY,
    FVG_BULL,
    FVG_BEAR
}

data class TbtZone(
    val id: String,
    val type: TbtZoneType,
    val startTimeMillis: Long,
    val top: Double,
    val bottom: Double,
    val tapped: Boolean,
    val label: String
)

enum class TbtPriceLineCategory {
    ZONE,
    POWER_SHIFT,
    OVERLAP
}

data class TbtPriceLine(
    val price: Double,
    val title: String,
    val colorHex: String,
    val lineStyle: Int, // 0 = Solid, 1 = Dotted, 2 = Dashed
    val lineWidth: Int,
    val category: TbtPriceLineCategory
)

data class TbtMarker(
    val timeMillis: Long,
    val position: String, // "aboveBar" or "belowBar"
    val colorHex: String,
    val shape: String, // "arrowUp", "arrowDown", "circle"
    val text: String
)

enum class TbtMarketBias(val label: String) {
    BULLISH("BULLISH"),
    BEARISH("BEARISH"),
    SIDEWAYS("SIDEWAYS")
}

data class TbtConditionSummary(
    val bias: TbtMarketBias = TbtMarketBias.SIDEWAYS,
    val activeBullPower: Double? = null,
    val activeBearPower: Double? = null,
    val lastSignal: String = "Waiting for data..."
)

data class TbtSmcConfig(
    val swingLen: Int = 5,
    val showZones: Boolean = true,
    val showPowerShift: Boolean = true,
    val showOverlap: Boolean = true,
    val showSignals: Boolean = true,
    val showHud: Boolean = true
)

data class TbtSmcOutput(
    val markers: List<TbtMarker> = emptyList(),
    val priceLines: List<TbtPriceLine> = emptyList(),
    val zones: List<TbtZone> = emptyList(),
    val summary: TbtConditionSummary = TbtConditionSummary()
) {
    companion object {
        val EMPTY = TbtSmcOutput()
    }
}

private data class MutableTbtZone(
    val id: String,
    val type: TbtZoneType,
    val startTimeMillis: Long,
    val top: Double,
    val bottom: Double,
    var tapped: Boolean,
    var label: String
) {
    fun toImmutable(): TbtZone = TbtZone(
        id = id,
        type = type,
        startTimeMillis = startTimeMillis,
        top = top,
        bottom = bottom,
        tapped = tapped,
        label = label
    )
}

private data class OverlappingLevel(
    val price: Double,
    val type: String
)

object TbtSmcEngine {

    fun calculate(
        candles: List<Candle>,
        config: TbtSmcConfig = TbtSmcConfig()
    ): TbtSmcOutput {
        if (candles.size < 25) {
            return TbtSmcOutput(
                summary = TbtConditionSummary(
                    bias = TbtMarketBias.SIDEWAYS,
                    activeBullPower = null,
                    activeBearPower = null,
                    lastSignal = "Waiting for data..."
                )
            )
        }

        val sLen = config.swingLen.coerceAtLeast(2)
        val markerMap = LinkedHashMap<Long, Pair<Int, TbtMarker>>()

        fun addMarker(
            timeMillis: Long,
            position: String,
            colorHex: String,
            shape: String,
            text: String,
            priority: Int
        ) {
            val existing = markerMap[timeMillis]
            if (existing == null || existing.first <= priority) {
                markerMap[timeMillis] = priority to TbtMarker(
                    timeMillis = timeMillis,
                    position = position,
                    colorHex = colorHex,
                    shape = shape,
                    text = text
                )
            }
        }

        val bodySizes = DoubleArray(candles.size)
        val trueRanges = DoubleArray(candles.size)
        for (i in candles.indices) {
            val c = candles[i]
            bodySizes[i] = abs(c.close - c.open)
            trueRanges[i] = if (i == 0) {
                c.high - c.low
            } else {
                val prevClose = candles[i - 1].close
                max(
                    c.high - c.low,
                    max(abs(c.high - prevClose), abs(c.low - prevClose))
                )
            }
        }

        fun getSma(arr: DoubleArray, index: Int, period: Int): Double {
            if (index < period) return arr.getOrElse(index) { 0.0 }
            var sum = 0.0
            for (k in (index - period + 1)..index) {
                sum += arr[k]
            }
            return sum / period.toDouble()
        }

        var lastSwingHigh: Double? = null
        var lastSwingLow: Double? = null

        val activeZones = mutableListOf<MutableTbtZone>()
        val overlappingLevels = mutableListOf<OverlappingLevel>()
        var activeBullPower: Double? = null
        var activeBearPower: Double? = null
        var lastSignal = "Neutral"

        for (i in 15 until candles.size) {
            val c = candles[i]
            val c1 = candles[i - 1]
            val c2 = candles[i - 2]

            val isBull = c.close > c.open
            val isBear = c.close < c.open
            val isBull1 = c1.close > c1.open
            val isBear1 = c1.close < c1.open
            val isBull2 = c2.close > c2.open
            val isBear2 = c2.close < c2.open

            val avgBody1 = getSma(bodySizes, i - 1, 14)
            val avgBody = getSma(bodySizes, i, 14)
            val atr = getSma(trueRanges, i, 14)

            // 1. Swing High / Low
            if (i >= sLen * 2) {
                val pivotIdx = i - sLen
                val pHigh = candles[pivotIdx].high
                val pLow = candles[pivotIdx].low
                var isSH = true
                var isSL = true
                for (k in (pivotIdx - sLen)..(pivotIdx + sLen)) {
                    if (candles[k].high > pHigh) isSH = false
                    if (candles[k].low < pLow) isSL = false
                    if (!isSH && !isSL) break
                }
                if (isSH) lastSwingHigh = pHigh
                if (isSL) lastSwingLow = pLow
            }

            // 2. Overlapping Level (S/R Flip)
            if (config.showOverlap) {
                val sh = lastSwingHigh
                if (sh != null && c1.close <= sh && c.close > sh) {
                    overlappingLevels.add(OverlappingLevel(price = sh, type = "BUY"))
                    while (overlappingLevels.size > 2) overlappingLevels.removeAt(0)
                }
                val sl = lastSwingLow
                if (sl != null && c1.close >= sl && c.close < sl) {
                    overlappingLevels.add(OverlappingLevel(price = sl, type = "SELL"))
                    while (overlappingLevels.size > 2) overlappingLevels.removeAt(0)
                }
            }

            // 3. Demand & Supply Zones
            val isBaseCandle = bodySizes[i - 1] < avgBody1 * 0.9
            val isBullImpulse = isBull && bodySizes[i] > avgBody * 1.3 && c.close > c1.high
            val isBearImpulse = isBear && bodySizes[i] > avgBody * 1.3 && c.close < c1.low

            if (config.showZones && isBaseCandle && isBullImpulse) {
                activeZones.add(
                    MutableTbtZone(
                        id = "DEMAND_$i",
                        type = TbtZoneType.DEMAND,
                        startTimeMillis = c1.openTime,
                        top = max(c1.open, c1.close),
                        bottom = c1.low,
                        tapped = false,
                        label = if (isBull2) "RBR Demand" else "DBR Demand"
                    )
                )
            }

            if (config.showZones && isBaseCandle && isBearImpulse) {
                activeZones.add(
                    MutableTbtZone(
                        id = "SUPPLY_$i",
                        type = TbtZoneType.SUPPLY,
                        startTimeMillis = c1.openTime,
                        top = c1.high,
                        bottom = min(c1.open, c1.close),
                        tapped = false,
                        label = if (isBear2) "DBD Supply" else "RBD Supply"
                    )
                )
            }

            // 4. Manage Active Zones & Power Shift
            for (j in activeZones.indices.reversed()) {
                val z = activeZones[j]
                if (z.type == TbtZoneType.DEMAND) {
                    if (c.close < z.bottom) {
                        activeZones.removeAt(j)
                    } else if (!z.tapped && c.low <= z.top && c.close > z.bottom && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBullPower = c.low
                    }
                } else if (z.type == TbtZoneType.SUPPLY) {
                    if (c.close > z.top) {
                        activeZones.removeAt(j)
                    } else if (!z.tapped && c.high >= z.bottom && c.close < z.top && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBearPower = c.high
                    }
                }
            }

            val bullPwr = activeBullPower
            if (bullPwr != null && c.close < bullPwr) {
                if (config.showPowerShift) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "aboveBar",
                        colorHex = "#ff1744",
                        shape = "arrowDown",
                        text = "Buyer Power Broken!",
                        priority = 2
                    )
                }
                lastSignal = "Buyer Power Broken (Bearish)"
                activeBullPower = null
            }

            val bearPwr = activeBearPower
            if (bearPwr != null && c.close > bearPwr) {
                if (config.showPowerShift) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "belowBar",
                        colorHex = "#00c853",
                        shape = "arrowUp",
                        text = "Seller Power Broken!",
                        priority = 2
                    )
                }
                lastSignal = "Seller Power Broken (Bullish)"
                activeBearPower = null
            }

            // 5. FVG & Volume Imbalance (VI)
            val bullFvg = c.low > c2.high && isBull1 && (c.low - c2.high) > atr * 0.15
            val bearFvg = c.high < c2.low && isBear1 && (c2.low - c.high) > atr * 0.15

            if (bullFvg) {
                activeZones.add(
                    MutableTbtZone(
                        id = "FVG_BULL_$i",
                        type = TbtZoneType.FVG_BULL,
                        startTimeMillis = c2.openTime,
                        top = c.low,
                        bottom = c2.high,
                        tapped = false,
                        label = "Bull FVG"
                    )
                )
            }
            if (bearFvg) {
                activeZones.add(
                    MutableTbtZone(
                        id = "FVG_BEAR_$i",
                        type = TbtZoneType.FVG_BEAR,
                        startTimeMillis = c2.openTime,
                        top = c2.low,
                        bottom = c.high,
                        tapped = false,
                        label = "Bear FVG"
                    )
                )
            }

            for (j in activeZones.indices.reversed()) {
                val z = activeZones[j]
                if (z.type == TbtZoneType.FVG_BULL && c.close < z.bottom) {
                    activeZones.removeAt(j)
                } else if (z.type == TbtZoneType.FVG_BEAR && c.close > z.top) {
                    activeZones.removeAt(j)
                }
            }

            val currBodyTop = max(c.open, c.close)
            val currBodyBot = min(c.open, c.close)
            val prevBodyTop = max(c1.open, c1.close)
            val prevBodyBot = min(c1.open, c1.close)

            val bullVi = currBodyBot > prevBodyTop && c.low <= c1.high && isBull && isBull1
            val bearVi = currBodyTop < prevBodyBot && c.high >= c1.low && isBear && isBear1

            if (config.showSignals) {
                if (bullVi) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "belowBar",
                        colorHex = "#00e5ff",
                        shape = "circle",
                        text = "VI",
                        priority = 1
                    )
                }
                if (bearVi) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "aboveBar",
                        colorHex = "#ff9100",
                        shape = "circle",
                        text = "VI",
                        priority = 1
                    )
                }
            }

            // 6. 2-Candle Liquidity Pool Grab
            val curSwingLow = lastSwingLow
            val curSwingHigh = lastSwingHigh
            val bullSweep = curSwingLow != null && c.low < curSwingLow && c.close > curSwingLow && isBull
            val bearSweep = curSwingHigh != null && c.high > curSwingHigh && c.close < curSwingHigh && isBear

            if (bullSweep) {
                if (config.showSignals) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "belowBar",
                        colorHex = "#00e676",
                        shape = "circle",
                        text = "Liq Grab",
                        priority = 2
                    )
                }
                lastSignal = "Bullish Liquidity Grab"
            }
            if (bearSweep) {
                if (config.showSignals) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "aboveBar",
                        colorHex = "#ff5252",
                        shape = "circle",
                        text = "Liq Grab",
                        priority = 2
                    )
                }
                lastSignal = "Bearish Liquidity Grab"
            }

            // 7. TBT 3-Candle Reversal
            val bull3cr = isBear2 && c1.low < c2.low && isBull && c.close > max(c1.high, c2.high)
            val bear3cr = isBull2 && c1.high > c2.high && isBear && c.close < min(c1.low, c2.low)

            if (bull3cr) {
                if (config.showSignals) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "belowBar",
                        colorHex = "#00c853",
                        shape = "arrowUp",
                        text = "3CR BUY",
                        priority = 3
                    )
                }
                lastSignal = "3CR BUY Confirmed"
            }
            if (bear3cr) {
                if (config.showSignals) {
                    addMarker(
                        timeMillis = c.openTime,
                        position = "aboveBar",
                        colorHex = "#ff1744",
                        shape = "arrowDown",
                        text = "3CR SELL",
                        priority = 3
                    )
                }
                lastSignal = "3CR SELL Confirmed"
            }
        }

        // Convert active Demand & Supply zones (last 4) to horizontal PriceLines
        val priceLines = mutableListOf<TbtPriceLine>()
        val recentZones = activeZones
            .filter { it.type == TbtZoneType.DEMAND || it.type == TbtZoneType.SUPPLY }
            .takeLast(4)

        if (config.showZones) {
            for (z in recentZones) {
                if (z.type == TbtZoneType.DEMAND) {
                    priceLines.add(
                        TbtPriceLine(
                            price = z.top,
                            title = z.label,
                            colorHex = if (z.tapped) "rgba(0, 200, 83, 0.45)" else "#00c853",
                            lineStyle = if (z.tapped) 2 else 0,
                            lineWidth = 1,
                            category = TbtPriceLineCategory.ZONE
                        )
                    )
                } else if (z.type == TbtZoneType.SUPPLY) {
                    priceLines.add(
                        TbtPriceLine(
                            price = z.bottom,
                            title = z.label,
                            colorHex = if (z.tapped) "rgba(255, 23, 68, 0.45)" else "#ff1744",
                            lineStyle = if (z.tapped) 2 else 0,
                            lineWidth = 1,
                            category = TbtPriceLineCategory.ZONE
                        )
                    )
                }
            }
        }

        if (config.showPowerShift) {
            activeBullPower?.let { price ->
                priceLines.add(
                    TbtPriceLine(
                        price = price,
                        title = "⚡ Buyer Power Shift",
                        colorHex = "#ffd600",
                        lineStyle = 2,
                        lineWidth = 2,
                        category = TbtPriceLineCategory.POWER_SHIFT
                    )
                )
            }
            activeBearPower?.let { price ->
                priceLines.add(
                    TbtPriceLine(
                        price = price,
                        title = "⚡ Seller Power Shift",
                        colorHex = "#ffd600",
                        lineStyle = 2,
                        lineWidth = 2,
                        category = TbtPriceLineCategory.POWER_SHIFT
                    )
                )
            }
        }

        if (config.showOverlap) {
            for (ov in overlappingLevels) {
                priceLines.add(
                    TbtPriceLine(
                        price = ov.price,
                        title = "Overlapping (${ov.type} POI)",
                        colorHex = "#aa00ff",
                        lineStyle = 1,
                        lineWidth = 1,
                        category = TbtPriceLineCategory.OVERLAP
                    )
                )
            }
        }

        val lastCandle = candles.last()
        val ema20 = candles.takeLast(20).map { it.close }.average()
        val bias = when {
            lastCandle.close > ema20 * 1.001 -> TbtMarketBias.BULLISH
            lastCandle.close < ema20 * 0.999 -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

        val markers = markerMap.values
            .map { it.second }
            .sortedBy { it.timeMillis }

        return TbtSmcOutput(
            markers = markers,
            priceLines = priceLines,
            zones = activeZones.map { it.toImmutable() },
            summary = TbtConditionSummary(
                bias = bias,
                activeBullPower = activeBullPower,
                activeBearPower = activeBearPower,
                lastSignal = lastSignal
            )
        )
    }
}
