package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Socket-independent JSON-RPC 2.0 request correlation and response dispatcher.
 * Manages request IDs, in-flight pending calls, timeouts, cancellation cleanup,
 * and server response frame formatting.
 */
internal class RpcChannel(
    private val scope: CoroutineScope,
    private val json: Json,
    private val sendRequest: (method: String, params: Map<String, Any>, onSent: ((String) -> Unit)?) -> String,
    private val sendFrame: (String) -> Boolean,
    private val removeQueuedMessage: (String) -> Unit,
    private val onIdleCheck: () -> Unit,
    private val logRejected: (method: String, id: String) -> Unit = { _, _ -> },
    private val outboundLock: Any = Any(),
    private val onResult: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val resolveStoredSessionId: (String?) -> String? = { it },
    private val logParseFailure: (Exception) -> Unit = {},
    private val decorateParams: (String, Map<String, Any>) -> Map<String, Any> = { _, p -> p },
) {
    /** A single in-flight [request] awaiting its RPC result/error. */
    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any?>,
        var timeoutJob: Job? = null,
        val suppressErrorEvent: Boolean = false,
    )

    private val requestId = AtomicInteger(0)
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()
    private val capabilityRequestIds = ConcurrentHashMap.newKeySet<String>()

    fun nextId(): String = requestId.incrementAndGet().toString()

    fun hasPendingCalls(): Boolean = pendingCalls.isNotEmpty()

    fun pendingCallIdsForTest(): Set<String> = pendingCalls.keys.toSet()

    fun pendingCallTimeoutForTest(id: String): Job? = pendingCalls[id]?.timeoutJob

    fun isErrorEventSuppressed(id: String): Boolean = pendingCalls[id]?.suppressErrorEvent == true

    fun registerCapabilityRequest(id: String) {
        capabilityRequestIds.add(id)
    }

    fun clearCapabilityRequests() {
        capabilityRequestIds.clear()
    }

    fun consumeCapabilityResponse(event: WsEvent): Boolean {
        val id =
            when (event) {
                is WsEvent.RpcResult -> event.id
                is WsEvent.RpcError -> event.id
                else -> return false
            }
        val isCapabilityResponse =
            synchronized(outboundLock) {
                capabilityRequestIds.remove(id)
            }
        if (!isCapabilityResponse) return false

        removeQueuedMessage(id)
        return true
    }

    fun createRequest(
        method: String,
        params: Map<String, Any>,
    ): JsonRpcRequest {
        val id = nextId()
        val decorated = decorateParams(method, params)
        return JsonRpcRequest(
            id = id,
            method = method,
            params = decorated.mapValues { it.value.toJsonElement() },
        )
    }

    fun encodeRequest(request: JsonRpcRequest): String = json.encodeToString(request)

    /**
     * Send a JSON-RPC request that expects a result and returns a [CompletableDeferred] for it.
     */
    fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = HermesWsClient.REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): CompletableDeferred<Any?> {
        val deferred = CompletableDeferred<Any?>()
        val id =
            sendRequest(method, params) { reqId ->
                pendingCalls[reqId] = PendingCall(method, deferred, suppressErrorEvent = suppressErrorEvent)
            }
        deferred.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                pendingCalls.remove(id)?.let { call ->
                    call.timeoutJob?.cancel()
                    removeQueuedMessage(id)
                    onIdleCheck()
                }
            }
        }
        // Arm the per-request timeout (fires if the server never answers).
        pendingCalls[id]?.timeoutJob =
            scope.launch {
                delay(timeoutMs)
                resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
            }
        return deferred
    }

    /**
     * Non-suspending typed twin of [request] for callers that must enqueue inside a
     * non-suspending owner check and await the raw result themselves.
     */
    fun <P, R> requestTyped(
        method: RpcMethod<P, R>,
        params: P,
        timeoutMs: Long = HermesWsClient.REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): CompletableDeferred<Any?> = request(method.name, encodeParams(method, params), timeoutMs, suppressErrorEvent)

    /**
     * Send a typed JSON-RPC request and await its deserialized result.
     */
    suspend fun <P, R> call(
        method: RpcMethod<P, R>,
        params: P,
        timeoutMs: Long = HermesWsClient.REQUEST_TIMEOUT_MS,
        suppressErrorEvent: Boolean = false,
    ): R = awaitResult(method, requestTyped(method, params, timeoutMs, suppressErrorEvent))

    suspend fun <P, R> awaitResult(
        method: RpcMethod<P, R>,
        deferred: CompletableDeferred<Any?>,
    ): R {
        val result =
            try {
                deferred.await()
            } catch (e: CancellationException) {
                deferred.cancel(e)
                throw e
            }
        val element = result.toJsonElement()
        return json.decodeFromJsonElement(method.result, element)
    }

    fun <P, R> encodeParams(
        method: RpcMethod<P, R>,
        params: P,
    ): JsonObject {
        val encoded = json.encodeToJsonElement(method.params, params)
        require(encoded is JsonObject) {
            "RPC params for ${method.name} must serialize to a JsonObject, got ${encoded::class.simpleName}"
        }
        return encoded
    }

    /** Complete (or fail) a single pending call and cancel its timer. */
    fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
    ) {
        val call = pendingCalls.remove(id) ?: return
        removeQueuedMessage(id)
        call.timeoutJob?.cancel()
        if (error != null) {
            call.deferred.completeExceptionally(
                HermesWsClient.HermesRpcException(
                    message = error.message,
                    code = error.code,
                    data = error.data,
                ),
            )
        } else {
            call.deferred.complete(result)
        }
        onIdleCheck()
    }

    /**
     * Fail and clear every in-flight [request].
     */
    fun rejectAllPending(
        error: HermesWsClient.HermesRpcException =
            HermesWsClient.HermesRpcException("Connection lost — request cancelled"),
    ) {
        if (pendingCalls.isEmpty()) return
        val snapshot = pendingCalls.toList()
        pendingCalls.clear()
        for ((id, call) in snapshot) {
            removeQueuedMessage(id)
            logRejected(call.method, id)
            call.timeoutJob?.cancel()
            call.deferred.completeExceptionally(error)
        }
    }

    /**
     * Answer a gateway-originated JSON-RPC request.
     */
    fun respondToServerRequest(
        id: String,
        result: JsonElement,
    ): Boolean {
        if (id.isBlank()) return false
        val frameJson =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()
        return sendFrame(frameJson)
    }

    /** Answer a gateway-originated request with a standard JSON-RPC error. */
    fun respondToServerRequestError(
        id: String,
        code: Int,
        message: String,
    ): Boolean {
        if (id.isBlank()) return false
        val frameJson =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }.toString()
        return sendFrame(frameJson)
    }

    /**
     * Handles an incoming WebSocket text frame: JSON decoding, request correlation,
     * server request dispatch, sequence deduplication via [replayTracker], and event emission.
     */
    fun handleIncomingFrame(
        text: String,
        replayTracker: ReplayTracker,
        emitEvent: (WsEvent) -> Unit,
    ) {
        val event =
            try {
                val rpc = json.decodeFromString<JsonRpcResponse>(text)
                // Resolve pending request() calls with the RAW JsonElement from
                // rpc.result, BEFORE EventParser.parse() converts it via toAny().
                // This preserves integer/float type fidelity needed by callers
                // that re-deserialize the result into typed data classes.
                val rpcId = rpc.id
                if (rpcId != null && rpc.error == null && rpc.result != null) {
                    onResult(rpcId)
                    removeQueuedMessage(rpcId)
                    resolvePending(rpcId, rpc.result, null)
                }

                // Resume/replay responses carry still-open server requests
                // separately from the event ring. Re-deliver them through the
                // same generic path before exposing the RPC result.
                @Suppress("UNCHECKED_CAST")
                val openRequests =
                    (rpc.result?.toAny() as? Map<*, *>)?.get("open_requests") as? List<Map<*, *>>
                openRequests?.forEach { openRequest ->
                    val openId = openRequest["id"] as? String ?: return@forEach
                    val method = openRequest["method"] as? String ?: return@forEach

                    @Suppress("UNCHECKED_CAST")
                    val params = openRequest["params"] as? Map<String, Any?> ?: emptyMap()
                    emitEvent(WsEvent.ServerRequest(openId, method, params, replayed = true))
                }

                if (rpc.id == null) {
                    // Issue #1163: inspect scalars, not a throwaway copy of the whole params tree.
                    val sid = rpc.params?.eventSessionId()
                    val seq = ((rpc.params?.get("seq") as? JsonPrimitive)?.toAny() as? Number)?.toInt()

                    if (!sid.isNullOrBlank() && seq != null) {
                        replayTracker.acceptLiveEvent(sid, seq) {
                            EventParser.parse(rpc, text)
                        }
                        return
                    }
                }

                val parsed = EventParser.parse(rpc, text)
                if (parsed is WsEvent.MessageComplete) {
                    parsed.copy(
                        storedSessionId = resolveStoredSessionId(parsed.sessionId),
                    )
                } else {
                    parsed
                }
            } catch (e: Exception) {
                logParseFailure(e)
                WsEvent.Unknown(text)
            }
        if (consumeCapabilityResponse(event)) return
        when (event) {
            is WsEvent.RpcResult -> {
                onResult(event.id)
                removeQueuedMessage(event.id)
                resolvePending(event.id, event.result, null)
            }

            is WsEvent.RpcError -> {
                onError(event.id)
                if (pendingCalls[event.id]?.suppressErrorEvent == true) {
                    // Opt-in suppression: the caller already handles the
                    // failure through the CompletableDeferred, so the event
                    // copy would only surface a duplicate UI banner for
                    // optional features (e.g. "subagent.list" on gateways
                    // without the method, issue #1089). Default request()
                    // behavior still emits the event for shared consumers.
                    resolvePending(event.id, null, event.error)
                    return
                }
                removeQueuedMessage(event.id)
                resolvePending(event.id, null, event.error)
            }

            else -> {}
        }
        emitEvent(event)
    }
}
