package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT symbol FROM favorite_symbols ORDER BY addedAt ASC")
    fun observeFavoriteSymbols(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteSymbolEntity)

    @Query("DELETE FROM favorite_symbols WHERE symbol = :symbol")
    suspend fun removeFavorite(symbol: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_symbols WHERE symbol = :symbol)")
    suspend fun isFavorite(symbol: String): Boolean
}

@Dao
interface TimeframeDao {
    @Query("SELECT * FROM timeframes ORDER BY durationMillis ASC, sortOrder ASC")
    fun observeTimeframes(): Flow<List<TimeframeEntity>>

    @Query("SELECT * FROM timeframes ORDER BY durationMillis ASC, sortOrder ASC")
    suspend fun getTimeframesOnce(): List<TimeframeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TimeframeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TimeframeEntity)

    @Query("UPDATE timeframes SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("DELETE FROM timeframes WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustom(id: String)
}

@Dao
interface DrawingDao {
    @Query("SELECT * FROM chart_drawings WHERE symbol = :symbol ORDER BY createdAt ASC")
    fun observeDrawingsForSymbol(symbol: String): Flow<List<DrawingEntity>>

    @Query("SELECT * FROM chart_drawings WHERE id = :id LIMIT 1")
    suspend fun getDrawingById(id: String): DrawingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DrawingEntity)

    @Query("DELETE FROM chart_drawings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chart_drawings WHERE symbol = :symbol")
    suspend fun deleteAllForSymbol(symbol: String)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM price_alerts WHERE symbol = :symbol ORDER BY createdAt DESC")
    fun observeAlertsForSymbol(symbol: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE isActive = 1 ORDER BY createdAt DESC")
    fun observeActiveAlerts(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE isActive = 1")
    suspend fun getActiveAlertsOnce(): List<AlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlertEntity)

    @Query("UPDATE price_alerts SET targetPrice = :newPrice WHERE id = :id")
    suspend fun updateTargetPrice(id: String, newPrice: Double)

    @Query("UPDATE price_alerts SET lastEvaluatedPrice = :price WHERE id = :id")
    suspend fun updateLastEvaluatedPrice(id: String, price: Double)

    @Query("UPDATE price_alerts SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    @Query("DELETE FROM price_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM alert_history ORDER BY triggeredAt DESC LIMIT 150")
    fun observeHistory(): Flow<List<AlertHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: AlertHistoryEntity)

    @Query("DELETE FROM alert_history")
    suspend fun clearHistory()
}

@Dao
interface PreferencesDao {
    @Query("SELECT * FROM chart_preferences WHERE id = 1")
    fun observePreferences(): Flow<ChartPreferencesEntity?>

    @Query("SELECT * FROM chart_preferences WHERE id = 1")
    suspend fun getPreferencesOnce(): ChartPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChartPreferencesEntity)
}
