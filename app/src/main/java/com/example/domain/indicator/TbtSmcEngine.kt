package com.example.domain.indicator

import com.example.domain.model.Candle
import org.json.JSONArray
import org.json.JSONObject
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

enum class TbtMarketBias(val label: String, val shortLabel: String) {
    BULLISH("BULLISH", "BULL"),
    BEARISH("BEARISH", "BEAR"),
    SIDEWAYS("SIDEWAYS", "RNG")
}

enum class TbtTradeDirection(val label: String) {
    BUY("BUY"),
    SELL("SELL")
}

enum class TbtMtfProfile(
    val shortLabel: String,
    val title: String,
    val timeframeIds: List<String>
) {
    INTRADAY_1H(
        shortLabel = "1H",
        title = "1H Setup",
        timeframeIds = listOf("1H", "30m", "15m", "10m", "5m", "3m")
    ),
    SWING_4H(
        shortLabel = "4H",
        title = "4H Setup",
        timeframeIds = listOf("4H", "2H", "1H", "30m", "15m", "10m", "5m", "3m")
    );

    fun toHubTab(): TbtMtfHubTab = TbtMtfHubTab(
        id = name,
        name = shortLabel,
        timeframeIds = timeframeIds,
        isBuiltIn = true
    )
}

/**
 * Represents a tab inside the Multi-Timeframe Content Hub (built-in 1H/4H or user custom tabs
 * such as "30m" with ["30m", "15m", "10m", "5m", "2m"]).
 */
