package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RpcChannelTest {
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Serializable
    private data class SampleRequestParams(
        val message: String,
        val count: Int? = null,
    )

    @Serializable
    private data class SampleResult(
        val status: String,
        val total: Int,
    )

    private val sampleMethod =
        RpcMethod("test.sample", SampleRequestParams.serializer(), SampleResult.serializer())

    private class FakeGateway(
        scope: CoroutineScope,
        json: Json,
        outboundLock: Any = Any(),
        val onResultCallback: (String) -> Unit = {},
        val onErrorCallback: (String) -> Unit = {},
        resolveStoredSessionId: (String?) -> String? = { it },
        val logParseFailureCallback: (Exception) -> Unit = {},
        decorateParams: (String, Map<String, Any>) -> Map<String, Any> = { _, p -> p },
    ) {
        val sentRequests = mutableListOf<SentRequestRecord>()
        val sentFrames = mutableListOf<String>()
        val removedQueuedIds = mutableListOf<String>()
        val rejectedLogs = mutableListOf<Pair<String, String>>()
        var idleCheckCount = 0
        var sendFrameReturnValue = true

        var immediateResolveOnSend: ((id: String, onSent: ((String) -> Unit)?) -> Unit)? = null

        val channel: RpcChannel =
            RpcChannel(
                scope = scope,
                json = json,
                sendRequest = { method, params, onSent ->
                    val id = "req-${sentRequests.size + 1}"
                    sentRequests.add(SentRequestRecord(id, method, params))
                    onSent?.invoke(id)
                    immediateResolveOnSend?.invoke(id, onSent)
                    id
                },
                sendFrame = { frame ->
                    sentFrames.add(frame)
                    sendFrameReturnValue
                },
                removeQueuedMessage = { id ->
                    removedQueuedIds.add(id)
                },
                onIdleCheck = {
                    idleCheckCount++
                },
                logRejected = { method, id ->
                    rejectedLogs.add(method to id)
                },
                outboundLock = outboundLock,
                onResult = onResultCallback,
                onError = onErrorCallback,
                resolveStoredSessionId = resolveStoredSessionId,
                logParseFailure = logParseFailureCallback,
                decorateParams = decorateParams,
            )

        data class SentRequestRecord(
            val id: String,
            val method: String,
            val params: Map<String, Any>,
        )
    }

    @Test
    fun nextIdProducesIncreasingNonBlankStrings() {
        val gateway = FakeGateway(CoroutineScope(CompletableDeferred<Unit>()), testJson)
        val firstId = gateway.channel.nextId()
        val secondId = gateway.channel.nextId()

        assertEquals("1", firstId)
        assertTrue(firstId.isNotBlank())
        assertTrue(secondId.isNotBlank())
        val firstNum = firstId.toLong()
        val secondNum = secondId.toLong()
        assertTrue(secondNum > firstNum)
        assertEquals(firstNum + 1L, secondNum)
    }

    @Test
    fun requestSuccessResolvesDeferredAndCleansUpInOrder() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val deferred = gateway.channel.request("user.ping", mapOf("key" to "value"))
            val id = gateway.sentRequests.first().id

            assertTrue(gateway.channel.hasPendingCalls())
            assertTrue(gateway.channel.pendingCallIdsForTest().contains(id))
            assertNotNull(gateway.channel.pendingCallTimeoutForTest(id))

            val dummyResult = JsonPrimitive("pong")
            gateway.channel.resolvePending(id, dummyResult, null)

            val resolved = deferred.await()
            assertEquals(dummyResult, resolved)
            assertFalse(gateway.channel.hasPendingCalls())
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)
            assertNull(gateway.channel.pendingCallTimeoutForTest(id))
        }

    @Test
    fun requestErrorResolvesWithHermesRpcExceptionAndErrorData() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val deferred = gateway.channel.request("user.fail")
            val id = gateway.sentRequests.first().id

            val errorData = buildJsonObject { put("reason", "auth_failed") }
            val rpcError = JsonRpcError(code = 401, message = "Unauthorized", data = errorData)

            gateway.channel.resolvePending(id, null, rpcError)

            try {
                deferred.await()
                fail("Expected HermesRpcException")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Unauthorized", e.message)
                assertEquals(401, e.code)
                assertEquals(errorData, e.data)
            }

            assertFalse(gateway.channel.hasPendingCalls())
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)
        }

    @Test
    fun requestTimeoutFailsWithExactMessageAndCodeMinusOne() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val timeoutMs = 5_000L
            val deferred = gateway.channel.request("agent.turn", timeoutMs = timeoutMs)
            val id = gateway.sentRequests.first().id

            runCurrent()
            advanceTimeBy(4_999L)
            runCurrent()
            assertTrue(gateway.channel.hasPendingCalls())
            assertFalse(deferred.isCompleted)

            advanceTimeBy(1L)
            runCurrent()

            assertTrue(deferred.isCompleted)
            assertFalse(gateway.channel.hasPendingCalls())
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)

            try {
                deferred.await()
                fail("Expected HermesRpcException on timeout")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Request timed out: agent.turn", e.message)
                assertEquals(-1, e.code)
                assertNull(e.data)
            }
        }

    @Test
    fun deferredCancellationCancelsTimerRemovesQueuedAndTriggersIdle() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val deferred = gateway.channel.request("slow.method", timeoutMs = 60_000L)
            val id = gateway.sentRequests.first().id

            val timeoutJob = gateway.channel.pendingCallTimeoutForTest(id)
            assertNotNull(timeoutJob)
            assertFalse(timeoutJob!!.isCancelled)

            deferred.cancel()
            runCurrent()

            assertTrue(deferred.isCancelled)
            assertFalse(gateway.channel.hasPendingCalls())
            assertTrue(timeoutJob.isCancelled)
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)

            // Advancing past original timeout should do nothing further
            advanceTimeBy(70_000L)
            runCurrent()
            assertEquals(1, gateway.idleCheckCount)
        }

    @Test
    fun suppressionFlagReflectedInIsErrorEventSuppressed() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            gateway.channel.request("normal.call", suppressErrorEvent = false)
            val normalId = gateway.sentRequests[0].id

            gateway.channel.request("silent.call", suppressErrorEvent = true)
            val silentId = gateway.sentRequests[1].id

            assertFalse(gateway.channel.isErrorEventSuppressed(normalId))
            assertTrue(gateway.channel.isErrorEventSuppressed(silentId))
            assertFalse(gateway.channel.isErrorEventSuppressed("non-existent-id"))
        }

    @Test
    fun rejectAllPendingFailsAllCallsWithProvidedExceptionAndNoIdleCheck() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val deferred1 = gateway.channel.request("method.one")
            val deferred2 = gateway.channel.request("method.two")
            val id1 = gateway.sentRequests[0].id
            val id2 = gateway.sentRequests[1].id

            val customException = HermesWsClient.HermesRpcException("Socket terminated abruptly", code = 1006)

            gateway.channel.rejectAllPending(customException)

            assertFalse(gateway.channel.hasPendingCalls())
            // Verified contract: rejectAllPending performs NO onIdleCheck
            assertEquals(0, gateway.idleCheckCount)
            assertEquals(setOf(id1, id2), gateway.removedQueuedIds.toSet())
            assertEquals(setOf("method.one" to id1, "method.two" to id2), gateway.rejectedLogs.toSet())

            try {
                deferred1.await()
                fail("deferred1 should have failed")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertSame(customException, e)
            }

            try {
                deferred2.await()
                fail("deferred2 should have failed")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertSame(customException, e)
            }
        }

    @Test
    fun rejectAllPendingDefaultExceptionMatchesExpectedMessage() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            val deferred = gateway.channel.request("some.call")

            gateway.channel.rejectAllPending()

            try {
                deferred.await()
                fail("Expected default exception")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Connection lost — request cancelled", e.message)
            }
        }

    @Test
    fun immediateResolutionDuringOnSentHandledSafely() =
        runTest {
            val gateway = FakeGateway(this, testJson)
            gateway.immediateResolveOnSend = { id, _ ->
                gateway.channel.resolvePending(id, "instant-result", null)
            }

            val deferred = gateway.channel.request("instant.call")
            val id = gateway.sentRequests.first().id

            assertTrue(deferred.isCompleted)
            assertEquals("instant-result", deferred.await())
            assertFalse(gateway.channel.hasPendingCalls())
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)
        }

    @Test
    fun typedCallSerializesParamsOmittingDefaultsAndDecodesResult() =
        runTest {
            val gateway = FakeGateway(this, testJson)

            val job =
                async {
                    gateway.channel.call(
                        sampleMethod,
                        SampleRequestParams(message = "hello", count = null),
                    )
                }

            runCurrent()
            assertEquals(1, gateway.sentRequests.size)
            val sent = gateway.sentRequests.first()
            assertEquals("test.sample", sent.method)
            // encodeDefaults = false means count (null) is omitted from params
            val encodedParams =
                gateway.channel.encodeParams(
                    sampleMethod,
                    SampleRequestParams(message = "hello", count = null),
                )
            assertFalse(encodedParams.containsKey("count"))
            assertTrue(encodedParams.containsKey("message"))

            // Resolve pending with raw json representation
            val rawResult =
                buildJsonObject {
                    put("status", "ok")
                    put("total", 42)
                    put("extra_ignored_key", "bonus")
                }
            gateway.channel.resolvePending(sent.id, rawResult, null)

            val result = job.await()
            assertEquals("ok", result.status)
            assertEquals(42, result.total)
        }

    @Test
    fun typedCallCancellationPropagatesAndCancelsDeferred() =
        runTest {
            val gateway = FakeGateway(this, testJson)

            val job =
                launch {
                    gateway.channel.call(
                        sampleMethod,
                        SampleRequestParams(message = "cancel-me"),
                    )
                }

            runCurrent()
            val id = gateway.sentRequests.first().id
            assertTrue(gateway.channel.hasPendingCalls())

            job.cancel()
            runCurrent()

            assertFalse(gateway.channel.hasPendingCalls())
            assertEquals(listOf(id), gateway.removedQueuedIds)
            assertEquals(1, gateway.idleCheckCount)
        }

    @Test
    fun rawJsonNumericFidelityPreservedIntoResolve() =
        runTest {
            val gateway = FakeGateway(this, testJson)

            val deferred =
                gateway.channel.requestTyped(
                    sampleMethod,
                    SampleRequestParams(message = "precision"),
                )
            val id = gateway.sentRequests.first().id

            // Map with integer-value double, e.g. 177.0 -> preserves int semantics in toJsonElement
            val mapResult = mapOf("status" to "precise", "total" to 177.0)
            gateway.channel.resolvePending(id, mapResult, null)

            val raw = deferred.await()
            val jsonElement = raw.toJsonElement() as JsonObject
            assertEquals(177, jsonElement["total"]?.jsonPrimitive?.int)

            // Direct decode with injected channel json to verify round-trip
            val decoded = testJson.decodeFromJsonElement(sampleMethod.result, jsonElement)
            assertEquals(177, decoded.total)
            assertEquals("precise", decoded.status)
        }

    @Test
    fun respondToServerRequestFormatsExactJsonRpcResponseAndPropagatesSink() {
        val gateway = FakeGateway(CoroutineScope(CompletableDeferred<Unit>()), testJson)
        val serverReqId = "srv-999"
        val payload = buildJsonObject { put("pong", true) }

        gateway.sendFrameReturnValue = true
        val result = gateway.channel.respondToServerRequest(serverReqId, payload)
        assertTrue(result)
        assertEquals(1, gateway.sentFrames.size)

        val frame = testJson.decodeFromString<JsonObject>(gateway.sentFrames.first())
        assertEquals("2.0", frame["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(serverReqId, frame["id"]?.jsonPrimitive?.content)
        assertEquals(payload, frame["result"])
        assertFalse(frame.containsKey("method"))
        assertFalse(frame.containsKey("params"))

        // Blank id returns false immediately without sending frame
        val blankResult = gateway.channel.respondToServerRequest("   ", payload)
        assertFalse(blankResult)
        assertEquals(1, gateway.sentFrames.size)

        // Sink returning false is propagated
        gateway.sendFrameReturnValue = false
        val falseResult = gateway.channel.respondToServerRequest("srv-1000", payload)
        assertFalse(falseResult)
        assertEquals(2, gateway.sentFrames.size)
    }

    @Test
    fun respondToServerRequestErrorFormatsExactJsonRpcErrorAndPropagatesSink() {
        val gateway = FakeGateway(CoroutineScope(CompletableDeferred<Unit>()), testJson)
        val serverReqId = "srv-888"

        gateway.sendFrameReturnValue = true
        val result = gateway.channel.respondToServerRequestError(serverReqId, -32601, "Method not found")
        assertTrue(result)
        assertEquals(1, gateway.sentFrames.size)

        val frame = testJson.decodeFromString<JsonObject>(gateway.sentFrames.first())
        assertEquals("2.0", frame["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(serverReqId, frame["id"]?.jsonPrimitive?.content)
        assertFalse(frame.containsKey("result"))
        val errorObj = frame["error"] as JsonObject
        assertEquals(-32601, errorObj["code"]?.jsonPrimitive?.int)
        assertEquals("Method not found", errorObj["message"]?.jsonPrimitive?.content)

        // Blank id returns false without sending frame
        assertFalse(gateway.channel.respondToServerRequestError("", -1, "Bad"))
        assertEquals(1, gateway.sentFrames.size)

        // Sink returning false is propagated
        gateway.sendFrameReturnValue = false
        val falseResult = gateway.channel.respondToServerRequestError("srv-889", -32000, "Error")
        assertFalse(falseResult)
        assertEquals(2, gateway.sentFrames.size)
    }

    @Test
    fun createRequestDecoratesOnceAllocatesIdAndEncodesOmittingDefaults() {
        var decorateCount = 0
        val gateway =
            FakeGateway(
                scope = CoroutineScope(CompletableDeferred<Unit>()),
                json = testJson,
                decorateParams = { method, params ->
                    decorateCount++
                    params + ("decoratedMethod" to method)
                },
            )

        val request = gateway.channel.createRequest("test.action", mapOf("key" to "value"))
        assertEquals("1", request.id)
        assertEquals("test.action", request.method)
        assertEquals(1, decorateCount)
        assertEquals(JsonPrimitive("value"), request.params["key"])
        assertEquals(JsonPrimitive("test.action"), request.params["decoratedMethod"])

        val encoded = gateway.channel.encodeRequest(request)
        // Injected json with encodeDefaults = false omits default jsonrpc ("2.0")
        assertFalse(encoded.contains("\"jsonrpc\""))
        assertTrue(encoded.contains("\"id\":\"1\"") || encoded.contains("\"id\": \"1\""))
        assertTrue(encoded.contains("\"method\":\"test.action\"") || encoded.contains("\"method\": \"test.action\""))

        // An empty params map is omitted by encodeRequest under encodeDefaults = false
        val plainGateway = FakeGateway(CoroutineScope(CompletableDeferred<Unit>()), testJson)
        val emptyParamsReq = plainGateway.channel.createRequest("test.empty", emptyMap())
        val encodedEmpty = plainGateway.channel.encodeRequest(emptyParamsReq)
        assertFalse(encodedEmpty.contains("\"params\""))
    }

    @Test
    fun handleIncomingFrameOrderRawJsonResolvedBeforeEventEmittedAndDoubleOnResult() =
        runTest {
            val onResultCalls = mutableListOf<String>()
            val events = mutableListOf<WsEvent>()
            val gateway =
                FakeGateway(
                    scope = this,
                    json = testJson,
                    onResultCallback = { onResultCalls.add(it) },
                )
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            val deferred = gateway.channel.request("client.calc")
            val id = gateway.sentRequests.first().id

            var rawResolvedAtEventTime: Boolean? = null
            var onResultCallsAtEventTime = 0
            val emitSink: (WsEvent) -> Unit = { event ->
                events.add(event)
                rawResolvedAtEventTime = deferred.isCompleted
                onResultCallsAtEventTime = onResultCalls.size
            }

            val incomingFrame = """{"jsonrpc":"2.0","id":"$id","result":{"ans":42.0}}"""
            gateway.channel.handleIncomingFrame(incomingFrame, replayTracker, emitSink)

            // onResult is invoked twice baseline: once before raw resolve, and again before parsed RpcResult resolution
            assertEquals(2, onResultCalls.size)
            assertEquals(listOf(id, id), onResultCalls)
            assertEquals(2, onResultCallsAtEventTime)

            // Raw deferred was settled BEFORE emitEvent was called
            assertEquals(true, rawResolvedAtEventTime)
            assertTrue(deferred.isCompleted)

            // Numeric fidelity preserved on result
            val rawResult = deferred.await()
            assertTrue(rawResult is JsonObject)
            val rawObj = rawResult as JsonObject
            assertEquals(JsonPrimitive(42.0), rawObj["ans"])

            // Event emitted was RpcResult
            assertEquals(1, events.size)
            val rpcResult = events.first() as WsEvent.RpcResult
            assertEquals(id, rpcResult.id)
        }

    @Test
    fun handleIncomingFrameRpcErrorInvokesCallbackAndRespectsSuppression() =
        runTest {
            val onErrorCalls = mutableListOf<String>()
            val events = mutableListOf<WsEvent>()
            val gateway =
                FakeGateway(
                    scope = this,
                    json = testJson,
                    onErrorCallback = { onErrorCalls.add(it) },
                )
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            // 1. Normal unsuppressed error
            val normalDeferred = gateway.channel.request("call.normal", suppressErrorEvent = false)
            val normalId = gateway.sentRequests[0].id
            val errorFrame1 =
                """{"jsonrpc":"2.0","id":"$normalId","error":{"code":-32603,"message":"Internal error"}}"""
            gateway.channel.handleIncomingFrame(errorFrame1, replayTracker) { events.add(it) }

            assertEquals(listOf(normalId), onErrorCalls)
            assertTrue(normalDeferred.isCompleted)
            try {
                normalDeferred.await()
                fail("Expected HermesRpcException")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals(-32603, e.code)
                assertEquals("Internal error", e.message)
            }
            assertEquals(1, events.size)
            assertTrue(events.first() is WsEvent.RpcError)
            assertEquals(normalId, (events.first() as WsEvent.RpcError).id)

            // 2. Suppressed error resolves deferred but emits NONE
            val silentDeferred = gateway.channel.request("call.silent", suppressErrorEvent = true)
            val silentId = gateway.sentRequests[1].id
            val errorFrame2 =
                """{"jsonrpc":"2.0","id":"$silentId","error":{"code":404,"message":"Not found"}}"""
            gateway.channel.handleIncomingFrame(errorFrame2, replayTracker) { events.add(it) }

            assertEquals(listOf(normalId, silentId), onErrorCalls)
            assertTrue(silentDeferred.isCompleted)
            try {
                silentDeferred.await()
                fail("Expected HermesRpcException on silent deferred")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals(404, e.code)
            }
            // Event list size remains 1 (no RpcError event emitted for suppressed call)
            assertEquals(1, events.size)
        }

    @Test
    fun handleIncomingFrameCapabilitySuppressionHidesEventUntilCleared() =
        runTest {
            val events = mutableListOf<WsEvent>()
            val gateway = FakeGateway(this, testJson)
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            val capId = "cap-42"
            gateway.channel.registerCapabilityRequest(capId)

            val capResponseFrame = """{"jsonrpc":"2.0","id":"$capId","result":{"supported":true}}"""
            gateway.channel.handleIncomingFrame(capResponseFrame, replayTracker) { events.add(it) }

            // Registered capability response is hidden from events
            assertTrue(events.isEmpty())

            // After clearing capabilities, subsequent responses with that ID are visible
            gateway.channel.registerCapabilityRequest(capId)
            gateway.channel.clearCapabilityRequests()
            gateway.channel.handleIncomingFrame(capResponseFrame, replayTracker) { events.add(it) }

            assertEquals(1, events.size)
            val rpcResult = events.first() as WsEvent.RpcResult
            assertEquals(capId, rpcResult.id)
        }

    @Test
    fun handleIncomingFrameMalformedJsonInvokesParseFailureAndEmitsUnknown() =
        runTest {
            val parseFailures = mutableListOf<Exception>()
            val events = mutableListOf<WsEvent>()
            val gateway =
                FakeGateway(
                    scope = this,
                    json = testJson,
                    logParseFailureCallback = { parseFailures.add(it) },
                )
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            val malformed = "{ invalid json frame ::"
            gateway.channel.handleIncomingFrame(malformed, replayTracker) { events.add(it) }

            assertEquals(1, parseFailures.size)
            assertEquals(1, events.size)
            val unknown = events.first() as WsEvent.Unknown
            assertEquals(malformed, unknown.raw)
        }

    @Test
    fun handleIncomingFrameServerRequestClassifiedCorrectlyOverRpcResult() =
        runTest {
            val events = mutableListOf<WsEvent>()
            val gateway = FakeGateway(this, testJson)
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            // Normal srq frame with id + method should emit ServerRequest, not RpcResult
            val srqFrame = """{"jsonrpc":"2.0","id":"srq-101","method":"clarify","params":{"text":"Confirm?"}}"""
            gateway.channel.handleIncomingFrame(srqFrame, replayTracker) { events.add(it) }

            assertEquals(1, events.size)
            val event = events.first()
            assertTrue("Expected ServerRequest, got: $event", event is WsEvent.ServerRequest)
            val srq = event as WsEvent.ServerRequest
            assertEquals("srq-101", srq.id)
            assertEquals("clarify", srq.method)
            assertFalse(srq.replayed)
        }

    @Test
    fun handleIncomingFrameSequencedNotificationDeduplicatesViaReplayTracker() =
        runTest {
            val events = mutableListOf<WsEvent>()
            val gateway = FakeGateway(this, testJson)
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            val seqFrame =
                """{"method":"events","params":{"type":"message.token","session_id":"s1","seq":11,"payload":{"text":"fresh"}}}"""

            // First delivery (fresh seq) emits once
            gateway.channel.handleIncomingFrame(seqFrame, replayTracker) { events.add(it) }
            assertEquals(1, events.size)
            val tokenEvent = events.first() as WsEvent.MessageToken
            assertEquals("fresh", tokenEvent.token)
            assertEquals("s1", tokenEvent.sessionId)

            // Duplicate delivery (same seq 11) is dropped, emits none
            gateway.channel.handleIncomingFrame(seqFrame, replayTracker) { events.add(it) }
            assertEquals(1, events.size)
        }

    @Test
    fun handleIncomingFrameSettlesRawResultBeforeReplayingOpenRequests() =
        runTest {
            val order = mutableListOf<String>()
            val gateway = FakeGateway(this, testJson, onResultCallback = { order.add("result:$it") })
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = {},
                )
            val deferred = gateway.channel.request("session.resume")
            val id = gateway.sentRequests.first().id
            deferred.invokeOnCompletion { order.add("resolved") }
            val frame =
                """{"id":"$id","result":{"open_requests":[{"id":"srq-open","method":"clarify","params":{"text":"replayed"}}]}}"""

            gateway.channel.handleIncomingFrame(frame, replayTracker) { event ->
                assertTrue("Raw deferred must settle before either event", deferred.isCompleted)
                when (event) {
                    is WsEvent.ServerRequest -> {
                        assertTrue(event.replayed)
                        assertEquals("clarify", event.method)
                        assertEquals("replayed", event.params["text"])
                        order.add("server:${event.id}")
                    }

                    is WsEvent.RpcResult -> {
                        order.add("rpc:${event.id}")
                    }

                    else -> {
                        fail("Unexpected event: $event")
                    }
                }
            }

            assertEquals(listOf("result:$id", "resolved", "server:srq-open", "result:$id", "rpc:$id"), order)
            assertTrue(deferred.await() is JsonObject)
            assertFalse(gateway.channel.hasPendingCalls())
        }

    @Test
    fun handleIncomingFrameDecoratesStoredSessionIdOnMessageComplete() =
        runTest {
            val events = mutableListOf<WsEvent>()
            val gateway =
                FakeGateway(
                    scope = this,
                    json = testJson,
                    resolveStoredSessionId = { sid -> "stored-$sid" },
                )
            val replayTracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { events.add(it) },
                )

            val completeFrame =
                """{"method":"events","params":{"type":"message.complete","session_id":"active-session","payload":{"text":"done","reasoning":"reason"}}}"""
            gateway.channel.handleIncomingFrame(completeFrame, replayTracker) { events.add(it) }

            assertEquals(1, events.size)
            val completeEvent = events.first() as WsEvent.MessageComplete
            assertEquals("done", completeEvent.text)
            assertEquals("reason", completeEvent.reasoning)
            assertEquals("active-session", completeEvent.sessionId)
            assertEquals("stored-active-session", completeEvent.storedSessionId)
        }
}
