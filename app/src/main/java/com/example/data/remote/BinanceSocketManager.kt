package com.example.data.remote

import com.example.domain.model.PriceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

class BinanceSocketManager(
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val subscriptionCounts = mutableMapOf<String, Int>()
    private val requestId = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private var isIntentionallyClosed = false
    @Volatile
    private var streamBaseUrl: String = STREAM_BASE_URL

    private fun isBybitSocket(): Boolean = streamBaseUrl.contains("bybit.com", ignoreCase = true)

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _tickerUpdates = MutableSharedFlow<List<WsTickerUpdate>>(extraBufferCapacity = 64)
    val tickerUpdates: SharedFlow<List<WsTickerUpdate>> = _tickerUpdates.asSharedFlow()

    private val _markPriceUpdates = MutableSharedFlow<List<WsMarkPriceUpdate>>(extraBufferCapacity = 64)
    val markPriceUpdates: SharedFlow<List<WsMarkPriceUpdate>> = _markPriceUpdates.asSharedFlow()

    private val _klineUpdates = MutableSharedFlow<WsKlineUpdate>(extraBufferCapacity = 128)
    val klineUpdates: SharedFlow<WsKlineUpdate> = _klineUpdates.asSharedFlow()

    fun setStreamBaseUrl(newUrl: String) {
        synchronized(lock) {
            if (streamBaseUrl == newUrl) return
            streamBaseUrl = newUrl
            reconnectAttempt = 0
            heartbeatJob?.cancel()
            if (webSocket != null) {
                webSocket?.cancel()
                webSocket = null
                if (subscriptionCounts.isNotEmpty() && !isIntentionallyClosed) {
                    connectInternal()
                }
            }
        }
    }

    fun subscribe(streams: Collection<String>) {
        if (streams.isEmpty()) return
        val newlyAdded = mutableListOf<String>()
        synchronized(lock) {
            isIntentionallyClosed = false
            streams.forEach { raw ->
                val stream = raw.trim()
                if (stream.isNotEmpty()) {
                    val count = subscriptionCounts[stream] ?: 0
                    subscriptionCounts[stream] = count + 1
                    if (count == 0) {
                        newlyAdded.add(stream)
                    }
                }
            }
            if (webSocket == null) {
                connectInternal()
            } else if (_connectionState.value == SocketConnectionState.CONNECTED && newlyAdded.isNotEmpty()) {
                sendCommand("SUBSCRIBE", newlyAdded)
            }
        }
    }

    fun unsubscribe(streams: Collection<String>) {
        if (streams.isEmpty()) return
        val toRemove = mutableListOf<String>()
        var shouldClose = false
        synchronized(lock) {
            streams.forEach { raw ->
                val stream = raw.trim()
                val count = subscriptionCounts[stream] ?: 0
                if (count <= 1) {
                    if (subscriptionCounts.remove(stream) != null) {
                        toRemove.add(stream)
                    }
                } else {
                    subscriptionCounts[stream] = count - 1
                }
            }
            if (_connectionState.value == SocketConnectionState.CONNECTED && toRemove.isNotEmpty()) {
                sendCommand("UNSUBSCRIBE", toRemove)
            }
            if (subscriptionCounts.isEmpty()) {
                shouldClose = true
            }
        }
        if (shouldClose) {
            closeInternal()
        }
    }

    fun forceReconnect() {
        synchronized(lock) {
            if (subscriptionCounts.isEmpty()) return
            webSocket?.cancel()
            webSocket = null
            connectInternal()
        }
    }

    private fun connectInternal() {
        reconnectJob?.cancel()
        val streamsSnapshot = synchronized(lock) { subscriptionCounts.keys.toList() }
        if (streamsSnapshot.isEmpty()) {
            _connectionState.value = SocketConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = if (reconnectAttempt > 0) {
            SocketConnectionState.RECONNECTING
        } else {
            SocketConnectionState.CONNECTING
        }

        val request = Request.Builder()
            .url(streamBaseUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _connectionState.value = SocketConnectionState.CONNECTED
                val activeStreams = synchronized(lock) { subscriptionCounts.keys.toList() }
                if (activeStreams.isNotEmpty()) {
                    sendCommand("SUBSCRIBE", activeStreams, webSocket)
                }
                heartbeatJob?.cancel()
                if (isBybitSocket()) {
                    val openedSocket = webSocket
                    heartbeatJob = scope.launch {
                        while (true) {
                            delay(20_000L)
                            val isStillCurrent = synchronized(lock) { this@BinanceSocketManager.webSocket === openedSocket }
                            if (!isStillCurrent || !isBybitSocket()) break
                            runCatching { openedSocket.send("""{"op":"ping"}""") }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    if (isBybitSocket()) {
                        BybitPayloadParser.parseSocketMessage(
                            rawText = text,
                            onTickers = { _tickerUpdates.tryEmit(it) },
                            onMarkPrices = { _markPriceUpdates.tryEmit(it) },
                            onKline = { _klineUpdates.tryEmit(it) }
                        )
                    } else {
                        BinancePayloadParser.parseSocketMessage(
                            rawText = text,
                            onTickers = { _tickerUpdates.tryEmit(it) },
                            onMarkPrices = { _markPriceUpdates.tryEmit(it) },
                            onKline = { _klineUpdates.tryEmit(it) }
                        )
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect(webSocket)
            }
        })
    }

    private fun handleDisconnect(closedSocket: WebSocket) {
        heartbeatJob?.cancel()
        val shouldReconnect = synchronized(lock) {
            if (webSocket === closedSocket) {
                webSocket = null
            }
            !isIntentionallyClosed && subscriptionCounts.isNotEmpty()
        }
        if (!shouldReconnect) {
            _connectionState.value = SocketConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = SocketConnectionState.RECONNECTING
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSec = min(15, 1 shl min(reconnectAttempt, 4))
            reconnectAttempt++
            delay(backoffSec * 1000L)
            synchronized(lock) {
                if (!isIntentionallyClosed && subscriptionCounts.isNotEmpty() && webSocket == null) {
                    connectInternal()
                }
            }
        }
    }

    private fun closeInternal() {
        synchronized(lock) {
            isIntentionallyClosed = true
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            webSocket?.close(1000, "No active streams")
            webSocket = null
            _connectionState.value = SocketConnectionState.DISCONNECTED
        }
    }

    private fun sendCommand(
        method: String,
        streams: List<String>,
        targetSocket: WebSocket? = webSocket
    ) {
        if (streams.isEmpty() || targetSocket == null) return
        if (isBybitSocket()) {
            val bybitTopics = streams.filter { it.startsWith("kline.") || it.startsWith("tickers.") }
            if (bybitTopics.isEmpty()) return
            val op = if (method == "UNSUBSCRIBE") "unsubscribe" else "subscribe"
            bybitTopics.chunked(10).forEach { chunk ->
                val json = JSONObject().apply {
                    put("op", op)
                    put("args", JSONArray(chunk))
                }
                targetSocket.send(json.toString())
            }
        } else {
            val binanceStreams = streams.filterNot { it.startsWith("kline.") || it.startsWith("tickers.") }
            if (binanceStreams.isEmpty()) return
            // Batch into chunks of 40 streams to respect Binance frame limits
            binanceStreams.chunked(40).forEach { chunk ->
                val json = JSONObject().apply {
                    put("method", method)
                    put("params", JSONArray(chunk))
                    put("id", requestId.getAndIncrement())
                }
                targetSocket.send(json.toString())
            }
        }
    }

    companion object {
        const val STREAM_BASE_URL = "wss://fstream.binance.com/stream"

        fun klineStreamName(
            symbol: String,
            pair: String,
            priceSource: PriceSource,
            nativeInterval: String
        ): String {
            return when (priceSource) {
                PriceSource.LAST -> "${symbol.lowercase()}@kline_$nativeInterval"
                PriceSource.MARK -> "${symbol.lowercase()}@markPriceKline_$nativeInterval"
                PriceSource.INDEX -> "${pair.lowercase()}@indexPriceKline_$nativeInterval"
            }
        }

        fun bybitKlineTopic(symbol: String, bybitInterval: String): String {
            return "kline.$bybitInterval.${symbol.uppercase()}"
        }

        fun bybitTickerTopic(symbol: String): String {
            return "tickers.${symbol.uppercase()}"
        }
    }
}
