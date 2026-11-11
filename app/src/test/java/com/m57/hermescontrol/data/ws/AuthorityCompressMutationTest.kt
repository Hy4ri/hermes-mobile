package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionCompressParams
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorityCompressMutationTest {
    @Test
    fun `authority compression is fenced and retains id until settled`() =
        runTest {
            val channel = RpcChannel(this, Json, { _, _, _ -> "" }, { true }, {}, {})
            val tracker = ReplayTracker(this, { SessionEventsSinceResult() }, {})

            fun frame(text: String) = channel.handleIncomingFrame(text, tracker) {}

            fun request() =
                channel.createRequest(
                    WsMethods.SESSION_COMPRESS,
                    channel.encodeParams(RpcMethods.SESSION_COMPRESS, SessionCompressParams("s1", "--preview")),
                )
            assertEquals(WsMethods.SESSION_COMPRESS, request().method)
            frame("""{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
            frame(
                """{"method":"event","params":{"type":"session.info","session_id":"s1","seq":1,""" +
                    """"payload":{"revision":7,"execution_generation":2}}}""",
            )
            val first = request()
            assertEquals(WsMethods.SESSION_MUTATE, first.method)
            assertEquals(
                "compress",
                first.params
                    .getValue("operation")
                    .jsonPrimitive.content,
            )
            assertEquals(JsonObject(mapOf("focus" to JsonPrimitive("--preview"))), first.params["payload"])
            assertEquals(
                "7",
                first.params
                    .getValue("expected_revision")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "2",
                first.params
                    .getValue("expected_generation")
                    .jsonPrimitive.content,
            )
            assertEquals(first.params["request_id"], request().params["request_id"])
            frame("""{"id":"${first.id}","result":{"status":"preview","lines":["report"]}}""")
            assertNotEquals(first.params["request_id"], request().params["request_id"])
        }

    @Test
    fun `structured options require both fences and remain in payload`() {
        val mutations = SessionMutations()
        val params =
            buildJsonObject {
                put("session_id", "s1")
                put("focus_topic", "decisions")
                put("preview", true)
                put("partial", true)
                put("keep_last", 3)
            }
        assertNull(mutations.prepare(WsMethods.SESSION_COMPRESS, params, 4))
        mutations.adoptEvent("session.info", "s1", buildJsonObject { put("revision", 9) })
        assertNull(mutations.prepare(WsMethods.SESSION_COMPRESS, params, null))
        val wire = requireNotNull(mutations.prepare(WsMethods.SESSION_COMPRESS, params, 4))
        assertEquals(
            buildJsonObject {
                put("focus", "decisions")
                put("preview", true)
                put("partial", true)
                put("keep_last", 3)
            },
            wire["payload"],
        )
    }
}
