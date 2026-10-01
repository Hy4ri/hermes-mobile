package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.contract.ClientCapabilitiesParams
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Connection status for the WebSocket client. */
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
 * Facade coordinating transport, RPC correlation, replay tracking, and connection health.
 */
object HermesWsClient {
    private const val TAG = "HermesWsClient"

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L

    private val outboundLock = Any()
    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reconnectPolicy = ReconnectPolicy()

    private val parsedEvents =
        MutableSharedFlow<WsEvent>(
            extraBufferCapacity = 2048,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<WsEvent> = parsedEvents.asSharedFlow()

    private val connectionHealth =
        ConnectionHealth(
            scope = wsScope,
            isConnected = { transport.isConnected },
            pingRequest = { timeoutMs ->
                request(WsMethods.GATEWAY_PING, emptyMap(), timeoutMs = timeoutMs).await()
                Unit
            },
            cancelSocket = { transport.cancelSocket() },
            pendingReply = { transport.pendingReply },
            nowMs = ::monotonicTimeMs,
            log = { Log.w(TAG, it) },
            debugLog = { Log.d(TAG, it) },
        )

    private val replayTracker =
        ReplayTracker(
            scope = wsScope,
            fetchEventsSince = {
                call(RpcMethods.SESSION_EVENTS_SINCE, it, timeoutMs = 10_000L)
            },
            emitEvent = { parsedEvents.tryEmit(it) },
            resolveStoredSessionId = { ActiveSessionHolder.resolveStoredSessionId(it) },
            onReplayFailure = { sid, e -> Log.w(TAG, "Replay failed for session $sid: ${e.message}") },
        )

    private val rpcChannel: RpcChannel =
        RpcChannel(
            scope = wsScope,
            json = OkHttpProvider.json,
            sendRequest = { method, params, onSent -> send(method, params, onSent) },
            sendFrame = { frameJson -> transport.sendDirectFrame(frameJson) },
            removeQueuedMessage = { transport.removeQueuedMessage(it) },
            onIdleCheck = { transport.disconnectIfIdleInBackground() },
            logRejected = { method, id -> Log.w(TAG, "Rejecting pending request on disconnect: $method (id=$id)") },
            outboundLock = outboundLock,
            onResult = { id -> transport.onRpcResult(id) },
            onError = { id -> transport.onRpcError(id) },
            resolveStoredSessionId = { ActiveSessionHolder.resolveStoredSessionId(it) },
            logParseFailure = { e -> Log.e(TAG, "Failed to parse message: ${e.javaClass.simpleName}") },
            decorateParams = { method, params -> WsProfileParams.decorate(method, params) },
        )

    private val transport: WsTransport =
        WsTransport(
            scope = wsScope,
            outboundLock = outboundLock,
            reconnectPolicy = reconnectPolicy,
            connectionHealth = connectionHealth,
            replayTracker = replayTracker,
            rpcChannel = rpcChannel,
            onRawMessage = { rawFrame ->
                rpcChannel.handleIncomingFrame(rawFrame, replayTracker) { event -> parsedEvents.tryEmit(event) }
            },
        )

    val connectionStatus: StateFlow<ConnectionStatus> = transport.connectionStatus

    var pendingReply: Boolean by transport::pendingReply
        private set

    var lastPongTimestamp: Long by connectionHealth::lastPongTimestamp
        private set

    val isHealthy: Boolean get() = connectionHealth.isHealthy
    val lastLatencyMs: StateFlow<Long?> = connectionHealth.lastLatencyMs

    suspend fun ping(timeoutMs: Long = ConnectionHealth.LIVENESS_PROBE_TIMEOUT_MS): Long =
        connectionHealth.ping(timeoutMs)

    private val _credentialWarning = MutableStateFlow<String?>(null)
    val credentialWarning: StateFlow<String?> = _credentialWarning.asStateFlow()

    fun clearCredentialWarning() {
        _credentialWarning.value = null
    }

    init {
        wsScope.launch {
            parsedEvents.collect { transport.observeEvent(it) }
        }
        wsScope.launch {
            events.collect { event ->
                val data: Map<String, Any?>? =
                    when (event) {
                        is WsEvent.GatewayReady -> event.data
                        is WsEvent.SessionInfo -> event.data
                        else -> null
                    }
                val warning = data?.get("credential_warning") as? String
                if (!warning.isNullOrBlank()) _credentialWarning.value = warning
            }
        }
        wsScope.launch {
            events.filterIsInstance<WsEvent.ChangeEvent>().collect { ChangeEventHub.emit(it) }
        }
        wsScope.launch {
            events.collect { event ->
                if (event is WsEvent.GatewayReady) {
                    runCatching {
                        send(
                            RpcMethods.CLIENT_CAPABILITIES.name,
                            rpcChannel.encodeParams(
                                RpcMethods.CLIENT_CAPABILITIES,
                                ClientCapabilitiesParams(serverRequests = true),
                            ),
                            onSent = { id -> rpcChannel.registerCapabilityRequest(id) },
                            queueIfDisconnected = false,
                        )
                    }
                    val epoch = event.data?.get("replay_epoch") as? String
                    replayTracker.updateEpoch(epoch)
                }
            }
        }
    }

    @VisibleForTesting
    val isConnected: Boolean get() = transport.isConnected

    fun setAppForeground(foreground: Boolean) = transport.setAppForeground(foreground)

    fun acquireExternalActivityConnectionLease() = transport.acquireExternalActivityConnectionLease()

    fun releaseExternalActivityConnectionLease() = transport.releaseExternalActivityConnectionLease()

    fun acquireBackgroundConnectionLease() = transport.acquireBackgroundConnectionLease()

    fun releaseBackgroundConnectionLease() = transport.releaseBackgroundConnectionLease()

    @VisibleForTesting
    internal fun hasBackgroundConnectionLease(): Boolean = transport.hasBackgroundConnectionLease()

    private fun openSocket() = transport.openSocket()

    fun connect() = transport.connect()

    fun reconnectForNetworkChange(
        networkAvailable: Boolean = com.m57.hermescontrol.data.remote.NetworkMonitor.isConnected.value,
        openReplacement: () -> Unit = ::openSocket,
    ) = transport.reconnectForNetworkChange(networkAvailable, openReplacement)

    internal suspend fun refreshWsTicketIfNeeded(generation: Int): Boolean =
        transport.refreshWsTicketIfNeeded(generation)

    internal suspend fun mintWsTicket(): String? = transport.mintWsTicket()

    fun disconnect(clearPendingMessages: Boolean = false) = transport.disconnect(clearPendingMessages)

    class HermesRpcException(
        message: String,
        val code: Int = 0,
        val data: JsonElement? = null,
    ) : Exception(message)

    const val REQUEST_TIMEOUT_MS: Long = 120_000L

    internal fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): CompletableDeferred<Any?> = rpcChannel.request(method, params, timeoutMs, suppressErrorEvent)

