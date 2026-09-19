package com.m57.hermescontrol.data.ws

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #1163: execute the wire parser on ART, without backend credentials or model calls. */
@RunWith(AndroidJUnit4::class)
class StreamingEventDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun streamingBurstPreservesEveryChunkAndSessionOnDevice() {
        val types = listOf("message.token", "message.delta", "thinking.delta", "reasoning.delta")
        for (type in types) {
            val actual = StringBuilder()
            val expected = StringBuilder()
            repeat(1000) { seq ->
                val frame =
                    """{"method":"event","params":{"type":"$type","seq":$seq,""" +
                        """"payload":{"session_id":"device-test","text":"chunk-$seq ../ \uD83C\uDF19\n",""" +
                        """"extra":{"nested":[{"text":"ignored","path":"../ignored"}]}}}}"""
                val response = Json.decodeFromString<JsonRpcResponse>(frame)
                val event = EventParser.parse(response, frame)
                val (token, session) =
                    when (event) {
                        is WsEvent.MessageToken -> event.token to event.sessionId
                        is WsEvent.ThinkingDelta -> event.token to event.sessionId
                        is WsEvent.ReasoningDelta -> event.token to event.sessionId
                        else -> error("Unexpected streaming event: $event")
                    }
                assertEquals("device-test", session)
                actual.append(token)
                expected.append("chunk-$seq ../ 🌙\n")
            }
            assertEquals(type, expected.toString(), actual.toString())
        }
    }

    @Test
    fun reasoningFastPathAvoidsContainerTraversalAndRendersOnDevice() {
        val fields =
            mapOf<String, JsonElement>(
                "type" to JsonPrimitive("reasoning.delta"),
                "payload" to JsonObject(mapOf("text" to JsonPrimitive("Device streaming verified"))),
            )
        val params =
            JsonObject(
                object : Map<String, JsonElement> by fields {
                    override val entries: Set<Map.Entry<String, JsonElement>>
                        get() = error("Streaming parser traversed the params tree")
                },
            )
        val event = EventParser.parse(JsonRpcResponse(method = "event", params = params)) as WsEvent.ReasoningDelta
        compose.setContent {
            HermesControlTheme {
                ReasoningCard(reasoningText = event.token)
            }
        }
        compose.onNodeWithTag("reasoning_card").performClick()
        compose.onNodeWithText("Device streaming verified").assertIsDisplayed()
    }
}
