package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.ConfigSetParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-authority gateway (hermes-agent 0.23.x) refuses `config.set key=model`
 * (`use_session_mutation_model`): a model pick there is `session.mutate operation=model`
 * with `payload {model, provider, confirm}`, fenced by revision and generation.
 */
class AuthorityModelMutationTest {
    private fun TestScope.authorityChannel(events: MutableList<WsEvent>): Pair<RpcChannel, (String) -> Unit> {
        val channel = RpcChannel(this, Json, { _, _, _ -> "" }, { true }, {}, {})
        val tracker = ReplayTracker(this, { SessionEventsSinceResult() }, {})
        val frame: (String) -> Unit = { text -> channel.handleIncomingFrame(text, tracker) { events += it } }
        frame("""{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
        frame(
            """{"method":"event","params":{"type":"session.info","session_id":"s1","seq":1,""" +
                """"payload":{"revision":5,"execution_generation":3}}}""",
        )
        return channel to frame
    }

    private fun RpcChannel.modelRequest(
        value: String,
        confirm: Boolean = false,
        key: String = "model",
    ) = createRequest(
        WsMethods.CONFIG_SET,
        encodeParams(
            RpcMethods.CONFIG_SET,
            ConfigSetParams(
                key = key,
                value = value,
                sessionId = "s1",
                confirmExpensiveModel = true.takeIf { confirm },
            ),
        ),
    )

    @Test
    fun `picker selection becomes a fenced model mutation`() =
        runTest {
            val (channel, _) = authorityChannel(mutableListOf())

            val request = channel.modelRequest("deepseek-flash --provider deepseek --session")

            assertEquals(WsMethods.SESSION_MUTATE, request.method)
            assertEquals(
                "model",
                request.params
                    .getValue("operation")
                    .jsonPrimitive.content,
            )
            assertEquals(
                JsonObject(mapOf("model" to JsonPrimitive("deepseek-flash"), "provider" to JsonPrimitive("deepseek"))),
                request.params["payload"],
            )
            assertEquals(
                "5",
                request.params
                    .getValue("expected_revision")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "3",
                request.params
                    .getValue("expected_generation")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "s1",
                request.params
                    .getValue("session_id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `other config keys, unknown flags and the legacy route stay config set`() =
        runTest {
            val (channel, _) = authorityChannel(mutableListOf())
            assertEquals(WsMethods.CONFIG_SET, channel.modelRequest("on", key = "verbose").method)
            // --global changes what the pick means (profile, not session): it stays the old request.
            assertEquals(WsMethods.CONFIG_SET, channel.modelRequest("gpt-5 --global").method)

            val legacy = RpcChannel(this, Json, { _, _, _ -> "" }, { true }, {}, {})
            assertEquals(WsMethods.CONFIG_SET, legacy.modelRequest("gpt-5 --provider openai --session").method)
        }

    @Test
    fun `reset is sent as the model reset token`() =
        runTest {
            val (channel, _) = authorityChannel(mutableListOf())
            val request = channel.modelRequest("reset")
            assertEquals(WsMethods.SESSION_MUTATE, request.method)
            assertEquals(JsonObject(mapOf("model" to JsonPrimitive("reset"))), request.params["payload"])
        }

    @Test
    fun `confirmation is surfaced as config set shape and the retry presents the token`() =
        runTest {
            val events = mutableListOf<WsEvent>()
            val (channel, frame) = authorityChannel(events)

            val first = channel.modelRequest("opus --provider anthropic --session")
            frame(
                """{"id":"${first.id}","result":{"session_id":"s1","operation":"model",""" +
                    """"status":"confirmation_required","confirm_required":true,"confirm":"tok-1",""" +
                    """"confirm_message":"Expensive model"}}""",
            )
            val result = events.filterIsInstance<WsEvent.RpcResult>().single { it.id == first.id }.result as Map<*, *>
            assertEquals("model", result["key"])
            assertEquals(true, result["confirm_required"])
            assertEquals("Expensive model", result["confirm_message"])

            // The user confirmed: the same pick goes out with the token, as a new request.
            val confirmed = channel.modelRequest("opus --provider anthropic --session", confirm = true)
            val payload = confirmed.params["payload"] as JsonObject
            assertEquals("tok-1", payload.getValue("confirm").jsonPrimitive.content)
            assertTrue(confirmed.params["request_id"] != first.params["request_id"])
        }

    @Test
    fun `prepare without a known revision falls back to config set`() {
        val mutations = SessionMutations()
        val params =
            JsonObject(
                mapOf(
                    "key" to JsonPrimitive("model"),
                    "value" to JsonPrimitive("gpt-5 --provider openai --session"),
                    "session_id" to JsonPrimitive("s1"),
                ),
            )
        assertNull(mutations.prepare(WsMethods.CONFIG_SET, params, 1))
    }
}
