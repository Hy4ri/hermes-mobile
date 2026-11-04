package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HostedRoomsSourceTest {
    private fun hostedCaller(handler: suspend (String, Map<String, Any>) -> Any?): TypedRpcCaller =
        object : TypedRpcCaller {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <P, R> call(
                method: RpcMethod<P, R>,
                params: P,
            ): R {
                val json = OkHttpProvider.json
                val wireParams = json.encodeToJsonElement(method.params, params).toAny() as Map<String, Any>
                val wireResult = ConnectorParser.toJsonElement(handler(method.name, wireParams))
                return json.decodeFromJsonElement(method.result, wireResult)
            }
        }

    private val requiredMethodsLiteral =
        listOf(
            "groups.capabilities",
            "groups.list",
            "groups.state",
            "groups.send",
            "groups.log",
            "groups.stop",
            "groups.retry",
            "groups.approve",
        )

    private val requiredFeaturesLiteral =
        listOf(
            "idempotent_send",
            "monotonic_log",
            "room_identity",
        )

    private fun fullCapabilitiesWireMap(
        protocolVersion: Int = 2,
        driver: Boolean = true,
        maxLogLimit: Int = 100,
        methods: List<String> = requiredMethodsLiteral,
        features: List<String> = requiredFeaturesLiteral,
    ): Map<String, Any> =
        mapOf(
            "protocol_version" to protocolVersion,
            "driver" to driver,
            "max_log_limit" to maxLogLimit,
            "methods" to methods,
            "features" to features,
            "persistent_process" to true,
            "authority_gateway_id" to "gw-main",
        )

    @Test
    fun `capabilities returns supported true when all protocol requirements met`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            fullCapabilitiesWireMap()
                        },
                )

            val caps = source.capabilities("work")

            assertEquals(RpcMethods.GROUPS_CAPABILITIES.name, recordedMethod)
            assertEquals("groups.capabilities", recordedMethod)
            assertEquals("work", recordedParams?.get("profile"))
            assertTrue(caps.supported)
            assertTrue(caps.driver)
            assertEquals(100, caps.maxLogLimit)
        }

    @Test
    fun `capabilities protocol detection detects missing method or feature or non-positive limit or wrong version`() =
        runTest {
            // Missing method
            val missingMethodSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            fullCapabilitiesWireMap(methods = listOf("groups.capabilities", "groups.list"))
                        },
                )
            assertFalse(missingMethodSource.capabilities("work").supported)

            // Missing feature
            val missingFeatureSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            fullCapabilitiesWireMap(features = listOf("idempotent_send"))
                        },
                )
            assertFalse(missingFeatureSource.capabilities("work").supported)

            // Protocol version != 2
            val wrongVersionSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            fullCapabilitiesWireMap(protocolVersion = 1)
                        },
                )
            assertFalse(wrongVersionSource.capabilities("work").supported)

            // Non-positive maxLogLimit
            val zeroLimitSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            fullCapabilitiesWireMap(maxLogLimit = 0)
                        },
                )
            assertFalse(zeroLimitSource.capabilities("work").supported)

            // Driver false preserves driver false and supported true if protocol version and features match
            val driverFalseSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            fullCapabilitiesWireMap(driver = false)
                        },
                )
            val driverFalseCaps = driverFalseSource.capabilities("work")
            assertTrue(driverFalseCaps.supported)
            assertFalse(driverFalseCaps.driver)
        }

    @Test
    fun `capabilities minus 32601 yields unsupported without legacy calls`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            throw HermesWsClient.HermesRpcException("Method not found", -32601)
                        },
                )

            val caps = source.capabilities("work")
            assertFalse(caps.supported)
            assertFalse(caps.driver)
            assertEquals(0, caps.maxLogLimit)
        }

    @Test
    fun `capabilities propagates network auth and generic errors instead of caching unsupported`() =
        runTest {
            val ioSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            throw IOException("connection reset")
                        },
                )
            try {
                ioSource.capabilities("work")
                fail("Expected IOException to propagate")
            } catch (e: IOException) {
                assertEquals("connection reset", e.message)
            }

            val authErrorSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            throw HermesWsClient.HermesRpcException("Unauthorized", -32001)
                        },
                )
            try {
                authErrorSource.capabilities("work")
                fail("Expected HermesRpcException to propagate")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals(-32001, e.code)
            }
        }

    @Test
    fun `capabilities propagates CancellationException`() =
        runTest {
            val cancelSource =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            throw CancellationException("job cancelled")
                        },
                )
            try {
                cancelSource.capabilities("work")
                fail("Expected CancellationException to propagate")
            } catch (e: CancellationException) {
                assertEquals("job cancelled", e.message)
            }
        }

    @Test
    fun `list sends explicit profile and offset wire params`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf(
                                "rooms" to
                                    listOf(
                                        mapOf(
                                            "room_id" to "room-1",
                                            "name" to "General",
                                            "latest_seq" to 42,
                                        ),
                                    ),
                                "next_offset" to 10,
                            )
                        },
                )

            val result = source.list("test-profile", 5)

            assertEquals("groups.list", recordedMethod)
            assertEquals("test-profile", recordedParams?.get("profile"))
            assertEquals(5, (recordedParams?.get("offset") as? Number)?.toInt())
            assertEquals(1, result.rooms.size)
            assertEquals("room-1", result.rooms[0].roomId)
            assertEquals("General", result.rooms[0].name)
            assertEquals(42L, result.rooms[0].latestSeq)
            assertEquals(10, result.nextOffset)
        }

    @Test
    fun `state sends explicit profile and room_id wire params`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf(
                                "room" to
                                    mapOf(
                                        "room_id" to "room-42",
                                        "name" to "Incident Response",
                                        "latest_seq" to 15,
                                    ),
                                "driver_status" to
                                    mapOf(
                                        "running" to true,
                                        "working" to false,
                                        "blocked" to false,
                                        "pending_actions" to emptyList<Map<String, Any>>(),
                                    ),
                            )
                        },
                )

            val snapshot = source.state("ops-profile", "room-42")

            assertEquals("groups.state", recordedMethod)
            assertEquals("ops-profile", recordedParams?.get("profile"))
            assertEquals("room-42", recordedParams?.get("room_id"))
            assertEquals("room-42", snapshot.room.roomId)
            assertEquals("Incident Response", snapshot.room.name)
            assertTrue(snapshot.driverStatus?.running == true)
        }

    @Test
    fun `log sends explicit profile room_id since_seq and limit wire params`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf(
                                "events" to
                                    listOf(
                                        mapOf(
                                            "room_id" to "room-42",
                                            "seq" to 1,
                                            "event_id" to "evt-1",
                                            "kind" to "message",
                                            "payload" to mapOf("text" to "hello"),
                                            "actor" to mapOf("id" to "user-1"),
                                        ),
                                    ),
                                "cursor" to 1,
                                "latest_seq" to 1,
                                "has_more" to false,
                            )
                        },
                )

            val page = source.log("ops-profile", "room-42", 7L, 50)

            assertEquals("groups.log", recordedMethod)
            assertEquals("ops-profile", recordedParams?.get("profile"))
            assertEquals("room-42", recordedParams?.get("room_id"))
            assertEquals(7L, (recordedParams?.get("since_seq") as? Number)?.toLong())
            assertEquals(50, (recordedParams?.get("limit") as? Number)?.toInt())
            assertEquals(1, page.events.size)
            assertEquals("evt-1", page.events[0].eventId)
            assertEquals(1L, page.cursor)
            assertFalse(page.hasMore)
        }

    @Test
    fun `send wraps payload in object with text property and sends room_id and profile and verifies receipt`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf("accepted" to true, "client_event_id" to "evt-uuid-1")
                        },
                )

            source.send("profile-a", "room-100", "evt-uuid-1", "Deploy v2")

            assertEquals("groups.send", recordedMethod)
            assertEquals("profile-a", recordedParams?.get("profile"))
            assertEquals("room-100", recordedParams?.get("room_id"))
            assertEquals("evt-uuid-1", recordedParams?.get("event_id"))
            val payload = recordedParams?.get("payload") as? Map<*, *>
            assertEquals("Deploy v2", payload?.get("text"))
        }

    @Test
    fun `send throws HermesRpcException when receipt accepted is false`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            mapOf("accepted" to false)
                        },
                )

            try {
                source.send("profile-a", "room-100", "evt-uuid-1", "Deploy v2")
                fail("Expected HermesRpcException when accepted is false")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertTrue(e.message?.contains("Send rejected") == true)
            }
        }

    @Test
    fun `stop sends room_id cancel_id and profile and handles cancelled receipt`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf("cancelled" to 1)
                        },
                )

            source.stop("profile-a", "room-100", "cancel-uuid-1")

            assertEquals("groups.stop", recordedMethod)
            assertEquals("profile-a", recordedParams?.get("profile"))
            assertEquals("room-100", recordedParams?.get("room_id"))
            assertEquals("cancel-uuid-1", recordedParams?.get("cancel_id"))
        }

    @Test
    fun `retry sends task_id member_id execution_generation room_id and profile and verifies receipt`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf("retried" to true)
                        },
                )

            val task =
                HostedTask(
                    kind = "retry",
                    memberId = "bot-analyst",
                    taskId = "task-99",
                    executionGeneration = 3,
                )

            source.retry("profile-a", "room-100", task)

            assertEquals("groups.retry", recordedMethod)
            assertEquals("profile-a", recordedParams?.get("profile"))
            assertEquals("room-100", recordedParams?.get("room_id"))
            assertEquals("task-99", recordedParams?.get("task_id"))
            assertEquals("bot-analyst", recordedParams?.get("member_id"))
            assertEquals(3, (recordedParams?.get("execution_generation") as? Number)?.toInt())
        }

    @Test
    fun `retry throws HermesRpcException when receipt retried is false`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            mapOf("retried" to false)
                        },
                )

            val task =
                HostedTask(
                    kind = "retry",
                    memberId = "bot-analyst",
                    taskId = "task-99",
                    executionGeneration = 3,
                )

            try {
                source.retry("profile-a", "room-100", task)
                fail("Expected HermesRpcException when retried is false")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertTrue(e.message?.contains("Retry rejected") == true)
            }
        }

    @Test
    fun `approve sends exact task fields choice request_id room_id and profile and verifies receipt`() =
        runTest {
            var recordedMethod: String? = null
            var recordedParams: Map<String, Any>? = null

            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { method, params ->
                            recordedMethod = method
                            recordedParams = params
                            mapOf("approved" to true)
                        },
                )

            val task =
                HostedTask(
                    kind = "approval",
                    memberId = "bot-coder",
                    taskId = "task-appr-1",
                    executionGeneration = 2,
                    requestId = "req-uuid-77",
                    approval = HostedApproval(choices = listOf("once", "deny"), command = "rm -rf"),
                )

            source.approve("profile-a", "room-100", task, "req-uuid-77", "once")

            assertEquals("groups.approve", recordedMethod)
            assertEquals("profile-a", recordedParams?.get("profile"))
            assertEquals("room-100", recordedParams?.get("room_id"))
            assertEquals("bot-coder", recordedParams?.get("member_id"))
            assertEquals("task-appr-1", recordedParams?.get("task_id"))
            assertEquals(2, (recordedParams?.get("execution_generation") as? Number)?.toInt())
            assertEquals("req-uuid-77", recordedParams?.get("request_id"))
            assertEquals("once", recordedParams?.get("choice"))
        }

    @Test
    fun `approve throws HermesRpcException when receipt approved is false`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    caller =
                        hostedCaller { _, _ ->
                            mapOf("approved" to false)
                        },
                )

            val task =
                HostedTask(
                    kind = "approval",
                    memberId = "bot-coder",
                    taskId = "task-appr-1",
                    executionGeneration = 2,
                    requestId = "req-uuid-77",
                    approval = HostedApproval(choices = listOf("once", "deny"), command = "rm -rf"),
                )

            try {
                source.approve("profile-a", "room-100", task, "req-uuid-77", "once")
                fail("Expected HermesRpcException when approved is false")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertTrue(e.message?.contains("Approval rejected") == true)
            }
        }
}
