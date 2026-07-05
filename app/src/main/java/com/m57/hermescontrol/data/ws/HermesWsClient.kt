package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection status for the WebSocket client.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    NO_NETWORK,
    AUTH_EXPIRED,
}

/**
 * WebSocket client for the Hermes Dashboard JSON-RPC 2.0 interface.
 *
 * Connects to `ws://HOST:PORT/api/ws?token=TOKEN`, auto-reconnects with
 * exponential backoff, and emits parsed [WsEvent]s via [events] SharedFlow
 * as well as direct callbacks.
 */
object HermesWsClient {
    private const val TAG = "HermesWsClient"

    // ── Backoff settings ─────────────────────────────────────────────────

    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val BACKOFF_MULTIPLIER = 2.0

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = INITIAL_BACKOFF_MS

    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var reconnectJob: Job? = null

    // ── Health and Ping/Pong tracking ────────────────────────────────────

    @Volatile
    var lastPongTimestamp: Long = 0L
        private set

    val isHealthy: Boolean
        get() = isConnected && (System.currentTimeMillis() - lastPongTimestamp < 60_000L)

    private var healthJob: Job? = null

    private fun startHealthTracking() {
        healthJob?.cancel()
        lastPongTimestamp = System.currentTimeMillis()
        healthJob =
            wsScope.launch {
                while (connected.get()) {
                    delay(30_000L)
                    if (connected.get() && System.currentTimeMillis() - lastPongTimestamp > 60_000L) {
                        Log.w(TAG, "WebSocket connection appears unhealthy (no frames received for > 60s)")
                    }
                }
            }
    }

    private fun stopHealthTracking() {
        healthJob?.cancel()
        healthJob = null
    }

    // ── Public observable stream ─────────────────────────────────────────

