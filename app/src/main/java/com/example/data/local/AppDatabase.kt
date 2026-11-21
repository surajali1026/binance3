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
    version = 10,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowTradeSetup INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtUseMultiTimeframe INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowVolumeProfile INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowLiquidityTraps INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chart_preferences_new (
                        id INTEGER NOT NULL,
                        lastSymbol TEXT NOT NULL,
                        lastTimeframeId TEXT NOT NULL,
                        lastPriceSource TEXT NOT NULL,
                        bullColorHex INTEGER NOT NULL,
                        bearColorHex INTEGER NOT NULL,
                        showGrid INTEGER NOT NULL,
                        showCountdown INTEGER NOT NULL,
                        autoFitDefault INTEGER NOT NULL,
                        backgroundMonitorEnabled INTEGER NOT NULL,
                        loudSoundEnabled INTEGER NOT NULL,
                        vibrationEnabled INTEGER NOT NULL,
                        lastBarSpacingDp REAL NOT NULL,
                        lastRightOffsetBars REAL NOT NULL,
                        showTbtSmc INTEGER NOT NULL,
                        tbtShowZones INTEGER NOT NULL,
                        tbtShowPowerShift INTEGER NOT NULL,
                        tbtShowOverlap INTEGER NOT NULL,
                        tbtShowSignals INTEGER NOT NULL,
                        tbtShowHud INTEGER NOT NULL,
                        tbtShowTradeSetup INTEGER NOT NULL,
                        tbtUseMultiTimeframe INTEGER NOT NULL,
                        tbtShowVolumeProfile INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO chart_preferences_new (
                        id, lastSymbol, lastTimeframeId, lastPriceSource, bullColorHex, bearColorHex,
                        showGrid, showCountdown, autoFitDefault, backgroundMonitorEnabled, loudSoundEnabled,
                        vibrationEnabled, lastBarSpacingDp, lastRightOffsetBars, showTbtSmc, tbtShowZones,
                        tbtShowPowerShift, tbtShowOverlap, tbtShowSignals, tbtShowHud, tbtShowTradeSetup,
                        tbtUseMultiTimeframe, tbtShowVolumeProfile
                    )
                    SELECT
                        id, lastSymbol, lastTimeframeId, lastPriceSource, bullColorHex, bearColorHex,
                        showGrid, showCountdown, autoFitDefault, backgroundMonitorEnabled, loudSoundEnabled,
                        vibrationEnabled, lastBarSpacingDp, lastRightOffsetBars, showTbtSmc, tbtShowZones,
                        tbtShowPowerShift, tbtShowOverlap, tbtShowSignals, tbtShowHud, tbtShowTradeSetup,
                        tbtUseMultiTimeframe, tbtShowVolumeProfile
                    FROM chart_preferences
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE chart_preferences")
                db.execSQL("ALTER TABLE chart_preferences_new RENAME TO chart_preferences")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chart_preferences_v7 (
                        id INTEGER NOT NULL,
                        lastSymbol TEXT NOT NULL,
                        lastTimeframeId TEXT NOT NULL,
                        lastPriceSource TEXT NOT NULL,
                        bullColorHex INTEGER NOT NULL,
                        bearColorHex INTEGER NOT NULL,
                        showGrid INTEGER NOT NULL,
                        showCountdown INTEGER NOT NULL,
                        autoFitDefault INTEGER NOT NULL,
                        backgroundMonitorEnabled INTEGER NOT NULL,
                        loudSoundEnabled INTEGER NOT NULL,
                        vibrationEnabled INTEGER NOT NULL,
                        lastBarSpacingDp REAL NOT NULL,
                        lastRightOffsetBars REAL NOT NULL,
                        showTbtSmc INTEGER NOT NULL,
                        tbtShowZones INTEGER NOT NULL,
                        tbtShowPowerShift INTEGER NOT NULL,
                        tbtShowOverlap INTEGER NOT NULL,
                        tbtShowSignals INTEGER NOT NULL,
                        tbtShowHud INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO chart_preferences_v7 (
                        id, lastSymbol, lastTimeframeId, lastPriceSource, bullColorHex, bearColorHex,
                        showGrid, showCountdown, autoFitDefault, backgroundMonitorEnabled, loudSoundEnabled,
                        vibrationEnabled, lastBarSpacingDp, lastRightOffsetBars, showTbtSmc, tbtShowZones,
                        tbtShowPowerShift, tbtShowOverlap, tbtShowSignals, tbtShowHud
                    )
                    SELECT
                        id, lastSymbol, lastTimeframeId, lastPriceSource, bullColorHex, bearColorHex,
                        showGrid, showCountdown, autoFitDefault, backgroundMonitorEnabled, loudSoundEnabled,
                        vibrationEnabled, lastBarSpacingDp, lastRightOffsetBars, showTbtSmc, tbtShowZones,
                        tbtShowPowerShift, tbtShowOverlap, tbtShowSignals, tbtShowHud
                    FROM chart_preferences
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE chart_preferences")
                db.execSQL("ALTER TABLE chart_preferences_v7 RENAME TO chart_preferences")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtAutoHideOnHtf INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtTargetRR REAL NOT NULL DEFAULT 3.0")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtSlBufferPct REAL NOT NULL DEFAULT 0.05")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtUseKillzone INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowVolImbalanceLine INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtShowZoneTags INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN tbtMtfProfile TEXT NOT NULL DEFAULT 'INTRADAY_1H'")
                db.execSQL("UPDATE chart_preferences SET tbtAutoHideOnHtf = 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN showMultiExchVol INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN multiExchShowTraps INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chart_preferences ADD COLUMN multiExchSelectedCsv TEXT NOT NULL DEFAULT 'BINANCE,BYBIT,OKX,BITGET,HYPERLIQUID,DYDX'")
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
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10
            )
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
