package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.JsonRpcResponse
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCardContractTest {
    @Test
    fun eventParser_readsPluginCardAndDropsBlankIdentity() {
        val parsed = EventParser.parse(event(validPayload()))
        val card = parsed as WsEvent.PluginCard
        assertEquals("sess-1", card.sessionId)
        assertEquals("weather", card.pluginId)
        assertEquals("c1", card.cardId)
        assertEquals("Bring a jacket.", card.text)
        assertEquals("forecast", card.directiveName)
        assertEquals(mapOf("city" to "Salt"), card.directiveAttrs)

        assertTrue(EventParser.parse(event(validPayload(pluginId = "  "))) is WsEvent.Unknown)
        assertTrue(EventParser.parse(event(validPayload(cardId = ""))) is WsEvent.Unknown)
        assertTrue(EventParser.parse(event(validPayload(text = " "))) is WsEvent.Unknown)
    }

    @Test
    fun mapServerMessages_hydratesDisplayTextAndStableId() {
        val row =
            SessionMessage(
                id = 42,
                role = "system",
                content = JsonPrimitive("[weather posted a card abc]"),
                display_metadata =
                    JsonObject(
                        mapOf(
                            "display_kind" to JsonPrimitive("plugin_card"),
                            "display_text" to JsonPrimitive("Bring a jacket."),
                            "plugin_card" to
                                JsonObject(
                                    mapOf(
                                        "plugin_id" to JsonPrimitive("weather"),
                                        "card_id" to JsonPrimitive("c1"),
                                        "directive" to
                                            JsonObject(
                                                mapOf(
                                                    "name" to JsonPrimitive("forecast"),
                                                    "attrs" to JsonObject(mapOf("city" to JsonPrimitive("Salt"))),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )
        val mapped = mapServerMessages("sess-1", listOf(row), 0, true, emptyList()).single()
        assertEquals("plugin-card-weather-c1", mapped.id)
        assertEquals(MessageRole.SYSTEM, mapped.role)
        assertEquals("Bring a jacket.", mapped.content)
        assertNull(mapped.displayKind)
        assertEquals("weather", mapped.pluginCard?.pluginId)
        assertEquals("c1", mapped.pluginCard?.cardId)
        assertEquals("forecast", mapped.pluginCard?.directiveName)
        assertEquals(mapOf("city" to "Salt"), mapped.pluginCard?.directiveAttrs)
    }

    @Test
    fun mapServerMessages_fallsBackToContentWhenDisplayTextMissing() {
        val row =
            SessionMessage(
                id = 3,
                role = "system",
                content = JsonPrimitive("fallback body"),
                display_metadata =
                    JsonObject(
                        mapOf(
                            "display_kind" to JsonPrimitive("plugin_card"),
                            "plugin_card" to
                                JsonObject(
                                    mapOf(
                                        "plugin_id" to JsonPrimitive("notes"),
                                        "card_id" to JsonPrimitive("n1"),
                                    ),
                                ),
                        ),
                    ),
            )
        val mapped = mapServerMessages("s", listOf(row), 0, true, emptyList()).single()
        assertEquals("fallback body", mapped.content)
        assertEquals("plugin-card-notes-n1", mapped.id)
    }

    @Test
    fun mapServerMessages_ignoresMalformedPluginCardMetadata() {
        val row =
            SessionMessage(
                id = 4,
                role = "system",
                content = JsonPrimitive("keep me"),
                display_metadata =
                    JsonObject(
                        mapOf(
                            "display_kind" to JsonPrimitive("plugin_card"),
                            "plugin_card" to JsonObject(mapOf("plugin_id" to JsonPrimitive(" "))),
                        ),
                    ),
            )
        val mapped = mapServerMessages("s", listOf(row), 0, true, emptyList()).single()
        assertNull(mapped.pluginCard)
        assertEquals("keep me", mapped.content)
    }

    @Test
    fun liveCardDedupesAgainstHistoryByCardIdentity() {
        val live =
            ChatWsEventReducer
                .reduce(
                    ChatUiState(currentSessionId = "sess-1"),
                    StreamingState(),
                    WsEvent.PluginCard(
                        "sess-1",
                        "weather",
                        "c1",
                        "Bring a jacket.",
                        "forecast",
                        mapOf("city" to "Salt"),
                    ),
                    "sess-1",
                ).state.messages
                .single()
        val history =
            mapServerMessages(
                "sess-1",
                listOf(
                    SessionMessage(
                        id = 9,
                        role = "system",
                        content = JsonPrimitive("[weather posted a card abc]"),
                        display_metadata =
                            JsonObject(
                                mapOf(
                                    "display_kind" to JsonPrimitive("plugin_card"),
                                    "display_text" to JsonPrimitive("Bring a jacket."),
                                    "plugin_card" to
                                        JsonObject(
                                            mapOf(
                                                "plugin_id" to JsonPrimitive("weather"),
                                                "card_id" to JsonPrimitive("c1"),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
                0,
                true,
                listOf(live),
            )
        val merged = mergeTranscriptWithLive(history, listOf(live))
        assertEquals(1, merged.size)
        assertEquals("Bring a jacket.", merged.single().content)
        assertEquals("c1", merged.single().pluginCard?.cardId)
    }

    @Test
    fun reducer_replacesExistingCardInsteadOfAppending() {
        val first =
            ChatWsEventReducer.reduce(
                ChatUiState(),
                StreamingState(),
                WsEvent.PluginCard(null, "weather", "c1", "old"),
                null,
            )
        val second =
            ChatWsEventReducer.reduce(
                first.state,
                StreamingState(),
                WsEvent.PluginCard(null, "weather", "c1", "new"),
                null,
            )
        assertEquals(listOf("new"), second.state.messages.map { it.content })
        assertEquals(
            "plugin-card-weather-c1",
            second.state.messages
                .single()
                .id,
        )
    }

    private fun event(payload: Map<String, Any?>): JsonRpcResponse =
        JsonRpcResponse(
            jsonrpc = "2.0",
            id = null,
            result = null,
            error = null,
            method = "event",
            params =
                mapOf(
                    "type" to "plugin.card",
                    "session_id" to "sess-1",
                    "payload" to payload,
                ).toJsonElement() as JsonObject,
        )

    private fun validPayload(
        pluginId: String = "weather",
        cardId: String = "c1",
        text: String = "Bring a jacket.",
    ): Map<String, Any?> =
        mapOf(
            "plugin_id" to pluginId,
            "card_id" to cardId,
            "text" to text,
            "directive" to mapOf("name" to "forecast", "attrs" to mapOf("city" to "Salt")),
        )
}
