package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.HostedContextParams
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
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
    fun `capabilities rejects an omitted log limit`() =
        runTest {
            val source = HermesHostedRoomsSource(hostedCaller { _, _ -> fullCapabilitiesWireMap() - "max_log_limit" })
            assertFalse(source.capabilities("work").supported)
        }

    @Test
    fun `list and state accept explicit null room sequence`() =
        runTest {
            val room = mapOf("room_id" to "empty-room", "name" to "Empty room", "latest_seq" to null)
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { method, _ ->
                        when (method) {
                            "groups.list" -> mapOf("rooms" to listOf(room))
                            "groups.state" -> mapOf("room" to room)
                            else -> error("Unexpected method: $method")
                        }
                    },
                )
            assertEquals(
                null,
                source
                    .list("work", 0)
                    .rooms
                    .single()
                    .latestSeq,
            )
            assertEquals(null, source.state("work", "empty-room").room.latestSeq)
        }

    @Test
    fun `mutation receipts use protocol defaults when success flags are omitted`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { method, _ ->
                        when (method) {
                            "groups.send" -> {
                                mapOf(
                                    "event" to
                                        mapOf(
                                            "room_id" to "room-1",
                                            "seq" to 1,
                                            "event_id" to "event-1",
                                            "kind" to "message",
                                            "payload" to mapOf("text" to "hello"),
                                            "actor" to mapOf("kind" to "user"),
                                        ),
                                )
                            }

                            "groups.retry" -> {
                                mapOf("task" to mapOf("task_id" to "task-1"))
                            }

                            "groups.approve" -> {
                                mapOf("result" to mapOf("choice" to "once"))
                            }

                            else -> {
                                error("Unexpected method: $method")
                            }
                        }
                    },
                )
            val task = HostedTask(memberId = "member-1", taskId = "task-1", executionGeneration = 1)
            source.send("work", "room-1", "client-1", "hello")
            source.retry("work", "room-1", task)
            source.approve("work", "room-1", task, "request-1", "once")
        }

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

    @Test
    fun `state decodes backend confirmed context and lead routing`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { _, _ ->
                        mapOf(
                            "room" to mapOf("room_id" to "r1", "name" to "Planning"),
                            "context" to
                                mapOf(
                                    "purpose" to "Ship the next release",
                                    "notes" to "Use the shared checklist",
                                    "routing" to "lead",
                                    "lead_member_id" to "member-reviewer",
                                ),
                        )
                    },
                )

            val context = source.state("work", "r1").context

            assertEquals("Ship the next release", context?.purpose)
            assertEquals("Use the shared checklist", context?.notes)
            assertEquals("lead", context?.routing)
            assertEquals("member-reviewer", context?.leadMemberId)
        }

    @Test
    fun `older context defaults routing without changing purpose and notes`() =
        runTest {
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { _, _ ->
                        mapOf(
                            "room" to mapOf("room_id" to "r1", "name" to "Planning"),
                            "context" to mapOf("purpose" to "Existing purpose", "notes" to "Existing notes"),
                        )
                    },
                )

            val context = source.state("work", "r1").context

            assertEquals("Existing purpose", context?.purpose)
            assertEquals("Existing notes", context?.notes)
            assertEquals("everyone", context?.routing)
            assertEquals("", context?.leadMemberId)
        }

    @Test
    fun `legacy state without context remains readable`() =
        runTest {
            for (includeNull in listOf(false, true)) {
                val result: Map<String, Any?> =
                    mapOf("room" to mapOf("room_id" to "r1", "name" to "Legacy")) +
                        if (includeNull) mapOf("context" to null) else emptyMap()
                val source = HermesHostedRoomsSource(hostedCaller { _, _ -> result })

                val snapshot = source.state("work", "r1")

                assertEquals("Legacy", snapshot.room.name)
                assertEquals(null, snapshot.context)
            }
        }

    private fun contextReceiptWire(
        roomId: String = "r1",
        routing: String = "lead",
        leadMemberId: String = "member-reviewer",
    ): Map<String, Any> =
        mapOf(
            "context" to
                mapOf(
                    "purpose" to "Normalized purpose",
                    "notes" to "Confirmed notes",
                    "routing" to routing,
                    "lead_member_id" to leadMemberId,
                ),
            "event" to
                mapOf(
                    "room_id" to roomId,
                    "seq" to 7,
                    "event_id" to "context-save-1",
                    "kind" to "room.context_set",
                    "payload" to emptyMap<String, Any>(),
                    "actor" to mapOf("kind" to "system", "id" to "room-control"),
                ),
        )

    @Test
    fun `context capability uses method list independently without new feature requirements`() =
        runTest {
            val oldSource = HermesHostedRoomsSource(hostedCaller { _, _ -> fullCapabilitiesWireMap() })
            val oldCapabilities = oldSource.capabilities("work")
            assertTrue(oldCapabilities.supported)
            assertFalse(oldCapabilities.contextSupported)

            val contextSource =
                HermesHostedRoomsSource(
                    hostedCaller { _, _ ->
                        fullCapabilitiesWireMap(methods = requiredMethodsLiteral + "groups.context", driver = false)
                    },
                )
            val contextCapabilities = contextSource.capabilities("work")
            assertTrue(contextCapabilities.supported)
            assertTrue(contextCapabilities.contextSupported)
            assertFalse(contextCapabilities.driver)

            val featureOnlySource =
                HermesHostedRoomsSource(
                    hostedCaller { _, _ ->
                        fullCapabilitiesWireMap(features = requiredFeaturesLiteral + "groups.context" + "room_context")
                    },
                )
            assertFalse(featureOnlySource.capabilities("work").contextSupported)
        }

    @Test
    fun `context save sends required identity and text but omits unchanged routing fields`() =
        runTest {
            var recordedParams: Map<String, Any>? = null
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { method, params ->
                        assertEquals("groups.context", method)
                        recordedParams = params
                        contextReceiptWire()
                    },
                )

            val receipt = source.context("work", "r1", "context-save-1", " Raw purpose ", " Raw notes ")

            assertEquals(
                mapOf(
                    "profile" to "work",
                    "room_id" to "r1",
                    "event_id" to "context-save-1",
                    "purpose" to " Raw purpose ",
                    "notes" to " Raw notes ",
                ),
                recordedParams,
            )
            assertEquals("Normalized purpose", receipt.context.purpose)
            assertEquals("Confirmed notes", receipt.context.notes)
            assertEquals("lead", receipt.context.routing)
            assertEquals("member-reviewer", receipt.context.leadMemberId)
            assertEquals("room.context_set", receipt.event.kind)
            assertEquals("r1", receipt.event.roomId)
        }

    @Test
    fun `explicit routing changes and empty text are transmitted rather than omitted`() =
        runTest {
            val calls = mutableListOf<Map<String, Any>>()
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { _, params ->
                        calls += params
                        contextReceiptWire()
                    },
                )

            source.context("work", "r1", "save-lead", "", "", "lead", "member-reviewer")
            source.context("work", "r1", "save-everyone", "", "", "everyone", "")

            assertEquals("lead", calls[0]["routing"])
            assertEquals("member-reviewer", calls[0]["lead_member_id"])
            assertEquals("everyone", calls[1]["routing"])
            assertEquals("", calls[1]["lead_member_id"])
            assertEquals("", calls[1]["purpose"])
            assertEquals("", calls[1]["notes"])
            assertEquals("save-everyone", calls[1]["event_id"])
        }

    @Test
    fun `context request requires event_id and receipt requires confirmed context and event`() =
        runTest {
            try {
                OkHttpProvider.json.decodeFromString(
                    HostedContextParams.serializer(),
                    """{"profile":"work","room_id":"r1","purpose":"","notes":""}""",
                )
                fail("Missing event_id must not deserialize")
            } catch (_: SerializationException) {
                // The backend requires event_id for an idempotent context write.
            }
            for (missingField in listOf("context", "event")) {
                val source =
                    HermesHostedRoomsSource(hostedCaller { _, _ -> contextReceiptWire() - missingField })
                try {
                    source.context("work", "r1", "save-1", "purpose", "notes")
                    fail("Missing $missingField must not be accepted as a confirmed save")
                } catch (_: SerializationException) {
                    // No default may turn a malformed ACK into confirmed settings.
                }
            }
        }

    @Test
    fun `context errors and cancellation propagate instead of becoming successful receipts`() =
        runTest {
            val failures =
                listOf(
                    IOException("Connection reset"),
                    HermesWsClient.HermesRpcException("The lead must be a room member", 4125),
                    CancellationException("Save cancelled"),
                )
            for (failure in failures) {
                var calls = 0
                val source =
                    HermesHostedRoomsSource(
                        hostedCaller { _, _ ->
                            calls++
                            throw failure
                        },
                    )
                try {
                    source.context("work", "r1", "save-1", "purpose", "notes")
                    fail("Expected the original failure")
                } catch (caught: Exception) {
                    assertTrue(caught === failure)
                }
                assertEquals(1, calls)
            }
        }

    @Test
    fun `state preserves roster member ids independently of profile handle and peer target`() =
        runTest {
            val peerTarget =
                mapOf(
                    "kind" to "peer",
                    "peer_id" to "connection-b",
                    "installation_id" to "gateway-b",
                    "profile" to "reviewer",
                    "capability_digest" to "a".repeat(64),
                )
            val members =
                listOf(
                    mapOf(
                        "member_id" to "local-seat",
                        "profile" to "analyst",
                        "handle" to "analysis",
                        "display_name" to "Local analyst",
                        "target" to mapOf("kind" to "local", "profile" to "analyst"),
                    ),
                    mapOf(
                        "member_id" to "remote-seat",
                        "profile" to "reviewer",
                        "handle" to "review",
                        "display_name" to "Peer reviewer",
                        "target" to peerTarget,
                    ),
                    mapOf("profile" to "legacy-profile"),
                )
            val source =
                HermesHostedRoomsSource(
                    hostedCaller { _, _ ->
                        mapOf("room" to mapOf("room_id" to "r1", "name" to "Review", "members" to members))
                    },
                )

            val roster = source.state("work", "r1").room.members

            assertEquals(listOf("local-seat", "remote-seat", null), roster.map { it.memberId })
            assertEquals("reviewer", roster[1].profile)
            assertEquals("review", roster[1].handle)
            assertEquals("Peer reviewer", roster[1].displayName)
            assertEquals(ConnectorParser.toJsonElement(peerTarget), roster[1].target)
            assertEquals("legacy-profile", roster[2].profile)
        }
}
