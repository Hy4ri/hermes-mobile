package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingEventParserTest {
    private val types = listOf("message.token", "message.delta", "thinking.delta", "reasoning.delta")

    @Test
    fun `streaming parsing matches map parser for wire field variants`() {
        val sessions = listOf("\"outer\"", "\"\"", "null", "42", "true", "{}", "[]")
        val payloads =
            listOf(
                "null",
                "[]",
                "42",
                "{}",
                """{"session_id":"inner","text":"hello 🌙\n"}""",
                """{"session_id":null,"text":null}""",
                """{"session_id":false,"text":123}""",
                """{"session_id":[],"text":true}""",
                """{"session_id":{},"text":{}}""",
                """{"text":[]}""",
            )
        for (type in types) {
            for (session in sessions + null) {
                for (payload in payloads + null) {
                    val fields = mutableListOf("\"type\":\"$type\"")
                    if (session != null) fields.add("\"session_id\":$session")
                    if (payload != null) fields.add("\"payload\":$payload")
                    val params = Json.parseToJsonElement("{${fields.joinToString()}}") as JsonObject

                    @Suppress("UNCHECKED_CAST")
                    val legacy = EventParser.parseParams(params.toAny() as Map<String, Any?>)
                    assertEquals(
                        params.toString(),
                        legacy,
                        EventParser.parse(JsonRpcResponse(method = "event", params = params)),
                    )
                }
            }
        }
    }

    @Test
    fun `streaming parsing never traverses params or payload maps`() {
        for (type in types) {
            val payload = lookupOnlyObject(mapOf("text" to JsonPrimitive("chunk")))
            val params =
                lookupOnlyObject(
                    mapOf(
                        "type" to JsonPrimitive(type),
                        "session_id" to JsonPrimitive("session"),
                        "payload" to payload,
                    ),
                )
            val expected =
                when (type) {
                    "thinking.delta" -> WsEvent.ThinkingDelta("chunk", "session")
                    "reasoning.delta" -> WsEvent.ReasoningDelta("chunk", "session")
                    else -> WsEvent.MessageToken("chunk", "session")
                }
            assertEquals(expected, EventParser.parse(JsonRpcResponse(method = "event", params = params)))
        }
    }

    private fun lookupOnlyObject(fields: Map<String, JsonElement>): JsonObject =
        JsonObject(
            object : Map<String, JsonElement> by fields {
                override val entries: Set<Map.Entry<String, JsonElement>>
                    get() = error("Streaming path must look up fields, not traverse the JSON tree (#1163)")
            },
        )
}
