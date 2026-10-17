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

class SubagentControlTypesTest {
    @Test
    fun subagentSteerParamsEncodesFieldsCorrectly() {
        val params = SubagentSteerParams(sessionId = "sess-1", subagentId = "sub-1", text = "hello")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubagentSteerParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-1")
                put("subagent_id", "sub-1")
                put("text", "hello")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "subagent_id", "text"), (encoded as JsonObject).keys)
    }

    @Test
    fun subagentInterruptParamsEncodesFieldsCorrectly() {
        val params = SubagentInterruptParams(sessionId = "sess-1", subagentId = "sub-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubagentInterruptParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-1")
                put("subagent_id", "sub-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "subagent_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun subagentSteerResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("status", "queued")
                put("subagent_id", "sub-1")
                put("text", "hello")
                put("unknown_key", "ignored")
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SubagentSteerResult.serializer(),
                payload,
            )

        assertEquals("queued", decoded.status)
        assertEquals("sub-1", decoded.subagentId)
        assertEquals("hello", decoded.text)
    }

    @Test
    fun subagentSteerResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SubagentSteerResult.serializer(),
                payload,
            )

        assertNull(decoded.status)
        assertNull(decoded.subagentId)
        assertNull(decoded.text)
    }

    @Test
    fun subagentInterruptResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("found", true)
                put("subagent_id", "sub-1")
                put("unknown_key", "ignored")
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SubagentInterruptResult.serializer(),
                payload,
            )

        assertEquals(true, decoded.found)
        assertEquals("sub-1", decoded.subagentId)
    }

    @Test
    fun subagentInterruptResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SubagentInterruptResult.serializer(),
                payload,
            )

        assertNull(decoded.found)
        assertNull(decoded.subagentId)
    }

    @Test
    fun rpcMethodsRegistrationMatchesSubagentControlContract() {
        assertEquals("subagent.interrupt", RpcMethods.SUBAGENT_INTERRUPT.name)
        assertEquals("subagent.steer", RpcMethods.SUBAGENT_STEER.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.SUBAGENT_INTERRUPT))
        assertTrue(RpcMethods.all.contains(RpcMethods.SUBAGENT_STEER))
    }
}