    fun <P, R> requestTyped(
        method: RpcMethod<P, R>,
        params: P,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): CompletableDeferred<Any?> =
        request(method.name, rpcChannel.encodeParams(method, params), timeoutMs, suppressErrorEvent)

    suspend fun <P, R> call(
        method: RpcMethod<P, R>,
        params: P,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): R = rpcChannel.awaitResult(method, requestTyped(method, params, timeoutMs, suppressErrorEvent))

    fun rejectAllPending(
        error: HermesRpcException =
            HermesRpcException("Connection lost — request cancelled"),
    ) = rpcChannel.rejectAllPending(error)

    fun respondToServerRequest(
        id: String,
        result: JsonElement,
    ): Boolean = rpcChannel.respondToServerRequest(id, result)

    fun respondToServerRequestError(
        id: String,
        code: Int,
        message: String,
    ): Boolean = rpcChannel.respondToServerRequestError(id, code, message)

    internal fun send(
        method: String,
        params: Map<String, Any> = emptyMap(),
        onSent: ((String) -> Unit)? = null,
    ): String = send(method, params, onSent, queueIfDisconnected = true)

    fun <P, R> send(
        method: RpcMethod<P, R>,
        params: P,
        onSent: ((String) -> Unit)? = null,
    ): String = send(method.name, rpcChannel.encodeParams(method, params), onSent)