data class TbtMtfHubTab(
    val id: String,
    val name: String,
    val timeframeIds: List<String>,
    val isBuiltIn: Boolean = false
) {
    val anchorTimeframeId: String
        get() = timeframeIds.firstOrNull() ?: "1H"

    companion object {
        val BUILT_IN_1H = TbtMtfProfile.INTRADAY_1H.toHubTab()
        val BUILT_IN_4H = TbtMtfProfile.SWING_4H.toHubTab()
        val DEFAULT_CUSTOM_30M = TbtMtfHubTab(
            id = "custom_30m",
            name = "30m",
            timeframeIds = listOf("30m", "15m", "10m", "5m", "2m"),
            isBuiltIn = false
        )

        val BUILT_IN_TABS: List<TbtMtfHubTab> = listOf(BUILT_IN_1H, BUILT_IN_4H)

        fun allTabs(customTabs: List<TbtMtfHubTab>): List<TbtMtfHubTab> =
            BUILT_IN_TABS + customTabs

        fun normalizeTimeframeToken(raw: String): String? {
            val cleaned = raw.trim().replace("min", "m", ignoreCase = true)
                .replace("hr", "H", ignoreCase = true)
                .replace("h", "H", ignoreCase = true)
                .replace("d", "D", ignoreCase = true)
            if (cleaned.isEmpty()) return null
            val bareNum = cleaned.toIntOrNull()
            if (bareNum != null && bareNum in 1..1440) {
                return "${bareNum}m"
            }
            val match = Regex("^(\\d+)([mHD])$", RegexOption.IGNORE_CASE).matchEntire(cleaned) ?: return null
            val amount = match.groupValues[1].toIntOrNull()?.coerceIn(1, 1440) ?: return null
            val unitRaw = match.groupValues[2]
            val suffix = when {
                unitRaw.equals("m", ignoreCase = true) -> "m"
                unitRaw.equals("H", ignoreCase = true) -> "H"
                unitRaw.equals("D", ignoreCase = true) -> "D"
                else -> return null
            }
            return "$amount$suffix"
        }

        fun durationMillisOf(tfId: String): Long {
            val norm = normalizeTimeframeToken(tfId) ?: return 15L * 60_000L
            val match = Regex("^(\\d+)([mHD])$").matchEntire(norm) ?: return 15L * 60_000L
            val amount = match.groupValues[1].toLongOrNull() ?: 15L
            return when (match.groupValues[2]) {
                "m" -> amount * 60_000L
                "H" -> amount * 3_600_000L
                "D" -> amount * 86_400_000L
                else -> amount * 60_000L
            }
        }

        fun parseTimeframeList(rawInput: String): List<String> {
            return rawInput
                .split(Regex("[,\\s;/|]+"))
                .mapNotNull { normalizeTimeframeToken(it) }
                .distinctBy { it.lowercase(Locale.US) }
                .sortedByDescending { durationMillisOf(it) }
        }

        fun serializeCustomTabs(tabs: List<TbtMtfHubTab>): String {
            val arr = JSONArray()
            tabs.filter { !it.isBuiltIn && it.timeframeIds.isNotEmpty() }.forEach { tab ->
                val obj = JSONObject()
                obj.put("id", tab.id)
                obj.put("name", tab.name)
                val tfs = JSONArray()
                tab.timeframeIds.forEach { tfs.put(it) }
                obj.put("tfs", tfs)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeCustomTabs(json: String?): List<TbtMtfHubTab> {
            if (json == null || json.isBlank()) {
                return listOf(DEFAULT_CUSTOM_30M)
            }
            return runCatching {
                val arr = JSONArray(json)
                val out = mutableListOf<TbtMtfHubTab>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val tfArr = obj.optJSONArray("tfs") ?: continue
                    val tfs = mutableListOf<String>()
                    for (j in 0 until tfArr.length()) {
                        val tok = normalizeTimeframeToken(tfArr.optString(j))
                        if (tok != null) tfs.add(tok)
                    }
                    if (id.isNotEmpty() && name.isNotEmpty() && tfs.isNotEmpty()) {
                        out.add(
                            TbtMtfHubTab(
                                id = id,
                                name = name,
                                timeframeIds = tfs.distinctBy { it.lowercase(Locale.US) },
                                isBuiltIn = false
                            )
                        )
                    }
                }
                out
            }.getOrElse { listOf(DEFAULT_CUSTOM_30M) }
        }
    }
}

enum class TbtCascadeAction(val badgeText: String) {
    BUY_READY("BUY READY"),
    WAIT_BUY_CONFIRM("WAIT CONFIRM"),
    SELL_READY("SELL READY"),
    WAIT_SELL_CONFIRM("WAIT CONFIRM"),
    MIXED_WAIT("MIXED")
}

data class TbtTimeframeSnapshot(
    val tfId: String,
    val label: String,
    val bias: TbtMarketBias,
    val statusShort: String,
    val hasBuyConfirm: Boolean = false,
    val hasSellConfirm: Boolean = false,
    val nearDemandPoi: Boolean = false,
    val nearSupplyPoi: Boolean = false,
    val isLoaded: Boolean = true
)

data class TbtMtfCascadeState(
    val profile: TbtMtfProfile,
    val snapshots: List<TbtTimeframeSnapshot>,
    val action: TbtCascadeAction,
    val headline: String,
    val alignedCount: Int,
    val totalCount: Int,
    val htfBias: TbtMarketBias,
    val tabId: String = profile.name,
    val tabName: String = profile.shortLabel
)

data class TbtChecklistItem(
    val label: String,
    val met: Boolean
)

data class TbtTradeSetup(
    val direction: TbtTradeDirection,
    val triggerName: String,
    val reason: String,
    val grade: String, // "A+", "A", "B", "C"
    val score: Int, // 0..5
    val entryPrice: Double,
    val retracementEntryPrice: Double? = null,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskReward: Double,
    val riskPercent: Double,
    val htfLabel: String? = null,
    val htfBias: TbtMarketBias? = null,
    val cascadeAction: TbtCascadeAction? = null,
    val executionTf: String? = null,
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
    val lastSignal: String = "Wait"
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
    val autoHideOnHtf: Boolean = false,
    val targetRR: Double = 3.0,
    val slBufferPct: Double = 0.05,
    val showVolImbalanceLine: Boolean = true,
    val showZoneTags: Boolean = true,
    val mtfProfile: TbtMtfProfile = TbtMtfProfile.INTRADAY_1H,
    val activeHubTab: TbtMtfHubTab? = null
) {
    val effectiveHubTab: TbtMtfHubTab
        get() = activeHubTab ?: mtfProfile.toHubTab()
}

data class TbtSmcOutput(
    val markers: List<TbtMarker> = emptyList(),
    val priceLines: List<TbtPriceLine> = emptyList(),
    val zones: List<TbtZone> = emptyList(),
    val summary: TbtConditionSummary = TbtConditionSummary(),
    val backtestReport: TbtBacktestReport = TbtBacktestReport(),
    val activeSetup: TbtTradeSetup? = null,
    val mtfCascade: TbtMtfCascadeState? = null
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

private data class CoreScanResult(
    val markers: List<TbtMarker>,
    val priceLines: List<TbtPriceLine>,
    val recentZones: List<TbtZone>,
    val bias: TbtMarketBias,
    val activeBullPower: Double?,
    val activeBearPower: Double?,
    val lastSignal: String,
    val openTrade: SimulatedTrade?,
    val viCandidatePrice: Double?,
    val wins: Int,
    val losses: Int,
    val recentBullConfirm: Boolean,
    val recentBearConfirm: Boolean,
    val nearDemandPoi: Boolean,
    val nearSupplyPoi: Boolean,
    val hasOverlapBuy: Boolean,
    val hasOverlapSell: Boolean,
    val lastDemandTapped: Boolean,
    val lastSupplyTapped: Boolean
)

object TbtSmcEngine {

    /**
     * Computes live Higher-Timeframe (HTF) Trend Bias + HTF Demand/Supply POIs
     * from real exchange candles fetched via Binance API.
     */
    fun analyzeHigherTimeframe(htfCandles: List<Candle>): TbtMarketBias? {
        return computeHtfAnalysis(htfCandles)?.bias
    }

    /**
     * Computes a compact per-timeframe Bullish / Bearish TBT SMC snapshot for the Multi-Timeframe Hub.
     */
    fun analyzeTimeframeSnapshot(
        tfId: String,
        candles: List<Candle>,
        config: TbtSmcConfig = TbtSmcConfig()
    ): TbtTimeframeSnapshot {
        if (candles.size < 25) {
            return TbtTimeframeSnapshot(
                tfId = tfId,
                label = tfId,
                bias = TbtMarketBias.SIDEWAYS,
                statusShort = "...",
                isLoaded = false
            )
        }
        val scan = runCoreScan(candles, config, htfAnalysis = null, buildVisuals = false)
        // Resolve decisive Bullish or Bearish signal per timeframe (use EMA/SMA direction if core scan is neutral)
        val decisiveBias = if (scan.bias != TbtMarketBias.SIDEWAYS) {
            scan.bias
        } else {
            val closes = candles.map { it.close }
            val lastClose = closes.last()
            val sma20 = closes.takeLast(20).average()
            when {
                lastClose > sma20 -> TbtMarketBias.BULLISH
                lastClose < sma20 -> TbtMarketBias.BEARISH
                candles.last().close >= candles.last().open -> TbtMarketBias.BULLISH
                else -> TbtMarketBias.BEARISH
            }
        }
        val statusShort = when (decisiveBias) {
            TbtMarketBias.BULLISH -> "BULL"
            TbtMarketBias.BEARISH -> "BEAR"
            TbtMarketBias.SIDEWAYS -> "FLAT"
        }
        return TbtTimeframeSnapshot(
            tfId = tfId,
            label = tfId,
            bias = decisiveBias,
            statusShort = statusShort,
            hasBuyConfirm = scan.recentBullConfirm,
            hasSellConfirm = scan.recentBearConfirm,
            nearDemandPoi = scan.nearDemandPoi,
            nearSupplyPoi = scan.nearSupplyPoi,
            isLoaded = true
        )
    }

    /**
     * Evaluates the full TBT SMC Multi-Timeframe Cascade for a built-in profile.
     */
    fun evaluateMtfCascade(
        profile: TbtMtfProfile,
        candlesByTf: Map<String, List<Candle>>,
        config: TbtSmcConfig = TbtSmcConfig()
    ): TbtMtfCascadeState = evaluateMtfCascadeForTab(profile.toHubTab(), candlesByTf, config)

    /**
     * Evaluates the Multi-Timeframe Content Hub for ANY built-in or custom tab
     * (e.g. 1H, 4H, or custom 30m: 30m -> 15m -> 10m -> 5m -> 2m).
     */
    fun evaluateMtfCascadeForTab(
        tab: TbtMtfHubTab,
        candlesByTf: Map<String, List<Candle>>,
        config: TbtSmcConfig = TbtSmcConfig()
    ): TbtMtfCascadeState {
        val tfIds = tab.timeframeIds.ifEmpty { TbtMtfProfile.INTRADAY_1H.timeframeIds }
        val snapshots = tfIds.map { tfId ->
            val tfCandles = candlesByTf.entries
                .firstOrNull { it.key.equals(tfId, ignoreCase = true) }
                ?.value
                .orEmpty()
            analyzeTimeframeSnapshot(tfId, tfCandles, config)
        }

        val matchingProfile = when (tab.id) {
            TbtMtfProfile.SWING_4H.name -> TbtMtfProfile.SWING_4H
            else -> TbtMtfProfile.INTRADAY_1H
        }

        // Partition timeframes into Higher/Structure TFs and Lowest 1-2 Confirmation TFs
        val ltfCount = when {
            snapshots.size >= 4 -> 2
            snapshots.size >= 2 -> 1
            else -> 0
        }
        val ltfSnapshots = if (ltfCount > 0) snapshots.takeLast(ltfCount) else emptyList()
        val htfSnapshots = if (ltfCount > 0) snapshots.dropLast(ltfCount) else snapshots
        val anchorSnap = snapshots.firstOrNull()
        val anchorBias = anchorSnap?.bias ?: TbtMarketBias.SIDEWAYS
        val anchorLoaded = anchorSnap?.isLoaded == true

        val ltfBuyConfirmed = ltfSnapshots.any { it.isLoaded && it.hasBuyConfirm }
        val ltfSellConfirmed = ltfSnapshots.any { it.isLoaded && it.hasSellConfirm }
        val ltfLabel = ltfSnapshots.joinToString("/") { it.label }.ifBlank { "LTF" }

        val htfBullAligned = anchorLoaded &&
            anchorBias == TbtMarketBias.BULLISH &&
            htfSnapshots.filter { it.isLoaded }.all { it.bias == TbtMarketBias.BULLISH } &&
            htfSnapshots.count { it.isLoaded } >= max(1, (htfSnapshots.size + 1) / 2)

        val htfBearAligned = anchorLoaded &&
            anchorBias == TbtMarketBias.BEARISH &&
            htfSnapshots.filter { it.isLoaded }.all { it.bias == TbtMarketBias.BEARISH } &&
            htfSnapshots.count { it.isLoaded } >= max(1, (htfSnapshots.size + 1) / 2)

        val (action, headline) = when {
            htfBullAligned && ltfBuyConfirmed ->
                TbtCascadeAction.BUY_READY to "BUY READY • $ltfLabel OK"
            htfBullAligned ->
                TbtCascadeAction.WAIT_BUY_CONFIRM to "${tab.name} BULL • Wait $ltfLabel"
            htfBearAligned && ltfSellConfirmed ->
                TbtCascadeAction.SELL_READY to "SELL READY • $ltfLabel OK"
            htfBearAligned ->
                TbtCascadeAction.WAIT_SELL_CONFIRM to "${tab.name} BEAR • Wait $ltfLabel"
            anchorBias == TbtMarketBias.BULLISH ->
                TbtCascadeAction.MIXED_WAIT to "${tab.name} BULL • Mixed"
            anchorBias == TbtMarketBias.BEARISH ->
                TbtCascadeAction.MIXED_WAIT to "${tab.name} BEAR • Mixed"
            else ->
                TbtCascadeAction.MIXED_WAIT to "${tab.name} • Loading"
        }

        val loadedSnapshots = snapshots.filter { it.isLoaded }
        val targetBias = when (action) {
            TbtCascadeAction.BUY_READY, TbtCascadeAction.WAIT_BUY_CONFIRM -> TbtMarketBias.BULLISH
            TbtCascadeAction.SELL_READY, TbtCascadeAction.WAIT_SELL_CONFIRM -> TbtMarketBias.BEARISH
            TbtCascadeAction.MIXED_WAIT -> anchorBias
        }
        val alignedCount = if (targetBias == TbtMarketBias.SIDEWAYS) {
            0
        } else {
            loadedSnapshots.count { it.bias == targetBias }
        }

        return TbtMtfCascadeState(
            profile = matchingProfile,
            snapshots = snapshots,
            action = action,
            headline = headline,
            alignedCount = alignedCount,
            totalCount = snapshots.size,
            htfBias = anchorBias,
            tabId = tab.id,
            tabName = tab.name
        )
    }

    private fun computeHtfAnalysis(htfCandles: List<Candle>): HtfAnalysis? {
        if (htfCandles.size < 20) return null
        val closes = htfCandles.map { it.close }
        val lastClose = closes.last()
        val sma20 = closes.takeLast(20).average()

        val recentSlice = htfCandles.takeLast(min(40, htfCandles.size))
        val half = recentSlice.size / 2
        val firstHalfHigh = recentSlice.take(half).maxOf { it.high }
        val secondHalfHigh = recentSlice.drop(half).maxOf { it.high }
        val firstHalfLow = recentSlice.take(half).minOf { it.low }
        val secondHalfLow = recentSlice.drop(half).minOf { it.low }

        val bullStructure = secondHalfHigh > firstHalfHigh && secondHalfLow >= firstHalfLow * 0.999
        val bearStructure = secondHalfLow < firstHalfLow && secondHalfHigh <= firstHalfHigh * 1.001

        val bias = when {
            lastClose > sma20 * 1.0012 || (lastClose > sma20 && bullStructure) -> TbtMarketBias.BULLISH
            lastClose < sma20 * 0.9988 || (lastClose < sma20 && bearStructure) -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

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
        htfLabel: String? = null,
        mtfCandlesByTf: Map<String, List<Candle>> = emptyMap(),
        activeTimeframeId: String? = null
    ): TbtSmcOutput {
        val activeTab = config.effectiveHubTab
        val mtfCascade = if (mtfCandlesByTf.isNotEmpty()) {
            evaluateMtfCascadeForTab(activeTab, mtfCandlesByTf, config)
        } else {
            null
        }

        val resolvedHtfLabel = htfLabel ?: activeTab.anchorTimeframeId
        val resolvedHtfCandles = if (htfCandles.size >= 20) {
            htfCandles
        } else {
            mtfCandlesByTf.entries
                .firstOrNull { it.key.equals(activeTab.anchorTimeframeId, ignoreCase = true) }
                ?.value
                .orEmpty()
        }
        val htfAnalysis = computeHtfAnalysis(resolvedHtfCandles)
        val htfBias = mtfCascade?.htfBias?.takeIf { it != TbtMarketBias.SIDEWAYS } ?: htfAnalysis?.bias

        if (candles.size < 25) {
            return TbtSmcOutput(
                markers = emptyList(),
                priceLines = emptyList(),
                zones = emptyList(),
                summary = TbtConditionSummary(
                    bias = TbtMarketBias.SIDEWAYS,
                    htfLabel = resolvedHtfLabel.takeIf { htfBias != null },
                    htfBias = htfBias,
                    activeBullPower = null,
                    activeBearPower = null,
                    lastSignal = "Wait..."
                ),
                backtestReport = TbtBacktestReport(),
                activeSetup = null,
                mtfCascade = mtfCascade
            )
        }

        val scan = runCoreScan(candles, config, htfAnalysis = htfAnalysis, buildVisuals = true)
        val priceLines = scan.priceLines.toMutableList()
        val safeRR = config.targetRR.coerceIn(1.0, 20.0)

        // If user is on a higher TF with no local open trade yet, surface the lowest TF ready setup!
        var chosenTrade = scan.openTrade
        var chosenViPrice = scan.viCandidatePrice
        var executionTfLabel = activeTimeframeId
        if (chosenTrade == null &&
            mtfCascade != null &&
            (mtfCascade.action == TbtCascadeAction.BUY_READY || mtfCascade.action == TbtCascadeAction.SELL_READY)
        ) {
            val ltfCandidates = activeTab.timeframeIds.takeLast(2).reversed().ifEmpty { listOf("3m", "5m") }
            for (ltfId in ltfCandidates) {
                val ltfCandles = mtfCandlesByTf.entries
                    .firstOrNull { it.key.equals(ltfId, ignoreCase = true) }
                    ?.value
                    .orEmpty()
                if (ltfCandles.size >= 25) {
                    val ltfScan = runCoreScan(ltfCandles, config, htfAnalysis = htfAnalysis, buildVisuals = false)
                    val ltfTrade = ltfScan.openTrade
                    val matchesDirection = ltfTrade != null && (
                        (mtfCascade.action == TbtCascadeAction.BUY_READY && ltfTrade.isLong) ||
                            (mtfCascade.action == TbtCascadeAction.SELL_READY && !ltfTrade.isLong)
                        )
                    if (matchesDirection) {
                        chosenTrade = ltfTrade
                        chosenViPrice = ltfScan.viCandidatePrice
                        executionTfLabel = ltfId
                        break
                    }
                }
            }
        }

        // Build actionable TBT SMC BUY / SELL Trade Setup with MTF Cascade confluence grading
        val activeSetup = chosenTrade?.let { active ->
            val direction = if (active.isLong) TbtTradeDirection.BUY else TbtTradeDirection.SELL
            val effectiveHtfBias = htfBias ?: scan.bias
            val htfAligned = (active.isLong && effectiveHtfBias == TbtMarketBias.BULLISH) ||
                (!active.isLong && effectiveHtfBias == TbtMarketBias.BEARISH)
            val cascadeAligned = when (mtfCascade?.action) {
                TbtCascadeAction.BUY_READY, TbtCascadeAction.WAIT_BUY_CONFIRM -> active.isLong
                TbtCascadeAction.SELL_READY, TbtCascadeAction.WAIT_SELL_CONFIRM -> !active.isLong
                else -> htfAligned
            }
            val cascadeCounter = when (mtfCascade?.action) {
                TbtCascadeAction.BUY_READY, TbtCascadeAction.WAIT_BUY_CONFIRM -> !active.isLong
                TbtCascadeAction.SELL_READY, TbtCascadeAction.WAIT_SELL_CONFIRM -> active.isLong
                else -> false
            }

            val riskPct = if (active.entryPrice > 0.0) {
                (abs(active.entryPrice - active.stopLoss) / active.entryPrice) * 100.0
            } else 0.0

            val validRetraceVi = chosenViPrice?.takeIf { vi ->
                if (active.isLong) {
                    vi > active.stopLoss && vi < active.entryPrice
                } else {
                    vi < active.stopLoss && vi > active.entryPrice
                }
            }

            val cascadeCheckLabel = when {
                mtfCascade != null ->
                    "${mtfCascade.tabName} MTF (${mtfCascade.alignedCount}/${mtfCascade.totalCount}) ${mtfCascade.action.badgeText}"
                htfBias != null ->
                    "$resolvedHtfLabel Bias (${htfBias.shortLabel})"
                else ->
                    "Bias (${scan.bias.shortLabel})"
            }

            val checklist = listOf(
                TbtChecklistItem(cascadeCheckLabel, cascadeAligned),
                TbtChecklistItem("DEM/SUP or OVL POI", active.poiAligned),
                TbtChecklistItem("BPWR / SPWR Shift", active.powerAligned),
                TbtChecklistItem("Trigger: ${active.triggerName}", true),
                TbtChecklistItem("Volume Conf", active.volumeConfirmed)
            )
            val score = checklist.count { it.met }
            val grade = when {
                cascadeCounter -> "C"
                score >= 4 && cascadeAligned -> "A+"
                score >= 3 -> "A"
                else -> "B"
            }

            if (config.showSignals) {
                val rrLabel = if (safeRR % 1.0 == 0.0) {
                    "${safeRR.toInt()}R"
                } else {
                    "${String.format(Locale.US, "%.1f", safeRR)}R"
                }
                priceLines.add(
                    TbtPriceLine(
                        price = active.entryPrice,
                        title = if (active.isLong) "BUY" else "SELL",
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
                        title = "TP $rrLabel",
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
                htfLabel = resolvedHtfLabel.takeIf { htfBias != null },
                htfBias = htfBias,
                cascadeAction = mtfCascade?.action,
                executionTf = executionTfLabel,
                volumeConfirmed = active.volumeConfirmed,
                checklist = checklist,
                signalTimeMillis = active.signalTimeMillis
            )
        }

        val totalTrades = scan.wins + scan.losses
        val winRate = if (totalTrades > 0) ((scan.wins * 100.0) / totalTrades.toDouble()).roundToInt() else null
        val netR = if (totalTrades > 0) {
            val rawNet = scan.wins * safeRR - scan.losses * 1.0
            (rawNet * 10.0).roundToInt() / 10.0
        } else {
            null
        }

        return TbtSmcOutput(
            markers = scan.markers,
            priceLines = priceLines,
            zones = scan.recentZones,
            summary = TbtConditionSummary(
                bias = scan.bias,
                htfLabel = resolvedHtfLabel.takeIf { htfBias != null },
                htfBias = htfBias,
                activeBullPower = scan.activeBullPower,
                activeBearPower = scan.activeBearPower,
                lastSignal = scan.lastSignal
            ),
            backtestReport = TbtBacktestReport(
                totalTrades = totalTrades,
                wins = scan.wins,
                losses = scan.losses,
                winRate = winRate,
                netR = netR
            ),
            activeSetup = activeSetup,
            mtfCascade = mtfCascade
        )
    }

    private fun runCoreScan(
        candles: List<Candle>,
        config: TbtSmcConfig,
        htfAnalysis: HtfAnalysis?,
        buildVisuals: Boolean
    ): CoreScanResult {
        val markersMap = LinkedHashMap<Long, Pair<Int, TbtMarker>>()
        val activeZones = mutableListOf<MutableTbtZone>()
        val overlapLevels = mutableListOf<OverlapLevel>()

        var activeBullPower: Double? = null
        var activeBearPower: Double? = null
        var lastSwingHigh: Double? = null
        var lastSwingLow: Double? = null
        var lastSignal = "Wait"
        var lastDemandTapIndex = -100
        var lastSupplyTapIndex = -100
        var lastSellerPowerBrokenIndex = -100
        var lastBuyerPowerBrokenIndex = -100
        var lastBullConfirmIndex = -100
        var lastBearConfirmIndex = -100
        var last3crSignalIndex = -100

        var latestViPrice: Double? = null
        var latestViIsBull = true
        var latestFvgMidPrice: Double? = null
        var latestFvgIsBull = true

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
            if (!buildVisuals) return
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

            // 0. Evaluate active simulated trade conservatively (SL before TP)
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

            // 2. Overlapping Level (OVL S/R Flip Hurdle)
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

            // 3. Demand & Supply Zone Creation (RBR/DBR DEM, DBD/RBD SUP)
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
                            label = "$tagShort DEM",
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
                            label = "$tagShort SUP",
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

            // 4. Manage Active Zones & Detect TBT Power Shifting (BPWR / SPWR)
            for (zIdx in activeZones.lastIndex downTo 0) {
                val z = activeZones[zIdx]
                if (z.type == TbtZoneType.DEMAND) {
                    if (c.close < z.bottom) {
                        activeZones.removeAt(zIdx)
                    } else if (!z.tapped && c.low <= z.top && c.close > z.bottom && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label}*"
                        activeBullPower = c.low
                        lastDemandTapIndex = i
                    }
                } else if (z.type == TbtZoneType.SUPPLY) {
                    if (c.close > z.top) {
                        activeZones.removeAt(zIdx)
                    } else if (!z.tapped && c.high >= z.bottom && c.close < z.top && c.openTime != z.startTimeMillis) {
                        z.tapped = true
                        z.label = "${z.label}*"
                        activeBearPower = c.high
                        lastSupplyTapIndex = i
                    }
                }
            }

            var buyerPowerBrokenNow = false
            var sellerPowerBrokenNow = false

            val bullPwr = activeBullPower
            if (bullPwr != null && c.close < bullPwr) {
                buyerPowerBrokenNow = true
                lastBuyerPowerBrokenIndex = i
                lastBearConfirmIndex = i
                if (config.showPowerShift) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff1744",
                            shape = "arrowDown",
                            text = "BPWR BRK"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "BPWR BRK (SELL)"
                activeBullPower = null
            }

            val bearPwr = activeBearPower
            if (bearPwr != null && c.close > bearPwr) {
                sellerPowerBrokenNow = true
                lastSellerPowerBrokenIndex = i
                lastBullConfirmIndex = i
                if (config.showPowerShift) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00c853",
                            shape = "arrowUp",
                            text = "SPWR BRK"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "SPWR BRK (BUY)"
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
                            label = "FVG+",
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
                            label = "FVG-",
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

            // 6. Refined 2-Candle Liquidity Pool Grab (LIQ) — filters micro-wick noise
            val slGrab = lastSwingLow
            val shGrab = lastSwingHigh
            val validSweepRange = atr <= 0.0 || (c.high - c.low) >= atr * 0.45
            val bullLiqGrab = slGrab != null && c.low < slGrab && c.close > slGrab && isBull && validSweepRange
            val bearLiqGrab = shGrab != null && c.high > shGrab && c.close < shGrab && isBear && validSweepRange

            if (bullLiqGrab) {
                lastBullConfirmIndex = i
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "belowBar",
                            colorHex = "#00e676",
                            shape = "circle",
                            text = "LIQ"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "LIQ BUY"
            }
            if (bearLiqGrab) {
                lastBearConfirmIndex = i
                if (config.showSignals) {
                    addMarker(
                        TbtMarker(
                            timeMillis = c.openTime,
                            position = "aboveBar",
                            colorHex = "#ff5252",
                            shape = "circle",
                            text = "LIQ"
                        ),
                        priority = 2
                    )
                }
                lastSignal = "LIQ SELL"
            }

            // 7. Refined TBT 3-Candle Reversal (3CR BUY / 3CR SELL) with displacement filter
            val strongBody = avgBody <= 0.0 || bodySizes[i] >= avgBody * 0.55
            val notBackToBack = (i - last3crSignalIndex) >= 2
            val bull3cr = isBear2 && c1.low < c2.low && isBull && c.close > max(c1.high, c2.high) && strongBody && notBackToBack
            val bear3cr = isBull2 && c1.high > c2.high && isBear && c.close < min(c1.low, c2.low) && strongBody && notBackToBack

            val nearBuyPoi = isNearBuyPoi(i, min(c.low, c1.low), c.close, atr)
            val nearSellPoi = isNearSellPoi(i, max(c.high, c1.high), c.close, atr)
            val bullPowerAligned = activeBullPower != null || (i - lastSellerPowerBrokenIndex <= 8)
            val bearPowerAligned = activeBearPower != null || (i - lastBuyerPowerBrokenIndex <= 8)

            if (bull3cr) {
                last3crSignalIndex = i
                lastBullConfirmIndex = i
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
                lastSignal = "3CR BUY"
                if (openTrade == null) {
                    val entry = c.close
                    val rawSl = min(c.low, min(c1.low, c2.low))
                    val sl = rawSl * (1.0 - safeBufferFraction)
                    val risk = entry - sl
                    if (risk > 0.0) {
                        val reason = when {
                            nearBuyPoi && bullPowerAligned -> "DEM/OVL + SPWR + 3CR BUY"
                            nearBuyPoi -> "DEM/OVL POI + 3CR BUY"
                            bullPowerAligned -> "SPWR BRK + 3CR BUY"
                            else -> "3CR BUY Reversal"
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
                last3crSignalIndex = i
                lastBearConfirmIndex = i
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
                lastSignal = "3CR SELL"
                if (openTrade == null) {
                    val entry = c.close
                    val rawSl = max(c.high, max(c1.high, c2.high))
                    val sl = rawSl * (1.0 + safeBufferFraction)
                    val risk = sl - entry
                    if (risk > 0.0) {
                        val reason = when {
                            nearSellPoi && bearPowerAligned -> "SUP/OVL + BPWR + 3CR SELL"
                            nearSellPoi -> "SUP/OVL POI + 3CR SELL"
                            bearPowerAligned -> "BPWR BRK + 3CR SELL"
                            else -> "3CR SELL Reversal"
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

            if (openTrade == null) {
                if (bullLiqGrab) {
                    val entry = c.close
                    val sl = c.low * (1.0 - safeBufferFraction)
                    val risk = entry - sl
                    if (risk > 0.0) {
                        openTrade = SimulatedTrade(
                            isLong = true,
                            triggerName = "LIQ BUY",
                            reason = if (nearBuyPoi) "LIQ Sweep @ DEM/OVL" else "LIQ Sweep BUY",
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
                            triggerName = "LIQ SELL",
                            reason = if (nearSellPoi) "LIQ Sweep @ SUP/OVL" else "LIQ Sweep SELL",
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
                            triggerName = "SPWR BRK",
                            reason = "SPWR Broken (Buyers Control)",
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
                            triggerName = "BPWR BRK",
                            reason = "BPWR Broken (Sellers Control)",
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
        val viCandidatePrice = latestViPrice ?: latestFvgMidPrice
        val viCandidateBull = if (latestViPrice != null) latestViIsBull else latestFvgIsBull

        if (buildVisuals) {
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
                            title = "BPWR",
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
                            title = "SPWR",
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
                            title = "OVL ${ov.type}",
                            colorHex = "#aa00ff",
                            lineStyle = 1,
                            lineWidth = 1,
                            category = TbtPriceLineCategory.OVERLAP
                        )
                    )
                }
            }

            if (config.showVolImbalanceLine && viCandidatePrice != null && viCandidatePrice > 0.0) {
                priceLines.add(
                    TbtPriceLine(
                        price = viCandidatePrice,
                        title = "VI",
                        colorHex = if (viCandidateBull) "#00e5ff" else "#ff9100",
                        lineStyle = 2,
                        lineWidth = 1,
                        category = TbtPriceLineCategory.VOL_IMBALANCE
                    )
                )
            }
        }

        val lastIdx = candles.lastIndex
        val lastCandle = candles[lastIdx]
        val recentCloses = candles.takeLast(20)
        val sma20 = recentCloses.sumOf { it.close } / recentCloses.size.toDouble()
        val lastAtr = getSma(trueRanges, lastIdx, 14)

        // Blend SMA20 + causal swing structure for cleaner multi-timeframe trend detection
        val recentSlice = candles.takeLast(min(40, candles.size))
        val half = recentSlice.size / 2
        val hhHl = recentSlice.drop(half).maxOf { it.high } > recentSlice.take(half).maxOf { it.high } &&
            recentSlice.drop(half).minOf { it.low } >= recentSlice.take(half).minOf { it.low } * 0.999
        val lhLl = recentSlice.drop(half).minOf { it.low } < recentSlice.take(half).minOf { it.low } &&
            recentSlice.drop(half).maxOf { it.high } <= recentSlice.take(half).maxOf { it.high } * 1.001

        val bias = when {
            lastCandle.close > sma20 * 1.001 || (lastCandle.close > sma20 && hhHl) -> TbtMarketBias.BULLISH
            lastCandle.close < sma20 * 0.999 || (lastCandle.close < sma20 && lhLl) -> TbtMarketBias.BEARISH
            else -> TbtMarketBias.SIDEWAYS
        }

        val sortedMarkers = if (buildVisuals) {
            markersMap.values.map { it.second }.sortedBy { it.timeMillis }
        } else {
            emptyList()
        }

        val recentBullConfirm = (lastIdx - lastBullConfirmIndex) <= 6 || (openTrade?.isLong == true)
        val recentBearConfirm = (lastIdx - lastBearConfirmIndex) <= 6 || (openTrade?.isLong == false)

        return CoreScanResult(
            markers = sortedMarkers,
            priceLines = priceLines,
            recentZones = recentZones,
            bias = bias,
            activeBullPower = activeBullPower,
            activeBearPower = activeBearPower,
            lastSignal = lastSignal,
            openTrade = openTrade,
            viCandidatePrice = viCandidatePrice,
            wins = wins,
            losses = losses,
            recentBullConfirm = recentBullConfirm,
            recentBearConfirm = recentBearConfirm,
            nearDemandPoi = isNearBuyPoi(lastIdx, lastCandle.low, lastCandle.close, lastAtr),
            nearSupplyPoi = isNearSellPoi(lastIdx, lastCandle.high, lastCandle.close, lastAtr),
            hasOverlapBuy = overlapLevels.any { it.type == "BUY" },
            hasOverlapSell = overlapLevels.any { it.type == "SELL" },
            lastDemandTapped = (lastIdx - lastDemandTapIndex) <= 8,
            lastSupplyTapped = (lastIdx - lastSupplyTapIndex) <= 8
        )
    }
}
