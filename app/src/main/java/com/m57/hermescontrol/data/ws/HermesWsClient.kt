package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
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
import java.util.concurrent.TimeUnit
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

    private val gson = Gson()
    private val requestId = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = INITIAL_BACKOFF_MS

    // B4 (Jun 18 2026, kanban t_2b834d90): dedicated IO coroutine scope for
    // the new single-Job reconnect scheduler below. SupervisorJob keeps one
    // child failure from cancelling the scope; the scope itself lives as
    // long as this singleton (object) exists.
    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var reconnectJob: Job? = null

    private val okHttpClient =
        OkHttpClient
            .Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // keep-alive forever
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

    // ── Public observable stream ─────────────────────────────────────────

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)

    /** Collect this from ViewModels to receive all parsed [WsEvent]s. */
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    // ── Connection status flow ──────────────────────────────────────────
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Observable connection status: DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

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
        openSocket()
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect() {
        intentionalClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
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
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val json = gson.toJson(request)
        // B2 (Jun 18 2026, kanban t_8884db16): outgoing JSON contains user
        // prompts (PromptSubmit params include the `text` field) — never
        // stream to logcat in release builds.
        if (BuildConfig.DEBUG) Log.d(TAG, "→ $json")
        webSocket?.send(json) ?: Log.w(TAG, "send() called while disconnected")
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
        val url = AuthManager.wsUrl()
        // B2 (Jun 18 2026, kanban t_8884db16): url contains the auth token
        // as a query param — never stream to logcat in release builds.
        val safeUrl = url.replace(Regex("token=[^&]+"), "token=REDACTED")
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, WsListenerImpl())
    }

    private fun scheduleReconnect() {
        if (intentionalClose.get()) return
        if (!AuthManager.isAutoReconnect()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Auto-reconnect disabled")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }
        val delay = currentBackoff
        currentBackoff =
            (currentBackoff * BACKOFF_MULTIPLIER)
                .toLong()
                .coerceAtMost(MAX_BACKOFF_MS)
        if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${delay}ms …")

        // B4 (Jun 18 2026, kanban t_2b834d90): replaced raw Thread{ sleep;
        // check; openSocket() } with a single cancellable coroutine Job.
        //
        // Old code had two failure modes:
        //   1. TOCTOU: line 149 `if (!intentionalClose && !connected)` was
        //      not atomic with line 150 `openSocket()`. Two reconnect threads
        //      spawned from a rapid onFailure/onClosed cycle could both pass
        //      the gate and both call openSocket() — the second overwrote
        //      `webSocket` without closing the first, leaking an orphaned
        //      listener.
        //   2. Unbounded thread spawn: a flaky network multiplied the
        //      daemon-thread count. Backoff grew but old threads weren't
        //      cancelled.
        //
        // New behavior: cancel any in-flight reconnect before scheduling the
        // next one — only one reconnect coroutine exists at any time.
        reconnectJob?.cancel()
        reconnectJob =
            wsScope.launch {
                delay(delay)
                if (!intentionalClose.get() && !connected.get()) {
                    openSocket()
                }
            }
    }

    private fun emit(event: WsEvent) {
        _events.tryEmit(event)
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl : WebSocketListener() {
        override fun onOpen(
            ws: WebSocket,
            response: Response,
        ) {
            Log.i(TAG, "WebSocket opened")
            connected.set(true)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            currentBackoff = INITIAL_BACKOFF_MS
        }

        override fun onMessage(
            ws: WebSocket,
            text: String,
        ) {
            // B2 (Jun 18 2026, kanban t_8884db16): incoming WS text contains
            // AI reply tokens and tool output — never stream to logcat in
            // release builds.
            if (BuildConfig.DEBUG) Log.d(TAG, "← $text")
            try {
                val rpc = gson.fromJson(text, JsonRpcResponse::class.java)
                val event = EventParser.parse(rpc, text)
                emit(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
                emit(WsEvent.Unknown(text))
            }
        }

        override fun onClosing(
            ws: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            ws.close(code, reason)
        }

        override fun onClosed(
            ws: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            connected.set(false)
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            scheduleReconnect()
        }

        override fun onFailure(
            ws: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            connected.set(false)
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            scheduleReconnect()
        }
    }
}