    private fun send(
        method: String,
        params: Map<String, Any>,
        onSent: ((String) -> Unit)?,
        queueIfDisconnected: Boolean,
    ): String =
        transport.send(
            rpcChannel.createRequest(method, params),
            onSent = onSent,
            queueIfDisconnected = queueIfDisconnected,
        )

    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
        queued: Boolean = false,
    ): String = transport.sendMessage(sessionId, text, onSent, queued)

    fun sendRedirect(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String = transport.sendRedirect(sessionId, text, onSent)

    @VisibleForTesting
    internal fun getSeqWatermarks(): Map<String, Int> = replayTracker.getSeqWatermarks()

    @VisibleForTesting
    internal fun setSeqWatermark(
        sessionId: String,
        seq: Int,
    ) = replayTracker.setSeqWatermark(sessionId, seq)

    @VisibleForTesting
    internal fun clearSeqWatermarks() = replayTracker.clearSeqWatermarks()

    @VisibleForTesting
    internal fun isReplayInFlight(): Boolean = replayTracker.isReplayInFlight()

    @VisibleForTesting
    internal fun setReplayEpochForTest(epoch: String?) = replayTracker.setReplayEpochForTest(epoch)

    @VisibleForTesting
    internal suspend fun fetchReplayForTest() = replayTracker.fetchReplayForTest()

    @VisibleForTesting
    internal fun probeLivenessOnTransportChange(
        timeoutMs: Long = ConnectionHealth.LIVENESS_PROBE_TIMEOUT_MS,
        onFailureAction: () -> Unit = { transport.cancelSocket() },
    ) = connectionHealth.probeLivenessOnTransportChange(timeoutMs, onFailureAction)

    @VisibleForTesting
    internal fun setLastLatencyForTest(latency: Long?) = connectionHealth.setLastLatencyForTest(latency)

    @VisibleForTesting
    internal fun forceHealthCheckForTest(staleMillis: Long) = connectionHealth.forceHealthCheckForTest(staleMillis)

    @VisibleForTesting
    internal fun setReconnectBackoffForTest(initialMillis: Long) = transport.setReconnectBackoffForTest(initialMillis)

    @VisibleForTesting
    internal fun setConnectionStatusForTest(status: ConnectionStatus) = transport.setConnectionStatusForTest(status)

    @VisibleForTesting
    internal val connectedForTest: AtomicBoolean
        get() = transport.connectedForTest

    @VisibleForTesting
    internal val intentionalCloseForTest: AtomicBoolean
        get() = transport.intentionalCloseForTest

    @VisibleForTesting
    internal val connectionGenerationForTest: AtomicInteger
        get() = transport.connectionGenerationForTest

    @VisibleForTesting
    internal val messageQueueForTest: ConcurrentLinkedQueue<String>
        get() = transport.messageQueueForTest

    @VisibleForTesting
    internal val outboundLockForTest: Any
        get() = transport.outboundLockForTest

    @VisibleForTesting
    internal val acceptQueuedMessagesForTest: AtomicBoolean
        get() = transport.acceptQueuedMessagesForTest

    @VisibleForTesting
    internal val connectionStatusForTest: MutableStateFlow<ConnectionStatus>
        get() = transport.connectionStatusForTest

    @VisibleForTesting
    internal var webSocketForTest: WebSocket? by transport::webSocketForTest

    @VisibleForTesting
    internal var currentBackoffForTest: Long by transport::currentBackoffForTest

    @VisibleForTesting
    internal var reconnectJobForTest: Job? by transport::reconnectJobForTest

    @VisibleForTesting
    internal var outboundDrainJobForTest: Job? by transport::outboundDrainJobForTest

    @VisibleForTesting
    internal fun scheduleReconnectForTest() = transport.scheduleReconnectForTest()

    @VisibleForTesting
    internal fun createListenerForTest(generation: Int): WebSocketListener = transport.createListenerForTest(generation)

    @VisibleForTesting
    internal fun pendingCallIdsForTest(): Set<String> = rpcChannel.pendingCallIdsForTest()

    @VisibleForTesting
    internal fun pendingCallTimeoutForTest(id: String): Job? = rpcChannel.pendingCallTimeoutForTest(id)

    @VisibleForTesting
    internal fun formatSafeOutgoingFrameLog(
        id: String,
        method: String,
        paramsKeys: Collection<String>?,
        byteLength: Int,
        queued: Boolean = false,
    ): String = transport.formatSafeOutgoingFrameLog(id, method, paramsKeys, byteLength, queued)

    @VisibleForTesting
    internal fun formatSafeQueuedFrameLog(msg: String): String = transport.formatSafeQueuedFrameLog(msg)

    @VisibleForTesting
    internal fun formatSafeIncomingFrameLog(text: String): String = transport.formatSafeIncomingFrameLog(text)
}
