package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import com.m57.hermescontrol.data.ws.contract.SessionIdParams
import com.m57.hermescontrol.data.ws.contract.SessionTitleParams
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The profile key as it goes out on the wire of the session-authority route: the real
 * `encodeParams` -> `createRequest` path, with `WsProfileParams` as the decorator.
 */
class RpcChannelProfileWireTest {
    @Before
    fun setUp() = AuthManager.resetAuthStateForTest()

    @After
    fun tearDown() = AuthManager.resetAuthStateForTest()

    private class Rig(
        scope: TestScope,
    ) {
        val channel =
            RpcChannel(
                scope,
                Json,
                { _, _, _ -> "" },
                { true },
                {},
                {},
                decorateParams = { method, params -> WsProfileParams.decorate(method, params) },
            )
        val tracker = ReplayTracker(scope, { SessionEventsSinceResult() }, {})

        fun frame(text: String) = channel.handleIncomingFrame(text, tracker) {}

        fun usage() =
            channel.createRequest(
                WsMethods.SESSION_USAGE,
                channel.encodeParams(RpcMethods.SESSION_USAGE, SessionIdParams("s1")),
            )

        fun title() =
            channel.createRequest(
                WsMethods.SESSION_TITLE,
                channel.encodeParams(RpcMethods.SESSION_TITLE, SessionTitleParams("s1", "New")),
            )

        fun openAuthoritySession() {
            frame("""{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
            frame(
                """{"method":"event","params":{"type":"session.info","session_id":"s1","seq":1,""" +
                    """"payload":{"revision":7}}}""",
            )
        }
    }

    @Test
    fun `usage goes out as session stats with the active profile`() =
        runTest {
            AuthManager.setActiveProfileId("hermisa")
            val rig = Rig(this)
            rig.openAuthoritySession()

            val request = rig.usage()

            assertEquals(WsMethods.SESSION_STATS, request.method)
            assertEquals(JsonPrimitive("hermisa"), request.params["profile"])
            assertEquals(JsonPrimitive("s1"), request.params["session_id"])
        }

    @Test
    fun `title edit goes out as session mutate with the profile and settles`() =
        runTest {
            AuthManager.setActiveProfileId("hermisa")
            val rig = Rig(this)
            rig.openAuthoritySession()

            val first = rig.title()

            assertEquals(WsMethods.SESSION_MUTATE, first.method)
            assertEquals(
                setOf("session_id", "request_id", "expected_revision", "operation", "payload", "profile"),
                first.params.keys,
            )
            assertEquals(JsonPrimitive("hermisa"), first.params["profile"])
            assertEquals(JsonPrimitive("rename"), first.params["operation"])
            assertEquals(JsonPrimitive(7), first.params["expected_revision"])
            // The request is retained until its reply arrives, with the same id on a repeat.
            assertEquals(first.params["request_id"], rig.title().params["request_id"])

            val receipt = """{"session_id":"s1","operation":"rename","revision":8,"title":"New"}"""
            rig.frame("""{"id":"${first.id}","result":$receipt}""")

            val next = rig.title()
            assertNotEquals(first.params["request_id"], next.params["request_id"])
            assertEquals(JsonPrimitive(8), next.params["expected_revision"])
            assertEquals(JsonPrimitive("hermisa"), next.params["profile"])
        }

    @Test
    fun `without an active profile no profile key is sent`() =
        runTest {
            AuthManager.setActiveProfileId(null)
            val rig = Rig(this)
            rig.openAuthoritySession()

            val usage = rig.usage()
            val title = rig.title()

            assertEquals(WsMethods.SESSION_STATS, usage.method)
            assertNull(usage.params["profile"])
            assertEquals(WsMethods.SESSION_MUTATE, title.method)
            assertNull(title.params["profile"])
        }
}
