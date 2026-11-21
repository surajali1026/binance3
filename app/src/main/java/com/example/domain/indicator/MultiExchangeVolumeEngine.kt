package com.example.domain.indicator

import com.example.domain.model.Candle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure deterministic engine for the standalone Multi-Exch Vol & Traps indicator.
 *
 * Works across all timeframes (1m, 3m, 5m, 15m, 30m, 1H, 4H, 1D, and custom intervals):
 * 1. Divides each recent visible candle's [low, high] range into price buckets.
 * 2. Aggregates real Buy vs. Sell volume per checked exchange from live/recent trade ticks,
 *    supplemented for older historical bars by real exchange kline taker-buy/sell flow.
 * 3. Determines Green (Buy dominant) vs Red (Sell dominant) per price level and counts how
 *    many checked exchanges dominate that side (e.g., 3 green, 4 red).
 * 4. Detects Buyer Traps (upper-candle aggressive Buy dominance that fails to hold and reverses
 *    weak) and Seller Traps (lower-candle aggressive Sell dominance that fails to break down
 *    and rebounds).
 */
object MultiExchangeVolumeEngine {

    private const val MAX_CANDLES_TO_EVALUATE = 36
    private const val BUCKETS_PER_CANDLE = 4

    fun calculate(
        candles: List<Candle>,
        snapshot: ExchangeVolumeSnapshot,
        selectedExchanges: Set<SupportedExchange>,
        showTraps: Boolean,
        isLoading: Boolean = false
    ): MultiExchangeVolumeOutput {
        if (candles.isEmpty() || selectedExchanges.isEmpty()) {
            return MultiExchangeVolumeOutput(
                levels = emptyList(),
                traps = emptyList(),
                connectedExchanges = snapshot.connectedExchanges.intersect(selectedExchanges),
                checkedExchanges = selectedExchanges,
                isLoading = isLoading
            )
        }

        val activeSelected = selectedExchanges
        val connected = snapshot.connectedExchanges.intersect(activeSelected)
        val startIdx = max(0, candles.size - MAX_CANDLES_TO_EVALUATE)
        val windowCandles = candles.subList(startIdx, candles.size)
        val windowStartMs = windowCandles.first().openTime
        val windowEndMs = windowCandles.last().closeTime

        // Filter trade ticks strictly to checked exchanges and current candle window
        val tradesPerExchange = LinkedHashMap<SupportedExchange, List<ExchangeTradeTick>>()
        for (ex in activeSelected) {
            val raw = snapshot.tradesByExchange[ex].orEmpty()
            if (raw.isNotEmpty()) {
                val filtered = raw.filter { it.timestampMillis in windowStartMs..windowEndMs && it.quoteQty > 0.0 }
                if (filtered.isNotEmpty()) {
                    tradesPerExchange[ex] = filtered
                }
            }
        }

        // Index kline flow strictly for checked exchanges
        val flowsPerExchange = LinkedHashMap<SupportedExchange, List<ExchangeCandleFlow>>()
        for (ex in activeSelected) {
            val raw = snapshot.flowsByExchange[ex].orEmpty()
            if (raw.isNotEmpty()) {
                val filtered = raw.filter { it.closeTimeMillis >= windowStartMs && it.openTimeMillis <= windowEndMs }
                if (filtered.isNotEmpty()) {
                    flowsPerExchange[ex] = filtered
                }
            }
        }

        val allLevels = ArrayList<IntraCandleVolumeLevel>(windowCandles.size * 2)
        val allTraps = ArrayList<CandleTrapTag>()

        for (candle in windowCandles) {
            val range = candle.high - candle.low
            if (range <= 1e-12) continue

            val bucketCount = BUCKETS_PER_CANDLE
            val bucketStep = range / bucketCount.toDouble()

            // bucketIdx -> exchange -> [buyQuoteVol, sellQuoteVol]
            val bucketMap = Array(bucketCount) { LinkedHashMap<SupportedExchange, DoubleArray>() }
            val exchangesWithTicksInCandle = HashSet<SupportedExchange>()

            // 1) Primary precision source: real trade ticks per checked exchange
            for ((exchange, ticks) in tradesPerExchange) {
                var matchedAny = false
                for (tick in ticks) {
                    if (tick.timestampMillis < candle.openTime || tick.timestampMillis > candle.closeTime) continue
                    matchedAny = true
                    val rawIdx = floor((tick.price - candle.low) / bucketStep).toInt()
                    val bIdx = rawIdx.coerceIn(0, bucketCount - 1)
                    val pair = bucketMap[bIdx].getOrPut(exchange) { DoubleArray(2) }
                    if (tick.isBuyAggressor) {
                        pair[0] += tick.quoteQty
                    } else {
                        pair[1] += tick.quoteQty
                    }
                }
                if (matchedAny) {
                    exchangesWithTicksInCandle.add(exchange)
                }
            }

            // 2) Secondary all-timeframe historical coverage: real exchange kline taker-buy/sell flow
            // for checked exchanges whose recent-trade window doesn't stretch back to older candles.
            for ((exchange, flows) in flowsPerExchange) {
                if (exchangesWithTicksInCandle.contains(exchange)) continue
                val matchingFlows = flows.filter { flow ->
                    flow.openTimeMillis <= candle.closeTime && flow.closeTimeMillis >= candle.openTime
                }
                if (matchingFlows.isEmpty()) continue
                var sumBuy = 0.0
                var sumSell = 0.0
                var flowOpen = matchingFlows.first().open
                var flowClose = matchingFlows.last().close
                var flowHigh = matchingFlows.maxOf { it.high }
                var flowLow = matchingFlows.minOf { it.low }
                for (f in matchingFlows) {
                    sumBuy += f.buyQuoteVolume
                    sumSell += f.sellQuoteVolume
                }
                if (sumBuy + sumSell <= 0.0) continue

                distributeExchangeFlowToBuckets(
                    exchange = exchange,
                    candle = candle,
                    flowOpen = flowOpen,
                    flowHigh = flowHigh,
                    flowLow = flowLow,
                    flowClose = flowClose,
                    buyVol = sumBuy,
                    sellVol = sumSell,
                    bucketCount = bucketCount,
                    bucketStep = bucketStep,
                    bucketMap = bucketMap
                )
            }

            // Build intra-candle levels for this candle
            val candleLevels = ArrayList<IntraCandleVolumeLevel>(bucketCount)
            for (bIdx in 0 until bucketCount) {
                val perEx = bucketMap[bIdx]
                if (perEx.isEmpty()) continue

                val breakdowns = ArrayList<ExchangeBucketBreakdown>(perEx.size)
                var totalBuy = 0.0
                var totalSell = 0.0
                var buyDomExchanges = 0
                var sellDomExchanges = 0

                for ((ex, vols) in perEx) {
                    val bVol = vols[0]
                    val sVol = vols[1]
                    if (bVol + sVol <= 0.0) continue
                    totalBuy += bVol
                    totalSell += sVol
                    if (bVol > sVol * 1.02) {
                        buyDomExchanges++
                    } else if (sVol > bVol * 1.02) {
                        sellDomExchanges++
                    }
                    breakdowns.add(ExchangeBucketBreakdown(ex, bVol, sVol))
                }
                if (breakdowns.isEmpty()) continue

                val dominantSide: DominanceSide
                val dominantCount: Int
                when {
                    buyDomExchanges > sellDomExchanges -> {
                        dominantSide = DominanceSide.BUY
                        dominantCount = buyDomExchanges
                    }
                    sellDomExchanges > buyDomExchanges -> {
                        dominantSide = DominanceSide.SELL
                        dominantCount = sellDomExchanges
                    }
                    totalBuy >= totalSell -> {
                        dominantSide = DominanceSide.BUY
                        dominantCount = max(1, buyDomExchanges)
                    }
                    else -> {
                        dominantSide = DominanceSide.SELL
                        dominantCount = max(1, sellDomExchanges)
                    }
                }

                // Require meaningful directional dominance (>= 53% skew OR multi-exchange agreement)
                val totalVol = totalBuy + totalSell
                val domRatio = if (dominantSide == DominanceSide.BUY) totalBuy / totalVol else totalSell / totalVol
                if (dominantCount < 1 || (dominantCount == 1 && domRatio < 0.54)) continue

                val pLow = candle.low + bIdx * bucketStep
                val pHigh = pLow + bucketStep
                val pMid = (pLow + pHigh) * 0.5

                candleLevels.add(
                    IntraCandleVolumeLevel(
                        id = "${candle.openTime}_$bIdx",
                        candleTimeMillis = candle.openTime,
                        bucketIndex = bIdx,
                        bucketCount = bucketCount,
                        priceLow = pLow,
                        priceHigh = pHigh,
                        priceMid = pMid,
                        dominantSide = dominantSide,
                        dominantExchangeCount = dominantCount,
                        activeExchangeCount = breakdowns.size,
                        totalBuyVolume = totalBuy,
                        totalSellVolume = totalSell,
                        exchanges = breakdowns.sortedByDescending { it.totalVolume }
                    )
                )
            }

            if (candleLevels.isEmpty()) continue

            // Keep top 2 most decisive levels per candle so chart stays crisp & readable on mobile
            val prunedLevels = candleLevels
                .sortedWith(
                    compareByDescending<IntraCandleVolumeLevel> { it.dominantExchangeCount }
                        .thenByDescending { it.totalBuyVolume + it.totalSellVolume }
                )
                .take(2)
                .sortedBy { it.bucketIndex }

            allLevels.addAll(prunedLevels)

            // 3) Buyer Trap & Seller Trap Detection
            if (showTraps) {
                detectTrapForCandle(candle, candleLevels)?.let { allTraps.add(it) }
            }
        }

        return MultiExchangeVolumeOutput(
            levels = allLevels,
            traps = allTraps,
            connectedExchanges = connected,
            checkedExchanges = activeSelected,
            isLoading = isLoading
        )
    }

