package com.example.domain.indicator

import com.example.domain.model.Candle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class TbtZoneType {
    DEMAND,
    SUPPLY,
    FVG_BULL,
    FVG_BEAR
}

data class TbtZone(
    val id: String,
    val type: TbtZoneType,
    val label: String,
    val startTimeMillis: Long,
    val top: Double,
    val bottom: Double,
    val tapped: Boolean
)

enum class TbtPriceLineCategory {
    ZONE,
    POWER_SHIFT,
    OVERLAP,
    VOL_IMBALANCE,
    TRADE_ENTRY,
    TRADE_SL,
    TRADE_TP
}

data class TbtPriceLine(
    val price: Double,
    val title: String,
    val colorHex: String,
    val lineStyle: Int, // 0 = Solid, 1 = Dotted, 2 = Dashed
    val lineWidth: Int, // 1 | 2 | 3 | 4
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

enum class TbtTradeDirection(val label: String) {
    BUY("BUY"),
    SELL("SELL")
}

data class TbtChecklistItem(
    val label: String,
    val met: Boolean
)

data class TbtTradeSetup(
    val direction: TbtTradeDirection,
    val triggerName: String,
    val reason: String,
    val grade: String, // "A+", "A", "B"
    val score: Int, // 0..5
    val entryPrice: Double,
    val retracementEntryPrice: Double? = null,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskReward: Double,
    val riskPercent: Double,
    val htfLabel: String? = null,
    val htfBias: TbtMarketBias? = null,
    val volumeConfirmed: Boolean = false,
    val checklist: List<TbtChecklistItem> = emptyList(),
    val signalTimeMillis: Long = 0L
)

data class TbtConditionSummary(
    val bias: TbtMarketBias = TbtMarketBias.SIDEWAYS,
    val htfLabel: String? = null,
    val htfBias: TbtMarketBias? = null,
    val activeBullPower: Double? = null,
    val activeBearPower: Double? = null,
    val lastSignal: String = "Waiting for data..."
)

data class TbtBacktestReport(
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRate: Int? = null,
    val netR: Double? = null
)

data class TbtSmcConfig(
    val swingLen: Int = 5,
    val showZones: Boolean = true,
    val showPowerShift: Boolean = true,
    val showOverlap: Boolean = true,
    val showSignals: Boolean = true,
    val showHud: Boolean = true,
    val autoHideOnHtf: Boolean = true,
    val targetRR: Double = 3.0,
    val slBufferPct: Double = 0.05,
    val showVolImbalanceLine: Boolean = true,
    val showZoneTags: Boolean = true
)

data class TbtSmcOutput(
    val markers: List<TbtMarker> = emptyList(),
    val priceLines: List<TbtPriceLine> = emptyList(),
    val zones: List<TbtZone> = emptyList(),
    val summary: TbtConditionSummary = TbtConditionSummary(),
    val backtestReport: TbtBacktestReport = TbtBacktestReport(),
    val activeSetup: TbtTradeSetup? = null
) {
    companion object {
        val EMPTY = TbtSmcOutput()
    }
}

private data class MutableTbtZone(
    val id: String,
    val type: TbtZoneType,
    var label: String,
    val startTimeMillis: Long,
    val top: Double,
    val bottom: Double,
    var tapped: Boolean
) {
    fun toImmutable(): TbtZone = TbtZone(
        id = id,
        type = type,
        label = label,
        startTimeMillis = startTimeMillis,
        top = top,
        bottom = bottom,
        tapped = tapped
    )
}

private data class OverlapLevel(
    val price: Double,
    val type: String // "BUY" | "SELL"
)

private data class SimulatedTrade(
    val isLong: Boolean,
    val triggerName: String,
    val reason: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val poiAligned: Boolean,
    val powerAligned: Boolean,
    val volumeConfirmed: Boolean,
    val signalTimeMillis: Long
)

private data class HtfAnalysis(
    val bias: TbtMarketBias,
    val demandTops: List<Double>,
    val demandBottoms: List<Double>,
    val supplyTops: List<Double>,
    val supplyBottoms: List<Double>
)

object TbtSmcEngine {

    /**
     * Computes live Higher-Timeframe (HTF) Trend Bias + HTF Demand/Supply POIs
     * from real exchange candles fetched via Binance API.
     */
    fun analyzeHigherTimeframe(htfCandles: List<Candle>): TbtMarketBias? {
        return computeHtfAnalysis(htfCandles)?.bias
    }

    private fun computeHtfAnalysis(htfCandles: List<Candle>): HtfAnalysis? {
        if (htfCandles.size < 20) return null
        val closes = htfCandles.map { it.close }
        val lastClose = closes.last()
        val sma20 = closes.takeLast(20).average()

        // Simple causal swing high/low check on HTF
        val recentSlice = htfCandles.takeLast(min(40, htfCandles.size))
        val half = recentSlice.size / 2
        val firstHalfHigh = recentSlice.take(half).maxOf { it.high }
        val secondHalfHigh = recentSlice.drop(half).maxOf { it.high }
        val firstHalfLow = recentSlice.take(half).minOf { it.low }
        val secondHalfLow = recentSlice.drop(half).minOf { it.low }

        val bullStructure = secondHalfHigh > firstHalfHigh && secondHalfLow >= firstHalfLow * 0.999
        val bearStructure = secondHalfLow < firstHalfLow && secondHalfHigh <= firstHalfHigh * 1.001

        val bias = when {
            lastClose > sma20 * 1.0015 || (lastClose > sma20 && bullStructure) -> TbtMarketBias.BULLISH
            lastClose < sma20 * 0.9985 || (lastClose < sma20 && bearStructure) -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

        // Track recent unmitigated HTF RBR/DBR Demand and DBD/RBD Supply boundaries
        val dTops = mutableListOf<Double>()
        val dBots = mutableListOf<Double>()
        val sTops = mutableListOf<Double>()
        val sBots = mutableListOf<Double>()

        val bodies = DoubleArray(htfCandles.size) { abs(htfCandles[it].close - htfCandles[it].open) }
        for (i in 15 until htfCandles.size) {
            val c = htfCandles[i]
            val c1 = htfCandles[i - 1]
            var sum1 = 0.0
            var sum0 = 0.0
            for (k in (i - 14) until i) sum1 += bodies[k]
            for (k in (i - 13)..i) sum0 += bodies[k]
            val avg1 = sum1 / 14.0
            val avg0 = sum0 / 14.0
            val isBase = bodies[i - 1] < avg1 * 0.95
            if (isBase && c.close > c.open && bodies[i] > avg0 * 1.25 && c.close > c1.high) {
                dTops.add(max(c1.open, c1.close))
                dBots.add(c1.low)
                if (dTops.size > 4) {
                    dTops.removeAt(0)
                    dBots.removeAt(0)
                }
            } else if (isBase && c.close < c.open && bodies[i] > avg0 * 1.25 && c.close < c1.low) {
                sTops.add(c1.high)
                sBots.add(min(c1.open, c1.close))
                if (sTops.size > 4) {
                    sTops.removeAt(0)
                    sBots.removeAt(0)
                }
            }
        }

        return HtfAnalysis(
            bias = bias,
            demandTops = dTops,
            demandBottoms = dBots,
            supplyTops = sTops,
            supplyBottoms = sBots
        )
    }

    fun calculate(
        candles: List<Candle>,
        config: TbtSmcConfig = TbtSmcConfig(),
        htfCandles: List<Candle> = emptyList(),
        htfLabel: String? = null
    ): TbtSmcOutput {
        val htfAnalysis = computeHtfAnalysis(htfCandles)
        val htfBias = htfAnalysis?.bias

        if (candles.size < 25) {
            return TbtSmcOutput(
                markers = emptyList(),
                priceLines = emptyList(),
                zones = emptyList(),
                summary = TbtConditionSummary(
                    bias = TbtMarketBias.SIDEWAYS,
                    htfLabel = htfLabel,
                    htfBias = htfBias,
                    activeBullPower = null,
                    activeBearPower = null,
                    lastSignal = "Waiting for data..."
                ),
                backtestReport = TbtBacktestReport(),
                activeSetup = null
            )
        }

        val markersMap = LinkedHashMap<Long, Pair<Int, TbtMarker>>()
        val activeZones = mutableListOf<MutableTbtZone>()
        val overlapLevels = mutableListOf<OverlapLevel>()

        var activeBullPower: Double? = null
        var activeBearPower: Double? = null
        var lastSwingHigh: Double? = null
        var lastSwingLow: Double? = null
        var lastSignal = "None"
        var lastDemandTapIndex = -100
        var lastSupplyTapIndex = -100
        var lastSellerPowerBrokenIndex = -100
        var lastBuyerPowerBrokenIndex = -100

        // Single Volume Imbalance entry level tracker
        var latestViPrice: Double? = null
        var latestViIsBull = true
        var latestFvgMidPrice: Double? = null
        var latestFvgIsBull = true

        // Causal historical backtest & active trade setup tracker (zero lookahead — safe for Bar Replay)
        val safeRR = config.targetRR.coerceIn(1.0, 20.0)
        val safeBufferFraction = config.slBufferPct.coerceIn(0.0, 5.0) / 100.0
        var openTrade: SimulatedTrade? = null
        var wins = 0
        var losses = 0

        val bodySizes = DoubleArray(candles.size)
        val trueRanges = DoubleArray(candles.size)
        val volumes = DoubleArray(candles.size)
        for (idx in candles.indices) {
            val c = candles[idx]
            bodySizes[idx] = abs(c.close - c.open)
            volumes[idx] = c.volume
            trueRanges[idx] = if (idx == 0) {
                c.high - c.low
            } else {
                val prevClose = candles[idx - 1].close
                max(
                    c.high - c.low,
                    max(abs(c.high - prevClose), abs(c.low - prevClose))
                )
            }
        }

        fun getSma(arr: DoubleArray, index: Int, period: Int): Double {
            if (index < period) return arr.getOrElse(index) { 0.0 }
            var sum = 0.0
            for (i in (index - period + 1)..index) sum += arr[i]
            return sum / period.toDouble()
        }

        fun addMarker(marker: TbtMarker, priority: Int = 1) {
            val key = marker.timeMillis
            val existing = markersMap[key]
            if (existing == null || existing.first <= priority) {
                markersMap[key] = priority to marker
            }
        }

        fun isNearBuyPoi(barIndex: Int, low: Double, close: Double, atr: Double): Boolean {
            if (barIndex - lastDemandTapIndex <= 6) return true
            val nearLocalDemand = activeZones.any { z ->
                z.type == TbtZoneType.DEMAND && low <= z.top + atr * 0.5 && close >= z.bottom - atr * 0.3
            }
            if (nearLocalDemand) return true
            val nearOverlapBuy = overlapLevels.any { ov ->
                ov.type == "BUY" && abs(low - ov.price) <= atr * 0.9
            }
            if (nearOverlapBuy) return true
            if (htfAnalysis != null) {
                for (idx in htfAnalysis.demandTops.indices) {
                    if (low <= htfAnalysis.demandTops[idx] + atr && close >= htfAnalysis.demandBottoms[idx] - atr) {
                        return true
                    }
                }
            }
            return false
        }

        fun isNearSellPoi(barIndex: Int, high: Double, close: Double, atr: Double): Boolean {
            if (barIndex - lastSupplyTapIndex <= 6) return true
            val nearLocalSupply = activeZones.any { z ->
                z.type == TbtZoneType.SUPPLY && high >= z.bottom - atr * 0.5 && close <= z.top + atr * 0.3
            }
            if (nearLocalSupply) return true
            val nearOverlapSell = overlapLevels.any { ov ->
                ov.type == "SELL" && abs(high - ov.price) <= atr * 0.9
            }
            if (nearOverlapSell) return true
            if (htfAnalysis != null) {
                for (idx in htfAnalysis.supplyTops.indices) {
                    if (high >= htfAnalysis.supplyBottoms[idx] - atr && close <= htfAnalysis.supplyTops[idx] + atr) {
                        return true
                    }
                }
            }
            return false
        }

        val sLen = config.swingLen

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
            val avgVol = getSma(volumes, i, 20)
            val volumeConfirmed = avgVol <= 0.0 || c.volume >= avgVol * 1.15

            // 0. Evaluate active simulated trade conservatively (SL checked before TP on same bar)
            openTrade?.let { trade ->
                if (trade.isLong) {
                    if (c.low <= trade.stopLoss) {
                        losses++
                        openTrade = null
                    } else if (c.high >= trade.takeProfit) {
                        wins++
                        openTrade = null
                    }
                } else {
                    if (c.high >= trade.stopLoss) {
                        losses++
                        openTrade = null
                    } else if (c.low <= trade.takeProfit) {
                        wins++
                        openTrade = null
                    }
                }
            }

            // 1. Swing High / Low Detection
            if (i >= sLen * 2) {
                val pivotIdx = i - sLen
                val pHigh = candles[pivotIdx].high
                val pLow = candles[pivotIdx].low
                var isPivotHigh = true
                var isPivotLow = true

                for (k in (pivotIdx - sLen)..(pivotIdx + sLen)) {
                    if (candles[k].high > pHigh) isPivotHigh = false
                    if (candles[k].low < pLow) isPivotLow = false
                }
                if (isPivotHigh) lastSwingHigh = pHigh
                if (isPivotLow) lastSwingLow = pLow
            }

            // 2. Overlapping Level (S/R Flip Hurdle)
            if (config.showOverlap) {
                val sh = lastSwingHigh
                if (sh != null && c1.close <= sh && c.close > sh) {
                    overlapLevels.add(OverlapLevel(price = sh, type = "BUY"))
                    if (overlapLevels.size > 2) overlapLevels.removeAt(0)
                }
                val sl = lastSwingLow
                if (sl != null && c1.close >= sl && c.close < sl) {
                    overlapLevels.add(OverlapLevel(price = sl, type = "SELL"))
                    if (overlapLevels.size > 2) overlapLevels.removeAt(0)
                }
            }

            // 3. Demand & Supply Zone Creation (RBR, DBR, DBD, RBD)
            val isBaseCandle = bodySizes[i - 1] < avgBody1 * 0.9
            val isBullImpulse = isBull && bodySizes[i] > avgBody * 1.3 && c.close > c1.high
            val isBearImpulse = isBear && bodySizes[i] > avgBody * 1.3 && c.close < c1.low

            if (isBaseCandle && isBullImpulse) {
                val tagShort = if (isBull2) "RBR" else "DBR"
                if (config.showZones) {
                    activeZones.add(
                        MutableTbtZone(
                            id = "DEMAND_$i",
                            type = TbtZoneType.DEMAND,
                            label = "$tagShort Demand",
                            startTimeMillis = c1.openTime,
                            top = max(c1.open, c1.close),
                            bottom = c1.low,
                            tapped = false
                        )
                    )
                }
                if (config.showZoneTags) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c1.openTime,
                            position = "belowBar",
                            colorHex = "#00c853",
                            shape = "circle",
                            text = tagShort
                        ),
                        priority = 1
                    )
                }
            }

            if (isBaseCandle && isBearImpulse) {
                val tagShort = if (isBear2) "DBD" else "RBD"
                if (config.showZones) {
                    activeZones.add(
                        MutableTbtZone(
                            id = "SUPPLY_$i",
                            type = TbtZoneType.SUPPLY,
                            label = "$tagShort Supply",
                            startTimeMillis = c1.openTime,
                            top = c1.high,
                            bottom = min(c1.open, c1.close),
                            tapped = false
                        )
                    )
                }
                if (config.showZoneTags) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c1.openTime,
                            position = "aboveBar",
                            colorHex = "#ff1744",
                            shape = "circle",
                            text = tagShort
                        ),
                        priority = 1
                    )
                }
            }

            // 4. Manage Active Zones & Detect TBT Power Shifting
            for (zIdx in activeZones.lastIndex downTo 0) {
                val z = activeZones[zIdx]
                if (z.type == TbtZoneType.DEMAND) {
                    if (c.close < z.bottom) {
                        activeZones.removeAt(zIdx)
                    } else if (!z.tapped && c.low <= z.top && c.close > z.bottom && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBullPower = c.low
                        lastDemandTapIndex = i
                    }
                } else if (z.type == TbtZoneType.SUPPLY) {
                    if (c.close > z.top) {
                        activeZones.removeAt(zIdx)
                    } else if (!z.tapped && c.high >= z.bottom && c.close < z.top && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBearPower = c.high
                        lastSupplyTapIndex = i
                    }
                }
            }

            // Check if Shifted Power Level is Broken
            var buyerPowerBrokenNow = false
            var sellerPowerBrokenNow = false

            val bullPwr = activeBullPower
            if (bullPwr != null && c.close < bullPwr) {
                buyerPowerBrokenNow = true
                lastBuyerPowerBrokenIndex = i
                if (config.showPowerShift) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff1744",
                            shape = "arrowDown",
                            text = "Buyer Power Broken!"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "Buyer Power Broken (SELL)"
                activeBullPower = null
            }

            val bearPwr = activeBearPower
            if (bearPwr != null && c.close > bearPwr) {
                sellerPowerBrokenNow = true
                lastSellerPowerBrokenIndex = i
                if (config.showPowerShift) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00c853",
                            shape = "arrowUp",
                            text = "Seller Power Broken!"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "Seller Power Broken (BUY)"
                activeBearPower = null
            }

            // 5. Fair Value Gap (FVG) & Volume Imbalance (VI)
            val bullFvg = c.low > c2.high && isBull1 && c.low - c2.high > atr * 0.15
            val bearFvg = c.high < c2.low && isBear1 && c2.low - c.high > atr * 0.15

            if (bullFvg) {
                latestFvgMidPrice = (c.low + c2.high) * 0.5
                latestFvgIsBull = true
                if (config.showZones) {
                    activeZones.add(
                        MutableTbtZone(
                            id = "FVG_BULL_$i",
                            type = TbtZoneType.FVG_BULL,
                            label = "Bull FVG",
                            startTimeMillis = c2.openTime,
                            top = c.low,
                            bottom = c2.high,
                            tapped = false
                        )
                    )
                }
            }
            if (bearFvg) {
                latestFvgMidPrice = (c2.low + c.high) * 0.5
                latestFvgIsBull = false
                if (config.showZones) {
                    activeZones.add(
                        MutableTbtZone(
                            id = "FVG_BEAR_$i",
                            type = TbtZoneType.FVG_BEAR,
                            label = "Bear FVG",
                            startTimeMillis = c2.openTime,
                            top = c2.low,
                            bottom = c.high,
                            tapped = false
                        )
                    )
                }
            }

            for (zIdx in activeZones.lastIndex downTo 0) {
                val z = activeZones[zIdx]
                if (z.type == TbtZoneType.FVG_BULL && c.close < z.bottom) activeZones.removeAt(zIdx)
                if (z.type == TbtZoneType.FVG_BEAR && c.close > z.top) activeZones.removeAt(zIdx)
            }

            val prevBodyTop = max(c1.open, c1.close)
            val prevBodyBot = min(c1.open, c1.close)
            val currBodyTop = max(c.open, c.close)
            val currBodyBot = min(c.open, c.close)

            val bullVi = currBodyBot > prevBodyTop && c.low <= c1.high && isBull && isBull1
            val bearVi = currBodyTop < prevBodyBot && c.high >= c1.low && isBear && isBear1

            if (bullVi) {
                latestViPrice = (prevBodyTop + currBodyBot) * 0.5
                latestViIsBull = true
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00e5ff",
                            shape = "circle",
                            text = "VI"
                        ),
                        priority = 1
                    )
                }
            }
            if (bearVi) {
                latestViPrice = (prevBodyBot + currBodyTop) * 0.5
                latestViIsBull = false
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff9100",
                            shape = "circle",
                            text = "VI"
                        ),
                        priority = 1
                    )
                }
            }

            // 6. 2-Candle Liquidity Pool Grab (pure TBT SMC — no ICT session gating)
            val slGrab = lastSwingLow
            val shGrab = lastSwingHigh
            val bullLiqGrab = slGrab != null && c.low < slGrab && c.close > slGrab && isBull
            val bearLiqGrab = shGrab != null && c.high > shGrab && c.close < shGrab && isBear

            if (bullLiqGrab) {
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00e676",
                            shape = "circle",
                            text = "Liq Grab"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "Bullish Liquidity Grab"
            }
            if (bearLiqGrab) {
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff5252",
                            shape = "circle",
                            text = "Liq Grab"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "Bearish Liquidity Grab"
            }

            // 7. TBT 3-Candle Reversal (3CR BUY / 3CR SELL)
            val bull3cr = isBear2 && c1.low < c2.low && isBull && c.close > max(c1.high, c2.high)
            val bear3cr = isBull2 && c1.high > c2.high && isBear && c.close < min(c1.low, c2.low)

            val nearBuyPoi = isNearBuyPoi(i, min(c.low, c1.low), c.close, atr)
            val nearSellPoi = isNearSellPoi(i, max(c.high, c1.high), c.close, atr)
            val bullPowerAligned = activeBullPower != null || (i - lastSellerPowerBrokenIndex <= 8)
            val bearPowerAligned = activeBearPower != null || (i - lastBuyerPowerBrokenIndex <= 8)

            if (bull3cr) {
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00c853",
                            shape = "arrowUp",
                            text = "3CR BUY"
                        ),
                        priority = 3
                    )
                }
                lastSignal = "3CR BUY Confirmed"
                if (openTrade == null) {
                    val entry = c.close
                    val rawSl = min(c.low, min(c1.low, c2.low))
                    val sl = rawSl * (1.0 - safeBufferFraction)
                    val risk = entry - sl
                    if (risk > 0.0) {
                        val reason = when {
                            nearBuyPoi && bullPowerAligned -> "Demand/Overlap Tap + Power Shift + 3CR BUY"
                            nearBuyPoi -> "Demand/Overlap POI + 3CR BUY"
                            bullPowerAligned -> "Seller Power Broken + 3CR BUY"
                            else -> "TBT 3-Candle Reversal BUY"
                        }
                        openTrade = SimulatedTrade(
                            isLong = true,
                            triggerName = "3CR BUY",
                            reason = reason,
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry + risk * safeRR,
                            poiAligned = nearBuyPoi,
                            powerAligned = bullPowerAligned,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                }
            }
            if (bear3cr) {
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff1744",
                            shape = "arrowDown",
                            text = "3CR SELL"
                        ),
                        priority = 3
                    )
                }
                lastSignal = "3CR SELL Confirmed"
                if (openTrade == null) {
                    val entry = c.close
                    val rawSl = max(c.high, max(c1.high, c2.high))
                    val sl = rawSl * (1.0 + safeBufferFraction)
                    val risk = sl - entry
                    if (risk > 0.0) {
                        val reason = when {
                            nearSellPoi && bearPowerAligned -> "Supply/Overlap Tap + Power Shift + 3CR SELL"
                            nearSellPoi -> "Supply/Overlap POI + 3CR SELL"
                            bearPowerAligned -> "Buyer Power Broken + 3CR SELL"
                            else -> "TBT 3-Candle Reversal SELL"
                        }
                        openTrade = SimulatedTrade(
                            isLong = false,
                            triggerName = "3CR SELL",
                            reason = reason,
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry - risk * safeRR,
                            poiAligned = nearSellPoi,
                            powerAligned = bearPowerAligned,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                }
            }

            // Also track Liquidity Grab & Power Break setups when no trade is active
            if (openTrade == null) {
                if (bullLiqGrab) {
                    val entry = c.close
                    val sl = c.low * (1.0 - safeBufferFraction)
                    val risk = entry - sl
                    if (risk > 0.0) {
                        openTrade = SimulatedTrade(
                            isLong = true,
                            triggerName = "Liq Grab BUY",
                            reason = if (nearBuyPoi) "2-Candle Liq Grab @ Demand/Overlap POI" else "2-Candle Liquidity Pool Grab BUY",
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry + risk * safeRR,
                            poiAligned = nearBuyPoi,
                            powerAligned = bullPowerAligned,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                } else if (bearLiqGrab) {
                    val entry = c.close
                    val sl = c.high * (1.0 + safeBufferFraction)
                    val risk = sl - entry
                    if (risk > 0.0) {
                        openTrade = SimulatedTrade(
                            isLong = false,
                            triggerName = "Liq Grab SELL",
                            reason = if (nearSellPoi) "2-Candle Liq Grab @ Supply/Overlap POI" else "2-Candle Liquidity Pool Grab SELL",
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry - risk * safeRR,
                            poiAligned = nearSellPoi,
                            powerAligned = bearPowerAligned,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                } else if (sellerPowerBrokenNow && isBull) {
                    val entry = c.close
                    val rawSl = min(c.low, c1.low)
                    val sl = rawSl * (1.0 - safeBufferFraction)
                    val risk = entry - sl
                    if (risk > 0.0) {
                        openTrade = SimulatedTrade(
                            isLong = true,
                            triggerName = "Power Break BUY",
                            reason = "Seller Power Broken (Buyers in Control)",
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry + risk * safeRR,
                            poiAligned = nearBuyPoi,
                            powerAligned = true,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                } else if (buyerPowerBrokenNow && isBear) {
                    val entry = c.close
                    val rawSl = max(c.high, c1.high)
                    val sl = rawSl * (1.0 + safeBufferFraction)
                    val risk = sl - entry
                    if (risk > 0.0) {
                        openTrade = SimulatedTrade(
                            isLong = false,
                            triggerName = "Power Break SELL",
                            reason = "Buyer Power Broken (Sellers in Control)",
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit = entry - risk * safeRR,
                            poiAligned = nearSellPoi,
                            powerAligned = true,
                            volumeConfirmed = volumeConfirmed,
                            signalTimeMillis = c.openTime
                        )
                    }
                }
            }
        }

        val priceLines = mutableListOf<TbtPriceLine>()
        val recentZones = activeZones.takeLast(4).map { it.toImmutable() }

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
            for (ov in overlapLevels) {
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

        val viCandidatePrice = latestViPrice ?: latestFvgMidPrice
        val viCandidateBull = if (latestViPrice != null) latestViIsBull else latestFvgIsBull

        // Single Volume Imbalance Entry Line
        if (config.showVolImbalanceLine && viCandidatePrice != null && viCandidatePrice > 0.0) {
            priceLines.add(
                TbtPriceLine(
                    price = viCandidatePrice,
                    title = "Vol Imbalance",
                    colorHex = if (viCandidateBull) "#00e5ff" else "#ff9100",
                    lineStyle = 2,
                    lineWidth = 1,
                    category = TbtPriceLineCategory.VOL_IMBALANCE
                )
            )
        }

        val lastCandle = candles.last()
        val recentCloses = candles.takeLast(20)
        val ema20 = recentCloses.sumOf { it.close } / recentCloses.size.toDouble()
        val bias = when {
            lastCandle.close > ema20 * 1.001 -> TbtMarketBias.BULLISH
            lastCandle.close < ema20 * 0.999 -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

        // Build actionable TBT SMC BUY / SELL Trade Setup (with Entry, optional VI Retrace, SL, TP & checklist)
        val activeSetup = openTrade?.let { active ->
            val direction = if (active.isLong) TbtTradeDirection.BUY else TbtTradeDirection.SELL
            val effectiveHtfBias = htfBias ?: bias
            val htfAligned = (active.isLong && effectiveHtfBias == TbtMarketBias.BULLISH) ||
                (!active.isLong && effectiveHtfBias == TbtMarketBias.BEARISH)
            val riskPct = if (active.entryPrice > 0.0) {
                (abs(active.entryPrice - active.stopLoss) / active.entryPrice) * 100.0
            } else 0.0

            val validRetraceVi = viCandidatePrice?.takeIf { vi ->
                if (active.isLong) {
                    vi > active.stopLoss && vi < active.entryPrice
                } else {
                    vi < active.stopLoss && vi > active.entryPrice
                }
            }

            val htfCheckLabel = if (htfLabel != null && htfBias != null) {
                "$htfLabel API Trend (${htfBias.label})"
            } else {
                "Trend Bias (${bias.label})"
            }

            val checklist = listOf(
                TbtChecklistItem(htfCheckLabel, htfAligned),
                TbtChecklistItem("Demand/Supply or Overlap POI", active.poiAligned),
                TbtChecklistItem("TBT Power Shift Control", active.powerAligned),
                TbtChecklistItem("Trigger: ${active.triggerName}", true),
                TbtChecklistItem("Binance Volume Confirmation", active.volumeConfirmed)
            )
            val score = checklist.count { it.met }
            val grade = when {
                score >= 4 -> "A+"
                score == 3 -> "A"
                else -> "B"
            }

            if (config.showSignals) {
                val rrLabel = if (safeRR % 1.0 == 0.0) {
                    safeRR.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", safeRR)
                }
                priceLines.add(
                    TbtPriceLine(
                        price = active.entryPrice,
                        title = if (active.isLong) "BUY ENTRY" else "SELL ENTRY",
                        colorHex = if (active.isLong) "#00e676" else "#ff5252",
                        lineStyle = 0,
                        lineWidth = 1,
                        category = TbtPriceLineCategory.TRADE_ENTRY
                    )
                )
                priceLines.add(
                    TbtPriceLine(
                        price = active.stopLoss,
                        title = "SL",
                        colorHex = "#ff1744",
                        lineStyle = 1,
                        lineWidth = 1,
                        category = TbtPriceLineCategory.TRADE_SL
                    )
                )
                priceLines.add(
                    TbtPriceLine(
                        price = active.takeProfit,
                        title = "TP 1:$rrLabel",
                        colorHex = "#00c853",
                        lineStyle = 1,
                        lineWidth = 1,
                        category = TbtPriceLineCategory.TRADE_TP
                    )
                )
            }

            TbtTradeSetup(
                direction = direction,
                triggerName = active.triggerName,
                reason = active.reason,
                grade = grade,
                score = score,
                entryPrice = active.entryPrice,
                retracementEntryPrice = validRetraceVi,
                stopLoss = active.stopLoss,
                takeProfit = active.takeProfit,
                riskReward = safeRR,
                riskPercent = riskPct,
                htfLabel = htfLabel,
                htfBias = htfBias,
                volumeConfirmed = active.volumeConfirmed,
                checklist = checklist,
                signalTimeMillis = active.signalTimeMillis
            )
        }

        val sortedMarkers = markersMap.values
            .map { it.second }
            .sortedBy { it.timeMillis }

        val totalTrades = wins + losses
        val winRate = if (totalTrades > 0) ((wins * 100.0) / totalTrades.toDouble()).roundToInt() else null
        val netR = if (totalTrades > 0) {
            val rawNet = wins * safeRR - losses * 1.0
            (rawNet * 10.0).roundToInt() / 10.0
        } else {
            null
        }

        return TbtSmcOutput(
            markers = sortedMarkers,
            priceLines = priceLines,
            zones = recentZones,
            summary = TbtConditionSummary(
                bias = bias,
                htfLabel = htfLabel,
                htfBias = htfBias,
                activeBullPower = activeBullPower,
                activeBearPower = activeBearPower,
                lastSignal = lastSignal
            ),
            backtestReport = TbtBacktestReport(
                totalTrades = totalTrades,
                wins = wins,
                losses = losses,
                winRate = winRate,
                netR = netR
            ),
            activeSetup = activeSetup
        )
    }
}
