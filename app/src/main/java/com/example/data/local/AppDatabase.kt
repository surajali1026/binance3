package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        FavoriteSymbolEntity::class,
        TimeframeEntity::class,
        DrawingEntity::class,
        AlertEntity::class,
        AlertHistoryEntity::class,
        ChartPreferencesEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun timeframeDao(): TimeframeDao
    abstract fun drawingDao(): DrawingDao
    abstract fun alertDao(): AlertDao
    abstract fun preferencesDao(): PreferencesDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN showTbtSmc INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowZones INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowPowerShift INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowOverlap INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowSignals INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowHud INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "binance_pro.db"
            ).addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        val instance = INSTANCE ?: return@launch
                        seedDefaults(instance)
                    }
                }
            }).build()
        }

        suspend fun seedDefaults(db: AppDatabase) {
            if (db.timeframeDao().getTimeframesOnce().isEmpty()) {
                db.timeframeDao().upsertAll(Timeframe.DEFAULTS.map(TimeframeEntity::fromDomain))
            }
            if (db.preferencesDao().getPreferencesOnce() == null) {
                db.preferencesDao().upsert(ChartPreferencesEntity())
                listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT").forEach { sym ->
                    db.watchlistDao().addFavorite(FavoriteSymbolEntity(sym))
                }
            }
        }
    }
}
