package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import okio.utf8Size
import java.util.concurrent.ConcurrentHashMap
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
    private const val MAX_OUTBOUND_MESSAGE_BYTES = 16 * 1024 * 1024
    private const val OUTBOUND_DRAIN_TIMEOUT_MS = 60_000L

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connectionGeneration = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val acceptQueuedMessages = AtomicBoolean(true)
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private val queuedMessagesById = ConcurrentHashMap<String, String>()
    private val outboundLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closingSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = INITIAL_BACKOFF_MS

    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var outboundDrainJob: Job? = null

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

    // ── Credential warning (issue #534) ─────────────────────────────────
    // Backend surfaces `credential_warning` in `gateway.ready` / `session.info`
    // WS payloads (desktop `requestDesktopOnboarding`). Mobile has no equivalent
    // at the auth layer, so we extract it here once, globally, and let any
    // screen render a banner that deep-links to ProvidersScreen.
    private val _credentialWarning = MutableStateFlow<String?>(null)

    /** Non-null when the backend reports a credential warning to resolve. */
    val credentialWarning: StateFlow<String?> = _credentialWarning.asStateFlow()

    fun clearCredentialWarning() {
        _credentialWarning.value = null
    }

    init {
        // Monitor network state to trigger immediate reconnect when network is restored
        wsScope.launch {
            NetworkMonitor.isConnected.collect { connected ->
                val shouldOpen =
                    synchronized(outboundLock) {
                        if (connected && !isConnected && !intentionalClose.get() && AuthManager.isAutoReconnect()) {
                            Log.d(TAG, "Network restored — triggering immediate reconnect")
                            currentBackoff = INITIAL_BACKOFF_MS
                            reconnectJob?.cancel()
                            reconnectJob = null
                            true
                        } else {
                            false
                        }
                    }
                if (shouldOpen) {
                    openSocket()
                }
            }
        }
        // Extract credential_warning from gateway.ready / session.info payloads.
        wsScope.launch {
            events.collect { event ->
                val data: Map<String, Any?>? =
                    when (event) {
                        is WsEvent.GatewayReady -> event.data
                        is WsEvent.SessionInfo -> event.data
                        else -> null
                    }
                val warning = data?.get("credential_warning") as? String
                if (!warning.isNullOrBlank()) {
                    _credentialWarning.value = warning
                }
            }
        }
    }

    // ── Connection helpers ────────────────────────────────────────────────

    @VisibleForTesting
    val isConnected: Boolean get() = connected.get()

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        acceptQueuedMessages.set(true)
        if (connected.get()) {
            Log.d(TAG, "Already connected — skipping")
            return
        }
        // Guard against re-entrant connect() while a connection is already in
        // flight. The singleton may be CONNECTING (mid handshake) or RECONNECTING
        // (a scheduled reconnect is pending). Opening a second socket on the same
        // `webSocket` field races the in-flight one and can leave the status
        // stuck on RECONNECTING (e.g. the chat tab calls connect() on every open
        // while the app-level reconnect is already running). Only start a fresh
        // socket from a terminal state.
        if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
            _connectionStatus.value == ConnectionStatus.RECONNECTING
        ) {
            Log.d(TAG, "Connection already in flight (${_connectionStatus.value}) — skipping")
            return
        }
        // AUTH_EXPIRED cannot be resolved by reconnecting alone — caller must
        // re-authenticate. Leave the status as-is so the UI can surface sign-in.
        if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
            Log.d(TAG, "Connection is AUTH_EXPIRED — skipping reconnect; re-auth required")
            return
        }
        intentionalClose.set(false)
        synchronized(outboundLock) {
            currentBackoff = INITIAL_BACKOFF_MS
        }
        _connectionStatus.value = ConnectionStatus.CONNECTING
        openSocket()
    }

    /**
     * If a session cookie is present (gated mode), mint a fresh WS ticket
     * from the dashboard. The ticket is single-use and has a 30-second TTL,
     * so we must mint a new one on every connect (first launch and reconnect).
     *
     * The session cookie is attached automatically by the shared CookieJar on
     * OkHttpProvider.probe (issue #470), so we no longer inject it manually.
     *
     * Returns true if the token is ready (either because we are not in gated mode,
     * or because ticket refresh succeeded). Returns false if we are in gated mode
     * and ticket refresh failed or cannot be performed.
     */
    private fun refreshWsTicketIfNeeded(): Boolean {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                // serverStore not initialized yet (transient early call); treat
                // as loopback so a stale-token refresh still runs. Log so a real
                // misconfiguration isn't silently swallowed.
                Log.w(TAG, "serverStore uninitialized during WS handshake; assuming non-gated")
                false
            }
        if (!isGated) {
            // The loopback dashboard token is regenerated on every server
            // restart. Refresh it before each WebSocket handshake so automatic
            // reconnect does not get stuck in AUTH_EXPIRED with a stale token.
            DashboardSessionTokenRefresher.refresh()
            return true
        }

        try {
            val client = OkHttpProvider.probe
            val request =
                Request
                    .Builder()
                    .url(AuthManager.endpointForBuild().resolve("api/auth/ws-ticket").toString())
                    .post("{}".toRequestBody())
                    .build()

            // Run network call on Dispatchers.IO to avoid NetworkOnMainThreadException
            val response =
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

            response.use {
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val ticketMatch = Regex("""\"ticket\":\"([^\"]+)\"""").find(body)
                    val ticket = ticketMatch?.groupValues?.getOrNull(1)
                    if (!ticket.isNullOrBlank()) {
                        AuthManager.setToken(ticket)
                        if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
                        return true
                    } else {
                        Log.w(TAG, "WS ticket refresh failed: response body did not contain ticket")
                        return handleWsTicketRefreshFailure(ConnectionStatus.DISCONNECTED)
                    }
                } else {
                    Log.w(TAG, "WS ticket refresh failed: HTTP ${response.code}")
                    val status =
                        when {
                            response.code == 401 || response.code == 403 -> ConnectionStatus.AUTH_EXPIRED
                            response.code == 408 || response.code == 429 || response.code >= 500 ->
                                ConnectionStatus.RECONNECTING
                            else -> ConnectionStatus.DISCONNECTED
                        }
                    return handleWsTicketRefreshFailure(status)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS ticket refresh failed: ${e.javaClass.simpleName}")
            return handleWsTicketRefreshFailure(ConnectionStatus.RECONNECTING)
        }
    }

    private fun handleWsTicketRefreshFailure(status: ConnectionStatus): Boolean {
        _connectionStatus.value = status
        if (status == ConnectionStatus.RECONNECTING) scheduleReconnect()
        return false
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect(clearPendingMessages: Boolean = false) {
        synchronized(outboundLock) {
            intentionalClose.set(true)
            acceptQueuedMessages.set(!clearPendingMessages)
            connectionGeneration.incrementAndGet()
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            stopHealthTracking()
            webSocket?.close(1000, "Client closed")
            webSocket = null
            closingSocket = null
            if (clearPendingMessages) {
                messageQueue.clear()
                queuedMessagesById.clear()
            }
            connected.set(false)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    // ── Awaited RPC request layer (issue #526) ─────────────────────────
    // Mirrors desktop apps/shared JsonRpcGatewayClient.request(): an in-flight
    // map with a per-call timeout and rejectAllPending on socket close, so a
    // dropped RPC response can't leave a caller awaiting forever.

    /** Thrown when an awaited [request] is neither answered nor rejected within [REQUEST_TIMEOUT_MS], or is rejected by a disconnect. */
    class HermesRpcException(
        message: String,
    ) : Exception(message)

    /**
     * Default per-request timeout. Matches the desktop
     * `apps/shared` `JsonRpcGatewayClient.DEFAULT_REQUEST_TIMEOUT_MS` (120s),
     * so legitimately long agent turns are not pruned early.
     */
    const val REQUEST_TIMEOUT_MS: Long = 120_000L

    /** A single in-flight [request] awaiting its RPC result/error. */
    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any?>,
        var timeoutJob: Job? = null,
    )

    /** Tracks in-flight [request] calls by their JSON-RPC id. */
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()

    /**
     * Send a JSON-RPC request that expects a result and returns a
     * [CompletableDeferred] for it — mirroring the desktop
     * `JsonRpcGatewayClient.request()`. Fire-and-forget notifications should
     * keep using [send].
     *
     * The deferred is completed on the matching [WsEvent.RpcResult] /
     * [WsEvent.RpcError], rejected after [timeoutMs] (no response), or
     * rejected by [rejectAllPending] when the socket closes.
     */
    fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): CompletableDeferred<Any?> {
        val deferred = CompletableDeferred<Any?>()
        val id =
            send(method, params) { reqId ->
                pendingCalls[reqId] = PendingCall(method, deferred)
            }
        // Arm the per-request timeout (fires if the server never answers).
        pendingCalls[id]?.timeoutJob =
            wsScope.launch {
                delay(timeoutMs)
                resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
            }
        return deferred
    }

    /** Complete (or fail) a single pending call and cancel its timer. */
    private fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
    ) {
        val call = pendingCalls.remove(id) ?: return
        removeQueuedMessage(id)
        call.timeoutJob?.cancel()
        if (error != null) {
            call.deferred.completeExceptionally(HermesRpcException(error.message))
        } else {
            call.deferred.complete(result)
        }
    }

    /**
     * Fail and clear every in-flight [request]. Called on disconnect /
     * reconnect so callers awaiting a result don't hang across a socket
     * close — mirrors desktop `JsonRpcGatewayClient.rejectAllPending(error)`
     * invoked on socket close.
     */
    fun rejectAllPending(
        error: HermesRpcException =
            HermesRpcException("Connection lost — request cancelled"),
    ) {
        if (pendingCalls.isEmpty()) return
        val snapshot = pendingCalls.toList()
        pendingCalls.clear()
        for ((id, call) in snapshot) {
            removeQueuedMessage(id)
            Log.w(TAG, "Rejecting pending request on disconnect: ${call.method} (id=$id)")
            call.timeoutJob?.cancel()
            call.deferred.completeExceptionally(error)
        }
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
        synchronized(outboundLock) {
            val ws = webSocket
            if (ws != null && connected.get()) {
                if (!ws.send(json)) {
                    if (webSocket !== ws || !acceptQueuedMessages.get()) return@synchronized
                    if (isRetryableMessage(json)) {
                        Log.w(TAG, "WS rejected outgoing message — queuing for reconnect")
                        queueMessage(id, json)
                        recoverRejectedSocket(ws)
                    } else {
                        Log.w(TAG, "WS rejected oversized outgoing message — not retrying")
                    }
                }
            } else if (acceptQueuedMessages.get()) {
                if (isRetryableMessage(json)) {
                    Log.d(TAG, "WS disconnected — queuing message")
                    queueMessage(id, json)
                } else {
                    Log.w(TAG, "WS disconnected with oversized outgoing message — not queueing")
                }
            }
        }
        return id
    }

    private fun isRetryableMessage(json: String): Boolean {
        if (json.length > MAX_OUTBOUND_MESSAGE_BYTES) return false
        if (json.length <= MAX_OUTBOUND_MESSAGE_BYTES / 4) return true
        return json.utf8Size() <= MAX_OUTBOUND_MESSAGE_BYTES.toLong()
    }

    private fun queueMessage(
        id: String,
        json: String,
    ) {
        messageQueue.add(json)
        queuedMessagesById[id] = json
    }

    private fun removeQueuedMessage(id: String) {
        synchronized(outboundLock) {
            queuedMessagesById.remove(id)?.let(messageQueue::remove)
        }
    }

    private fun markQueuedMessageSent(json: String) {
        queuedMessagesById.entries.removeIf { it.value == json }
    }

    private fun recoverRejectedSocket(ws: WebSocket) {
        connected.set(false)
        if (closingSocket === ws) return
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        if (ws.queueSize() == 0L) {
            ws.cancel()
            scheduleReconnect()
            return
        }
        if (intentionalClose.get()) return
        outboundDrainJob?.cancel()
        outboundDrainJob =
            wsScope.launch {
                delay(OUTBOUND_DRAIN_TIMEOUT_MS)
                synchronized(outboundLock) {
                    if (!connected.get() && !intentionalClose.get() && webSocket === ws) {
                        ws.cancel()
                        outboundDrainJob = null
                        scheduleReconnect()
                    }
                }
            }
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

    /**
     * Convenience: redirect the active model turn while it is still generating
     * (backend `session.redirect`). Fire-and-forget — the backend either rewrites
     * the live turn, queues the correction as the next turn, or rejects it (the
     * caller's ViewModel handles the `4010` fall-back to [sendMessage]).
     */
    fun sendRedirect(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            method = WsMethods.SESSION_REDIRECT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    // ── Internal ─────────────────────────────────────────────────────────

    private fun openSocket() {
        if (!refreshWsTicketIfNeeded()) {
            Log.w(TAG, "Aborting openSocket: WS ticket refresh failed")
            return
        }
        val url = AuthManager.wsUrl()
        val safeUrl = url.replace(Regex("token=[^&]+"), "token=REDACTED")
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

        val request = Request.Builder().url(url).build()
        val generation = connectionGeneration.incrementAndGet()
        val newSocket = OkHttpProvider.websocket.newWebSocket(request, WsListenerImpl(generation))
        synchronized(outboundLock) {
            if (connectionGeneration.get() == generation && !intentionalClose.get()) {
                webSocket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(outboundLock) {
            if (intentionalClose.get() || reconnectJob?.isActive == true) return
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
            val reconnectDelay = currentBackoff
            currentBackoff =
                (currentBackoff * BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceAtMost(MAX_BACKOFF_MS)
            if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${reconnectDelay}ms …")

            reconnectJob =
                wsScope.launch {
                    delay(reconnectDelay)
                    val owner = currentCoroutineContext()[Job]
                    val shouldOpen =
                        synchronized(outboundLock) {
                            if (reconnectJob !== owner) {
                                false
                            } else {
                                reconnectJob = null
                                !intentionalClose.get() && !connected.get()
                            }
                        }
                    if (shouldOpen) openSocket()
                }
        }
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl(
        private val generation: Int,
    ) : WebSocketListener() {
        private fun isCurrent(): Boolean = connectionGeneration.get() == generation && !intentionalClose.get()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(1000, "Superseded")
                    return
                }
                HermesWsClient.webSocket = webSocket
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                Log.i(TAG, "WebSocket opened")
                connected.set(true)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                currentBackoff = INITIAL_BACKOFF_MS
                startHealthTracking()

                while (true) {
                    val msg = messageQueue.peek() ?: break
                    if (!isRetryableMessage(msg)) {
                        Log.w(TAG, "Dropping oversized queued message")
                        messageQueue.poll()
                        markQueuedMessageSent(msg)
                        continue
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "→ (queued) $msg")
                    if (!webSocket.send(msg)) {
                        recoverRejectedSocket(webSocket)
                        break
                    }
                    messageQueue.poll()
                    markQueuedMessageSent(msg)
                }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (!isCurrent() || HermesWsClient.webSocket !== webSocket) return
            if (BuildConfig.DEBUG) Log.d(TAG, "← $text")
            lastPongTimestamp = System.currentTimeMillis()
            // Resolve any in-flight `request()` awaiting this RPC result/error
            // (issue #526) before fanning the parsed event out to collectors.
            val event =
                try {
                    val rpc = OkHttpProvider.json.decodeFromString<JsonRpcResponse>(text)
                    EventParser.parse(rpc, text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                    WsEvent.Unknown(text)
                }
            when (event) {
                is WsEvent.RpcResult -> resolvePending(event.id, event.result, null)
                is WsEvent.RpcError -> resolvePending(event.id, null, event.error)
                else -> Unit
            }
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
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(code, reason)
                    return
                }
                closingSocket = webSocket
                // Do NOT log [reason] — it may carry server-side context.
                Log.d(TAG, "WebSocket closing: $code")
                if (code == 4001 || code == 4401 ||
                    reason.contains("unauthorized", ignoreCase = true) ||
                    reason.startsWith("auth:", ignoreCase = true)
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                }
                webSocket.close(code, reason)
            }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (HermesWsClient.webSocket === webSocket) HermesWsClient.webSocket = null
                // Do NOT log [reason] — it may carry server-side context. The
                // reason is still inspected internally to detect auth failures.
                Log.i(TAG, "WebSocket closed: $code")
                connected.set(false)
                stopHealthTracking()
                if (code == 4001 || code == 4401 ||
                    reason.contains("unauthorized", ignoreCase = true) ||
                    reason.startsWith("auth:", ignoreCase = true)
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (HermesWsClient.webSocket === webSocket) HermesWsClient.webSocket = null
                // Log the exception class only — [Throwable.message] can leak URLs
                // or headers. The message is still inspected internally for auth
                // detection.
                Log.e(TAG, "WebSocket failure: ${t.javaClass.simpleName}", t)
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
}
