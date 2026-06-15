package com.m57.hermescontrol.data.ws

import android.util.Log
import com.google.gson.Gson
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    // ── Callbacks (legacy / convenience) ─────────────────────────────────

    var onMessage: ((WsEvent) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String?) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // ── Connection helpers ────────────────────────────────────────────────

    val isConnected: Boolean get() = connected.get()

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "Already connected — skipping")
            return
        }
        intentionalClose.set(false)
        currentBackoff = INITIAL_BACKOFF_MS
        openSocket()
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect() {
        intentionalClose.set(true)
        webSocket?.close(1000, "Client closed")
        webSocket = null
        connected.set(false)
    }

    // ── Send helpers ─────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request with the given [method] and optional [params].
     * @return the request id used (can be matched against [WsEvent.RpcResult]).
     */
    fun send(
        method: String,
        params: Map<String, Any> = emptyMap(),
    ): String {
        val id = requestId.incrementAndGet().toString()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val json = gson.toJson(request)
        Log.d(TAG, "→ $json")
        webSocket?.send(json) ?: Log.w(TAG, "send() called while disconnected")
        return id
    }

    /** Convenience: submit a user prompt to an existing session. */
    fun sendMessage(
        sessionId: String,
        text: String,
    ): String =
        send(
            method = WsMethods.PROMPT_SUBMIT,
            params = mapOf("session_id" to sessionId, "text" to text),
        )

    // ── Internal ─────────────────────────────────────────────────────────

    private fun openSocket() {
        val url = AuthManager.wsUrl()
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, WsListenerImpl())
    }

    private fun scheduleReconnect() {
        if (intentionalClose.get()) return
        if (!AuthManager.isAutoReconnect()) {
            Log.d(TAG, "Auto-reconnect disabled")
            return
        }
        val delay = currentBackoff
        currentBackoff =
            (currentBackoff * BACKOFF_MULTIPLIER)
                .toLong()
                .coerceAtMost(MAX_BACKOFF_MS)
        Log.d(TAG, "Reconnecting in ${delay}ms …")

        // Simple thread-based scheduling; could be replaced with coroutine delay.
        Thread {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!intentionalClose.get() && !connected.get()) {
                openSocket()
            }
        }.apply {
            isDaemon = true
            name = "hermes-ws-reconnect"
            start()
        }
    }

    private fun emit(event: WsEvent) {
        _events.tryEmit(event)
        onMessage?.invoke(event)
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl : WebSocketListener() {
        override fun onOpen(
            ws: WebSocket,
            response: Response,
        ) {
            Log.i(TAG, "WebSocket opened")
            connected.set(true)
            currentBackoff = INITIAL_BACKOFF_MS
            onConnected?.invoke()
        }

        override fun onMessage(
            ws: WebSocket,
            text: String,
        ) {
            Log.d(TAG, "← $text")
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
            ws.close(1000, null)
        }

        override fun onClosed(
            ws: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            connected.set(false)
            onDisconnected?.invoke(reason)
            scheduleReconnect()
        }

        override fun onFailure(
            ws: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            connected.set(false)
            onError?.invoke(t)
            onDisconnected?.invoke(t.message)
            scheduleReconnect()
        }
    }
}
