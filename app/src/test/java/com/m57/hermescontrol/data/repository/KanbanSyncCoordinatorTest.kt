package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.ws.KanbanEvent
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanEventsEnvelope
import com.m57.hermescontrol.data.ws.KanbanLiveStatus
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanSyncCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private lateinit var coordinator: KanbanSyncCoordinator

    @Before
    fun setUp() {
        coordinator =
            KanbanSyncCoordinator(
                repository = mockRepository,
                eventsClientProvider = { mockEventsClient },
                reloadDebounceMs = 250L,
                pollIntervalMs = 60_000L,
                nudgeDebounceMs = 400L,
            )
    }

    @Test
    fun testLiveStatusTransitions() =
        runTest(testDispatcher) {
            val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
            verify(exactly = 0) { mockEventsClient.connect(any(), any(), any(), any(), any()) }

            coordinator.connect(testScope, "dev", onReload = {})
            verify { mockEventsClient.connect(any(), eq("dev"), any(), any(), capture(statusSlot)) }

            statusSlot.captured(KanbanLiveStatus.CONNECTED)
            assertEquals(KanbanSyncStatus.Live, coordinator.syncStatus.value)

            statusSlot.captured(KanbanLiveStatus.DISCONNECTED)
            assertTrue(coordinator.syncStatus.value is KanbanSyncStatus.Polling)

            statusSlot.captured(KanbanLiveStatus.AUTH_FAILED)
            assertEquals(KanbanSyncStatus.AuthFailed, coordinator.syncStatus.value)
        }

    @Test
    fun testDebouncedEventReload() =
        runTest(testDispatcher) {
            val eventsSlot = slot<(KanbanEventsEnvelope) -> Unit>()
            var reloadCount = 0

            coordinator.connect(testScope, "dev", onReload = { reloadCount++ })
            verify { mockEventsClient.connect(any(), eq("dev"), any(), capture(eventsSlot), any()) }

            eventsSlot.captured(KanbanEventsEnvelope(events = listOf(KanbanEvent(id = 1, kind = "status"))))

            testDispatcher.scheduler.advanceTimeBy(100)
            assertEquals(0, reloadCount)

            testDispatcher.scheduler.advanceTimeBy(151)
            assertEquals(1, reloadCount)
        }

    @Test
    fun testDebouncedNudge() =
        runTest(testDispatcher) {
            coordinator.scheduleNudge(testScope, "dev")
            testDispatcher.scheduler.advanceTimeBy(200)

            // Still within debounce window
            coVerify(exactly = 0) { mockRepository.nudgeDispatcher(any()) }

            testDispatcher.scheduler.advanceTimeBy(201)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.nudgeDispatcher("dev") }
        }

    @Test
    fun testDisconnectCancelsEverything() =
        runTest(testDispatcher) {
            coordinator.connect(testScope, "dev", onReload = {})
            coordinator.disconnect()

            verify { mockEventsClient.disconnect() }
            assertEquals(KanbanSyncStatus.Disconnected, coordinator.syncStatus.value)
        }
}
