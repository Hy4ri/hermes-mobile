package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlTypesTest {
    @Test
    fun sessionInterruptParamsEncodesSessionIdExactly() {
        val params = SessionInterruptParams(sessionId = "sess-int-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionInterruptParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-int-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionCorrectionParamsEncodesSessionIdAndTextExactly() {
        val params = SessionCorrectionParams(sessionId = "sess-corr-1", text = "stop that action")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCorrectionParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-corr-1")
                put("text", "stop that action")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "text"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionInterruptResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("status", "interrupted")
                put("unknown_key", "ignored_value")
                put("extra_flag", true)
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionInterruptResult.serializer(),
                payload,
            )

        assertEquals("interrupted", decoded.status)
    }

    @Test
    fun sessionInterruptResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionInterruptResult.serializer(),
                payload,
            )

        assertNull(decoded.status)
    }

    @Test
    fun sessionCorrectionResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("status", "applied")
                put("text", "stop that action")
                put("ignored_num", 42)
                put("nested", buildJsonObject { put("ignored", "yes") })
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionCorrectionResult.serializer(),
                payload,
            )

        assertEquals("applied", decoded.status)
        assertEquals("stop that action", decoded.text)
    }

    @Test
    fun sessionCorrectionResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionCorrectionResult.serializer(),
                payload,
            )

        assertNull(decoded.status)
        assertNull(decoded.text)
    }

    @Test
    fun rpcMethodsRegistrationMatchesControlContract() {
        assertEquals("session.interrupt", RpcMethods.SESSION_INTERRUPT.name)
        assertEquals("session.steer", RpcMethods.SESSION_STEER.name)
        assertEquals("session.redirect", RpcMethods.SESSION_REDIRECT.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_INTERRUPT))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_STEER))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_REDIRECT))
    }
}
