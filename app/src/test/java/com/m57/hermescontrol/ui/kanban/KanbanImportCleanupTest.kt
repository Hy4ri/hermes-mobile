package com.m57.hermescontrol.ui.kanban

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanImportCleanupTest {
    @Test
    fun cleansAfterSuccess() =
        runTest {
            var cleaned = false
            val result =
                withKanbanImportCleanup(
                    cleanup = { cleaned = true },
                    onCleanupFailure = { throw AssertionError(it) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) { "imported" }
            assertEquals("imported", result)
            assertTrue(cleaned)
        }

    @Test
    fun cleansAfterImportFailureWithoutMaskingIt() =
        runTest {
            val failure = IllegalStateException("import failed")
            var cleaned = false
            try {
                withKanbanImportCleanup(
                    cleanup = { cleaned = true },
                    onCleanupFailure = { throw AssertionError(it) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) { throw failure }
            } catch (e: IllegalStateException) {
                assertSame(failure, e)
            }
            assertTrue(cleaned)
        }

    @Test
    fun cancellationWaitsForSuspendingCleanupAndRemainsCancelled() =
        runTest {
            var cleaned = false
            var cancellationPropagated = false
            val job =
                launch {
                    try {
                        withKanbanImportCleanup(
                            cleanup = {
                                delay(100)
                                cleaned = true
                            },
                            onCleanupFailure = { throw AssertionError(it) },
                            dispatcher = StandardTestDispatcher(testScheduler),
                        ) { awaitCancellation() }
                    } catch (e: CancellationException) {
                        cancellationPropagated = true
                        throw e
                    }
                }
            runCurrent()
            job.cancel()
            advanceUntilIdle()
            assertTrue(cleaned)
            assertTrue(cancellationPropagated)
            assertTrue(job.isCancelled)
        }

    @Test
    fun cleanupFailureIsReportedWithoutMaskingImportResult() =
        runTest {
            val failure = IllegalStateException("HTTP 500")
            var reported: Exception? = null
            val result =
                withKanbanImportCleanup(
                    cleanup = { throw failure },
                    onCleanupFailure = { reported = it },
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) { "imported" }
            assertEquals("imported", result)
            // Coroutine stack-trace recovery can copy exceptions across dispatcher boundaries.
            assertEquals(failure.message, reported?.message)
            assertTrue(reported is IllegalStateException)
        }

    @Test
    fun cleanupIsBounded() =
        runTest {
            var reported = false
            withKanbanImportCleanup(
                cleanup = { awaitCancellation() },
                onCleanupFailure = { reported = true },
                dispatcher = StandardTestDispatcher(testScheduler),
            ) { Unit }
            assertTrue(reported)
            assertEquals(10_000L, testScheduler.currentTime)
        }
}
