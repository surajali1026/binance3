package com.example.domain.indicator

import com.example.domain.model.Candle
import com.example.domain.model.Timeframe
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
    HTF_ZONE,
    POWER_SHIFT,
    OVERLAP,
    VOLUME_PROFILE,
    TRADE_ENTRY,
    TRADE_SL,
    TRADE_TP
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

enum class TbtTradeDirection(val label: String) {
    BUY("BUY"),
    SELL("SELL"),
    WAIT("WAIT")
}

data class TbtTimeframeBiasInfo(
    val timeframeLabel: String,
    val role: String, // "LTF", "HTF", "Macro"
    val bias: TbtMarketBias,
    val lastSignal: String
)

data class TbtTradeSetup(
    val direction: TbtTradeDirection = TbtTradeDirection.WAIT,
    val isConfirmedEntry: Boolean = false,
    val confidencePercent: Int = 0,
    val statusLabel: String = "Wait for setup",
    val entryPrice: Double = 0.0,
    val entryZoneLow: Double = 0.0,
    val entryZoneHigh: Double = 0.0,
    val stopLoss: Double = 0.0,
    val takeProfit1: Double = 0.0,
    val takeProfit2: Double = 0.0,
    val riskReward1: Double = 0.0,
    val riskReward2: Double = 0.0,
    val volumeRetraceEntry: Double? = null,
    val volumeProfileReason: String = "",
    val slReason: String = "",
    val tp1Reason: String = "",
    val tp2Reason: String = "",
    val confluenceReasons: List<String> = emptyList()
)

data class TbtConditionSummary(
    val bias: TbtMarketBias = TbtMarketBias.SIDEWAYS,
    val activeBullPower: Double? = null,
    val activeBearPower: Double? = null,
    val lastSignal: String = "Waiting for data...",
    val mtfBiases: List<TbtTimeframeBiasInfo> = emptyList(),
    val mtfAlignmentLabel: String = "Single TF",
    val dealingZoneLabel: String = "Equilibrium",
    val volumePocPrice: Double? = null,
    val volumeImbalanceLabel: String = "",
    val volumeSourcesLabel: String = "Futures",
    val tradeSetup: TbtTradeSetup = TbtTradeSetup()
)

data class TbtSmcConfig(
    val swingLen: Int = 5,
    val showZones: Boolean = true,
    val showPowerShift: Boolean = true,
    val showOverlap: Boolean = true,
    val showSignals: Boolean = true,
    val showHud: Boolean = true,
    val showTradeSetup: Boolean = true,
    val useMultiTimeframe: Boolean = true,
    val showVolumeProfile: Boolean = true
)

