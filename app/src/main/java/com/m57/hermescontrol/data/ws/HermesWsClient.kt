package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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

    private var initialBackoffMs = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val BACKOFF_MULTIPLIER = 2.0

    /**
     * Test hook: the initial reconnect backoff, overridable so reconnect
     * tests are deterministic instead of racing real time (CI runners
     * routinely exceed fixed latch windows). Production keeps 1s.
     */
    @VisibleForTesting
    internal fun setReconnectBackoffForTest(initialMillis: Long) {
        initialBackoffMs = initialMillis
        currentBackoff = initialMillis
    }

    private const val MAX_OUTBOUND_MESSAGE_BYTES = 16 * 1024 * 1024
    private const val OUTBOUND_DRAIN_TIMEOUT_MS = 60_000L

    /**
     * No inbound frame for this long means the link is dead even though the
     * TCP session may still look open (silent NAT/VPN drop). OkHttp pings
     * every 30s (OkHttpProvider.websocket) and any inbound frame refreshes
     * [lastPongTimestamp], so a healthy link never exceeds ~30s of silence.
     * 90s tolerates exactly one missed ping cycle before acting.
     */
    private const val STALE_THRESHOLD_MS = 90_000L

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connectionGeneration = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val acceptQueuedMessages = AtomicBoolean(true)
    private val appInForeground = AtomicBoolean(true)
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private val queuedMessagesById = ConcurrentHashMap<String, String>()
    private val outboundLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closingSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = initialBackoffMs

    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Started lazily on the first [connect] instead of in [init]: the
     * reconnect-on-network-change subscription only matters once a socket
     * has actually been opened. init-time subscription also leaks a live
     * real-IO collector into the unit-test JVM (class load), where
     * NetworkMonitor emissions crashed it after unmockkAll.
     */
    private val networkCollectorStarted = AtomicBoolean(false)

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
                    if (!runHealthCheckPass()) break
                }
            }
    }

    /**
     * One liveness pass. Returns false when the tracking loop should stop:
     * either the connection is already gone or the watchdog fired a cancel.
     *
     * Cancelling (not close()) forces an immediate onFailure, which rides the
     * existing teardown path: generation bump, RECONNECTING, scheduleReconnect.
     */
    private fun runHealthCheckPass(): Boolean {
        if (!connected.get()) return false
        val staleMs = System.currentTimeMillis() - lastPongTimestamp
        if (staleMs <= STALE_THRESHOLD_MS) return true
        Log.w(TAG, "WebSocket stale (${staleMs / 1000}s without frames) — cancelling to trigger reconnect")
        synchronized(outboundLock) {
            if (!connected.get()) return false
            webSocket?.cancel()
        }
        return false
    }

    /**
     * Test hook: backdate liveness past the staleness threshold and force one
     * synchronous watchdog pass, so reconnect tests are deterministic instead
     * of racing the real 30s/90s cadence.
     */
    @VisibleForTesting
    internal fun forceHealthCheckForTest(staleMillis: Long) {
        lastPongTimestamp = System.currentTimeMillis() - staleMillis
        runHealthCheckPass()
    }

    private fun stopHealthTracking() {
        healthJob?.cancel()
        healthJob = null
    }

    // ── Public observable stream ─────────────────────────────────────────

    private val parsedEvents =
        MutableSharedFlow<WsEvent>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Collect this from ViewModels to receive all parsed [WsEvent]s. */
    val events: SharedFlow<WsEvent> = parsedEvents.asSharedFlow()

    // ── Connection status flow ──────────────────────────────────────────
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Observable connection status */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // ── Reply-pending tracking ─────────────────────────────────────────
    // True while at least one agent turn is in flight. Drives the chat
    // notification foreground service: it only runs while a reply is
    // actually pending, instead of for the whole time the app is
    // backgrounded (issue #794).
    @Volatile
    var pendingReply: Boolean = false
        private set

    private val pendingPromptSubmits = ConcurrentHashMap.newKeySet<String>()

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
        // Track whether a reply is actually in flight so the chat notification
        // foreground service only runs while the user is waiting for one
        // (issue #794), instead of for the whole time the app is backgrounded.
        // Also covers turns started from other devices on the same session.
        wsScope.launch {
            events.collect { event ->
                when (event) {
                    is WsEvent.MessageStart,
                    is WsEvent.MessageToken,
                    is WsEvent.ThinkingDelta,
                    is WsEvent.ReasoningDelta,
                    is WsEvent.ToolStart,
                    -> pendingReply = true

                    is WsEvent.MessageComplete -> {
                        pendingReply = false
                        disconnectIfIdleInBackground()
                    }
                    else -> {}
                }
            }
        }
        // Forward parsed change events (issue #784) to the hub so screens can
        // refresh on change without touching this singleton — its real static
        // init must never run inside unit tests (see ChangeEventHub).
        wsScope.launch {
            events
                .filterIsInstance<WsEvent.ChangeEvent>()
                .collect { ChangeEventHub.emit(it) }
        }
    }

    // ── Connection helpers ────────────────────────────────────────────────

    @VisibleForTesting
    val isConnected: Boolean get() = connected.get()

    fun setAppForeground(foreground: Boolean) {
        appInForeground.set(foreground)
        if (!foreground) disconnectIfIdleInBackground()
    }

    private fun disconnectIfIdleInBackground() {
        synchronized(outboundLock) {
            if (!appInForeground.get() &&
                !pendingReply &&
                pendingCalls.isEmpty() &&
                messageQueue.isEmpty()
            ) {
                disconnect()
            }
        }
    }

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        if (networkCollectorStarted.compareAndSet(false, true)) {
            wsScope.launch {
                NetworkMonitor.networkChanges.collect { networkAvailable ->
                    reconnectForNetworkChange(networkAvailable)
                }
            }
        }
        var socketToCancel: WebSocket? = null
        val shouldOpen =
            synchronized(outboundLock) {
                acceptQueuedMessages.set(true)
                if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                    connectionGeneration.incrementAndGet()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    outboundDrainJob?.cancel()
                    outboundDrainJob = null
                    socketToCancel = webSocket
                    webSocket = null
                    closingSocket = null
                    connected.set(false)
                    stopHealthTracking()
                }
                if (connected.get()) {
                    Log.d(TAG, "Already connected — skipping")
                    return@synchronized false
                }
                if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                    _connectionStatus.value == ConnectionStatus.RECONNECTING
                ) {
                    Log.d(TAG, "Connection already in flight (${_connectionStatus.value}) — skipping")
                    return@synchronized false
                }
                // An explicit connect follows successful authentication and is
                // therefore allowed to leave the terminal AUTH_EXPIRED state.
                intentionalClose.set(false)
                _connectionStatus.value = ConnectionStatus.CONNECTING
                currentBackoff = initialBackoffMs
                true
            }
        if (socketToCancel != null) {
            rejectAllPending()
            socketToCancel.cancel()
        }
        if (shouldOpen) openSocket()
    }

    @VisibleForTesting
    internal fun reconnectForNetworkChange(
        networkAvailable: Boolean = NetworkMonitor.isConnected.value,
        openReplacement: () -> Unit = ::openSocket,
    ) {
        val socketToCancel: WebSocket?
        val shouldOpen: Boolean
        synchronized(outboundLock) {
            if (intentionalClose.get() || _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                return
            }
            Log.d(TAG, "Default network changed — reconnecting WebSocket")
            currentBackoff = initialBackoffMs
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            connectionGeneration.incrementAndGet()
            socketToCancel = webSocket
            webSocket = null
            closingSocket = null
            connected.set(false)
            stopHealthTracking()
            shouldOpen = networkAvailable && AuthManager.isAutoReconnect()
            _connectionStatus.value =
                when {
                    !networkAvailable -> ConnectionStatus.NO_NETWORK
                    shouldOpen -> ConnectionStatus.RECONNECTING
                    else -> ConnectionStatus.DISCONNECTED
                }
            rejectAllPending()
        }
        socketToCancel?.cancel()
        if (shouldOpen) openReplacement()
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
    internal fun refreshWsTicketIfNeeded(generation: Int): Boolean {
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
            val token =
                synchronized(DashboardSessionTokenRefresher) {
                    runCatching {
                        DashboardSessionTokenRefresher.fetch(AuthManager.baseUrl(), OkHttpProvider.probe)
                    }.getOrNull()
                }
            synchronized(outboundLock) {
                if (isCurrentGeneration(generation) && token != null) {
                    runCatching { AuthManager.setToken(token) }
                }
            }
            return true
        }

        val ticketResult = requestWsTicket()
        if (!ticketResult.ticket.isNullOrBlank()) {
            synchronized(outboundLock) {
                if (!isCurrentGeneration(generation)) return false
                AuthManager.setToken(ticketResult.ticket)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
            return true
        }
        val status =
            when {
                ticketResult.exception -> ConnectionStatus.RECONNECTING
                ticketResult.httpCode == 401 || ticketResult.httpCode == 403 -> ConnectionStatus.AUTH_EXPIRED
                ticketResult.httpCode == 408 || ticketResult.httpCode == 429 -> ConnectionStatus.RECONNECTING
                ticketResult.httpCode != null && ticketResult.httpCode >= 500 -> ConnectionStatus.RECONNECTING
                else -> ConnectionStatus.DISCONNECTED
            }
        return handleWsTicketRefreshFailure(status, generation)
    }

    /**
     * Mint a fresh WS ticket for a *secondary* consumer (e.g. the kanban
     * events stream) WITHOUT touching the shared token slot.
     *
     * The shared slot is a single-use ticket that the chat WebSocket consumes
     * at handshake. If two clients mint and stash into the same slot on their
     * own schedules, each can grab the other's just-consumed ticket and the
     * pair starves each other into a permanent reconnect loop. Secondary
     * consumers must use their own ticket per connection.
     *
     * Gated mode: cookie-auth'd POST to /api/auth/ws-ticket, returns the
     * ticket. Loopback mode: refresh the dashboard token and return it.
     * Returns null when a ticket could not be obtained.
     */
    internal fun mintWsTicket(): String? {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                false
            }
        if (!isGated) {
            DashboardSessionTokenRefresher.refresh()
            return AuthManager.getToken()
        }
        return requestWsTicket().ticket
    }

    private data class TicketRequestResult(
        val ticket: String?,
        val httpCode: Int?,
        val exception: Boolean = false,
    )

    /** POST /api/auth/ws-ticket (cookie-auth'd via the shared CookieJar) and parse the ticket. */
    private fun requestWsTicket(): TicketRequestResult {
        try {
            val client = OkHttpProvider.probe
            val request =
                Request
                    .Builder()
                    .url(AuthManager.endpointForBuild().resolve("api/auth/ws-ticket").toString())
                    .post("{}".toRequestBody())
                    .build()

            // Run the ENTIRE call on Dispatchers.IO — execute() already hops,
            // but ResponseBody.string() reads the socket on the CALLING
            // thread. When the caller is main (kanban events connect), that
            // read throws NetworkOnMainThreadException and the mint fails.
            val (code, body) =
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    if (CookieManager.isInitialized()) {
                        CookieManager.useStore(CookieManager.cookieJar.currentServer())
                    }
                    client.newCall(request).execute().use { resp ->
                        resp.code to resp.body.string()
                    }
                }

            if (code in 200..299) {
                // Real JSON decode — the ticket is base64url and may contain
                // characters a naive [^"]+ regex cannot extract (see
                // testGatedMode_parsesEscapedTicketFromRealJsonShape).
                val parsed =
                    runCatching {
                        OkHttpProvider.json.decodeFromString<WsTicketResponse>(body)
                    }.getOrNull()
                val ticket = parsed?.ticket
                if (!ticket.isNullOrBlank()) {
                    return TicketRequestResult(ticket, null)
                }
                Log.w(TAG, "WS ticket mint failed: unparseable ticket response")
            } else {
                Log.w(TAG, "WS ticket mint failed: HTTP $code")
                return TicketRequestResult(null, code)
            }
        } catch (e: Exception) {
            // Include the stack: the exception class alone (e.g.
            // NetworkOnMainThreadException) can't show WHICH caller on what
            // thread tripped the mint (main-scope kanban events connect vs
            // the WS reconnect path).
            Log.w(TAG, "WS ticket mint failed: ${e.javaClass.simpleName}", e)
            return TicketRequestResult(null, null, exception = true)
        }
        return TicketRequestResult(null, null)
    }

    private fun handleWsTicketRefreshFailure(
        status: ConnectionStatus,
        generation: Int,
    ): Boolean {
        synchronized(outboundLock) {
            if (!isCurrentGeneration(generation)) return false
            _connectionStatus.value = status
            if (status == ConnectionStatus.RECONNECTING) scheduleReconnect()
        }
        return false
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect(clearPendingMessages: Boolean = false) {
        ActiveSessionHolder.clear()
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
                pendingPromptSubmits.clear()
                pendingReply = false
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
        disconnectIfIdleInBackground()
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
        val decoratedParams = WsProfileParams.decorate(method, params)
        val request =
            JsonRpcRequest(
                id = id,
                method = method,
                params = decoratedParams.mapValues { it.value.toJsonElement() },
            )
        val json = OkHttpProvider.json.encodeToString(request)
        if (BuildConfig.DEBUG) Log.d(TAG, "→ $json")
        var reconnect = false
        synchronized(outboundLock) {
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSubmits.add(id)
                pendingReply = true
            }
            onSent?.invoke(id)
            val ws = webSocket
            if (ws != null && connected.get()) {
                // Never replay send(true): the server may have executed it even if its response is lost.
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
                    reconnect = true
                } else {
                    Log.w(TAG, "WS disconnected with oversized outgoing message — not queueing")
                }
            }
        }
        if (reconnect) connect()
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
        if (queuedMessagesById.putIfAbsent(id, json) == null) messageQueue.add(json)
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
        queued: Boolean = false,
    ): String {
        val params =
            buildMap {
                put("session_id", sessionId)
                put("text", text)
                // Explicit queue semantics: the gateway's busy-input policy
                // forces "run after, never interrupt" when prompt.submit
                // carries queued=true (hermes-agent methods_prompt.py
                // _handle_busy_submit) — used by /queue.
                if (queued) put("queued", true)
            }
        return send(
            method = WsMethods.PROMPT_SUBMIT,
            params = params,
            onSent = onSent,
        )
    }

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
        val generation =
            synchronized(outboundLock) {
                if (intentionalClose.get()) return
                connectionGeneration.incrementAndGet()
            }
        if (!refreshWsTicketIfNeeded(generation)) {
            Log.w(TAG, "Aborting openSocket: WS ticket refresh failed")
            return
        }
        if (!isCurrentGeneration(generation)) return
        val url = AuthManager.wsUrl()
        val safeUrl = url.replace(Regex("token=[^&]+"), "token=REDACTED")
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

        val request = Request.Builder().url(url).build()
        val newSocket = OkHttpProvider.websocket.newWebSocket(request, WsListenerImpl(generation))
        synchronized(outboundLock) {
            if (connectionGeneration.get() == generation && !intentionalClose.get()) {
                webSocket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    private fun isCurrentGeneration(generation: Int): Boolean =
        connectionGeneration.get() == generation && !intentionalClose.get()

    private fun scheduleReconnect() {
        synchronized(outboundLock) {
            if (intentionalClose.get() ||
                _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED ||
                reconnectJob?.isActive == true
            ) {
                return
            }
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
                                !intentionalClose.get() &&
                                    !connected.get() &&
                                    _connectionStatus.value != ConnectionStatus.AUTH_EXPIRED
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
        private fun isCurrent(): Boolean = isCurrentGeneration(generation)

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
                currentBackoff = initialBackoffMs
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
            disconnectIfIdleInBackground()
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
                    val parsed = EventParser.parse(rpc, text)
                    if (parsed is WsEvent.MessageComplete) {
                        parsed.copy(
                            storedSessionId = ActiveSessionHolder.resolveStoredSessionId(parsed.sessionId),
                        )
                    } else {
                        parsed
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                    WsEvent.Unknown(text)
                }
            when (event) {
                is WsEvent.RpcResult -> {
                    synchronized(outboundLock) { pendingPromptSubmits.remove(event.id) }
                    removeQueuedMessage(event.id)
                    resolvePending(event.id, event.result, null)
                }

                is WsEvent.RpcError -> {
                    synchronized(outboundLock) {
                        if (pendingPromptSubmits.remove(event.id) &&
                            pendingPromptSubmits.isEmpty()
                        ) {
                            pendingReply = false
                        }
                    }
                    removeQueuedMessage(event.id)
                    resolvePending(event.id, null, event.error)
                    disconnectIfIdleInBackground()
                }

                else -> Unit
            }
            // tryEmit on a DROP_OLDEST flow only returns false when the
            // buffer is full AND no subscriber is draining; with extraBuffer=512
            // and always-on init collectors this is unreachable in practice.
            parsedEvents.tryEmit(event)
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
                ActiveSessionHolder.clear()
                stopHealthTracking()
                if (code == 4001 || code == 4401 ||
                    reason.contains("unauthorized", ignoreCase = true) ||
                    reason.startsWith("auth:", ignoreCase = true)
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
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
                ActiveSessionHolder.clear()
                stopHealthTracking()
                val code = response?.code ?: 0
                if (code == 401 || t.message?.contains(
                        "401",
                    ) == true || t.message?.contains("unauthorized", ignoreCase = true) == true
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }
    }
}
