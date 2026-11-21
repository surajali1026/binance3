package com.example.domain.model

import com.example.domain.indicator.TbtSmcConfig

enum class DrawingTool {
    NONE,
    HORIZONTAL_LINE,
    RECTANGLE,
    TRENDLINE,
    PRICE_RANGE
}

enum class DrawingType {
    HORIZONTAL_LINE,
    RECTANGLE,
    TRENDLINE
}

enum class LineStyle(val label: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted")
}

data class ChartPoint(
    val timeMillis: Long,
    val price: Double
)

data class ChartDrawing(
    val id: String,
    val symbol: String,
    val type: DrawingType,
    val points: List<ChartPoint>,
    val colorHex: Long = 0xFFF0B90BL,
    val strokeWidth: Float = 2f,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AlertCondition(val label: String, val needsCandleClose: Boolean = false) {
    REACHES("Price reaches level"),
    CROSSES_ABOVE("Price crosses above"),
    CROSSES_BELOW("Price crosses below"),
    CANDLE_CLOSE_ABOVE("Candle closes above", needsCandleClose = true),
    CANDLE_CLOSE_BELOW("Candle closes below", needsCandleClose = true),
    HORIZ_BREAKOUT("S/R horizontal breakout"),
    TRENDLINE_TOUCH("Trendline touch")
}

data class PriceAlert(
    val id: String,
    val symbol: String,
    val priceSource: PriceSource = PriceSource.LAST,
    val condition: AlertCondition,
    val targetPrice: Double,
    val anchorTime1: Long? = null,
    val anchorPrice1: Double? = null,
    val anchorTime2: Long? = null,
    val anchorPrice2: Double? = null,
    val timeframeId: String = "15m",
    val linkedDrawingId: String? = null,
    val isActive: Boolean = true,
    val lastEvaluatedPrice: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun trendlinePriceAt(timeMillis: Long): Double? {
        val t1 = anchorTime1 ?: return null
        val p1 = anchorPrice1 ?: return null
        val t2 = anchorTime2 ?: return null
        val p2 = anchorPrice2 ?: return null
        val dt = t2 - t1
        if (dt == 0L) return p1
        return p1 + (p2 - p1) * ((timeMillis - t1).toDouble() / dt.toDouble())
    }
}

data class AlertHistoryEntry(
    val id: String,
    val symbol: String,
    val conditionLabel: String,
    val triggeredPrice: Double,
    val triggeredAt: Long,
    val message: String
)

data class ChartPreferences(
    val lastSymbol: String = "BTCUSDT",
    val lastTimeframeId: String = "15m",
    val lastPriceSource: PriceSource = PriceSource.LAST,
    val bullColorHex: Long = 0xFF0ECB81L,
    val bearColorHex: Long = 0xFFF6465DL,
    val showGrid: Boolean = true,
    val showCountdown: Boolean = true,
    val autoFitDefault: Boolean = true,
    val backgroundMonitorEnabled: Boolean = true,
    val loudSoundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val lastBarSpacingDp: Float = 9f,
    val lastRightOffsetBars: Float = 8f,
    val showTbtSmc: Boolean = true,
    val tbtShowZones: Boolean = true,
    val tbtShowPowerShift: Boolean = true,
    val tbtShowOverlap: Boolean = true,
    val tbtShowSignals: Boolean = true,
    val tbtShowHud: Boolean = true,
    val tbtAutoHideOnHtf: Boolean = true,
    val tbtTargetRR: Double = 3.0,
    val tbtSlBufferPct: Double = 0.05,
    val tbtUseKillzone: Boolean = true,
    val tbtShowVolImbalanceLine: Boolean = true,
    val tbtShowZoneTags: Boolean = true
) {
    fun toTbtSmcConfig(): TbtSmcConfig = TbtSmcConfig(
        swingLen = 5,
        showZones = tbtShowZones,
        showPowerShift = tbtShowPowerShift,
        showOverlap = tbtShowOverlap,
        showSignals = tbtShowSignals,
        showHud = tbtShowHud,
        autoHideOnHtf = tbtAutoHideOnHtf,
        targetRR = tbtTargetRR,
        slBufferPct = tbtSlBufferPct,
        showVolImbalanceLine = tbtShowVolImbalanceLine,
        showZoneTags = tbtShowZoneTags
    )
}

data class BarReplayState(
    val isActive: Boolean = false,
    val isPickingStart: Boolean = false,
    val cutoffIndex: Int = -1,
    val isPlaying: Boolean = false,
    val speedMultiplier: Int = 1
)

data class PriceRangeMeasurement(
    val start: ChartPoint,
    val end: ChartPoint,
    val barCount: Int
)