    /**
     * Maps an exchange's kline taker-buy & taker-sell volume onto intra-candle price zones
     * using real candle anatomy (body, upper rejection wick, lower rejection wick).
     */
    private fun distributeExchangeFlowToBuckets(
        exchange: SupportedExchange,
        candle: Candle,
        flowOpen: Double,
        flowHigh: Double,
        flowLow: Double,
        flowClose: Double,
        buyVol: Double,
        sellVol: Double,
        bucketCount: Int,
        bucketStep: Double,
        bucketMap: Array<LinkedHashMap<SupportedExchange, DoubleArray>>
    ) {
        val total = buyVol + sellVol
        if (total <= 0.0) return

        val bodyTop = max(flowOpen, flowClose)
        val bodyBottom = min(flowOpen, flowClose)
        val candleRange = max(candle.high - candle.low, 1e-12)
        val upperWickRatio = ((flowHigh - bodyTop).coerceAtLeast(0.0) / candleRange).coerceIn(0.0, 1.0)
        val lowerWickRatio = ((bodyBottom - flowLow).coerceAtLeast(0.0) / candleRange).coerceIn(0.0, 1.0)
        val isBearishClose = flowClose < flowOpen

        val buyWeights = DoubleArray(bucketCount)
        val sellWeights = DoubleArray(bucketCount)

        for (b in 0 until bucketCount) {
            val bMid = candle.low + (b + 0.5) * bucketStep
            val isUpper = b >= bucketCount - 1
            val isLower = b == 0
            val inBody = bMid in bodyBottom..bodyTop

            var bw = if (inBody) 0.34 else 0.16
            var sw = if (inBody) 0.34 else 0.16

            // Upper wick with rejection concentrates aggressive trapped buyers near high
            if (isUpper && upperWickRatio >= 0.25) {
                bw += if (isBearishClose) 0.36 else 0.22
                sw += 0.10
            } else if (isUpper && flowClose >= flowOpen) {
                bw += 0.26
            }

            // Lower wick with rebound concentrates aggressive trapped sellers near low
            if (isLower && lowerWickRatio >= 0.25) {
                sw += if (!isBearishClose) 0.36 else 0.22
                bw += 0.10
            } else if (isLower && isBearishClose) {
                sw += 0.26
            }

            buyWeights[b] = bw
            sellWeights[b] = sw
        }

        val bwSum = buyWeights.sum().coerceAtLeast(1e-9)
        val swSum = sellWeights.sum().coerceAtLeast(1e-9)

        for (b in 0 until bucketCount) {
            val pair = bucketMap[b].getOrPut(exchange) { DoubleArray(2) }
            pair[0] += buyVol * (buyWeights[b] / bwSum)
            pair[1] += sellVol * (sellWeights[b] / swSum)
        }
    }

