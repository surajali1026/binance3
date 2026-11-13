package com.example.data.repository

import com.example.data.local.AlertDao
import com.example.data.local.AlertEntity
import com.example.data.local.AlertHistoryEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ChartPreferencesEntity
import com.example.data.local.DrawingDao
import com.example.data.local.DrawingEntity
import com.example.data.local.FavoriteSymbolEntity
import com.example.data.local.PreferencesDao
import com.example.data.local.TimeframeDao
import com.example.data.local.TimeframeEntity
import com.example.data.local.WatchlistDao
import com.example.domain.model.AlertHistoryEntry
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPreferences
import com.example.domain.model.CustomTimeUnit
import com.example.domain.model.PriceAlert
import com.example.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChartStateRepository(
    private val db: AppDatabase
) {
    private val watchlistDao: WatchlistDao = db.watchlistDao()
    private val timeframeDao: TimeframeDao = db.timeframeDao()
    private val drawingDao: DrawingDao = db.drawingDao()
    private val alertDao: AlertDao = db.alertDao()
    private val preferencesDao: PreferencesDao = db.preferencesDao()

    suspend fun ensureSeeded() {
        AppDatabase.seedDefaults(db)
    }

    // Watchlist Favorites
    val favoriteSymbols: Flow<Set<String>> =
        watchlistDao.observeFavoriteSymbols().map { it.toSet() }

    suspend fun toggleFavoriteSymbol(symbol: String) {
        if (watchlistDao.isFavorite(symbol)) {
            watchlistDao.removeFavorite(symbol)
        } else {
            watchlistDao.addFavorite(FavoriteSymbolEntity(symbol))
        }
    }

    // Timeframes
    val timeframes: Flow<List<Timeframe>> = timeframeDao.observeTimeframes().map { entities ->
        if (entities.isEmpty()) Timeframe.DEFAULTS else entities.map { it.toDomain() }
    }

    suspend fun toggleFavoriteTimeframe(id: String, isFavorite: Boolean) {
        timeframeDao.setFavorite(id, isFavorite)
    }

    suspend fun addCustomTimeframe(amount: Int, unit: CustomTimeUnit): Timeframe {
        val current = timeframeDao.getTimeframesOnce()
        val maxSort = (current.maxOfOrNull { it.sortOrder } ?: 15) + 1
        val tf = Timeframe.createCustom(amount, unit, maxSort)
        timeframeDao.upsert(TimeframeEntity.fromDomain(tf))
        return tf
    }

    suspend fun deleteCustomTimeframe(id: String) {
        timeframeDao.deleteCustom(id)
    }

    // Drawings
    fun observeDrawings(symbol: String): Flow<List<ChartDrawing>> =
        drawingDao.observeDrawingsForSymbol(symbol).map { list -> list.map { it.toDomain() } }

    suspend fun saveDrawing(drawing: ChartDrawing) {
        drawingDao.upsert(DrawingEntity.fromDomain(drawing))
    }

    suspend fun deleteDrawing(id: String) {
        drawingDao.deleteById(id)
    }

    suspend fun clearDrawings(symbol: String) {
        drawingDao.deleteAllForSymbol(symbol)
    }

    // Alerts & History
    fun observeAlertsForSymbol(symbol: String): Flow<List<PriceAlert>> =
        alertDao.observeAlertsForSymbol(symbol).map { list -> list.map { it.toDomain() } }

    val activeAlerts: Flow<List<PriceAlert>> =
        alertDao.observeActiveAlerts().map { list -> list.map { it.toDomain() } }

    suspend fun getActiveAlertsOnce(): List<PriceAlert> =
        alertDao.getActiveAlertsOnce().map { it.toDomain() }

    val alertHistory: Flow<List<AlertHistoryEntry>> =
        alertDao.observeHistory().map { list -> list.map { it.toDomain() } }

    suspend fun saveAlert(alert: PriceAlert) {
        alertDao.upsert(AlertEntity.fromDomain(alert))
    }

    suspend fun updateAlertTargetPrice(id: String, newPrice: Double) {
        alertDao.updateTargetPrice(id, newPrice)
    }

    suspend fun updateAlertLastEvaluatedPrice(id: String, price: Double) {
        alertDao.updateLastEvaluatedPrice(id, price)
    }

    suspend fun setAlertActive(id: String, isActive: Boolean) {
        alertDao.setActive(id, isActive)
    }

    suspend fun deleteAlert(id: String) {
        alertDao.deleteById(id)
    }

    suspend fun recordAlertTriggered(alert: PriceAlert, triggeredPrice: Double, message: String) {
        alertDao.setActive(alert.id, false)
        alertDao.insertHistory(
            AlertHistoryEntity(
                id = "${alert.id}_${System.currentTimeMillis()}",
                symbol = alert.symbol,
                conditionLabel = alert.condition.label,
                triggeredPrice = triggeredPrice,
                triggeredAt = System.currentTimeMillis(),
                message = message
            )
        )
    }

    suspend fun clearAlertHistory() {
        alertDao.clearHistory()
    }

    // Chart Preferences
    val preferences: Flow<ChartPreferences> = preferencesDao.observePreferences().map {
        it?.toDomain() ?: ChartPreferences()
    }

    suspend fun getPreferencesOnce(): ChartPreferences =
        preferencesDao.getPreferencesOnce()?.toDomain() ?: ChartPreferences()

    suspend fun updatePreferences(transform: (ChartPreferences) -> ChartPreferences) {
        val current = getPreferencesOnce()
        val updated = transform(current)
        preferencesDao.upsert(ChartPreferencesEntity.fromDomain(updated))
    }
}
