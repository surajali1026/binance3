package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AlertCondition
import com.example.domain.model.AlertHistoryEntry
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPoint
import com.example.domain.model.ChartPreferences
import com.example.domain.model.DrawingType
import com.example.domain.model.LineStyle
import com.example.domain.model.PriceAlert
import com.example.domain.model.PriceSource
import com.example.domain.model.Timeframe
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "favorite_symbols")
data class FavoriteSymbolEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "timeframes")
data class TimeframeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val durationMillis: Long,
    val nativeInterval: String?,
    val isCustom: Boolean,
    val isFavorite: Boolean,
    val sortOrder: Int
) {
    fun toDomain(): Timeframe = Timeframe(
        id = id,
        label = label,
        durationMillis = durationMillis,
        nativeInterval = nativeInterval,
        isCustom = isCustom,
        isFavorite = isFavorite,
        sortOrder = sortOrder
    )

    companion object {
        fun fromDomain(tf: Timeframe): TimeframeEntity = TimeframeEntity(
            id = tf.id,
            label = tf.label,
            durationMillis = tf.durationMillis,
            nativeInterval = tf.nativeInterval,
            isCustom = tf.isCustom,
            isFavorite = tf.isFavorite,
            sortOrder = tf.sortOrder
        )
    }
}

@Entity(tableName = "chart_drawings")
data class DrawingEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val type: String,
    val pointsJson: String,
    val colorHex: Long,
    val strokeWidth: Float,
    val lineStyle: String,
    val isLocked: Boolean,
    val createdAt: Long
) {
    fun toDomain(): ChartDrawing {
        val points = mutableListOf<ChartPoint>()
        val array = JSONArray(pointsJson)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            points.add(ChartPoint(obj.getLong("t"), obj.getDouble("p")))
        }
        return ChartDrawing(
            id = id,
            symbol = symbol,
            type = runCatching { DrawingType.valueOf(type) }.getOrDefault(DrawingType.HORIZONTAL_LINE),
            points = points,
            colorHex = colorHex,
            strokeWidth = strokeWidth,
            lineStyle = runCatching { LineStyle.valueOf(lineStyle) }.getOrDefault(LineStyle.SOLID),
            isLocked = isLocked,
            createdAt = createdAt
        )
    }

    companion object {
        fun fromDomain(drawing: ChartDrawing): DrawingEntity {
            val array = JSONArray()
            drawing.points.forEach { pt ->
                array.put(JSONObject().apply {
                    put("t", pt.timeMillis)
                    put("p", pt.price)
                })
            }
            return DrawingEntity(
                id = drawing.id,
                symbol = drawing.symbol,
                type = drawing.type.name,
                pointsJson = array.toString(),
                colorHex = drawing.colorHex,
                strokeWidth = drawing.strokeWidth,
                lineStyle = drawing.lineStyle.name,
                isLocked = drawing.isLocked,
                createdAt = drawing.createdAt
            )
        }
    }
}

@Entity(tableName = "price_alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val priceSource: String,
    val condition: String,
    val targetPrice: Double,
    val anchorTime1: Long?,
    val anchorPrice1: Double?,
    val anchorTime2: Long?,
    val anchorPrice2: Double?,
    val timeframeId: String,
    val linkedDrawingId: String?,
    val isActive: Boolean,
    val lastEvaluatedPrice: Double?,
    val createdAt: Long
) {
    fun toDomain(): PriceAlert = PriceAlert(
        id = id,
        symbol = symbol,
        priceSource = runCatching { PriceSource.valueOf(priceSource) }.getOrDefault(PriceSource.LAST),
        condition = runCatching { AlertCondition.valueOf(condition) }.getOrDefault(AlertCondition.REACHES),
        targetPrice = targetPrice,
        anchorTime1 = anchorTime1,
        anchorPrice1 = anchorPrice1,
        anchorTime2 = anchorTime2,
        anchorPrice2 = anchorPrice2,
        timeframeId = timeframeId,
        linkedDrawingId = linkedDrawingId,
        isActive = isActive,
        lastEvaluatedPrice = lastEvaluatedPrice,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(alert: PriceAlert): AlertEntity = AlertEntity(
            id = alert.id,
            symbol = alert.symbol,
            priceSource = alert.priceSource.name,
            condition = alert.condition.name,
            targetPrice = alert.targetPrice,
            anchorTime1 = alert.anchorTime1,
            anchorPrice1 = alert.anchorPrice1,
            anchorTime2 = alert.anchorTime2,
            anchorPrice2 = alert.anchorPrice2,
            timeframeId = alert.timeframeId,
            linkedDrawingId = alert.linkedDrawingId,
            isActive = alert.isActive,
            lastEvaluatedPrice = alert.lastEvaluatedPrice,
            createdAt = alert.createdAt
        )
    }
}

