package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.DispatchResult
import com.m57.hermescontrol.data.remote.KanbanApiService
import com.m57.hermescontrol.data.repository.DefaultKanbanDispatcherNudger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanDispatcherNudgerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockApi = mockk<KanbanApiService>()

    @Test
    fun testNudgeDebouncingSchedulesSingleCall() =
        testScope.runTest {
            coEvery { mockApi.nudgeDispatcher(any()) } returns Response.success(DispatchResult())

            val nudger =
                DefaultKanbanDispatcherNudger(
                    apiProvider = { mockApi },
                    scope = this,
                    debounceMs = 400L,
                )

            nudger.scheduleNudge("board-a")
            advanceTimeBy(100L)
            nudger.scheduleNudge("board-a")
            advanceTimeBy(100L)
            nudger.scheduleNudge("board-a")

            // Before 400ms passes from last call
            advanceTimeBy(300L)
            coVerify(exactly = 0) { mockApi.nudgeDispatcher("board-a") }

            // After 400ms passes
            advanceTimeBy(150L)
            coVerify(exactly = 1) { mockApi.nudgeDispatcher("board-a") }
        }

    @Test
    fun testNudgeScopedPerBoard() =
        testScope.runTest {
            coEvery { mockApi.nudgeDispatcher(any()) } returns Response.success(DispatchResult())

            val nudger =
                DefaultKanbanDispatcherNudger(
                    apiProvider = { mockApi },
                    scope = this,
                    debounceMs = 400L,
                )

            nudger.scheduleNudge("board-a")
            nudger.scheduleNudge("board-b")

            advanceTimeBy(450L)

            coVerify(exactly = 1) { mockApi.nudgeDispatcher("board-a") }
            coVerify(exactly = 1) { mockApi.nudgeDispatcher("board-b") }
        }

    @Test
    fun testNudgeFailureAbsorbedSilently() =
        testScope.runTest {
            coEvery { mockApi.nudgeDispatcher(any()) } throws RuntimeException("Network timeout")

            val nudger =
                DefaultKanbanDispatcherNudger(
                    apiProvider = { mockApi },
                    scope = this,
                    debounceMs = 100L,
                )

            nudger.scheduleNudge("board-a")
            advanceTimeBy(150L)

            coVerify(exactly = 1) { mockApi.nudgeDispatcher("board-a") }
            // No unhandled exception thrown
        }
}