data class TbtMtfInput(
    val currentTfLabel: String = "15m",
    val htfLabel: String = "1H",
    val htfCandles: List<Candle> = emptyList(),
    val macroLabel: String = "4H",
    val macroCandles: List<Candle> = emptyList(),
    val spotCandles: List<Candle> = emptyList()
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

data class TbtMtfPair(
    val htf: Timeframe,
    val macro: Timeframe
)

object TbtMtfTimeframeResolver {
    fun resolve(active: Timeframe, available: List<Timeframe> = Timeframe.DEFAULTS): TbtMtfPair? {
        val pool = if (available.isNotEmpty()) available else Timeframe.DEFAULTS
        fun findByIdOrDuration(id: String, targetMs: Long): Timeframe {
            return pool.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: Timeframe.DEFAULTS.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: pool.minByOrNull { abs(it.durationMillis - targetMs) }
                ?: active
        }

        val (htf, macro) = when (active.id.lowercase()) {
            "1m", "3m" -> findByIdOrDuration("15m", 15 * Timeframe.MINUTE_MS) to
                findByIdOrDuration("1H", Timeframe.HOUR_MS)
            "5m", "9m" -> findByIdOrDuration("1H", Timeframe.HOUR_MS) to
                findByIdOrDuration("4H", 4 * Timeframe.HOUR_MS)
            "15m" -> findByIdOrDuration("1H", Timeframe.HOUR_MS) to
                findByIdOrDuration("4H", 4 * Timeframe.HOUR_MS)
            "30m", "45m" -> findByIdOrDuration("4H", 4 * Timeframe.HOUR_MS) to
                findByIdOrDuration("1D", Timeframe.DAY_MS)
            "1h", "2h" -> findByIdOrDuration("4H", 4 * Timeframe.HOUR_MS) to
                findByIdOrDuration("1D", Timeframe.DAY_MS)
            "4h", "6h", "8h", "12h" -> findByIdOrDuration("1D", Timeframe.DAY_MS) to
                findByIdOrDuration("1W", Timeframe.WEEK_MS)
            "1d" -> findByIdOrDuration("1W", Timeframe.WEEK_MS) to
                findByIdOrDuration("1M", Timeframe.MONTH_MS)
            "1w", "1mth" -> findByIdOrDuration("1M", Timeframe.MONTH_MS) to
                findByIdOrDuration("1M", Timeframe.MONTH_MS)
            else -> {
                if (active.durationMillis >= Timeframe.MONTH_MS) {
                    return null
                }
                val htfTarget = active.durationMillis * 4L
                val macroTarget = active.durationMillis * 16L
                val pickedHtf = pool.filter { it.durationMillis > active.durationMillis * 2L }
                    .minByOrNull { abs(it.durationMillis - htfTarget) }
                    ?: findByIdOrDuration("1H", htfTarget)
                val pickedMacro = pool.filter { it.durationMillis > pickedHtf.durationMillis * 2L }
                    .minByOrNull { abs(it.durationMillis - macroTarget) }
                    ?: findByIdOrDuration("4H", macroTarget)
                pickedHtf to pickedMacro
            }
        }
        if (htf.id == active.id && macro.id == active.id) return null
        return TbtMtfPair(htf = htf, macro = macro)
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

private data class SingleTfAnalysis(
    val bias: TbtMarketBias,
    val structureBias: Int, // 1 bull, -1 bear, 0 neutral
    val markers: List<TbtMarker>,
    val activeZones: List<TbtZone>,
    val overlappingLevels: List<OverlappingLevel>,
    val activeBullPower: Double?,
    val activeBearPower: Double?,
    val lastSignal: String,
    val lastSwingHigh: Double?,
    val lastSwingLow: Double?,
    val recentSwingHigh: Double,
    val recentSwingLow: Double,
    val atr: Double,
    val lastBullConfirmIdx: Int,
    val lastBearConfirmIdx: Int,
    val candleCount: Int
)

object TbtSmcEngine {

    fun calculate(
        candles: List<Candle>,
        config: TbtSmcConfig = TbtSmcConfig(),
        mtfInput: TbtMtfInput? = null
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

        val ltf = analyzeSingleTf(candles = candles, config = config, emitChartMarkers = true)

        val currentTfLabel = mtfInput?.currentTfLabel ?: "15m"
        val htfAnalysis = if (config.useMultiTimeframe && mtfInput != null && mtfInput.htfCandles.size >= 25) {
            analyzeSingleTf(
                candles = mtfInput.htfCandles,
                config = config.copy(showZones = true, showOverlap = true, showPowerShift = true),
                emitChartMarkers = false
            )
        } else null

        val macroAnalysis = if (config.useMultiTimeframe && mtfInput != null && mtfInput.macroCandles.size >= 25) {
            analyzeSingleTf(
                candles = mtfInput.macroCandles,
                config = config.copy(showZones = true, showOverlap = false, showPowerShift = true),
                emitChartMarkers = false
            )
        } else null

        // Build MTF bias summary list
        val mtfBiases = buildList {
            add(TbtTimeframeBiasInfo(currentTfLabel, "LTF", ltf.bias, ltf.lastSignal))
            if (mtfInput != null && htfAnalysis != null) {
                add(TbtTimeframeBiasInfo(mtfInput.htfLabel, "HTF", htfAnalysis.bias, htfAnalysis.lastSignal))
            }
            if (mtfInput != null && macroAnalysis != null && mtfInput.macroLabel != mtfInput.htfLabel) {
                add(TbtTimeframeBiasInfo(mtfInput.macroLabel, "Macro", macroAnalysis.bias, macroAnalysis.lastSignal))
            }
        }

        val spot = candles.last().close
        val dealingMid = (ltf.recentSwingHigh + ltf.recentSwingLow) / 2.0
        val dealingSpan = (ltf.recentSwingHigh - ltf.recentSwingLow).coerceAtLeast(1e-9)
        val dealingZoneLabel = when {
            spot < dealingMid - dealingSpan * 0.08 -> "Discount"
            spot > dealingMid + dealingSpan * 0.08 -> "Premium"
            else -> "Equilibrium"
        }

        val mtfAlignmentLabel = computeMtfAlignmentLabel(
            ltfBias = ltf.bias,
            htfBias = htfAnalysis?.bias,
            macroBias = macroAnalysis?.bias,
            hasMtf = htfAnalysis != null || macroAnalysis != null
        )

        val volumeProfile = analyzeVolumeProfile(
            candles = candles,
            spotCandles = mtfInput?.spotCandles.orEmpty(),
            spotPrice = spot
        )

        val tradeSetup = buildTradeSetup(
            candles = candles,
            ltf = ltf,
            htfLabel = mtfInput?.htfLabel ?: "HTF",
            htf = htfAnalysis,
            macroLabel = mtfInput?.macroLabel ?: "Macro",
            macro = macroAnalysis,
            dealingZoneLabel = dealingZoneLabel,
            volumeProfile = volumeProfile
        )

        // Assemble PriceLines
        val priceLines = mutableListOf<TbtPriceLine>()
        val recentLtfZones = ltf.activeZones
            .filter { it.type == TbtZoneType.DEMAND || it.type == TbtZoneType.SUPPLY }
            .takeLast(4)

        if (config.showZones) {
            for (z in recentLtfZones) {
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

            // Nearest HTF Key Demand & Supply zones plotted directly on active chart
            if (config.useMultiTimeframe && htfAnalysis != null) {
                val htfLabel = mtfInput?.htfLabel ?: "HTF"
                htfAnalysis.activeZones
                    .filter { it.type == TbtZoneType.DEMAND && it.top <= spot * 1.03 }
                    .maxByOrNull { it.top }
                    ?.let { z ->
                        priceLines.add(
                            TbtPriceLine(
                                price = z.top,
                                title = "$htfLabel Demand",
                                colorHex = "#00bfa5",
                                lineStyle = 2,
                                lineWidth = 1,
                                category = TbtPriceLineCategory.HTF_ZONE
                            )
                        )
                    }
                htfAnalysis.activeZones
                    .filter { it.type == TbtZoneType.SUPPLY && it.bottom >= spot * 0.97 }
                    .minByOrNull { it.bottom }
                    ?.let { z ->
                        priceLines.add(
                            TbtPriceLine(
                                price = z.bottom,
                                title = "$htfLabel Supply",
                                colorHex = "#ff6d00",
                                lineStyle = 2,
                                lineWidth = 1,
                                category = TbtPriceLineCategory.HTF_ZONE
                            )
                        )
                    }
            }
        }

        if (config.showPowerShift) {
            ltf.activeBullPower?.let { price ->
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
            ltf.activeBearPower?.let { price ->
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
            for (ov in ltf.overlappingLevels) {
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

        if (config.showVolumeProfile) {
            val vpLinePrice = when (tradeSetup.direction) {
                TbtTradeDirection.BUY, TbtTradeDirection.SELL -> tradeSetup.volumeRetraceEntry ?: volumeProfile.pocPrice
                TbtTradeDirection.WAIT -> volumeProfile.pocPrice
            }
            if (vpLinePrice != null && vpLinePrice.isFinite() && vpLinePrice > 0.0) {
                val vpTitle = when (tradeSetup.direction) {
                    TbtTradeDirection.BUY -> "VP Retrace BUY"
                    TbtTradeDirection.SELL -> "VP Retrace SELL"
                    TbtTradeDirection.WAIT -> "VP Big Vol (${volumeProfile.sourcesLabel})"
                }
                priceLines.add(
                    TbtPriceLine(
                        price = vpLinePrice,
                        title = vpTitle,
                        colorHex = "#b388ff",
                        lineStyle = 0,
                        lineWidth = 2,
                        category = TbtPriceLineCategory.VOLUME_PROFILE
                    )
                )
            }
        }

        if (config.showTradeSetup && tradeSetup.direction != TbtTradeDirection.WAIT) {
            val isBuy = tradeSetup.direction == TbtTradeDirection.BUY
            priceLines.add(
                TbtPriceLine(
                    price = tradeSetup.entryPrice,
                    title = if (isBuy) "BUY ENTRY" else "SELL ENTRY",
                    colorHex = "#00e5ff",
                    lineStyle = 0,
                    lineWidth = 2,
                    category = TbtPriceLineCategory.TRADE_ENTRY
                )
            )
            priceLines.add(
                TbtPriceLine(
                    price = tradeSetup.stopLoss,
                    title = "SL",
                    colorHex = "#ff1744",
                    lineStyle = 2,
                    lineWidth = 2,
                    category = TbtPriceLineCategory.TRADE_SL
                )
            )
        }

        // Combined overall bias: respects LTF and MTF consensus
        val overallBias = when {
            htfAnalysis != null && htfAnalysis.bias == ltf.bias -> ltf.bias
            htfAnalysis != null && macroAnalysis != null && htfAnalysis.bias == macroAnalysis.bias && htfAnalysis.bias != TbtMarketBias.SIDEWAYS -> htfAnalysis.bias
            else -> ltf.bias
        }

        // Zones exposed for internal POI logic (not rendered as red/green rectangles on chart)
        val exposedZones = buildList {
            addAll(recentLtfZones)
            addAll(ltf.activeZones.filter { it.type == TbtZoneType.FVG_BULL || it.type == TbtZoneType.FVG_BEAR }.takeLast(3))
        }

        return TbtSmcOutput(
            markers = ltf.markers,
            priceLines = priceLines,
            zones = exposedZones,
            summary = TbtConditionSummary(
                bias = overallBias,
                activeBullPower = ltf.activeBullPower,
                activeBearPower = ltf.activeBearPower,
                lastSignal = ltf.lastSignal,
                mtfBiases = mtfBiases,
                mtfAlignmentLabel = mtfAlignmentLabel,
                dealingZoneLabel = dealingZoneLabel,
                volumePocPrice = volumeProfile.pocPrice,
                volumeImbalanceLabel = tradeSetup.volumeProfileReason.ifBlank { volumeProfile.pocSummary },
                volumeSourcesLabel = volumeProfile.sourcesLabel,
                tradeSetup = tradeSetup
            )
        )
    }

    private fun analyzeSingleTf(
        candles: List<Candle>,
        config: TbtSmcConfig,
        emitChartMarkers: Boolean
    ): SingleTfAnalysis {
        val sLen = config.swingLen.coerceAtLeast(2)
        // Key by (timeMillis, position) so an aboveBar and belowBar marker on the same bar don't erase each other
        val markerMap = LinkedHashMap<Pair<Long, String>, Pair<Int, TbtMarker>>()

        fun addMarker(
            timeMillis: Long,
            position: String,
            colorHex: String,
            shape: String,
            text: String,
            priority: Int
        ) {
            if (!emitChartMarkers) return
            val key = timeMillis to position
            val existing = markerMap[key]
            if (existing == null || existing.first <= priority) {
                markerMap[key] = priority to TbtMarker(
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
        var structureBias = 0 // 1 bullish, -1 bearish

        val activeZones = mutableListOf<MutableTbtZone>()
        val overlappingLevels = mutableListOf<OverlappingLevel>()
        var activeBullPower: Double? = null
        var activeBearPower: Double? = null
        var lastSignal = "Neutral"
        var lastBullConfirmIdx = -999
        var lastBearConfirmIdx = -999
        var lastBuySetupIdx = -999
        var lastSellSetupIdx = -999

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

            // 1. Swing High / Low Detection
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

            // 2. Overlapping Level (S/R Flip Hurdle — no unsolicited BOS/CHoCH markers)
            val sh = lastSwingHigh
            if (sh != null && c1.close <= sh && c.close > sh) {
                structureBias = 1
                if (config.showOverlap) {
                    overlappingLevels.add(OverlappingLevel(price = sh, type = "BUY"))
                    while (overlappingLevels.size > 2) overlappingLevels.removeAt(0)
                }
            }
            val sl = lastSwingLow
            if (sl != null && c1.close >= sl && c.close < sl) {
                structureBias = -1
                if (config.showOverlap) {
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

            // 4. Manage Active Zones & Detect TBT Power Shifting
            var tappedDemandThisBar = false
            var tappedSupplyThisBar = false
            for (j in activeZones.indices.reversed()) {
                val z = activeZones[j]
                if (z.type == TbtZoneType.DEMAND) {
                    if (c.close < z.bottom) {
                        activeZones.removeAt(j)
                    } else if (!z.tapped && c.low <= z.top && c.close > z.bottom && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBullPower = c.low
                        tappedDemandThisBar = true
                    } else if (z.tapped && c.low <= z.top && c.close > z.bottom) {
                        tappedDemandThisBar = true
                    }
                } else if (z.type == TbtZoneType.SUPPLY) {
                    if (c.close > z.top) {
                        activeZones.removeAt(j)
                    } else if (!z.tapped && c.high >= z.bottom && c.close < z.top && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label} (Tapped)"
                        activeBearPower = c.high
                        tappedSupplyThisBar = true
                    } else if (z.tapped && c.high >= z.bottom && c.close < z.top) {
                        tappedSupplyThisBar = true
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
                lastBearConfirmIdx = i
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
                lastBullConfirmIdx = i
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
                lastBullConfirmIdx = i
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
                lastBearConfirmIdx = i
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
                lastBullConfirmIdx = i
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
                lastBearConfirmIdx = i
            }

            // 8. High-Confluence BUY / SELL POI Signal Markers (without overriding 3CR BUY/SELL on same bar)
            val nearDemand = tappedDemandThisBar || activeZones.any {
                it.type == TbtZoneType.DEMAND && c.low <= it.top + atr * 0.35 && c.close >= it.bottom
            }
            val nearSupply = tappedSupplyThisBar || activeZones.any {
                it.type == TbtZoneType.SUPPLY && c.high >= it.bottom - atr * 0.35 && c.close <= it.top
            }
            if (config.showTradeSetup && config.showSignals && i - lastBuySetupIdx >= 5 &&
                !bull3cr && nearDemand && (bullSweep || structureBias == 1 && isBull && bodySizes[i] > avgBody * 1.1)
            ) {
                lastBuySetupIdx = i
                addMarker(
                    timeMillis = c.openTime,
                    position = "belowBar",
                    colorHex = "#00e676",
                    shape = "arrowUp",
                    text = "BUY",
                    priority = 4
                )
                lastSignal = "BUY @ Demand POI"
                lastBullConfirmIdx = i
            }
            if (config.showTradeSetup && config.showSignals && i - lastSellSetupIdx >= 5 &&
                !bear3cr && nearSupply && (bearSweep || structureBias == -1 && isBear && bodySizes[i] > avgBody * 1.1)
            ) {
                lastSellSetupIdx = i
                addMarker(
                    timeMillis = c.openTime,
                    position = "aboveBar",
                    colorHex = "#ff1744",
                    shape = "arrowDown",
                    text = "SELL",
                    priority = 4
                )
                lastSignal = "SELL @ Supply POI"
                lastBearConfirmIdx = i
            }
        }

        val lastCandle = candles.last()
        val ema20 = candles.takeLast(20).map { it.close }.average()
        val lastAtr = getSma(trueRanges, candles.lastIndex, 14)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: (lastCandle.high - lastCandle.low).coerceAtLeast(lastCandle.close * 0.002)

        val bias = when {
            lastCandle.close > ema20 * 1.001 && structureBias >= 0 -> TbtMarketBias.BULLISH
            lastCandle.close < ema20 * 0.999 && structureBias <= 0 -> TbtMarketBias.BEARISH
            structureBias > 0 && lastCandle.close >= ema20 * 0.998 -> TbtMarketBias.BULLISH
            structureBias < 0 && lastCandle.close <= ema20 * 1.002 -> TbtMarketBias.BEARISH
            lastCandle.close > ema20 * 1.002 -> TbtMarketBias.BULLISH
            lastCandle.close < ema20 * 0.998 -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

        val lookback = candles.takeLast(45)
        val recentHigh = lookback.maxOf { it.high }
        val recentLow = lookback.minOf { it.low }

        val markers = markerMap.values
            .map { it.second }
            .sortedBy { it.timeMillis }

        return SingleTfAnalysis(
            bias = bias,
            structureBias = structureBias,
            markers = markers,
            activeZones = activeZones.map { it.toImmutable() },
            overlappingLevels = overlappingLevels.toList(),
            activeBullPower = activeBullPower,
            activeBearPower = activeBearPower,
            lastSignal = lastSignal,
            lastSwingHigh = lastSwingHigh,
            lastSwingLow = lastSwingLow,
            recentSwingHigh = recentHigh,
            recentSwingLow = recentLow,
            atr = lastAtr,
            lastBullConfirmIdx = lastBullConfirmIdx,
            lastBearConfirmIdx = lastBearConfirmIdx,
            candleCount = candles.size
        )
    }

    private fun computeMtfAlignmentLabel(
        ltfBias: TbtMarketBias,
        htfBias: TbtMarketBias?,
        macroBias: TbtMarketBias?,
        hasMtf: Boolean
    ): String {
        if (!hasMtf) return "Single TF"
        val biases = listOfNotNull(ltfBias, htfBias, macroBias)
        val bulls = biases.count { it == TbtMarketBias.BULLISH }
        val bears = biases.count { it == TbtMarketBias.BEARISH }
        return when {
            bulls == biases.size -> "Full Bullish MTF (${bulls}/${biases.size})"
            bears == biases.size -> "Full Bearish MTF (${bears}/${biases.size})"
            htfBias == TbtMarketBias.BULLISH && macroBias == TbtMarketBias.BULLISH -> "Bullish HTF Pullback"
            htfBias == TbtMarketBias.BEARISH && macroBias == TbtMarketBias.BEARISH -> "Bearish HTF Pullback"
            bulls > bears -> "Bullish Lean ($bulls/${biases.size})"
            bears > bulls -> "Bearish Lean ($bears/${biases.size})"
            else -> "Mixed MTF Range"
        }
    }

    private data class VolumeProfileBin(
        val midPrice: Double,
        val totalVol: Double,
        val buyVol: Double,
        val sellVol: Double
    )

    private data class VolumeProfileAnalysis(
        val bins: List<VolumeProfileBin>,
        val pocPrice: Double?,
        val pocMultiplier: Double,
        val sourcesLabel: String,
        val pocSummary: String
    ) {
        fun findRetracementNode(
            isBuy: Boolean,
            spot: Double,
            sl: Double,
            zoneLow: Double?,
            zoneHigh: Double?
        ): Pair<Double?, String> {
            if (bins.isEmpty()) return null to ""
            val avgVol = bins.map { it.totalVol }.average().coerceAtLeast(1e-9)

            // Search directional retracement range:
            // BUY -> below/at spot and safely above SL
            // SELL -> above/at spot and safely below SL
            val directionalBins = if (isBuy) {
                bins.filter { it.midPrice <= spot * 1.0005 && it.midPrice >= sl * 1.0005 }
            } else {
                bins.filter { it.midPrice >= spot * 0.9995 && it.midPrice <= sl * 0.9995 }
            }
            val candidates = directionalBins.ifEmpty {
                if (isBuy) bins.filter { it.midPrice <= spot } else bins.filter { it.midPrice >= spot }
            }.ifEmpty { bins }

            val best = candidates.maxByOrNull { bin ->
                val inPoiBonus = if (zoneLow != null && zoneHigh != null && bin.midPrice in zoneLow..zoneHigh) 1.35 else 1.0
                val delta = if (isBuy) (bin.buyVol - bin.sellVol) else (bin.sellVol - bin.buyVol)
                val imbalanceFactor = 1.0 + (delta / bin.totalVol.coerceAtLeast(1e-9)).coerceAtLeast(0.0) * 0.65
                bin.totalVol * inPoiBonus * imbalanceFactor
            } ?: return pocPrice to pocSummary

            val ratio = (best.totalVol / avgVol).coerceAtLeast(1.0)
            val ratioRounded = kotlin.math.round(ratio * 10.0) / 10.0
            val dirTag = if (isBuy) {
                if (best.buyVol > best.sellVol * 1.1) "Bull Imbalance" else "Big Vol HVN"
            } else {
                if (best.sellVol > best.buyVol * 1.1) "Bear Imbalance" else "Big Vol HVN"
            }
            val zoneTag = if (zoneLow != null && zoneHigh != null && best.midPrice in zoneLow..zoneHigh) " @ POI" else ""
            return best.midPrice to "$sourcesLabel ${ratioRounded}x Vol • $dirTag$zoneTag"
        }
    }

    private fun analyzeVolumeProfile(
        candles: List<Candle>,
        spotCandles: List<Candle>,
        spotPrice: Double
    ): VolumeProfileAnalysis {
        if (candles.isEmpty() || !spotPrice.isFinite() || spotPrice <= 0.0) {
            return VolumeProfileAnalysis(emptyList(), null, 1.0, "Futures", "")
        }

        val window = candles.takeLast(min(candles.size, 84))
        val spotByTime = if (spotCandles.isNotEmpty()) {
            val sampleSpot = spotCandles.last().close
            // Only blend Binance Spot volume when price scale matches (exclude 1000PEPE vs PEPE mismatch)
            if (sampleSpot > 0.0 && abs(sampleSpot - spotPrice) / spotPrice < 0.06) {
                spotCandles.associateBy { it.openTime }
            } else emptyMap()
        } else emptyMap()

        var matchedSpotBars = 0
        var rangeLow = Double.MAX_VALUE
        var rangeHigh = Double.MIN_VALUE
        for (c in window) {
            if (c.low < rangeLow) rangeLow = c.low
            if (c.high > rangeHigh) rangeHigh = c.high
        }
        val span = (rangeHigh - rangeLow)
        if (!span.isFinite() || span <= 0.0) {
            val volSources = if (spotByTime.isNotEmpty()) "Futures+Spot" else "Futures"
            return VolumeProfileAnalysis(emptyList(), spotPrice, 1.0, volSources, "$volSources Flat Range")
        }

        val numBins = 32
        val step = span / numBins.toDouble()
        val totalArr = DoubleArray(numBins)
        val buyArr = DoubleArray(numBins)
        val sellArr = DoubleArray(numBins)

        for (c in window) {
            val spotBar = spotByTime[c.openTime]
            if (spotBar != null) matchedSpotBars++
            val barVol = c.volume.coerceAtLeast(0.0) + (spotBar?.volume?.coerceAtLeast(0.0) ?: 0.0)
            if (barVol <= 0.0) continue

            val barSpan = (c.high - c.low)
            val buyRatio = if (barSpan > 0.0) {
                ((c.close - c.low) / barSpan).coerceIn(0.12, 0.88)
            } else if (c.close >= c.open) 0.72 else 0.28
            val barBuy = barVol * buyRatio
            val barSell = barVol - barBuy

            val startBin = ((c.low - rangeLow) / step).toInt().coerceIn(0, numBins - 1)
            val endBin = ((c.high - rangeLow) / step).toInt().coerceIn(startBin, numBins - 1)
            val bodyLow = min(c.open, c.close)
            val bodyHigh = max(c.open, c.close)

            var weightSum = 0.0
            val weights = DoubleArray(endBin - startBin + 1)
            for (b in startBin..endBin) {
                val binMid = rangeLow + (b + 0.5) * step
                val w = if (binMid in bodyLow..bodyHigh) 2.0 else 0.75
                weights[b - startBin] = w
                weightSum += w
            }
            if (weightSum <= 0.0) weightSum = 1.0
            for (b in startBin..endBin) {
                val frac = weights[b - startBin] / weightSum
                totalArr[b] += barVol * frac
                buyArr[b] += barBuy * frac
                sellArr[b] += barSell * frac
            }
        }

        val bins = buildList {
            for (b in 0 until numBins) {
                if (totalArr[b] > 0.0) {
                    add(
                        VolumeProfileBin(
                            midPrice = rangeLow + (b + 0.5) * step,
                            totalVol = totalArr[b],
                            buyVol = buyArr[b],
                            sellVol = sellArr[b]
                        )
                    )
                }
            }
        }
        val pocBin = bins.maxByOrNull { it.totalVol }
        val meanVol = bins.takeIf { it.isNotEmpty() }?.map { it.totalVol }?.average()?.coerceAtLeast(1e-9) ?: 1.0
        val pocMult = if (pocBin != null) (pocBin.totalVol / meanVol).coerceAtLeast(1.0) else 1.0
        val multRounded = kotlin.math.round(pocMult * 10.0) / 10.0
        val sourcesLabel = if (matchedSpotBars >= 10) "Futures+Spot" else "Futures"
        val pocSummary = if (pocBin != null) {
            val dom = if (pocBin.buyVol >= pocBin.sellVol) "Bull Vol POC" else "Bear Vol POC"
            "$sourcesLabel ${multRounded}x Avg • $dom"
        } else ""

        return VolumeProfileAnalysis(
            bins = bins,
            pocPrice = pocBin?.midPrice,
            pocMultiplier = pocMult,
            sourcesLabel = sourcesLabel,
            pocSummary = pocSummary
        )
    }

    private fun buildTradeSetup(
        candles: List<Candle>,
        ltf: SingleTfAnalysis,
        htfLabel: String,
        htf: SingleTfAnalysis?,
        macroLabel: String,
        macro: SingleTfAnalysis?,
        dealingZoneLabel: String,
        volumeProfile: VolumeProfileAnalysis
    ): TbtTradeSetup {
        val spot = candles.last().close
        if (!spot.isFinite() || spot <= 0.0) return TbtTradeSetup()
        val atr = ltf.atr.coerceAtLeast(spot * 0.0015)

        val ltfDemandBelow = ltf.activeZones
            .filter { it.type == TbtZoneType.DEMAND && it.bottom <= spot }
            .maxByOrNull { it.top }
        val ltfSupplyAbove = ltf.activeZones
            .filter { it.type == TbtZoneType.SUPPLY && it.top >= spot }
            .minByOrNull { it.bottom }

        val htfDemandBelow = htf?.activeZones
            ?.filter { it.type == TbtZoneType.DEMAND && it.bottom <= spot }
            ?.maxByOrNull { it.top }
        val htfSupplyAbove = htf?.activeZones
            ?.filter { it.type == TbtZoneType.SUPPLY && it.top >= spot }
            ?.minByOrNull { it.bottom }

        val inLtfDemand = ltfDemandBelow != null && spot <= ltfDemandBelow.top + atr * 0.65 && spot >= ltfDemandBelow.bottom
        val inHtfDemand = htfDemandBelow != null && spot <= htfDemandBelow.top + atr * 1.1 && spot >= htfDemandBelow.bottom
        val inLtfSupply = ltfSupplyAbove != null && spot >= ltfSupplyAbove.bottom - atr * 0.65 && spot <= ltfSupplyAbove.top
        val inHtfSupply = htfSupplyAbove != null && spot >= htfSupplyAbove.bottom - atr * 1.1 && spot <= htfSupplyAbove.top

        val recentBullConfirm = (ltf.candleCount - 1) - ltf.lastBullConfirmIdx <= 5
        val recentBearConfirm = (ltf.candleCount - 1) - ltf.lastBearConfirmIdx <= 5

        var bullScore = 0
        var bearScore = 0
        val bullReasons = mutableListOf<String>()
        val bearReasons = mutableListOf<String>()

        if (macro != null) {
            if (macro.bias == TbtMarketBias.BULLISH) {
                bullScore += 20
                bullReasons.add("$macroLabel Macro Bullish")
            } else if (macro.bias == TbtMarketBias.BEARISH) {
                bearScore += 20
                bearReasons.add("$macroLabel Macro Bearish")
            }
        }
        if (htf != null) {
            if (htf.bias == TbtMarketBias.BULLISH) {
                bullScore += 25
                bullReasons.add("$htfLabel HTF Bullish")
            } else if (htf.bias == TbtMarketBias.BEARISH) {
                bearScore += 25
                bearReasons.add("$htfLabel HTF Bearish")
            }
        }
        if (ltf.bias == TbtMarketBias.BULLISH) {
            bullScore += 15
            bullReasons.add("LTF Structure Bullish")
        } else if (ltf.bias == TbtMarketBias.BEARISH) {
            bearScore += 15
            bearReasons.add("LTF Structure Bearish")
        }

        if (inLtfDemand) {
            bullScore += 15
            bullReasons.add("Inside LTF Demand POI")
        }
        if (inHtfDemand) {
            bullScore += 15
            bullReasons.add("Inside $htfLabel HTF Demand")
        }
        if (inLtfSupply) {
            bearScore += 15
            bearReasons.add("Inside LTF Supply POI")
        }
        if (inHtfSupply) {
            bearScore += 15
            bearReasons.add("Inside $htfLabel HTF Supply")
        }

        if (dealingZoneLabel == "Discount") {
            bullScore += 10
            bullReasons.add("Discount Pricing (<50%)")
        } else if (dealingZoneLabel == "Premium") {
            bearScore += 10
            bearReasons.add("Premium Pricing (>50%)")
        }

        if (recentBullConfirm) {
            bullScore += 15
            bullReasons.add(ltf.lastSignal)
        }
        if (recentBearConfirm) {
            bearScore += 15
            bearReasons.add(ltf.lastSignal)
        }
        if (ltf.activeBullPower != null) {
            bullScore += 8
            bullReasons.add("Buyer Power Active")
        }
        if (ltf.activeBearPower != null) {
            bearScore += 8
            bearReasons.add("Seller Power Active")
        }

        val scoreDiff = abs(bullScore - bearScore)
        val hasStructuralPoi = ltfDemandBelow != null || htfDemandBelow != null || ltf.activeBullPower != null ||
            ltfSupplyAbove != null || htfSupplyAbove != null || ltf.activeBearPower != null

        if (!hasStructuralPoi || max(bullScore, bearScore) < 35 || (scoreDiff < 15 && !recentBullConfirm && !recentBearConfirm)) {
            return TbtTradeSetup(
                direction = TbtTradeDirection.WAIT,
                isConfirmedEntry = false,
                confidencePercent = max(bullScore, bearScore).coerceIn(15, 55),
                statusLabel = "Wait • Mixed MTF / Mid-Range",
                volumeRetraceEntry = volumeProfile.pocPrice,
                volumeProfileReason = volumeProfile.pocSummary,
                confluenceReasons = listOf("Wait for clean Demand/Supply tap or MTF alignment")
            )
        }

        val isBuy = bullScore >= bearScore
        val rawConf = if (isBuy) bullScore else bearScore
        val confidence = rawConf.coerceIn(45, 97)
        val minRisk = max(atr * 0.45, spot * 0.002)
        val slBuffer = max(atr * 0.25, spot * 0.0012)

        return if (isBuy) {
            val activeZone = when {
                inHtfDemand && inLtfDemand -> ltfDemandBelow
                inLtfDemand -> ltfDemandBelow
                inHtfDemand -> htfDemandBelow
                else -> ltfDemandBelow ?: htfDemandBelow
            }
            val confirmed = recentBullConfirm || (inLtfDemand && ltf.activeBullPower != null)
            val entry = if (confirmed) {
                spot
            } else {
                activeZone?.top ?: ltf.activeBullPower ?: ltf.lastSwingLow ?: (spot - atr * 0.6)
            }.coerceAtMost(spot * 1.005)

            val rawFloor = minOf(
                activeZone?.bottom ?: entry,
                ltf.activeBullPower ?: Double.MAX_VALUE,
                if (activeZone == null && ltf.lastSwingLow != null && ltf.lastSwingLow < entry) ltf.lastSwingLow else Double.MAX_VALUE
            )
            val structuralSl = (if (rawFloor.isFinite() && rawFloor < entry) rawFloor else entry - atr * 1.1) - slBuffer
            val sl = min(structuralSl, entry - minRisk).coerceAtLeast(spot * 0.25)
            val risk = (entry - sl).coerceAtLeast(minRisk)

            val slReason = when {
                ltf.activeBullPower != null && rawFloor == ltf.activeBullPower -> "Below Buyer Power + Buf"
                activeZone != null -> "Below ${activeZone.label}"
                else -> "Below Swing Low + ATR"
            }

            // TP1: nearest opposing LTF Supply bottom or LTF swing high >= 1.6R
            val ltfSupplyTarget = ltf.activeZones
                .filter { it.type == TbtZoneType.SUPPLY && it.bottom >= entry + risk * 1.5 }
                .minByOrNull { it.bottom }
            val candidateTp1 = listOfNotNull(
                ltfSupplyTarget?.bottom,
                ltf.lastSwingHigh?.takeIf { it >= entry + risk * 1.5 },
                htf?.lastSwingHigh?.takeIf { it >= entry + risk * 1.6 }
            ).minOrNull()
            val tp1 = (candidateTp1 ?: (entry + risk * 2.0)).coerceIn(entry + risk * 1.6, entry + risk * 4.5)
            val tp1Reason = when {
                ltfSupplyTarget != null && candidateTp1 == ltfSupplyTarget.bottom -> "LTF Supply POI"
                ltf.lastSwingHigh != null && candidateTp1 == ltf.lastSwingHigh -> "LTF Swing High Liq"
                else -> "Fixed 1:2.0R Target"
            }

            // TP2: opposing HTF Supply or HTF/Macro Swing High beyond TP1
            val htfSupplyTarget = htf?.activeZones
                ?.filter { it.type == TbtZoneType.SUPPLY && it.bottom >= tp1 + risk * 0.5 }
                ?.minByOrNull { it.bottom }
            val macroSupplyTarget = macro?.activeZones
                ?.filter { it.type == TbtZoneType.SUPPLY && it.bottom >= tp1 + risk * 0.5 }
                ?.minByOrNull { it.bottom }
            val candidateTp2 = listOfNotNull(
                htfSupplyTarget?.bottom,
                htf?.lastSwingHigh?.takeIf { it >= tp1 + risk * 0.5 },
                macroSupplyTarget?.bottom,
                macro?.lastSwingHigh?.takeIf { it >= tp1 + risk * 0.5 },
                ltf.recentSwingHigh.takeIf { it >= tp1 + risk * 0.5 }
            ).minOrNull()
            val tp2 = (candidateTp2 ?: max(tp1 + risk * 1.4, entry + risk * 3.5))
                .coerceAtLeast(tp1 + risk * 0.8)
            val tp2Reason = when {
                htfSupplyTarget != null && candidateTp2 == htfSupplyTarget.bottom -> "$htfLabel HTF Supply"
                htf?.lastSwingHigh != null && candidateTp2 == htf.lastSwingHigh -> "$htfLabel External High"
                macroSupplyTarget != null && candidateTp2 == macroSupplyTarget.bottom -> "$macroLabel Macro Supply"
                macro?.lastSwingHigh != null && candidateTp2 == macro.lastSwingHigh -> "$macroLabel External High"
                else -> "MTF Extension Target"
            }

            val (volRetracePrice, volRetraceReason) = volumeProfile.findRetracementNode(
                isBuy = true,
                spot = spot,
                sl = sl,
                zoneLow = activeZone?.bottom,
                zoneHigh = activeZone?.top
            )

            TbtTradeSetup(
                direction = TbtTradeDirection.BUY,
                isConfirmedEntry = confirmed,
                confidencePercent = confidence,
                statusLabel = if (confirmed) "BUY NOW • MTF Confirmed" else "BUY LIMIT @ Demand",
                entryPrice = entry,
                entryZoneLow = activeZone?.bottom ?: sl,
                entryZoneHigh = activeZone?.top ?: entry,
                stopLoss = sl,
                takeProfit1 = tp1,
                takeProfit2 = tp2,
                riskReward1 = ((tp1 - entry) / risk).coerceAtLeast(0.1),
                riskReward2 = ((tp2 - entry) / risk).coerceAtLeast(0.1),
                volumeRetraceEntry = volRetracePrice,
                volumeProfileReason = volRetraceReason,
                slReason = slReason,
                tp1Reason = tp1Reason,
                tp2Reason = tp2Reason,
                confluenceReasons = bullReasons.distinct().take(4)
            )
        } else {
            val activeZone = when {
                inHtfSupply && inLtfSupply -> ltfSupplyAbove
                inLtfSupply -> ltfSupplyAbove
                inHtfSupply -> htfSupplyAbove
                else -> ltfSupplyAbove ?: htfSupplyAbove
            }
            val confirmed = recentBearConfirm || (inLtfSupply && ltf.activeBearPower != null)
            val entry = if (confirmed) {
                spot
            } else {
                activeZone?.bottom ?: ltf.activeBearPower ?: ltf.lastSwingHigh ?: (spot + atr * 0.6)
            }.coerceAtLeast(spot * 0.995)

            val rawCeil = maxOf(
                activeZone?.top ?: entry,
                ltf.activeBearPower ?: Double.MIN_VALUE,
                if (activeZone == null && ltf.lastSwingHigh != null && ltf.lastSwingHigh > entry) ltf.lastSwingHigh else Double.MIN_VALUE
            )
            val structuralSl = (if (rawCeil.isFinite() && rawCeil > entry) rawCeil else entry + atr * 1.1) + slBuffer
            val sl = max(structuralSl, entry + minRisk)
            val risk = (sl - entry).coerceAtLeast(minRisk)

            val slReason = when {
                ltf.activeBearPower != null && rawCeil == ltf.activeBearPower -> "Above Seller Power + Buf"
                activeZone != null -> "Above ${activeZone.label}"
                else -> "Above Swing High + ATR"
            }

            // TP1: nearest opposing LTF Demand top or LTF Swing Low
            val ltfDemandTarget = ltf.activeZones
                .filter { it.type == TbtZoneType.DEMAND && it.top <= entry - risk * 1.5 }
                .maxByOrNull { it.top }
            val candidateTp1 = listOfNotNull(
                ltfDemandTarget?.top,
                ltf.lastSwingLow?.takeIf { it <= entry - risk * 1.5 && it > 0.0 },
                htf?.lastSwingLow?.takeIf { it <= entry - risk * 1.6 && it > 0.0 }
            ).maxOrNull()
            val tp1 = (candidateTp1 ?: (entry - risk * 2.0))
                .coerceIn((entry - risk * 4.5).coerceAtLeast(spot * 0.1), entry - risk * 1.6)
            val tp1Reason = when {
                ltfDemandTarget != null && candidateTp1 == ltfDemandTarget.top -> "LTF Demand POI"
                ltf.lastSwingLow != null && candidateTp1 == ltf.lastSwingLow -> "LTF Swing Low Liq"
                else -> "Fixed 1:2.0R Target"
            }

            // TP2: opposing HTF Demand or HTF/Macro Swing Low
            val htfDemandTarget = htf?.activeZones
                ?.filter { it.type == TbtZoneType.DEMAND && it.top <= tp1 - risk * 0.5 && it.top > 0.0 }
                ?.maxByOrNull { it.top }
            val macroDemandTarget = macro?.activeZones
                ?.filter { it.type == TbtZoneType.DEMAND && it.top <= tp1 - risk * 0.5 && it.top > 0.0 }
                ?.maxByOrNull { it.top }
            val candidateTp2 = listOfNotNull(
                htfDemandTarget?.top,
                htf?.lastSwingLow?.takeIf { it <= tp1 - risk * 0.5 && it > 0.0 },
                macroDemandTarget?.top,
                macro?.lastSwingLow?.takeIf { it <= tp1 - risk * 0.5 && it > 0.0 },
                ltf.recentSwingLow.takeIf { it <= tp1 - risk * 0.5 && it > 0.0 }
            ).maxOrNull()
            val tp2 = (candidateTp2 ?: min(tp1 - risk * 1.4, entry - risk * 3.5))
                .coerceAtMost(tp1 - risk * 0.8)
                .coerceAtLeast(spot * 0.05)
            val tp2Reason = when {
                htfDemandTarget != null && candidateTp2 == htfDemandTarget.top -> "$htfLabel HTF Demand"
                htf?.lastSwingLow != null && candidateTp2 == htf.lastSwingLow -> "$htfLabel External Low"
                macroDemandTarget != null && candidateTp2 == macroDemandTarget.top -> "$macroLabel Macro Demand"
                macro?.lastSwingLow != null && candidateTp2 == macro.lastSwingLow -> "$macroLabel External Low"
                else -> "MTF Extension Target"
            }

            val (volRetracePrice, volRetraceReason) = volumeProfile.findRetracementNode(
                isBuy = false,
                spot = spot,
                sl = sl,
                zoneLow = activeZone?.bottom,
                zoneHigh = activeZone?.top
            )

            TbtTradeSetup(
                direction = TbtTradeDirection.SELL,
                isConfirmedEntry = confirmed,
                confidencePercent = confidence,
                statusLabel = if (confirmed) "SELL NOW • MTF Confirmed" else "SELL LIMIT @ Supply",
                entryPrice = entry,
                entryZoneLow = activeZone?.bottom ?: entry,
                entryZoneHigh = activeZone?.top ?: sl,
                stopLoss = sl,
                takeProfit1 = tp1,
                takeProfit2 = tp2,
                riskReward1 = ((entry - tp1) / risk).coerceAtLeast(0.1),
                riskReward2 = ((entry - tp2) / risk).coerceAtLeast(0.1),
                volumeRetraceEntry = volRetracePrice,
                volumeProfileReason = volRetraceReason,
                slReason = slReason,
                tp1Reason = tp1Reason,
                tp2Reason = tp2Reason,
                confluenceReasons = bearReasons.distinct().take(4)
            )
        }
    }

    private fun formatRr(rr: Double): String {
        val rounded = kotlin.math.round(rr * 10.0) / 10.0
        return rounded.toString()
    }
}