@Entity(tableName = "alert_history")
data class AlertHistoryEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val conditionLabel: String,
    val triggeredPrice: Double,
    val triggeredAt: Long,
    val message: String
) {
    fun toDomain(): AlertHistoryEntry = AlertHistoryEntry(
        id = id,
        symbol = symbol,
        conditionLabel = conditionLabel,
        triggeredPrice = triggeredPrice,
        triggeredAt = triggeredAt,
        message = message
    )

    companion object {
        fun fromDomain(entry: AlertHistoryEntry): AlertHistoryEntity = AlertHistoryEntity(
            id = entry.id,
            symbol = entry.symbol,
            conditionLabel = entry.conditionLabel,
            triggeredPrice = entry.triggeredPrice,
            triggeredAt = entry.triggeredAt,
            message = entry.message
        )
    }
}

@Entity(tableName = "chart_preferences")
data class ChartPreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val lastSymbol: String = "BTCUSDT",
    val lastTimeframeId: String = "15m",
    val lastPriceSource: String = PriceSource.LAST.name,
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
    val tbtAutoHideOnHtf: Boolean = false,
    val tbtTargetRR: Double = 3.0,
    val tbtSlBufferPct: Double = 0.05,
    val tbtUseKillzone: Boolean = false,
    val tbtShowVolImbalanceLine: Boolean = true,
    val tbtShowZoneTags: Boolean = true,
    val tbtMtfProfile: String = com.example.domain.indicator.TbtMtfProfile.INTRADAY_1H.name
) {
    fun toDomain(): ChartPreferences = ChartPreferences(
        lastSymbol = lastSymbol,
        lastTimeframeId = lastTimeframeId,
        lastPriceSource = runCatching { PriceSource.valueOf(lastPriceSource) }.getOrDefault(PriceSource.LAST),
        bullColorHex = bullColorHex,
        bearColorHex = bearColorHex,
        showGrid = showGrid,
        showCountdown = showCountdown,
        autoFitDefault = autoFitDefault,
        backgroundMonitorEnabled = backgroundMonitorEnabled,
        loudSoundEnabled = loudSoundEnabled,
        vibrationEnabled = vibrationEnabled,
        lastBarSpacingDp = lastBarSpacingDp,
        lastRightOffsetBars = lastRightOffsetBars,
        showTbtSmc = showTbtSmc,
        tbtShowZones = tbtShowZones,
        tbtShowPowerShift = tbtShowPowerShift,
        tbtShowOverlap = tbtShowOverlap,
        tbtShowSignals = tbtShowSignals,
        tbtShowHud = tbtShowHud,
        tbtAutoHideOnHtf = tbtAutoHideOnHtf,
        tbtTargetRR = tbtTargetRR,
        tbtSlBufferPct = tbtSlBufferPct,
        tbtUseKillzone = tbtUseKillzone,
        tbtShowVolImbalanceLine = tbtShowVolImbalanceLine,
        tbtShowZoneTags = tbtShowZoneTags,
        tbtMtfProfile = runCatching {
            com.example.domain.indicator.TbtMtfProfile.valueOf(tbtMtfProfile)
        }.getOrDefault(com.example.domain.indicator.TbtMtfProfile.INTRADAY_1H)
    )

    companion object {
        fun fromDomain(prefs: ChartPreferences): ChartPreferencesEntity = ChartPreferencesEntity(
            id = 1,
            lastSymbol = prefs.lastSymbol,
            lastTimeframeId = prefs.lastTimeframeId,
            lastPriceSource = prefs.lastPriceSource.name,
            bullColorHex = prefs.bullColorHex,
            bearColorHex = prefs.bearColorHex,
            showGrid = prefs.showGrid,
            showCountdown = prefs.showCountdown,
            autoFitDefault = prefs.autoFitDefault,
            backgroundMonitorEnabled = prefs.backgroundMonitorEnabled,
            loudSoundEnabled = prefs.loudSoundEnabled,
            vibrationEnabled = prefs.vibrationEnabled,
            lastBarSpacingDp = prefs.lastBarSpacingDp,
            lastRightOffsetBars = prefs.lastRightOffsetBars,
            showTbtSmc = prefs.showTbtSmc,
            tbtShowZones = prefs.tbtShowZones,
            tbtShowPowerShift = prefs.tbtShowPowerShift,
            tbtShowOverlap = prefs.tbtShowOverlap,
            tbtShowSignals = prefs.tbtShowSignals,
            tbtShowHud = prefs.tbtShowHud,
            tbtAutoHideOnHtf = prefs.tbtAutoHideOnHtf,
            tbtTargetRR = prefs.tbtTargetRR,
            tbtSlBufferPct = prefs.tbtSlBufferPct,
            tbtUseKillzone = prefs.tbtUseKillzone,
            tbtShowVolImbalanceLine = prefs.tbtShowVolImbalanceLine,
            tbtShowZoneTags = prefs.tbtShowZoneTags,
            tbtMtfProfile = prefs.tbtMtfProfile.name
        )
    }
}
