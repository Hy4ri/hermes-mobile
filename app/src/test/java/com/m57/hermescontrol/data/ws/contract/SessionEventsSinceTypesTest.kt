package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsSinceTypesTest {
    @Test
    fun paramsWithLastSeenEncodesToExpectedJsonObject() {
        val params = SessionEventsSinceParams(sessionId = "s1", lastSeen = 7)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionEventsSinceParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s1")
                put("last_seen", 7)
            }

        assertEquals(expected, encoded)
    }

    @Test
    fun paramsWithNullLastSeenEncodesToSessionIdOnly() {
        val params = SessionEventsSinceParams(sessionId = "s1", lastSeen = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionEventsSinceParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun resultDecodesFullRealisticPayloadIgnoringUnknownKeys() {
        val payload =
            buildJsonObject {
                put(
                    "events",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "message.token")
                                put("seq", 1)
                                put("session_id", "s1")
                            },
                        )
                    },
                )
                put("latest_seq", 42)
                put("truncated", false)
                put("epoch", "ep-999")
                // Extra unknown keys from server that must be ignored
                put("count", 1)
                put(
                    "open_requests",
                    kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("req-1"))
                    },
                )
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionEventsSinceResult.serializer(),
                payload,
            )

        assertEquals("ep-999", decoded.epoch)
        assertEquals(42, decoded.latestSeq)
        assertEquals(false, decoded.truncated)
        assertEquals(1, decoded.events?.size)
    }

    @Test
    fun resultDecodesEmptyObjectToAllNullDefaults() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionEventsSinceResult.serializer(),
                payload,
            )

        assertNull(decoded.events)
        assertNull(decoded.latestSeq)
        assertNull(decoded.truncated)
        assertNull(decoded.epoch)
    }

    @Test
    fun resultToleratesTruncatedAbsent() {
        val payload =
            buildJsonObject {
                put("latest_seq", 10)
                put("epoch", "epoch-1")
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionEventsSinceResult.serializer(),
                payload,
            )

        assertNull(decoded.truncated)
        assertEquals(10, decoded.latestSeq)
        assertEquals("epoch-1", decoded.epoch)
        assertNull(decoded.events)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("session.events.since", RpcMethods.SESSION_EVENTS_SINCE.name)
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_EVENTS_SINCE))
    }
}