    private val rawMessages =
        MutableSharedFlow<String>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Collect this from ViewModels to receive all parsed [WsEvent]s. */
    val events: SharedFlow<WsEvent> =
        rawMessages
            .buffer(Channel.BUFFERED)
            .map { text ->
                try {
                    val rpc = OkHttpProvider.json.decodeFromString<JsonRpcResponse>(text)
                    EventParser.parse(rpc, text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                    WsEvent.Unknown(text)
                }
            }.flowOn(Dispatchers.Default) // CPU-bound
            .shareIn(wsScope, SharingStarted.Eagerly)

    // ── Connection status flow ──────────────────────────────────────────
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Observable connection status */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    init {
        // Monitor network state to trigger immediate reconnect when network is restored
        wsScope.launch {
            NetworkMonitor.isConnected.collect { connected ->
                if (connected && !isConnected && !intentionalClose.get() && AuthManager.isAutoReconnect()) {
                    Log.d(TAG, "Network restored — triggering immediate reconnect")
                    currentBackoff = INITIAL_BACKOFF_MS
                    reconnectJob?.cancel()
                    openSocket()
                }
            }
        }
    }

    // ── Connection helpers ────────────────────────────────────────────────

    @VisibleForTesting
    val isConnected: Boolean get() = connected.get()

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "Already connected — skipping")
            return
        }
        intentionalClose.set(false)
        currentBackoff = INITIAL_BACKOFF_MS
        _connectionStatus.value = ConnectionStatus.CONNECTING
        refreshWsTicketIfNeeded()
        openSocket()
    }

    /**
     * If a session cookie is present (gated mode), mint a fresh WS ticket
     * from the dashboard. The ticket is single-use and has a 30-second TTL,
     * so we must mint a new one on every connect (first launch and reconnect).
     */
    private fun refreshWsTicketIfNeeded() {
        val sessionCookie = AuthManager.getSessionCookie()
        if (sessionCookie.isNullOrBlank()) {
            return
        }
        try {
            val client = OkHttpProvider.probe
            val request =
                Request
                    .Builder()
                    .url("http://${AuthManager.getHost()}:${AuthManager.getPort()}/api/auth/ws-ticket")
                    .header("Cookie", "hermes_session_at=$sessionCookie")
                    .post("{}".toRequestBody())
                    .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val ticketMatch = Regex(""""ticket":"([^"]+)"""").find(body)
                val ticket = ticketMatch?.groupValues?.getOrNull(1)
                if (!ticket.isNullOrBlank()) {
                    AuthManager.setToken(ticket)
                    if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
                }
            } else {
                Log.w(TAG, "WS ticket refresh failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS ticket refresh failed: ${e.message}")
        }
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect() {
        intentionalClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        stopHealthTracking()
        webSocket?.close(1000, "Client closed")
        webSocket = null
        connected.set(false)
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    // ── Send helpers ─────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request with the given [method] and optional [params].
     * @return the request id used (can be matched against [WsEvent.RpcResult]).
     */
    fun send(
        method: String,
        params: Map<String, Any> = emptyMap(),
        onSent: ((String) -> Unit)? = null,
    ): String {
        val id = requestId.incrementAndGet().toString()
        onSent?.invoke(id)
        val request = JsonRpcRequest(id = id, method = method, params = params.mapValues { it.value.toJsonElement() })
        val json = OkHttpProvider.json.encodeToString(request)
        if (BuildConfig.DEBUG) Log.d(TAG, "→ $json")
        val ws = webSocket
        if (ws != null && connected.get()) {
            ws.send(json)
        } else {
            Log.d(TAG, "WS disconnected — queuing message")
            messageQueue.add(json)
        }
        return id
    }

    /** Convenience: submit a user prompt to an existing session. */
    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            method = WsMethods.PROMPT_SUBMIT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    // ── Internal ─────────────────────────────────────────────────────────

    private fun openSocket() {
        refreshWsTicketIfNeeded()
        val url = AuthManager.wsUrl()
        val safeUrl = url.replace(Regex("token=[^&]+"), "token=REDACTED")
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

        val request = Request.Builder().url(url).build()
        webSocket = OkHttpProvider.websocket.newWebSocket(request, WsListenerImpl())
    }

    private fun scheduleReconnect() {
        if (intentionalClose.get()) return
        if (!AuthManager.isAutoReconnect()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Auto-reconnect disabled")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }
        if (!NetworkMonitor.isConnected.value) {
            Log.d(TAG, "No network available — delaying reconnect scheduling")
            _connectionStatus.value = ConnectionStatus.NO_NETWORK
            return
        }
        val delay = currentBackoff
        currentBackoff =
            (currentBackoff * BACKOFF_MULTIPLIER)
                .toLong()
                .coerceAtMost(MAX_BACKOFF_MS)
        if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${delay}ms …")

        reconnectJob?.cancel()
        reconnectJob =
            wsScope.launch {
                delay(delay)
                if (!intentionalClose.get() && !connected.get()) {
                    openSocket()
                }
            }
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            Log.i(TAG, "WebSocket opened")
            connected.set(true)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            currentBackoff = INITIAL_BACKOFF_MS
            startHealthTracking()

            while (messageQueue.isNotEmpty()) {
                val msg = messageQueue.poll()
                if (msg != null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "→ (queued) $msg")
                    webSocket.send(msg)
                }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (BuildConfig.DEBUG) Log.d(TAG, "← $text")
            lastPongTimestamp = System.currentTimeMillis()
            val emitted = rawMessages.tryEmit(text)
            if (!emitted && BuildConfig.DEBUG) {
                Log.w(TAG, "WebSocket message dropped due to buffer overflow")
            }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            connected.set(false)
            stopHealthTracking()
            if (code == 4001 || reason.contains("unauthorized", ignoreCase = true)) {
                _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
            } else {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                scheduleReconnect()
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            connected.set(false)
            stopHealthTracking()
            val code = response?.code ?: 0
            if (code == 401 || t.message?.contains(
                    "401",
                ) == true || t.message?.contains("unauthorized", ignoreCase = true) == true
            ) {
                _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
            } else {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                scheduleReconnect()
            }
        }
    }
}
