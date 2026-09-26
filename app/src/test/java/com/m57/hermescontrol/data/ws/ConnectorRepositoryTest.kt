package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorListResult
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectorRepositoryTest {
    @Test
    fun `exact method constants match upstream gateway expectations`() {
        assertEquals("connectors.list", WsMethods.CONNECTORS_LIST)
        assertEquals("connectors.connect", WsMethods.CONNECTORS_CONNECT)
    }

    @Test
    fun `listConnectors sends exact parameters without extra profile injection`() =
        runBlocking {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val repo =
                HermesConnectorRepository(
                    rpcRequest = { method, params ->
                        recordedMethod = method
                        recordedParams = params
                        mapOf("available" to true, "connectors" to emptyList<Map<String, Any>>())
                    },
                )

            val result = repo.listConnectors("sess-abc-123")
            assertTrue(result is ConnectorListResult.Success)
            assertEquals(WsMethods.CONNECTORS_LIST, recordedMethod)
            assertEquals(
                mapOf("owner" to mapOf("type" to "session", "session_id" to "sess-abc-123")),
                recordedParams,
            )
            // Verify no profile or singular slug mutations
            assertFalse(recordedParams?.containsKey("profile") == true)
            assertFalse(recordedParams?.containsKey("connector") == true)
        }

    @Test
    fun `connect sends exact parameters with connectors array and reconnect boolean`() =
        runBlocking {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val repo =
                HermesConnectorRepository(
                    rpcRequest = { method, params ->
                        recordedMethod = method
                        recordedParams = params
                        mapOf(
                            "results" to
                                listOf(
                                    mapOf("connector" to "linear", "status" to "initiated"),
                                ),
                            "summary" to mapOf("total" to 1, "initiated" to 1),
                        )
                    },
                )

            val result =
                repo.connect(
                    sessionId = "sess-456",
                    connectors = listOf("linear", "github_app-1"),
                    reconnect = true,
                )

            assertTrue(result is ConnectorConnectResult.Success)
            assertEquals(WsMethods.CONNECTORS_CONNECT, recordedMethod)
            assertEquals(
                mapOf(
                    "owner" to mapOf("type" to "session", "session_id" to "sess-456"),
                    "connectors" to listOf("linear", "github_app-1"),
                    "reconnect" to true,
                ),
                recordedParams,
            )

            // Verify no singular slug key mutation
            assertFalse(recordedParams?.containsKey("connector") == true)
        }

    @Test
    fun `connect preserves reconnect false default`() =
        runBlocking {
            var recordedParams: Map<String, Any>? = null

            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, params ->
                        recordedParams = params
                        mapOf(
                            "results" to
                                listOf(
                                    mapOf("connector" to "slack", "status" to "active"),
                                ),
                            "summary" to mapOf("total" to 1, "active" to 1),
                        )
                    },
                )

            repo.connect(sessionId = "sess-789", connectors = listOf("slack"))
            assertEquals(false, recordedParams?.get("reconnect"))
        }

    @Test
    fun `listConnectors validates non-blank session_id before network`() =
        runBlocking {
            var rpcCalled = false
            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        rpcCalled = true
                        null
                    },
                )

            val r1 = repo.listConnectors("")
            val r2 = repo.listConnectors("   ")

            assertFalse(rpcCalled)
            assertTrue(r1 is ConnectorListResult.Error)
            assertTrue((r1 as ConnectorListResult.Error).error is ConnectorError.InvalidParams)
            assertTrue(r2 is ConnectorListResult.Error)
            assertTrue((r2 as ConnectorListResult.Error).error is ConnectorError.InvalidParams)
        }

    @Test
    fun `connect validates session_id and connectors list before network`() =
        runBlocking {
            var rpcCalled = false
            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        rpcCalled = true
                        null
                    },
                )

            // Blank session_id
            val r1 = repo.connect(sessionId = "", connectors = listOf("linear"))
            assertFalse(rpcCalled)
            assertTrue(r1 is ConnectorConnectResult.Error)
            assertTrue((r1 as ConnectorConnectResult.Error).error is ConnectorError.InvalidParams)

            // Empty connectors
            val r2 = repo.connect(sessionId = "sess-1", connectors = emptyList())
            assertFalse(rpcCalled)
            assertTrue(r2 is ConnectorConnectResult.Error)
            assertTrue((r2 as ConnectorConnectResult.Error).error is ConnectorError.InvalidParams)
        }

    @Test
    fun `connect validates slug pattern matches backend expectations`() =
        runBlocking {
            var rpcCalled = false
            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        rpcCalled = true
                        null
                    },
                )

            // Invalid slugs: uppercase, starting with hyphen/underscore, containing spaces/symbols
            val invalidSlugs =
                listOf(
                    "Linear", // uppercase
                    "-linear", // leading hyphen
                    "_linear", // leading underscore
                    "linear app", // space
                    "linear/app", // slash
                    "linear@app", // symbol
                    "", // empty
                )

            for (invalidSlug in invalidSlugs) {
                rpcCalled = false
                val result = repo.connect(sessionId = "sess-1", connectors = listOf(invalidSlug))
                assertFalse("Expected RPC not to be called for invalid slug '$invalidSlug'", rpcCalled)
                assertTrue("Expected InvalidParams error for '$invalidSlug'", result is ConnectorConnectResult.Error)
                assertTrue((result as ConnectorConnectResult.Error).error is ConnectorError.InvalidParams)
            }

            // Valid slugs
            assertTrue(ConnectorRepository.isValidSlug("gmail"))
            assertTrue(ConnectorRepository.isValidSlug("google_drive"))
            assertTrue(ConnectorRepository.isValidSlug("jira-service-desk"))
            assertTrue(ConnectorRepository.isValidSlug("0auth_provider"))
        }

    @Test
    fun `cancellation exception rethrows and is not swallowed`() =
        runBlocking {
            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw CancellationException("coroutine cancelled")
                    },
                )

            try {
                repo.listConnectors("sess-1")
                fail("Expected CancellationException to be rethrown on listConnectors")
            } catch (e: CancellationException) {
                assertEquals("coroutine cancelled", e.message)
            }

            try {
                repo.connect("sess-1", listOf("linear"))
                fail("Expected CancellationException to be rethrown on connect")
            } catch (e: CancellationException) {
                assertEquals("coroutine cancelled", e.message)
            }
        }

    @Test
    fun `maps HermesRpcException to typed errors`() =
        runBlocking {
            // 4031 CONNECTORS_UNAVAILABLE
            val repo4031 =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw HermesWsClient.HermesRpcException(
                            message = "Connectors unavailable",
                            code = 4031,
                            data = JsonObject(mapOf("reason" to JsonPrimitive("CONNECTORS_UNAVAILABLE"))),
                        )
                    },
                )
            val r4031 = repo4031.listConnectors("sess-1")
            assertTrue(r4031 is ConnectorListResult.Error)
            assertTrue((r4031 as ConnectorListResult.Error).error is ConnectorError.Unavailable)

            // 4001 NOT_OWNER
            val repo4001 =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw HermesWsClient.HermesRpcException(
                            message = "Session not owned",
                            code = 4001,
                            data = JsonObject(mapOf("reason" to JsonPrimitive("NOT_OWNER"))),
                        )
                    },
                )
            val r4001 = repo4001.connect("sess-1", listOf("linear"))
            assertTrue(r4001 is ConnectorConnectResult.Error)
            assertTrue((r4001 as ConnectorConnectResult.Error).error is ConnectorError.NotOwner)

            // 5033 UNSUPPORTED_RUNTIME
            val repo5033 =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw HermesWsClient.HermesRpcException(
                            message = "Compute host required",
                            code = 5033,
                            data = JsonObject(mapOf("reason" to JsonPrimitive("UNSUPPORTED_RUNTIME"))),
                        )
                    },
                )
            val r5033 = repo5033.connect("sess-1", listOf("linear"))
            assertTrue(r5033 is ConnectorConnectResult.Error)
            assertTrue((r5033 as ConnectorConnectResult.Error).error is ConnectorError.UnsupportedRuntime)

            // 5034 INVALID_CONNECTOR_RESPONSE
            val repo5034 =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw HermesWsClient.HermesRpcException(
                            message = "Invalid connector response",
                            code = 5034,
                            data = JsonObject(mapOf("reason" to JsonPrimitive("INVALID_CONNECTOR_RESPONSE"))),
                        )
                    },
                )
            val r5034 = repo5034.connect("sess-1", listOf("linear"))
            assertTrue(r5034 is ConnectorConnectResult.Error)
            assertTrue((r5034 as ConnectorConnectResult.Error).error is ConnectorError.InvalidResponse)

            // -32601 Unsupported backend
            val repo32601 =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw HermesWsClient.HermesRpcException(
                            message = "Method not found",
                            code = -32601,
                            data = null,
                        )
                    },
                )
            val r32601 = repo32601.listConnectors("sess-1")
            assertTrue(r32601 is ConnectorListResult.Error)
            assertTrue((r32601 as ConnectorListResult.Error).error is ConnectorError.UnsupportedBackend)
        }

    @Test
    fun `generic network exception maps to typed NetworkError`() =
        runBlocking {
            val repo =
                HermesConnectorRepository(
                    rpcRequest = { _, _ ->
                        throw java.io.IOException("Connection reset by peer")
                    },
                )

            val rList = repo.listConnectors("sess-1")
            assertTrue(rList is ConnectorListResult.Error)
            assertTrue((rList as ConnectorListResult.Error).error is ConnectorError.NetworkError)

            val rConnect = repo.connect("sess-1", listOf("linear"))
            assertTrue(rConnect is ConnectorConnectResult.Error)
            assertTrue((rConnect as ConnectorConnectResult.Error).error is ConnectorError.NetworkError)
        }

    @Test
    fun `default rpcRequest cancels underlying deferred on caller cancellation for listConnectors`() =
        runBlocking {
            mockkObject(HermesWsClient)
            try {
                val deferred = CompletableDeferred<Any?>()
                every { HermesWsClient.request(WsMethods.CONNECTORS_LIST, any(), any()) } returns deferred
                every { HermesWsClient.request(WsMethods.CONNECTORS_LIST, any()) } returns deferred

                val repo = HermesConnectorRepository()
                val job =
                    launch {
                        repo.listConnectors("sess-cancel-test")
                    }

                yield()
                assertFalse("Deferred must not be cancelled while waiting", deferred.isCancelled)

                job.cancel()
                job.join()

                assertTrue("Deferred must be cancelled when caller coroutine is cancelled", deferred.isCancelled)
            } finally {
                unmockkObject(HermesWsClient)
            }
        }

    @Test
    fun `default rpcRequest cancels underlying deferred on caller cancellation for connect`() =
        runBlocking {
            mockkObject(HermesWsClient)
            try {
                val deferred = CompletableDeferred<Any?>()
                every { HermesWsClient.request(WsMethods.CONNECTORS_CONNECT, any(), any()) } returns deferred
                every { HermesWsClient.request(WsMethods.CONNECTORS_CONNECT, any()) } returns deferred

                val repo = HermesConnectorRepository()
                val job =
                    launch {
                        repo.connect("sess-cancel-test", listOf("linear"))
                    }

                yield()
                assertFalse("Deferred must not be cancelled while waiting", deferred.isCancelled)

                job.cancel()
                job.join()

                assertTrue("Deferred must be cancelled when caller coroutine is cancelled", deferred.isCancelled)
            } finally {
                unmockkObject(HermesWsClient)
            }
        }
}
