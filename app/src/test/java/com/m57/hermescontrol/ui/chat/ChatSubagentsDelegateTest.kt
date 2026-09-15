package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.model.SubagentListItem
import com.m57.hermescontrol.data.model.SubagentListResponse
import com.m57.hermescontrol.data.model.SubagentTailResponse
import com.m57.hermescontrol.data.ws.SubagentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSubagentsDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-123"))
    private val mockRepository = mockk<SubagentRepository>()

    private lateinit var delegate: ChatSubagentsDelegate

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        delegate =
            ChatSubagentsDelegate(
                uiState = uiState,
                scope = testScope,
                ioDispatcher = testDispatcher,
                runtimeSessionId = { "session-123" },
                subagentRepository = mockRepository,
                pollingIntervalMs = 1000L,
            )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testHydrateSubagents_whenNoPriorEvents_addsNewIndicators() =
        testScope.runTest {
            coEvery { mockRepository.listSubagents("session-123") } returns
                SubagentListResponse(
                    subagents =
                        listOf(
                            SubagentListItem(
                                subagentId = "sub-1",
                                goal = "Download datasets",
                                status = "running",
                                model = "claude-sonnet",
                                elapsedSeconds = 5.2,
                            ),
                            SubagentListItem(
                                subagentId = "sub-2",
                                goal = "Train model",
                                status = "completed",
                                model = "gpt-4o",
                                elapsedSeconds = 32.0,
                            ),
                        ),
                )

            delegate.hydrateSubagents("session-123")
            advanceUntilIdle()

            val indicators = uiState.value.subagentIndicators
            assertEquals(2, indicators.size)

            val first = indicators[0]
            assertEquals("sub-1", first.subagentId)
            assertEquals("Download datasets", first.goal)
            assertEquals("running", first.status)
            assertEquals("subagent.progress", first.type)
            assertEquals("claude-sonnet", first.model)
            assertEquals(5.2, first.durationSeconds)

            val second = indicators[1]
            assertEquals("sub-2", second.subagentId)
            assertEquals("Train model", second.goal)
            assertEquals("completed", second.status)
            assertEquals("subagent.complete", second.type)
            assertEquals(32.0, second.durationSeconds)
        }

    @Test
    fun testHydrateSubagents_deduplicatesAndMergesWithExistingPushEvent() =
        testScope.runTest {
            val initialLogs = listOf(SubagentLogLine(text = "Downloading chunk 1"))
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.progress",
                                goal = "Download datasets",
                                taskIndex = 1,
                                taskCount = 3,
                                subagentId = "sub-1",
                                logs = initialLogs,
                                lastEventTimestamp = 1000L,
                            ),
                        ),
                )

            coEvery { mockRepository.listSubagents("session-123") } returns
                SubagentListResponse(
                    subagents =
                        listOf(
                            SubagentListItem(
                                subagentId = "sub-1",
                                goal = "Download datasets",
                                status = "running",
                                model = "claude-sonnet",
                                elapsedSeconds = 8.5,
                            ),
                        ),
                )

            delegate.hydrateSubagents("session-123")
            advanceUntilIdle()

            val indicators = uiState.value.subagentIndicators
            assertEquals(1, indicators.size)

            val merged = indicators.first()
            assertEquals("sub-1", merged.subagentId)
            assertEquals(1, merged.taskIndex)
            assertEquals(3, merged.taskCount)
            assertEquals(initialLogs, merged.logs)
            assertEquals("claude-sonnet", merged.model)
            assertEquals(8.5, merged.durationSeconds)
            assertEquals("running", merged.status)
        }

    @Test
    fun testHydrateSubagents_newerPushEventWinsOverOlderInFlightSnapshot() =
        testScope.runTest {
            val requestTime = 1000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.complete",
                        subagentId = "sub-1",
                        goal = "Run benchmark",
                        status = "completed",
                        lastEventTimestamp = 2000L, // Newer than requestTime
                    ),
                )

            val listItems =
                listOf(
                    SubagentListItem(
                        subagentId = "sub-1",
                        goal = "Run benchmark",
                        status = "running", // Older snapshot state
                        elapsedSeconds = 12.0,
                    ),
                )

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(1, result.size)
            assertEquals("completed", result.first().status)
            assertEquals("subagent.complete", result.first().type)
        }

    @Test
    fun testHydrateSubagents_terminalStateNotRevertedToRunning() =
        testScope.runTest {
            val requestTime = 2000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.complete",
                        subagentId = "sub-1",
                        goal = "Run test suite",
                        status = "completed",
                        lastEventTimestamp = 1000L,
                    ),
                )

            val listItems =
                listOf(
                    SubagentListItem(
                        subagentId = "sub-1",
                        goal = "Run test suite",
                        status = "running",
                    ),
                )

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(1, result.size)
            assertEquals("completed", result.first().status)
            assertEquals("subagent.complete", result.first().type)
        }

    @Test
    fun testHydrateSubagents_removesStaleRunningIndicatorOmittedFromSnapshot() =
        testScope.runTest {
            val requestTime = 1000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-1",
                        goal = "Active worker 1",
                        status = "running",
                        lastEventTimestamp = 900L,
                    ),
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-2",
                        goal = "Active worker 2",
                        status = "running",
                        lastEventTimestamp = 900L,
                    ),
                )

            val listItems =
                listOf(
                    SubagentListItem(
                        subagentId = "sub-2",
                        goal = "Active worker 2",
                        status = "running",
                    ),
                )

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(1, result.size)
            assertFalse(result.any { it.subagentId == "sub-1" })
            assertTrue(result.any { it.subagentId == "sub-2" })
        }

    @Test
    fun testHydrateSubagents_newerPushEventIndicatorOmittedFromSnapshotIsNotRemoved() =
        testScope.runTest {
            val requestTime = 1000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.start",
                        subagentId = "sub-1",
                        goal = "Spawned during request",
                        status = "running",
                        lastEventTimestamp = 1500L, // Newer than requestTime (1000L)
                    ),
                )

            // Older snapshot in-flight returns without sub-1
            val listItems = emptyList<SubagentListItem>()

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(1, result.size)
            assertEquals("sub-1", result.first().subagentId)
            assertEquals("running", result.first().status)
        }

    @Test
    fun testHydrateSubagents_placeholderWithoutSubagentIdIsPreserved() =
        testScope.runTest {
            val requestTime = 1000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.spawn_requested",
                        subagentId = null,
                        goal = "Plan decomposition",
                        status = "running",
                        lastEventTimestamp = 800L,
                    ),
                )

            val listItems = emptyList<SubagentListItem>()

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(1, result.size)
            assertNull(result.first().subagentId)
            assertEquals("Plan decomposition", result.first().goal)
        }

    @Test
    fun testHydrateSubagents_terminalIndicatorsOmittedFromSnapshotArePreserved() =
        testScope.runTest {
            val requestTime = 2000L
            val current =
                listOf(
                    SubagentIndicator(
                        type = "subagent.complete",
                        subagentId = "sub-completed",
                        goal = "Finished task",
                        status = "completed",
                        lastEventTimestamp = 1000L,
                    ),
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-failed",
                        goal = "Failed task",
                        status = "failed",
                        lastEventTimestamp = 1000L,
                    ),
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-cancelled",
                        goal = "Cancelled task",
                        status = "cancelled",
                        lastEventTimestamp = 1000L,
                    ),
                )

            // Snapshot active roster omits all terminal subagents
            val listItems = emptyList<SubagentListItem>()

            val result = delegate.mergeSubagentList(current, listItems, requestTime)
            assertEquals(3, result.size)
            assertTrue(result.any { it.subagentId == "sub-completed" && it.isComplete })
            assertTrue(result.any { it.subagentId == "sub-failed" && it.isFailed })
            assertTrue(result.any { it.subagentId == "sub-cancelled" && it.isCancelled })
        }

    @Test
    fun testHydrateSubagents_nullResponseDoesNotClearIndicators() =
        testScope.runTest {
            val initial =
                listOf(
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-1",
                        goal = "Active worker",
                        status = "running",
                        lastEventTimestamp = 500L,
                    ),
                )
            uiState.value = uiState.value.copy(subagentIndicators = initial)

            coEvery { mockRepository.listSubagents("session-123") } returns null

            delegate.hydrateSubagents("session-123")
            advanceUntilIdle()

            assertEquals(initial, uiState.value.subagentIndicators)
        }

    @Test
    fun testHydrateSubagents_exceptionDoesNotClearIndicators() =
        testScope.runTest {
            val initial =
                listOf(
                    SubagentIndicator(
                        type = "subagent.progress",
                        subagentId = "sub-1",
                        goal = "Active worker",
                        status = "running",
                        lastEventTimestamp = 500L,
                    ),
                )
            uiState.value = uiState.value.copy(subagentIndicators = initial)

            coEvery { mockRepository.listSubagents("session-123") } throws RuntimeException("Network timeout")

            delegate.hydrateSubagents("session-123")
            advanceUntilIdle()

            assertEquals(initial, uiState.value.subagentIndicators)
        }

    @Test
    fun testHydrateSubagents_reconnectReconciliationRemovesStaleRunningCard() =
        testScope.runTest {
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.start",
                                subagentId = "sub-1",
                                goal = "Download files",
                                status = "running",
                                lastEventTimestamp = 500L,
                            ),
                            SubagentIndicator(
                                type = "subagent.start",
                                subagentId = "sub-2",
                                goal = "Process files",
                                status = "running",
                                lastEventTimestamp = 500L,
                            ),
                        ),
                )

            // sub-1 finished while disconnected, sub-2 is still running
            coEvery { mockRepository.listSubagents("session-123") } returns
                SubagentListResponse(
                    subagents =
                        listOf(
                            SubagentListItem(
                                subagentId = "sub-2",
                                goal = "Process files",
                                status = "running",
                                elapsedSeconds = 15.0,
                            ),
                        ),
                )

            delegate.hydrateSubagents("session-123")
            advanceUntilIdle()

            val indicators = uiState.value.subagentIndicators
            assertEquals(1, indicators.size)
            assertEquals("sub-2", indicators.first().subagentId)
            assertFalse(indicators.any { it.subagentId == "sub-1" })
        }

    @Test
    fun testInspectSubagentTranscript_replacesTailWithoutDuplicateAppending() =
        testScope.runTest {
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.progress",
                                subagentId = "sub-1",
                                status = "running",
                            ),
                        ),
                )

            coEvery { mockRepository.tailSubagent("session-123", "sub-1", 16384) } returnsMany
                listOf(
                    SubagentTailResponse(subagentId = "sub-1", text = "Line 1\n", truncated = false),
                    SubagentTailResponse(subagentId = "sub-1", text = "Line 1\nLine 2\n", truncated = false),
                )

            delegate.inspectSubagentTranscript("sub-1")
            assertEquals("sub-1", uiState.value.inspectingSubagentId)
            assertTrue(uiState.value.subagentTranscript?.isLoading == true)

            // Advance time to run first poll
            advanceTimeBy(100)
            assertEquals("Line 1\n", uiState.value.subagentTranscript?.text)
            assertFalse(uiState.value.subagentTranscript?.isLoading == true)

            // Advance time by polling interval
            advanceTimeBy(1000)
            assertEquals("Line 1\nLine 2\n", uiState.value.subagentTranscript?.text)

            delegate.closeSubagentTranscript()
        }

    @Test
    fun testInspectSubagentTranscript_stopsPollingWhenSubagentCompletes() =
        testScope.runTest {
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.progress",
                                subagentId = "sub-1",
                                status = "running",
                            ),
                        ),
                )

            coEvery { mockRepository.tailSubagent("session-123", "sub-1", 16384) } returns
                SubagentTailResponse(subagentId = "sub-1", text = "Finished output", truncated = false)

            delegate.inspectSubagentTranscript("sub-1")
            advanceTimeBy(100)
            assertEquals("Finished output", uiState.value.subagentTranscript?.text)

            // Mark subagent completed
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.complete",
                                subagentId = "sub-1",
                                status = "completed",
                            ),
                        ),
                )

            advanceTimeBy(3000)
            // Verify tailSubagent was called at most once or twice, not indefinitely
            coVerify(atMost = 2) { mockRepository.tailSubagent("session-123", "sub-1", 16384) }
            delegate.closeSubagentTranscript()
        }

    @Test
    fun testCloseSubagentTranscript_cancelsPollingAndClearsState() =
        testScope.runTest {
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.progress",
                                subagentId = "sub-1",
                                status = "running",
                            ),
                        ),
                )

            coEvery { mockRepository.tailSubagent("session-123", "sub-1", 16384) } returns
                SubagentTailResponse(subagentId = "sub-1", text = "Output")

            delegate.inspectSubagentTranscript("sub-1")
            advanceTimeBy(100)
            assertNotNull(uiState.value.subagentTranscript)

            delegate.closeSubagentTranscript()
            assertNull(uiState.value.inspectingSubagentId)
            assertNull(uiState.value.subagentTranscript)
        }

    @Test
    fun testTailError_setsRetryableErrorState() =
        testScope.runTest {
            uiState.value =
                uiState.value.copy(
                    subagentIndicators =
                        listOf(
                            SubagentIndicator(
                                type = "subagent.progress",
                                subagentId = "sub-1",
                                status = "running",
                            ),
                        ),
                )

            coEvery { mockRepository.tailSubagent("session-123", "sub-1", 16384) } throws
                RuntimeException("Gateway connection error")

            delegate.inspectSubagentTranscript("sub-1")
            advanceTimeBy(100)

            val transcript = uiState.value.subagentTranscript
            assertNotNull(transcript)
            assertEquals("Gateway connection error", transcript?.error)
            assertFalse(transcript?.isLoading == true)

            // Now recover on retry
            coEvery { mockRepository.tailSubagent("session-123", "sub-1", 16384) } returns
                SubagentTailResponse(subagentId = "sub-1", text = "Recovered logs")

            delegate.retryTranscript()
            advanceTimeBy(100)

            val recovered = uiState.value.subagentTranscript
            assertEquals("Recovered logs", recovered?.text)
            assertNull(recovered?.error)

            delegate.closeSubagentTranscript()
        }
}
