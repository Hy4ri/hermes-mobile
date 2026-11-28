package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.JsonRpcResponse
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
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
            cardRow(
                id = 42,
                pluginId = "weather",
                cardId = "c1",
                content = "[weather posted a card abc]",
                displayText = "Bring a jacket.",
                directiveName = "forecast",
                attrs = mapOf("city" to "Salt"),
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
            cardRow(
                id = 3,
                pluginId = "notes",
                cardId = "n1",
                content = "fallback body",
                displayText = null,
            )
        val mapped = mapServerMessages("s", listOf(row), 0, true, emptyList()).single()
        assertEquals("fallback body", mapped.content)
        assertEquals("plugin-card-notes-n1", mapped.id)
    }

    @Test
    fun mapServerMessages_ignoresMalformedPluginCardMetadata() {
        val row =
            cardRow(
                id = 4,
                pluginId = " ",
                cardId = "",
                content = "keep me",
                displayText = null,
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
                    cardRow(
                        id = 9,
                        pluginId = "weather",
                        cardId = "c1",
                        content = "[weather posted a card abc]",
                        displayText = "Bring a jacket.",
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

    @Test
    fun hydratedUserRoleCardIsASystemEventAndDedupesAgainstLive() {
        val live =
            ChatWsEventReducer
                .reduce(
                    ChatUiState(currentSessionId = "sess-1"),
                    StreamingState(),
                    WsEvent.PluginCard("sess-1", "weather", "c1", "Bring a jacket."),
                    "sess-1",
                ).state.messages
                .single()
        val hydrated =
            mapServerMessages(
                "sess-1",
                listOf(
                    cardRow(
                        id = 11,
                        pluginId = "weather",
                        cardId = "c1",
                        content = "[weather posted a card abc]",
                        displayText = "Bring a jacket.",
                    ),
                ),
                0,
                true,
                listOf(live),
            ).single()
        assertEquals(MessageRole.SYSTEM, hydrated.role)
        assertNull(hydrated.displayKind)
        val turns = groupIntoTurns(listOf(hydrated))
        val agent = turns.single() as com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn.Agent
        assertTrue(agent.entries.single() is AgentEntry.SystemEvent)
        assertEquals(1, mergeTranscriptWithLive(listOf(hydrated), listOf(live)).size)
    }

    private fun cardRow(
        id: Int,
        pluginId: String,
        cardId: String,
        content: String,
        displayText: String?,
        directiveName: String? = null,
        attrs: Map<String, String> = emptyMap(),
    ): SessionMessage {
        val card =
            buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("plugin_id", JsonPrimitive(pluginId))
                put("card_id", JsonPrimitive(cardId))
                if (directiveName != null) {
                    put(
                        "directive",
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(directiveName),
                                "attrs" to JsonObject(attrs.mapValues { JsonPrimitive(it.value) }),
                            ),
                        ),
                    )
                }
            }
        val meta =
            buildMap<String, kotlinx.serialization.json.JsonElement> {
                if (displayText != null) put("display_text", JsonPrimitive(displayText))
                put("plugin_card", JsonObject(card))
            }
        return SessionMessage(
            id = id,
            role = "user",
            content = JsonPrimitive(content),
            display_kind = "plugin_card",
            display_metadata = JsonObject(meta),
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
