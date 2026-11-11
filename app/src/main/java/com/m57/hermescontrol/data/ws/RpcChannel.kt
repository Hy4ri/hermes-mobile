package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.DESKTOP_SESSION_SOURCE
import com.m57.hermescontrol.data.ws.contract.GUI_CREATE_SOURCE
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
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
    private val createSourceHost: () -> String = { "" },
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

    /** Newest `execution_generation` seen per runtime session id (session-authority route only). */
    private val generations = ConcurrentHashMap<String, Int>()
    private val mutations = SessionMutations()

    /** #1711: hosts that refused `source: "desktop"` on `session.create` and take `"gui"`. */
    private val guiCreateHosts = ConcurrentHashMap.newKeySet<String>()

    /** In-flight `session.create` requests sent with `"desktop"`, by id, with their host. */
    private val desktopCreates = ConcurrentHashMap<String, Pair<String, JsonRpcRequest>>()

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
        declinesNotShown = false
        canonicalClarifyBatch = false
        sessionAuthority = false
    }

    /** Backend advertised (in the `client.capabilities` result) that a 4404 counts as a decline. */
    @Volatile
    var declinesNotShown: Boolean = false
        private set

    /** Backend advertised (in the `client.capabilities` result) that it supports canonical clarify batch. */
    @Volatile
    var canonicalClarifyBatch: Boolean = false
        private set

    /** Legacy routes can advertise clarify.lock support without accepting prompt.submit's clarify_batch. */
    @Volatile
    var sessionAuthority: Boolean = false
        private set

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

        if (event is WsEvent.RpcResult) {
            val result = event.result as? Map<*, *>
            declinesNotShown = result?.get("declines_not_shown") == true
            canonicalClarifyBatch = result?.get("canonical_clarify_batch") == true
        }
        removeQueuedMessage(id)
        return true
    }

    fun createRequest(
        method: String,
        params: Map<String, Any>,
    ): JsonRpcRequest {
        require(!sessionAuthority || method != WsMethods.SESSION_STATUS) {
            "session.status is not available on the session-authority route"
        }
        val id = nextId()
        val decorated = decorateParams(method, params)
        val wire = wireMethod(method, decorated)
        val request =
            JsonRpcRequest(
                id = id,
                method = wire,
                params = decorated.mapValues { it.value.toJsonElement() },
            )
        if (wire == WsMethods.SESSION_MUTATE && method != wire) mutations.track(id, JsonObject(request.params))
        if (method != WsMethods.SESSION_CREATE || request.source() != DESKTOP_SESSION_SOURCE) return request
        val host = createSourceHost()
        if (host in guiCreateHosts) return request.withGuiSource()
        desktopCreates[id] = host to request
        return request
    }

    /** The authority route serves the usage counters as `session.stats`; `session.usage` is not registered there. */
    private fun wireMethod(
        method: String,
        params: Map<String, Any>,
    ): String =
        when {
            !sessionAuthority -> method

            method == WsMethods.SESSION_USAGE -> WsMethods.SESSION_STATS

            // `toAuthorityWire` rewrote the params of an edit whose fences are known; otherwise it stays as sent.
            params["operation"] != null && isMutationMethod(method) -> WsMethods.SESSION_MUTATE

            else -> method
        }

    private fun JsonRpcRequest.source(): String? = (params["source"] as? JsonPrimitive)?.contentOrNull

    private fun JsonRpcRequest.withGuiSource(): JsonRpcRequest =
        copy(params = params + ("source" to JsonPrimitive(GUI_CREATE_SOURCE)))

    /**
     * #1711: the session-authority gateway refuses the stored label `"desktop"` on
     * `session.create` with 4001 `fields: ["source"]`. Send the same request id again with
     * `"gui"`, so callers see one create, and keep `"gui"` for that host.
     */
    private fun retryCreateWithGuiSource(event: WsEvent.RpcError): Boolean {
        val (host, sent) = desktopCreates.remove(event.id) ?: return false
        if (!isSourceRefusal(event.error)) return false
        guiCreateHosts.add(host)
        return sendFrame(encodeRequest(sent.withGuiSource()))
    }

    private fun isSourceRefusal(error: JsonRpcError): Boolean {
        if (error.code != 4001) return false
        val fields = (error.data as? JsonObject)?.get("fields") as? JsonArray ?: return false
        return fields.any { (it as? JsonPrimitive)?.contentOrNull == "source" }
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
    ): CompletableDeferred<Any?> =
        synchronized(outboundLock) {
            // #1564: arm the timeout within the same registration/teardown transaction.
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
            deferred
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
        return toAuthorityWire(method.name, encoded)
    }

    /**
     * The session authority speaks the canonical wire: its params are exact, so each method below
     * is rewritten here once, for every caller. A legacy route stays untouched; it may refuse the
     * new keys.
     *
     * - `prompt.submit` (#1802): admits only identified input. The id is minted once, so a frame
     *   the offline queue replays after a dropped ack carries the same id and is de-duplicated.
     * - `session.interrupt` / `steer` / `redirect`: fenced by the turn's `execution_generation`.
     * - `approval.respond`: exactly `{session_id, execution_generation, prompt_id, choice}`.
     */
    private fun toAuthorityWire(
        method: String,
        params: JsonObject,
    ): JsonObject {
        if (!sessionAuthority) return params
        return when (method) {
            WsMethods.PROMPT_SUBMIT -> {
                if ("submission_id" in params || "input_id" in params) {
                    params
                } else {
                    JsonObject(params + ("submission_id" to JsonPrimitive(UUID.randomUUID().toString())))
                }
            }

            WsMethods.SESSION_TITLE,
            WsMethods.SESSION_ARCHIVE,
            WsMethods.SESSION_DELETE,
            WsMethods.SESSION_WORKSPACE_MOVE,
            WsMethods.SESSION_COMPRESS,
            WsMethods.SESSION_BRANCH,
            WsMethods.SESSION_BRANCH_WHOLE,
            -> {
                val sessionId = (params["session_id"] ?: params["session_key"]).eventStringOrNull()
                mutations.prepare(method, params, sessionId?.let { generations[it] }) ?: params
            }

            WsMethods.SESSION_INTERRUPT, WsMethods.SESSION_STEER, WsMethods.SESSION_REDIRECT -> {
                withGeneration(params)
            }

            WsMethods.APPROVAL_RESPOND -> {
                val promptId = params["prompt_id"] ?: params["request_id"] ?: return params
                val choice = params["choice"] ?: return params
                val fenced = withGeneration(params)
                val generation = fenced["execution_generation"] ?: return params
                JsonObject(
                    mapOf(
                        "session_id" to (params["session_id"] ?: return params),
                        "execution_generation" to generation,
                        "prompt_id" to promptId,
                        "choice" to choice,
                    ),
                )
            }

            else -> {
                params
            }
        }
    }

    private fun withGeneration(params: JsonObject): JsonObject {
        if ("execution_generation" in params) return params
        val sessionId = params["session_id"].eventStringOrNull() ?: return params
        val generation = generations[sessionId] ?: return params
        return JsonObject(params + ("execution_generation" to JsonPrimitive(generation)))
    }

    /** Remember the newest `execution_generation` each session event carries; it never moves back. */
    private fun recordGeneration(params: JsonObject) {
        val sessionId = params.eventSessionId().takeUnless { it.isNullOrBlank() } ?: return
        val generation =
            (
                (params["execution_generation"] ?: (params["payload"] as? JsonObject)?.get("execution_generation"))
                    as? JsonPrimitive
            )?.intOrNull ?: return
        generations.merge(sessionId, generation) { old, new -> maxOf(old, new) }
    }

    /** A canonical create/resume snapshot carries both fences even when no session.info event follows. */
    private fun recordSnapshotFences(result: JsonElement?) {
        if (!sessionAuthority || result !is JsonObject) return
        if (result["revision"] == null || result["execution_generation"] == null) return
        val sessionId = result.eventSessionId().takeUnless { it.isNullOrBlank() } ?: return
        mutations.adoptEvent("session.info", sessionId, result)
        recordGeneration(result)
    }

    private fun recordRevision(params: JsonObject) {
        val sessionId = params.eventSessionId().takeUnless { it.isNullOrBlank() } ?: return
        mutations.adoptEvent(
            (params["type"] as? JsonPrimitive)?.contentOrNull,
            sessionId,
            params["payload"] as? JsonObject,
        )
    }

    /** Complete (or fail) a single pending call and cancel its timer. */
    fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
    ) {
        mutations.settle(id, result as? JsonElement, error?.data)
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
        val snapshot =
            synchronized(outboundLock) {
                if (pendingCalls.isEmpty()) return
                val list = pendingCalls.toList()
                pendingCalls.clear()
                list
            }
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
                val decoded = json.decodeFromString<JsonRpcResponse>(text)
                val rpc =
                    if (decoded.id != null && decoded.error == null) {
                        recordSnapshotFences(decoded.result)
                        decoded.copy(result = mutations.mapResult(decoded.id, decoded.result))
                    } else {
                        decoded
                    }
                if (rpc.id == null) {
                    rpc.params?.let(::recordRevision)
                    rpc.params?.let(::recordGeneration)
                }
                // Resolve pending request() calls with the RAW JsonElement from
                // rpc.result, BEFORE EventParser.parse() converts it via toAny().
                // This preserves integer/float type fidelity needed by callers
                // that re-deserialize the result into typed data classes.
                val rpcId = rpc.id
                if (sessionAuthority && rpcId != null && rpc.result is JsonObject) {
                    val result = rpc.result
                    val sessionId = result["session_id"].eventStringOrNull()
                    if (!sessionId.isNullOrBlank()) {
                        mutations.adoptEvent("session.updated", sessionId, result)
                        (result["execution_generation"] as? JsonPrimitive)?.intOrNull?.let { generation ->
                            generations.merge(sessionId, generation) { old, new -> maxOf(old, new) }
                        }
                    }
                }
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
            is WsEvent.GatewayReady -> {
                sessionAuthority = event.data?.get("session_authority") == true
                generations.clear()
                mutations.clear()
            }

            is WsEvent.RpcResult -> {
                desktopCreates.remove(event.id)
                onResult(event.id)
                removeQueuedMessage(event.id)
                resolvePending(event.id, event.result, null)
            }

            is WsEvent.RpcError -> {
                if (retryCreateWithGuiSource(event)) return
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

/** Legacy methods the session authority serves as `session.mutate`. */
private fun isMutationMethod(method: String) =
    method in
        setOf(
            WsMethods.SESSION_TITLE,
            WsMethods.SESSION_ARCHIVE,
            WsMethods.SESSION_DELETE,
            WsMethods.SESSION_WORKSPACE_MOVE,
            WsMethods.SESSION_COMPRESS,
            WsMethods.SESSION_BRANCH,
            WsMethods.SESSION_BRANCH_WHOLE,
        )
