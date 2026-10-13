package com.m57.hermescontrol.data.ws

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Suppress("FunctionName")
private fun createJsonRpcResponse(
    jsonrpc: String = "2.0",
    id: String? = null,
    result: Any? = null,
    error: JsonRpcError? = null,
    method: String? = "event",
    params: Any? = null,
): JsonRpcResponse =
    JsonRpcResponse(
        jsonrpc = jsonrpc,
        id = id,
        result = result?.toJsonElement(),
        error = error,
        method = method,
        params = params?.toJsonElement() as? JsonObject,
    )

class GatewayNoticeParserTest {
    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun pushGatewayEvent(
        type: String,
        payload: Map<String, Any?>?,
        sessionId: String? = null,
    ): WsEvent {
        val params = mutableMapOf<String, Any?>("type" to type, "payload" to payload)
        if (sessionId != null) {
            params["session_id"] = sessionId
        }
        val response = createJsonRpcResponse(method = "event", params = params)
        return EventParser.parse(response)
    }

    @Test
    fun notificationShow_fullFieldsAndSession_fullyPreserved() {
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Agent is warming up",
                        "level" to "warn",
                        "kind" to "sticky",
                        "ttl_ms" to 5000L,
                        "key" to "warmup-notice",
                        "id" to "notice-123",
                    ),
                sessionId = "session-42",
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        val show = event as WsEvent.NotificationShow
        assertEquals("Agent is warming up", show.text)
        assertEquals("warn", show.level)
        assertEquals("sticky", show.kind)
        assertEquals(5000L, show.ttlMs)
        assertEquals("warmup-notice", show.key)
        assertEquals("notice-123", show.id)
        assertEquals("session-42", show.sessionId)
    }

    @Test
    fun notificationShow_doesNotTruncateFractionalOrStringTtl() {
        for (ttl in listOf(1.25, "5000")) {
            val event =
                pushGatewayEvent(
                    "notification.show",
                    mapOf("text" to "Notice", "level" to "info", "kind" to "ttl", "ttl_ms" to ttl),
                    "session-42",
                )
            assertTrue(event is WsEvent.Unknown)
        }
    }

    @Test
    fun notificationShow_blankRequiredMetadataIsMalformed() {
        for (field in listOf("level", "kind")) {
            val payload = mutableMapOf<String, Any?>("text" to "Notice", "level" to "info", "kind" to "ttl")
            payload[field] = "  "
            assertTrue(pushGatewayEvent("notification.show", payload, "session-42") is WsEvent.Unknown)
        }
    }

    @Test
    fun notificationShow_optionalFieldsMissing_preservedAsNull() {
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Simple alert",
                        "level" to "info",
                        "kind" to "toast",
                    ),
                sessionId = "session-99",
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        val show = event as WsEvent.NotificationShow
        assertEquals("Simple alert", show.text)
        assertEquals("info", show.level)
        assertEquals("toast", show.kind)
        assertEquals(null, show.ttlMs)
        assertEquals(null, show.key)
        assertEquals(null, show.id)
        assertEquals("session-99", show.sessionId)
    }

    @Test
    fun notificationShow_explicitNullOptionalFields_preservedAsNull() {
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Notice with null optionals",
                        "level" to "error",
                        "kind" to "agent",
                        "ttl_ms" to null,
                        "key" to null,
                        "id" to null,
                    ),
                sessionId = "session-abc",
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        val show = event as WsEvent.NotificationShow
        assertEquals("Notice with null optionals", show.text)
        assertEquals("error", show.level)
        assertEquals("agent", show.kind)
        assertEquals(null, show.ttlMs)
        assertEquals(null, show.key)
        assertEquals(null, show.id)
        assertEquals("session-abc", show.sessionId)
    }

    @Test
    fun notificationShow_unscopedSession_parsesWithNullSessionId() {
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Unscoped warning",
                        "level" to "warn",
                        "kind" to "global",
                        "key" to "global-1",
                    ),
                sessionId = null,
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        val show = event as WsEvent.NotificationShow
        assertEquals("Unscoped warning", show.text)
        assertEquals("warn", show.level)
        assertEquals("global", show.kind)
        assertEquals(null, show.ttlMs)
        assertEquals("global-1", show.key)
        assertEquals(null, show.id)
        assertEquals(null, show.sessionId)
    }

    @Test
    fun notificationShow_legitimateZeroAndNegativeTTL_retainsValue() {
        val zeroTtlEvent =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Zero TTL notice",
                        "level" to "info",
                        "kind" to "ttl",
                        "ttl_ms" to 0L,
                    ),
                sessionId = "s-zero",
            )
        assertTrue(zeroTtlEvent is WsEvent.NotificationShow)
        assertEquals(0L, (zeroTtlEvent as WsEvent.NotificationShow).ttlMs)

        val negativeTtlEvent =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Negative TTL notice",
                        "level" to "info",
                        "kind" to "ttl",
                        "ttl_ms" to -100L,
                    ),
                sessionId = "s-neg",
            )
        assertTrue(negativeTtlEvent is WsEvent.NotificationShow)
        assertEquals(-100L, (negativeTtlEvent as WsEvent.NotificationShow).ttlMs)
    }

    @Test
    fun notificationShow_largeLongTTL_retainsValueWithoutIntOverflow() {
        val largeTtl = 5_000_000_000L // Exceeds Int.MAX_VALUE (2_147_483_647)
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Long-lasting notice",
                        "level" to "info",
                        "kind" to "ttl",
                        "ttl_ms" to largeTtl,
                    ),
                sessionId = "s-large",
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        val show = event as WsEvent.NotificationShow
        assertEquals(largeTtl, show.ttlMs)
    }

    @Test
    fun notificationShow_intTtl_preservesLongSemantics() {
        val event =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Int TTL notice",
                        "level" to "info",
                        "kind" to "ttl",
                        "ttl_ms" to 3000,
                    ),
                sessionId = "s-int",
            )

        assertTrue("Expected NotificationShow but got $event", event is WsEvent.NotificationShow)
        assertEquals(3000L, (event as WsEvent.NotificationShow).ttlMs)
    }

    @Test
    fun notificationShow_missingOrBlankRequiredText_producesUnknown() {
        val missingText =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "level" to "warn",
                        "kind" to "sticky",
                    ),
                sessionId = "s-missing-text",
            )
        assertTrue("Missing text should yield Unknown", missingText is WsEvent.Unknown)

        val emptyText =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "",
                        "level" to "warn",
                        "kind" to "sticky",
                    ),
                sessionId = "s-empty-text",
            )
        assertTrue("Empty text should yield Unknown", emptyText is WsEvent.Unknown)

        val whitespaceText =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "   ",
                        "level" to "warn",
                        "kind" to "sticky",
                    ),
                sessionId = "s-ws-text",
            )
        assertTrue("Blank text should yield Unknown", whitespaceText is WsEvent.Unknown)
    }

    @Test
    fun notificationShow_malformedOrMissingRequiredStrings_producesUnknown() {
        val nonStringText =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to 12345,
                        "level" to "info",
                        "kind" to "toast",
                    ),
            )
        assertTrue("Non-string text should yield Unknown", nonStringText is WsEvent.Unknown)

        val missingLevel =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Valid text",
                        "kind" to "toast",
                    ),
            )
        assertTrue("Missing level should yield Unknown", missingLevel is WsEvent.Unknown)

        val nonStringLevel =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Valid text",
                        "level" to 42,
                        "kind" to "toast",
                    ),
            )
        assertTrue("Non-string level should yield Unknown", nonStringLevel is WsEvent.Unknown)

        val missingKind =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Valid text",
                        "level" to "info",
                    ),
            )
        assertTrue("Missing kind should yield Unknown", missingKind is WsEvent.Unknown)

        val nonStringKind =
            pushGatewayEvent(
                type = "notification.show",
                payload =
                    mapOf(
                        "text" to "Valid text",
                        "level" to "info",
                        "kind" to true,
                    ),
            )
        assertTrue("Non-string kind should yield Unknown", nonStringKind is WsEvent.Unknown)
    }

    @Test
    fun notificationShow_nullOrMalformedPayload_producesUnknown() {
        val nullPayload = pushGatewayEvent(type = "notification.show", payload = null)
        assertTrue("Null payload should yield Unknown", nullPayload is WsEvent.Unknown)

        val emptyPayload = pushGatewayEvent(type = "notification.show", payload = emptyMap())
        assertTrue("Empty payload should yield Unknown", emptyPayload is WsEvent.Unknown)
    }

    @Test
    fun notificationClear_fullFieldsAndSession_fullyPreserved() {
        val event =
            pushGatewayEvent(
                type = "notification.clear",
                payload = mapOf("key" to "warmup-notice"),
                sessionId = "session-42",
            )

        assertTrue("Expected NotificationClear but got $event", event is WsEvent.NotificationClear)
        val clear = event as WsEvent.NotificationClear
        assertEquals("warmup-notice", clear.key)
        assertEquals("session-42", clear.sessionId)
    }

    @Test
    fun notificationClear_unscopedSession_parsesWithNullSessionId() {
        val event =
            pushGatewayEvent(
                type = "notification.clear",
                payload = mapOf("key" to "global-banner"),
                sessionId = null,
            )

        assertTrue("Expected NotificationClear but got $event", event is WsEvent.NotificationClear)
        val clear = event as WsEvent.NotificationClear
        assertEquals("global-banner", clear.key)
        assertEquals(null, clear.sessionId)
    }

    @Test
    fun notificationClear_missingOrBlankKey_producesUnknown() {
        val missingKey =
            pushGatewayEvent(
                type = "notification.clear",
                payload = emptyMap(),
                sessionId = "s-1",
            )
        assertTrue("Missing clear key should yield Unknown", missingKey is WsEvent.Unknown)

        val emptyKey =
            pushGatewayEvent(
                type = "notification.clear",
                payload = mapOf("key" to ""),
                sessionId = "s-1",
            )
        assertTrue("Empty clear key should yield Unknown", emptyKey is WsEvent.Unknown)

        val blankKey =
            pushGatewayEvent(
                type = "notification.clear",
                payload = mapOf("key" to "   "),
                sessionId = "s-1",
            )
        assertTrue("Blank clear key should yield Unknown", blankKey is WsEvent.Unknown)

        val nonStringKey =
            pushGatewayEvent(
                type = "notification.clear",
                payload = mapOf("key" to 999),
                sessionId = "s-1",
            )
        assertTrue("Non-string clear key should yield Unknown", nonStringKey is WsEvent.Unknown)
    }

    @Test
    fun notificationClear_nullPayload_producesUnknown() {
        val nullPayload = pushGatewayEvent(type = "notification.clear", payload = null)
        assertTrue("Null payload should yield Unknown", nullPayload is WsEvent.Unknown)
    }

    @Test
    fun regression_ordinaryMessagesAndChangeProjects_parseUnchanged() {
        val msgTokenEvent =
            pushGatewayEvent(
                type = "message.delta",
                payload = mapOf("text" to "streaming token"),
                sessionId = "s-token",
            )
        assertTrue("message.delta must parse to MessageToken", msgTokenEvent is WsEvent.MessageToken)
        assertEquals("streaming token", (msgTokenEvent as WsEvent.MessageToken).token)

        val msgCompleteEvent =
            pushGatewayEvent(
                type = "message.complete",
                payload = mapOf("text" to "completed response"),
                sessionId = "s-done",
            )
        assertTrue("message.complete must parse to MessageComplete", msgCompleteEvent is WsEvent.MessageComplete)
        assertEquals("completed response", (msgCompleteEvent as WsEvent.MessageComplete).text)

        val projectsEvent =
            pushGatewayEvent(
                type = "projects.changed",
                payload = mapOf("action" to "reload"),
                sessionId = null,
            )
        assertTrue("projects.changed must parse to ChangeEvent", projectsEvent is WsEvent.ChangeEvent)
        val change = projectsEvent as WsEvent.ChangeEvent
        assertEquals("projects.changed", change.type)
        assertEquals(mapOf("action" to "reload"), change.data)
    }
}
