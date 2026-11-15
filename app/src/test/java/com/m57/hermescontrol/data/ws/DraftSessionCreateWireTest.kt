package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionCreateParams
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings picked in a new-chat draft ride `session.create`. The legacy route takes
 * `reasoning_effort` / `fast`; the session authority's canonical `session.create` is a closed
 * key set that names them `reasoning` / `service_tier`.
 */
class DraftSessionCreateWireTest {
    private val params = SessionCreateParams(source = "desktop", reasoningEffort = "high", fast = true)

    @Test
    fun `authority route uses canonical reasoning and service tier`() =
        runTest {
            val channel = RpcChannel(this, Json, { _, _, _ -> "" }, { true }, {}, {})
            val tracker = ReplayTracker(this, { SessionEventsSinceResult() }, {})
            channel.handleIncomingFrame(
                """{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""",
                tracker,
            ) {}

            val request =
                channel.createRequest(
                    WsMethods.SESSION_CREATE,
                    channel.encodeParams(RpcMethods.SESSION_CREATE, params),
                )

            assertEquals(
                "high",
                request.params
                    .getValue("reasoning")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "fast",
                request.params
                    .getValue("service_tier")
                    .jsonPrimitive.content,
            )
            assertNull(request.params["reasoning_effort"])
            assertNull(request.params["fast"])
        }

    @Test
    fun `legacy route keeps its own names`() =
        runTest {
            val channel = RpcChannel(this, Json, { _, _, _ -> "" }, { true }, {}, {})

            val request =
                channel.createRequest(
                    WsMethods.SESSION_CREATE,
                    channel.encodeParams(RpcMethods.SESSION_CREATE, params),
                )

            assertEquals(
                "high",
                request.params
                    .getValue("reasoning_effort")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "true",
                request.params
                    .getValue("fast")
                    .jsonPrimitive.content,
            )
            assertNull(request.params["service_tier"])
        }
}