    private fun detectTrapForCandle(
        candle: Candle,
        levels: List<IntraCandleVolumeLevel>
    ): CandleTrapTag? {
        val range = candle.high - candle.low
        if (range <= 1e-12) return null

        val closePos = ((candle.close - candle.low) / range).coerceIn(0.0, 1.0)
        val bodyTop = max(candle.open, candle.close)
        val bodyBottom = min(candle.open, candle.close)
        val upperWickFrac = ((candle.high - bodyTop) / range).coerceIn(0.0, 1.0)
        val lowerWickFrac = ((bodyBottom - candle.low) / range).coerceIn(0.0, 1.0)
        val isBearishOrWeak = candle.close <= candle.open || closePos <= 0.45
        val isBullishOrRebound = candle.close >= candle.open || closePos >= 0.55

        // Buyer Trap: strong aggressive BUY dominance in upper half of candle, yet price fails
        // to hold highs (significant upper rejection wick & closes weak below bucket midpoint).
        val upperBuyTrapLevel = levels
            .filter { it.bucketIndex >= it.bucketCount / 2 && it.dominantSide == DominanceSide.BUY }
            .maxByOrNull { it.dominantExchangeCount * 1_000_000.0 + it.totalBuyVolume }

        if (upperBuyTrapLevel != null && isBearishOrWeak && upperWickFrac >= 0.26 && candle.close < upperBuyTrapLevel.priceMid) {
            val total = upperBuyTrapLevel.totalBuyVolume + upperBuyTrapLevel.totalSellVolume
            val buyShare = if (total > 0.0) upperBuyTrapLevel.totalBuyVolume / total else 0.0
            val strongEnough = (upperBuyTrapLevel.dominantExchangeCount >= 2 && buyShare >= 0.54) ||
                (upperBuyTrapLevel.activeExchangeCount == 1 && buyShare >= 0.62)
            if (strongEnough) {
                return CandleTrapTag(
                    id = "trap_buy_${candle.openTime}",
                    candleTimeMillis = candle.openTime,
                    price = candle.high,
                    type = TrapType.BUYER_TRAP,
                    dominantExchangeCount = upperBuyTrapLevel.dominantExchangeCount,
                    totalBuyVolume = upperBuyTrapLevel.totalBuyVolume,
                    totalSellVolume = upperBuyTrapLevel.totalSellVolume
                )
            }
        }

        // Seller Trap: strong aggressive SELL dominance in lower half of candle, yet price fails
        // to break lower (significant lower rejection wick & rebounds/closes above bucket midpoint).
        val lowerSellTrapLevel = levels
            .filter { it.bucketIndex < it.bucketCount / 2 && it.dominantSide == DominanceSide.SELL }
            .maxByOrNull { it.dominantExchangeCount * 1_000_000.0 + it.totalSellVolume }

        if (lowerSellTrapLevel != null && isBullishOrRebound && lowerWickFrac >= 0.26 && candle.close > lowerSellTrapLevel.priceMid) {
            val total = lowerSellTrapLevel.totalBuyVolume + lowerSellTrapLevel.totalSellVolume
            val sellShare = if (total > 0.0) lowerSellTrapLevel.totalSellVolume / total else 0.0
            val strongEnough = (lowerSellTrapLevel.dominantExchangeCount >= 2 && sellShare >= 0.54) ||
                (lowerSellTrapLevel.activeExchangeCount == 1 && sellShare >= 0.62)
            if (strongEnough) {
                return CandleTrapTag(
                    id = "trap_sell_${candle.openTime}",
                    candleTimeMillis = candle.openTime,
                    price = candle.low,
                    type = TrapType.SELLER_TRAP,
                    dominantExchangeCount = lowerSellTrapLevel.dominantExchangeCount,
                    totalBuyVolume = lowerSellTrapLevel.totalBuyVolume,
                    totalSellVolume = lowerSellTrapLevel.totalSellVolume
                )
            }
        }

        return null
    }
}
