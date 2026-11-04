package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HostedApproval
import com.m57.hermescontrol.data.ws.HostedCapabilities
import com.m57.hermescontrol.data.ws.HostedDriverStatus
import com.m57.hermescontrol.data.ws.HostedEvent
import com.m57.hermescontrol.data.ws.HostedLogPage
import com.m57.hermescontrol.data.ws.HostedRoom
import com.m57.hermescontrol.data.ws.HostedRoomList
import com.m57.hermescontrol.data.ws.HostedRoomSnapshot
import com.m57.hermescontrol.data.ws.HostedRoomsSource
import com.m57.hermescontrol.data.ws.HostedTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HostedRoomsControllerTest {
    private class FakeHostedRoomsSource : HostedRoomsSource {
        var capabilitiesResult: HostedCapabilities =
            HostedCapabilities(supported = true, driver = true, maxLogLimit = 100)
        var capabilitiesError: Exception? = null

        var listPages: MutableList<HostedRoomList> = mutableListOf()
        var listCalls: MutableList<Pair<String, Int>> = mutableListOf()

        var snapshots: MutableMap<String, HostedRoomSnapshot> = mutableMapOf()
        var logPages: MutableMap<String, MutableList<HostedLogPage>> = mutableMapOf()

        var sendCalls: MutableList<List<String>> = mutableListOf()
        var sendError: Exception? = null
        var stopCalls: MutableList<Pair<String, String>> = mutableListOf()
        var retryCalls: MutableList<Triple<String, String, HostedTask>> = mutableListOf()
        var approveCalls: MutableList<List<Any>> = mutableListOf()

        var onLogCall: ((roomId: String, sinceSeq: Long) -> Unit)? = null
        var logDeferred: CompletableDeferred<HostedLogPage>? = null
        var sendStartedDeferred: CompletableDeferred<Unit>? = null
        var sendDeferred: CompletableDeferred<Unit>? = null

        override suspend fun capabilities(profile: String): HostedCapabilities {
            capabilitiesError?.let { throw it }
            return capabilitiesResult
        }

        override suspend fun list(
            profile: String,
            offset: Int,
        ): HostedRoomList {
            listCalls.add(profile to offset)
            if (listPages.isNotEmpty()) {
                val page = listPages.removeAt(0)
                return page
            }
            return HostedRoomList(emptyList(), null)
        }

        override suspend fun state(
            profile: String,
            roomId: String,
        ): HostedRoomSnapshot =
            snapshots[roomId] ?: HostedRoomSnapshot(
                room = HostedRoom(roomId = roomId, name = "Room $roomId"),
                driverStatus = HostedDriverStatus(running = true),
            )

        override suspend fun log(
            profile: String,
            roomId: String,
            sinceSeq: Long,
            limit: Int,
        ): HostedLogPage {
            onLogCall?.invoke(roomId, sinceSeq)
            logDeferred?.let { return it.await() }
            val pages = logPages[roomId]
            if (!pages.isNullOrEmpty()) {
                return pages.removeAt(0)
            }
            return HostedLogPage(emptyList(), cursor = sinceSeq, latestSeq = sinceSeq, hasMore = false)
        }

        override suspend fun send(
            profile: String,
            roomId: String,
            eventId: String,
            text: String,
        ) {
            sendCalls.add(listOf(profile, roomId, eventId, text))
            sendStartedDeferred?.complete(Unit)
            sendDeferred?.await()
            sendError?.let { throw it }
        }

        override suspend fun stop(
            profile: String,
            roomId: String,
            cancelId: String,
        ) {
            stopCalls.add(roomId to cancelId)
        }

        override suspend fun retry(
            profile: String,
            roomId: String,
            task: HostedTask,
        ) {
            retryCalls.add(Triple(roomId, task.taskId, task))
        }

        override suspend fun approve(
            profile: String,
            roomId: String,
            task: HostedTask,
            requestId: String,
            choice: String,
        ) {
            approveCalls.add(listOf(profile, roomId, task, requestId, choice))
        }
    }

    private fun testOwner(
        scopeKey: String = "scope-1",
        profile: String = "prof-a",
        generation: Int = 1,
        connected: Boolean = true,
    ) = HostedOwner(scopeKey = scopeKey, profile = profile, generation = generation, connected = connected)

    private fun hashId(id: String): String =
        "user:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    private fun makeEvent(
        roomId: String,
        seq: Long,
        eventId: String = "evt-$seq",
    ): HostedEvent =
        HostedEvent(
            roomId = roomId,
            seq = seq,
            eventId = eventId,
            kind = "message",
            payload = JsonObject(mapOf("text" to JsonPrimitive("msg $seq"))),
            actor = JsonObject(mapOf("id" to JsonPrimitive("user-1"))),
        )

    @Test
    fun `unsupported capability marks state unsupported without legacy fallback calls`() =
        runTest {
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    capabilitiesResult = HostedCapabilities(supported = false, driver = false, maxLogLimit = 0)
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()

            assertTrue(controller.state.value.unsupported)
            assertEquals(0, fakeSource.listCalls.size)
            assertFalse(controller.state.value.writable)
        }

    @Test
    fun `rpc minus 32601 on capabilities marks state unsupported without failing or legacy calls`() =
        runTest {
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    capabilitiesError = HermesWsClient.HermesRpcException("Method not found", -32601)
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()

            assertTrue(controller.state.value.unsupported)
            assertNull(controller.state.value.error)
            assertEquals(0, fakeSource.listCalls.size)
        }

    @Test
    fun `list pagination loops until nextOffset is null and rejects non-advancing offset`() =
        runTest {
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom("r1", "Room 1")), nextOffset = 1))
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom("r2", "Room 2")), nextOffset = 2))
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom("r3", "Room 3")), nextOffset = null))
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()

            assertEquals(3, controller.state.value.rooms.size)
            assertEquals(
                listOf("r1", "r2", "r3"),
                controller.state.value.rooms
                    .map { it.roomId },
            )
            assertEquals(listOf(0, 1, 2), fakeSource.listCalls.map { it.second })

            // Non-advancing offset failure
            val stuckSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom("r1", "Room 1")), nextOffset = 0))
                }
            val stuckController = HostedRoomsController(stuckSource, ownerProvider = { owner })
            stuckController.refresh()
            assertNotNull(stuckController.state.value.error)
            assertTrue(
                stuckController.state.value.error!!
                    .contains("Hosted room list did not advance"),
            )
        }

    @Test
    fun `driver false leaves controller read-only`() =
        runTest {
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    capabilitiesResult = HostedCapabilities(supported = true, driver = false, maxLogLimit = 100)
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom("r1", "Room 1"))))
                    snapshots["r1"] =
                        HostedRoomSnapshot(
                            room = HostedRoom("r1", "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select("r1")
            controller.refresh()

            assertFalse(controller.state.value.writable)
            controller.send("Should be ignored")
            assertEquals(0, fakeSource.sendCalls.size)
        }

    @Test
    fun `replay sequential pages advances cursor and handles reconnect dedupe`() =
        runTest {
            val roomId = "r1"
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    logPages[roomId] =
                        mutableListOf(
                            HostedLogPage(
                                events = listOf(makeEvent(roomId, 1), makeEvent(roomId, 2)),
                                cursor = 2,
                                latestSeq = 3,
                                hasMore = true,
                            ),
                            HostedLogPage(
                                events = listOf(makeEvent(roomId, 3)),
                                cursor = 3,
                                latestSeq = 3,
                                hasMore = false,
                            ),
                        )
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(roomId)
            controller.refresh()

            assertEquals(3, controller.state.value.events.size)
            assertEquals(3L, controller.state.value.cursor)

            // Transient reconnect: same scope & profile, new generation
            owner = testOwner(generation = 2)
            controller.syncOwner()
            // Cursor and existing events remain in place across reconnect
            assertEquals(3L, controller.state.value.cursor)
            assertEquals(3, controller.state.value.events.size)

            // New page arrives starting from cursor 3 (seq 4)
            fakeSource.logPages[roomId] =
                mutableListOf(
                    HostedLogPage(
                        events = listOf(makeEvent(roomId, 4)),
                        cursor = 4,
                        latestSeq = 4,
                        hasMore = false,
                    ),
                )
            controller.refresh()
            assertEquals(4, controller.state.value.events.size)
            assertEquals(4L, controller.state.value.cursor)
        }

    @Test
    fun `replay rejects room mismatch seq mismatch and no-progress pagination`() =
        runTest {
            val roomId = "r1"
            val baseSnapshot =
                HostedRoomSnapshot(
                    room = HostedRoom(roomId, "Room 1"),
                    driverStatus = HostedDriverStatus(running = true),
                )

            // 1. Room mismatch in event
            val roomMismatchSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] = baseSnapshot
                    logPages[roomId] =
                        mutableListOf(
                            HostedLogPage(
                                events = listOf(makeEvent("wrong-room", 1)),
                                cursor = 1,
                                latestSeq = 1,
                                hasMore = false,
                            ),
                        )
                }
            var owner: HostedOwner? = testOwner()
            val c1 = HostedRoomsController(roomMismatchSource, ownerProvider = { owner })
            c1.refresh()
            c1.select(roomId)
            c1.refresh()
            assertTrue(
                c1.state.value.error!!
                    .contains("Hosted room replay sequence mismatch"),
            )

            // 2. Seq mismatch (expected 1, got 2)
            val seqMismatchSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] = baseSnapshot
                    logPages[roomId] =
                        mutableListOf(
                            HostedLogPage(
                                events = listOf(makeEvent(roomId, 2)),
                                cursor = 2,
                                latestSeq = 2,
                                hasMore = false,
                            ),
                        )
                }
            val c2 = HostedRoomsController(seqMismatchSource, ownerProvider = { owner })
            c2.refresh()
            c2.select(roomId)
            c2.refresh()
            assertTrue(
                c2.state.value.error!!
                    .contains("Hosted room replay sequence mismatch"),
            )

            // 3. No-progress pagination (hasMore = true but cursor did not advance)
            val noProgressSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] = baseSnapshot
                    logPages[roomId] =
                        mutableListOf(
                            HostedLogPage(events = emptyList(), cursor = 0, latestSeq = 1, hasMore = true),
                        )
                }
            val c3 = HostedRoomsController(noProgressSource, ownerProvider = { owner })
            c3.refresh()
            c3.select(roomId)
            c3.refresh()
            assertTrue(
                c3.state.value.error!!
                    .contains("Hosted room replay did not advance"),
            )
        }

    @Test
    fun `scope or profile switch clears history and fences late responses`() =
        runTest {
            val roomId = "r1"
            val deferredLog = CompletableDeferred<HostedLogPage>()
            val logCalled = CompletableDeferred<Unit>()
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    onLogCall = { _, _ -> logCalled.complete(Unit) }
                    logDeferred = deferredLog
                }
            var owner: HostedOwner? = testOwner(profile = "prof-a")
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(roomId)

            // Launch refresh undispatched so it enters source.log immediately
            val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) { controller.refresh() }
            assertTrue(logCalled.isCompleted)

            // Switch profile while refresh is suspended in log
            owner = testOwner(profile = "prof-b")
            controller.syncOwner()

            // State should be completely cleared for prof-b
            assertNull(controller.state.value.selectedRoomId)
            assertTrue(
                controller.state.value.events
                    .isEmpty(),
            )
            assertNull(controller.state.value.snapshot)

            // Complete the old deferred log response
            deferredLog.complete(
                HostedLogPage(events = listOf(makeEvent(roomId, 1)), cursor = 1, latestSeq = 1, hasMore = false),
            )
            refreshJob.join()

            // Old log response must NOT have mutated state because revision was bumped
            assertTrue(
                controller.state.value.events
                    .isEmpty(),
            )
            assertNull(controller.state.value.selectedRoomId)
        }

    @Test
    fun `in-flight mutation completion is fenced on profile switch`() =
        runTest {
            val roomId = "r1"
            val sendStarted = CompletableDeferred<Unit>()
            val sendDeferred = CompletableDeferred<Unit>()
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    this.sendStartedDeferred = sendStarted
                    this.sendDeferred = sendDeferred
                }
            var owner: HostedOwner? = testOwner(profile = "prof-a")
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(roomId)
            controller.refresh()

            val sendJob = launch(start = CoroutineStart.UNDISPATCHED) { controller.send("In-flight mutation") }
            assertTrue(sendStarted.isCompleted)

            // Switch profile before send finishes
            owner = testOwner(profile = "prof-b")
            controller.syncOwner()

            // Complete network send
            sendDeferred.complete(Unit)
            sendJob.join()

            // prof-b state should not have pending send or busy
            assertFalse(controller.state.value.busy)
            assertNull(controller.state.value.pendingSend)
            assertNull(controller.state.value.selectedRoomId)
        }

    @Test
    fun `reconnect keeps uncertain send and explicit retry reuses same eventId and text`() =
        runTest {
            val roomId = "r1"
            var generatedId = "fixed-uuid-1"
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    // Fail initial send to simulate uncertain send where connection drops or server fails receipt
                    sendError = HermesWsClient.HermesRpcException("Connection lost during send")
                }
            var owner: HostedOwner? = testOwner(generation = 1)
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner }, newId = { generatedId })

            controller.refresh()
            controller.select(roomId)
            controller.refresh()

            // Send message (fails due to sendError, leaves pendingSend uncertain)
            controller.send("Deploy urgent fix")
            assertEquals(1, fakeSource.sendCalls.size)
            val firstCall = fakeSource.sendCalls[0]
            assertEquals("prof-a", firstCall[0])
            assertEquals("r1", firstCall[1])
            assertEquals("fixed-uuid-1", firstCall[2])
            assertEquals("Deploy urgent fix", firstCall[3])

            val initialPending = controller.state.value.pendingSend
            assertNotNull(initialPending)
            assertEquals("fixed-uuid-1", initialPending!!.eventId)

            // Simulate reconnect before confirmation: same profile, new generation
            owner = testOwner(generation = 2)
            controller.syncOwner()

            // Pending send must still be retained across reconnect
            val pending = controller.state.value.pendingSend
            assertNotNull(pending)
            assertEquals("fixed-uuid-1", pending!!.eventId)
            assertEquals("Deploy urgent fix", pending.text)

            // No auto-resend on reconnect
            assertEquals(1, fakeSource.sendCalls.size)

            // Restore capabilities/state after generation change before explicit retry (writable requirement)
            controller.refresh()
            assertTrue(controller.state.value.writable)

            // Clear send error for successful explicit retry
            fakeSource.sendError = null
            generatedId = "different-uuid-2"
            controller.retryPendingSend()

            assertEquals(2, fakeSource.sendCalls.size)
            val retryCall = fakeSource.sendCalls[1]
            assertEquals("fixed-uuid-1", retryCall[2])
            assertEquals("Deploy urgent fix", retryCall[3])

            // When replay log sees the confirmed wireEventId, pendingSend is cleared
            val wireId = hashId("fixed-uuid-1")
            fakeSource.logPages[roomId] =
                mutableListOf(
                    HostedLogPage(
                        events = listOf(makeEvent(roomId, 1, wireId)),
                        cursor = 1,
                        latestSeq = 1,
                        hasMore = false,
                    ),
                )
            controller.refresh()
            assertNull(controller.state.value.pendingSend)
        }

    @Test
    fun `stale response cannot change selected room`() =
        runTest {
            val r1 = "r1"
            val r2 = "r2"
            val deferredR1 = CompletableDeferred<HostedLogPage>()
            val logR1Called = CompletableDeferred<Unit>()
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(r1, "Room 1"), HostedRoom(r2, "Room 2"))))
                    snapshots[r1] =
                        HostedRoomSnapshot(
                            room = HostedRoom(r1, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    snapshots[r2] =
                        HostedRoomSnapshot(
                            room = HostedRoom(r2, "Room 2"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    onLogCall = { roomId, _ -> if (roomId == r1) logR1Called.complete(Unit) }
                    logDeferred = deferredR1
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(r1)

            val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) { controller.refresh() }
            assertTrue(logR1Called.isCompleted)

            // User switches selection to r2
            controller.select(r2)
            assertEquals(r2, controller.state.value.selectedRoomId)

            // Late response for r1 completes
            deferredR1.complete(
                HostedLogPage(events = listOf(makeEvent(r1, 1)), cursor = 1, latestSeq = 1, hasMore = false),
            )
            refreshJob.join()

            // Selected room remains r2, events from r1 not published
            assertEquals(r2, controller.state.value.selectedRoomId)
            assertTrue(
                controller.state.value.events
                    .isEmpty(),
            )
        }

    @Test
    fun `selection change blocked while pending send is unacknowledged`() =
        runTest {
            val r1 = "r1"
            val r2 = "r2"
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(r1, "Room 1"), HostedRoom(r2, "Room 2"))))
                    snapshots[r1] =
                        HostedRoomSnapshot(
                            room = HostedRoom(r1, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    snapshots[r2] =
                        HostedRoomSnapshot(
                            room = HostedRoom(r2, "Room 2"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                    // Fail send so pendingSend remains unacknowledged
                    sendError = HermesWsClient.HermesRpcException("Send timed out")
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(r1)
            controller.refresh()

            // Send a message that fails
            controller.send("Important message")
            assertNotNull(controller.state.value.pendingSend)

            // Attempting to select r2 must be blocked to prevent silently abandoning pending send
            controller.select(r2)
            assertEquals(r1, controller.state.value.selectedRoomId)
        }

    @Test
    fun `task retry and approval send exact generation request identity and reject invalid choices`() =
        runTest {
            val roomId = "r1"
            val retryTask =
                HostedTask(
                    kind = "retry",
                    memberId = "bot-1",
                    taskId = "task-retry-1",
                    executionGeneration = 4,
                )
            val approvalTask =
                HostedTask(
                    kind = "approval",
                    memberId = "bot-2",
                    taskId = "task-appr-1",
                    executionGeneration = 5,
                    requestId = "req-uuid-99",
                    approval = HostedApproval(choices = listOf("once", "deny"), command = "cat /etc/hosts"),
                )
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus =
                                HostedDriverStatus(
                                    running = true,
                                    pendingActions = listOf(retryTask, approvalTask),
                                ),
                        )
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner })

            controller.refresh()
            controller.select(roomId)
            controller.refresh()

            // Valid retry
            controller.retry(retryTask)
            assertEquals(1, fakeSource.retryCalls.size)
            assertEquals("task-retry-1", fakeSource.retryCalls[0].second)
            assertEquals(4, fakeSource.retryCalls[0].third.executionGeneration)

            // Valid approval
            controller.approve(approvalTask, "once")
            assertEquals(1, fakeSource.approveCalls.size)
            val approveCall = fakeSource.approveCalls[0]
            assertEquals("prof-a", approveCall[0])
            assertEquals("r1", approveCall[1])
            assertEquals(approvalTask, approveCall[2])
            assertEquals("req-uuid-99", approveCall[3])
            assertEquals("once", approveCall[4])

            // Invalid choice (not in setOf("once", "deny"))
            val invalidChoiceTask =
                HostedTask(
                    kind = "approval",
                    memberId = "bot-2",
                    taskId = "task-appr-2",
                    executionGeneration = 6,
                    requestId = "req-2",
                    approval = HostedApproval(choices = listOf("always"), command = "ls"),
                )
            // Task not in pending actions
            controller.approve(invalidChoiceTask, "always")
            assertEquals(1, fakeSource.approveCalls.size) // No extra call made
            assertNotNull(controller.state.value.error)
        }

    @Test
    fun `stop sends room_id and new cancel id with profile`() =
        runTest {
            val roomId = "r1"
            val fakeSource =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(rooms = listOf(HostedRoom(roomId, "Room 1"))))
                    snapshots[roomId] =
                        HostedRoomSnapshot(
                            room = HostedRoom(roomId, "Room 1"),
                            driverStatus = HostedDriverStatus(running = true),
                        )
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(fakeSource, ownerProvider = { owner }, newId = { "cancel-123" })

            controller.refresh()
            controller.select(roomId)
            controller.refresh()

            controller.stop()

            assertEquals(1, fakeSource.stopCalls.size)
            assertEquals("r1", fakeSource.stopCalls[0].first)
            assertEquals("cancel-123", fakeSource.stopCalls[0].second)
        }

    @Test
    fun `replayed uncertain send clears retry without transmitting again`() =
        runTest {
            val source =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(listOf(HostedRoom("r1", "Room"))))
                    sendError = HermesWsClient.HermesRpcException("Receipt lost")
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(source, { owner }, { "original-id" })
            controller.refresh()
            controller.select("r1")
            controller.refresh()
            controller.send("Already accepted")
            assertNotNull(controller.state.value.pendingSend)

            owner = testOwner(generation = 2)
            controller.syncOwner()
            source.logPages["r1"] =
                mutableListOf(
                    HostedLogPage(listOf(makeEvent("r1", 1, hashId("original-id"))), 1, 1, false),
                )
            controller.refresh()
            assertNull(controller.state.value.pendingSend)
            controller.retryPendingSend()
            assertEquals(1, source.sendCalls.size)
            assertEquals(1L, controller.state.value.cursor)
        }

    @Test
    fun `late send receipt across reconnect leaves original key available for reconciliation`() =
        runTest {
            val response = CompletableDeferred<Unit>()
            val source =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(listOf(HostedRoom("r1", "Room"))))
                    sendDeferred = response
                }
            var owner: HostedOwner? = testOwner()
            val controller = HostedRoomsController(source, { owner }, { "original-id" })
            controller.refresh()
            controller.select("r1")
            controller.refresh()
            val send = launch(start = CoroutineStart.UNDISPATCHED) { controller.send("Message") }
            assertEquals(1, source.sendCalls.size)
            owner = testOwner(generation = 2)
            controller.syncOwner()
            response.complete(Unit)
            send.join()
            assertEquals(
                "original-id",
                controller.state.value.pendingSend
                    ?.eventId,
            )
            assertFalse(controller.state.value.writable)
            assertFalse(controller.state.value.busy)
        }

    @Test
    fun `same profile on another connection clears all owned room data`() =
        runTest {
            val source =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(listOf(HostedRoom("r1", "Room"))))
                    sendError = HermesWsClient.HermesRpcException("Receipt lost")
                }
            var owner: HostedOwner? = testOwner(scopeKey = "connection-a")
            val controller = HostedRoomsController(source, { owner })
            controller.refresh()
            controller.select("r1")
            controller.refresh()
            controller.send("Message")
            assertNotNull(controller.state.value.pendingSend)
            owner = testOwner(scopeKey = "connection-b")
            controller.syncOwner()
            assertNull(controller.state.value.pendingSend)
            assertNull(controller.state.value.snapshot)
            assertNull(controller.state.value.selectedRoomId)
            assertTrue(
                controller.state.value.rooms
                    .isEmpty(),
            )
        }

    @Test
    fun `driver availability can recover without reconnect`() =
        runTest {
            val source =
                FakeHostedRoomsSource().apply {
                    capabilitiesResult = HostedCapabilities(true, false, 100)
                    listPages.add(HostedRoomList(listOf(HostedRoom("r1", "Room"))))
                }
            val controller = HostedRoomsController(source, { testOwner() })
            controller.refresh()
            controller.select("r1")
            controller.refresh()
            assertFalse(controller.state.value.writable)
            source.capabilitiesResult = HostedCapabilities(true, true, 100)
            controller.refresh()
            assertTrue(controller.state.value.writable)
        }

    @Test
    fun `failed replay remains read only until a complete refresh succeeds`() =
        runTest {
            val source =
                FakeHostedRoomsSource().apply {
                    listPages.add(HostedRoomList(listOf(HostedRoom("r1", "Room"))))
                }
            val controller = HostedRoomsController(source, { testOwner() })
            controller.refresh()
            controller.select("r1")
            controller.refresh()
            assertTrue(controller.state.value.writable)
            source.logPages["r1"] =
                mutableListOf(
                    HostedLogPage(listOf(makeEvent("r1", 2)), 2, 2, false),
                )
            controller.refresh()
            assertFalse(controller.state.value.writable)
            val response = CompletableDeferred<HostedLogPage>()
            source.logDeferred = response
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { controller.refresh() }
            assertFalse(controller.state.value.writable)
            assertNotNull(controller.state.value.error)
            response.complete(HostedLogPage(emptyList(), 0, 0, false))
            refresh.join()
            assertTrue(controller.state.value.writable)
            assertNull(controller.state.value.error)
        }
}
