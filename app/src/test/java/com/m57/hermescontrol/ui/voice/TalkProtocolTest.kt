package com.m57.hermescontrol.ui.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class TalkProtocolTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    private val ready =
        json(
            """{"ok":true,"configured":true,"voiceMode":"native","model":"gpt-realtime-2.1",
        "source":"codex-oauth","taskContinuity":{"supported":false}}""",
        )
    private val session =
        json(
            """{"ok":true,"voiceMode":"native","model":"gpt-realtime-2.1","authSource":"codex-oauth",
        "clientSecret":"ephemeral-test","offerUrl":"https://api.openai.com/v1/realtime/calls"}""",
        )

    @Test
    fun `API keys and lookalike subscription sources cannot authorize voice`() {
        for (source in listOf("configured", "env", "subscription", "not-codex-oauth")) {
            assertThrows(TalkSetupException::class.java) {
                TalkProtocol.validateStatus(JsonObject(ready + ("source" to JsonPrimitive(source))))
            }
        }
    }

    @Test
    fun `live and cascade cannot silently replace native realtime`() {
        for (mode in listOf("live", "cascade")) {
            assertThrows(TalkSetupException::class.java) {
                TalkProtocol.validateStatus(JsonObject(ready + ("voiceMode" to JsonPrimitive(mode))))
            }
        }
        assertThrows(TalkSetupException::class.java) {
            TalkProtocol.validateStatus(JsonObject(ready + ("model" to JsonPrimitive("gpt-live-1-codex"))))
        }
    }

    @Test
    fun `unavailable subscription cannot proceed to negotiation`() {
        assertThrows(TalkSetupException::class.java) {
            TalkProtocol.validateStatus(JsonObject(ready + ("configured" to JsonPrimitive(false))))
        }
    }

    @Test
    fun `minted session must still declare Codex OAuth`() {
        for (source in listOf("configured", "env", "")) {
            assertThrows(TalkSetupException::class.java) {
                TalkProtocol.validateSession(JsonObject(session + ("authSource" to JsonPrimitive(source))))
            }
        }
    }

    @Test
    fun `negotiation cannot use a missing or empty ephemeral credential`() {
        assertThrows(
            TalkSetupException::class.java,
        ) { TalkProtocol.validateSession(JsonObject(session - "clientSecret")) }
        assertThrows(TalkSetupException::class.java) {
            TalkProtocol.validateSession(JsonObject(session + ("clientSecret" to JsonPrimitive(""))))
        }
    }

    @Test
    fun `provider descriptor cannot replace the requested model`() {
        assertThrows(TalkSetupException::class.java) {
            TalkProtocol.validateSession(JsonObject(session + ("model" to JsonPrimitive("gpt-realtime-mini"))))
        }
    }
}
