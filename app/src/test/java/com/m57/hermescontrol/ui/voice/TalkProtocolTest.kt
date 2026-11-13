package com.m57.hermescontrol.ui.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TalkProtocolTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    private val ready =
        json(
            """{
        "ok":true,"configured":true,"voiceMode":"native","model":"gpt-realtime-2.1",
        "source":"codex-oauth","mobileRealtime":{"protocol":1},"taskContinuity":{"supported":true}
    }""",
        )

    @Test
    fun `rejects API billing and lookalike subscription strings`() {
        for (source in listOf("configured", "env", "subscription", "not-codex-oauth")) {
            assertThrows(IllegalStateException::class.java) {
                TalkProtocol.validateStatus(JsonObject(ready + ("source" to JsonPrimitive(source))))
            }
        }
    }

    @Test
    fun `rejects live and cascade instead of replacing requested model`() {
        for (mode in listOf("live", "cascade")) {
            assertThrows(IllegalStateException::class.java) {
                TalkProtocol.validateStatus(JsonObject(ready + ("voiceMode" to JsonPrimitive(mode))))
            }
        }
    }

    @Test
    fun `rejects unpatched server before a session can be minted`() {
        assertThrows(IllegalStateException::class.java) {
            TalkProtocol.validateStatus(
                JsonObject(ready - "mobileRealtime"),
            )
        }
    }

    @Test
    fun `same bot label cannot redirect session or profile`() {
        val catalog =
            json(
                """{"ok":true,"targets":[
            {"label":"Bot","target_id":"a","peer_id":"local","profile":"alpha","session_id":"session-a"},
            {"label":"Bot","target_id":"b","peer_id":"local","profile":"beta","session_id":"session-b"},
            {"label":"Bot","target_id":"default","peer_id":"local","profile":"default","session_id":"main"}
        ]}""",
            )
        assertEquals("a", TalkProtocol.exactTarget(catalog, "alpha", "session-a"))
        assertEquals("b", TalkProtocol.exactTarget(catalog, "beta", "session-b"))
        assertEquals("default", TalkProtocol.exactTarget(catalog, "default", "main"))
        assertThrows(IllegalStateException::class.java) { TalkProtocol.exactTarget(catalog, "alpha", "session-b") }
    }

    @Test
    fun `missing or ambiguous target never falls back to default`() {
        val row = """{"target_id":"a","peer_id":"local","profile":"alpha","session_id":"session-a"}"""
        for (rows in listOf("", "$row,$row")) {
            assertThrows(IllegalStateException::class.java) {
                TalkProtocol.exactTarget(json("""{"ok":true,"targets":[$rows]}"""), "alpha", "session-a")
            }
        }
    }

    @Test
    fun `server binding cannot switch to another bot or peer`() {
        val session =
            json(
                """{
            "ok":true,"voiceMode":"native","model":"gpt-realtime-2.1","authSource":"codex-oauth",
            "mobileRealtime":{"protocol":1},"clientSecret":"ephemeral-test",
            "task":{"target_id":"a","tab_id":"tab","profile":"alpha","session_id":"session-a",
            "peer_id":"local","connection_id":"connection-a","generation":1}
        }""",
            )
        TalkProtocol.validateSession(session, "a", "tab", "alpha", "session-a")
        assertThrows(IllegalStateException::class.java) {
            TalkProtocol.validateSession(session, "b", "tab", "beta", "session-b")
        }
        val foreign = JsonObject(session.obj("task") + ("peer_id" to JsonPrimitive("other-host")))
        assertThrows(IllegalStateException::class.java) {
            TalkProtocol.validateSession(JsonObject(session + ("task" to foreign)), "a", "tab", "alpha", "session-a")
        }
    }
}
