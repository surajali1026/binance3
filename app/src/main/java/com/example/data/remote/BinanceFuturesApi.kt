package com.example.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

enum class MarketEndpointMode(val label: String, val socketUrl: String) {
    FUTURES_PROD("Binance USD-M Futures", "wss://fstream.binance.com/stream"),
    FUTURES_DEMO("Binance Futures Demo", "wss://demo-fstream.binance.com/stream"),
    BYBIT_LINEAR("Bybit Perpetual Futures", "wss://stream.bybit.com/v5/public/linear"),
    VISION_MIRROR("Binance Vision Mirror", "wss://data-stream.binance.vision/stream")
}

interface BinanceFuturesApi {

    @GET("fapi/v1/exchangeInfo")
    suspend fun getExchangeInfo(): ExchangeInfoResponse

    @GET("fapi/v1/ticker/24hr")
    suspend fun getTickers24hr(): List<Ticker24hrDto>

    @GET("fapi/v1/premiumIndex")
    suspend fun getAllPremiumIndex(): List<PremiumIndexDto>

    @GET("fapi/v1/premiumIndex")
    suspend fun getPremiumIndex(@Query("symbol") symbol: String): PremiumIndexDto

    @GET("fapi/v1/klines")
    suspend fun getLastPriceKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): ResponseBody

    @GET("fapi/v1/markPriceKlines")
    suspend fun getMarkPriceKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): ResponseBody

    @GET("fapi/v1/indexPriceKlines")
    suspend fun getIndexPriceKlines(
        @Query("pair") pair: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): ResponseBody

    companion object {
        const val PROD_BASE_URL = "https://fapi.binance.com/"
        const val DEMO_BASE_URL = "https://demo-fapi.binance.com/"
    }
}

interface BybitLinearApi {

    @GET("v5/market/instruments-info")
    suspend fun getLinearInstruments(
        @Query("category") category: String = "linear",
        @Query("limit") limit: Int = 1000
    ): ResponseBody

    @GET("v5/market/tickers")
    suspend fun getLinearTickers(
        @Query("category") category: String = "linear"
    ): ResponseBody

    @GET("v5/market/kline")
    suspend fun getLastPriceKlines(
        @Query("category") category: String = "linear",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("end") end: Long? = null
    ): ResponseBody

    @GET("v5/market/mark-price-kline")
    suspend fun getMarkPriceKlines(
        @Query("category") category: String = "linear",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("end") end: Long? = null
    ): ResponseBody

    @GET("v5/market/index-price-kline")
    suspend fun getIndexPriceKlines(
        @Query("category") category: String = "linear",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("end") end: Long? = null
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://api.bybit.com/"
    }
}

interface BinanceVisionApi {

    @GET("api/v3/exchangeInfo")
    suspend fun getExchangeInfo(): ExchangeInfoResponse

    @GET("api/v3/ticker/24hr")
    suspend fun getTickers24hr(): List<Ticker24hrDto>

    @GET("api/v3/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://data-api.binance.vision/"
    }
}

interface CoinGeckoApi {

    @GET("api/v3/coins/markets")
    suspend fun getTopMarkets(
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("order") order: String = "market_cap_desc",
        @Query("per_page") perPage: Int = 250,
        @Query("page") page: Int = 1,
        @Query("sparkline") sparkline: Boolean = false
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://api.coingecko.com/"
    }
}

interface CoinPaprikaApi {

    @GET("v1/tickers")
    suspend fun getTickers(
        @Query("quotes") quotes: String = "USD"
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://api.coinpaprika.com/"
    }
}
