package com.example

import android.app.Application
import android.content.Context
import com.example.alerts.AlertNotifier
import com.example.alerts.AlertScheduler
import com.example.data.local.AppDatabase
import com.example.data.remote.BinanceFuturesApi
import com.example.data.remote.BinanceSocketManager
import com.example.data.remote.BinanceVisionApi
import com.example.data.remote.BybitLinearApi
import com.example.data.remote.CoinGeckoApi
import com.example.data.remote.CoinPaprikaApi
import com.example.data.repository.ChartStateRepository
import com.example.data.repository.MarketRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val okHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    val binanceProdApi: BinanceFuturesApi by lazy {
        Retrofit.Builder()
            .baseUrl(BinanceFuturesApi.PROD_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BinanceFuturesApi::class.java)
    }

    val binanceDemoApi: BinanceFuturesApi by lazy {
        Retrofit.Builder()
            .baseUrl(BinanceFuturesApi.DEMO_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BinanceFuturesApi::class.java)
    }

    val binanceVisionApi: BinanceVisionApi by lazy {
        Retrofit.Builder()
            .baseUrl(BinanceVisionApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BinanceVisionApi::class.java)
    }

    val bybitLinearApi: BybitLinearApi by lazy {
        Retrofit.Builder()
            .baseUrl(BybitLinearApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BybitLinearApi::class.java)
    }

    val coinGeckoApi: CoinGeckoApi by lazy {
        Retrofit.Builder()
            .baseUrl(CoinGeckoApi.BASE_URL)
            .client(okHttpClient)
            .build()
            .create(CoinGeckoApi::class.java)
    }

    val coinPaprikaApi: CoinPaprikaApi by lazy {
        Retrofit.Builder()
            .baseUrl(CoinPaprikaApi.BASE_URL)
            .client(okHttpClient)
            .build()
            .create(CoinPaprikaApi::class.java)
    }

    val socketManager: BinanceSocketManager by lazy {
        BinanceSocketManager(okHttpClient)
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(appContext)
    }

    val marketRepository: MarketRepository by lazy {
        MarketRepository(
            prodApi = binanceProdApi,
            demoApi = binanceDemoApi,
            bybitApi = bybitLinearApi,
            visionApi = binanceVisionApi,
            socketManager = socketManager,
            coinGeckoApi = coinGeckoApi,
            coinPaprikaApi = coinPaprikaApi
        )
    }

    val chartStateRepository: ChartStateRepository by lazy {
        ChartStateRepository(database)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}

class BinanceProApplication : Application(), androidx.work.Configuration.Provider {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.ensureChannels(this)
        val container = AppContainer.get(this)
        appScope.launch {
            container.chartStateRepository.ensureSeeded()
            combine(
                container.chartStateRepository.activeAlerts,
                container.chartStateRepository.preferences
            ) { alerts, prefs ->
                alerts.isNotEmpty() to prefs.backgroundMonitorEnabled
            }.collect { (hasActiveAlerts, bgEnabled) ->
                AlertScheduler.syncBackgroundMonitoring(
                    context = this@BinanceProApplication,
                    hasActiveAlerts = hasActiveAlerts,
                    backgroundMonitorEnabled = bgEnabled,
                    fromBackground = true
                )
            }
        }
    }
}
