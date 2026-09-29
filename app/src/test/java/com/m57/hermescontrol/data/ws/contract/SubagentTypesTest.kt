package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTypesTest {
    @Test
    fun listEncodesOnlySessionId() {
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionIdParams.serializer(), SessionIdParams("s1"))
        assertEquals(buildJsonObject { put("session_id", "s1") }, encoded)
    }

    @Test
    fun tailEncodesExactlyTheTwoRequiredIds() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SubagentTailParams.serializer(),
                SubagentTailParams("s1", "sub-1"),
            )
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("subagent_id", "sub-1")
            },
            encoded,
        )
    }

    @Test
    fun descriptorsAreRegisteredAndPreserveRawResults() {
        assertTrue(RpcMethods.SUBAGENT_LIST in RpcMethods.all)
        assertTrue(RpcMethods.SUBAGENT_TAIL in RpcMethods.all)
        assertEquals("subagent.list", RpcMethods.SUBAGENT_LIST.name)
        assertEquals("subagent.tail", RpcMethods.SUBAGENT_TAIL.name)
        val raw: JsonElement = buildJsonObject { put("future_field", "retained") }
        assertEquals(raw, OkHttpProvider.json.decodeFromJsonElement(RpcMethods.SUBAGENT_LIST.result, raw))
        assertEquals(raw, OkHttpProvider.json.decodeFromJsonElement(RpcMethods.SUBAGENT_TAIL.result, raw))
    }
}
