package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHealthTest {
    private class Fixture(
        val scope: CoroutineScope,
        var connected: Boolean = true,
        var pingDelayMs: Long = 100L,
        var pingShouldFail: Boolean = false,
        var pendingReplyState: Boolean = false,
        var initialTimeMs: Long = 100_000L,
    ) {
        var currentTimeMs: Long = initialTimeMs
        val pingCalls = mutableListOf<Long>()
        val cancelCalls = AtomicInteger(0)
        val logs = mutableListOf<String>()
        val debugLogs = mutableListOf<String>()

        val health: ConnectionHealth =
            ConnectionHealth(
                scope = scope,
                isConnected = { connected },
                pingRequest = { timeoutMs ->
                    pingCalls.add(timeoutMs)
                    if (pingShouldFail) {
                        throw IllegalStateException("Timed out after ${timeoutMs}ms")
                    }
                    if (pingDelayMs > 0) {
                        delay(pingDelayMs)
                        currentTimeMs += pingDelayMs
                    }
                },
                cancelSocket = {
                    cancelCalls.incrementAndGet()
                },
                pendingReply = { pendingReplyState },
                nowMs = { currentTimeMs },
                log = { logs.add(it) },
                debugLog = { debugLogs.add(it) },
            )
    }

    @Test
    fun pingClockElapsedAndFlowUpdates() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingDelayMs = 120L,
                    initialTimeMs = 1_000L,
                )
            try {
                assertEquals(1_000L, fixture.currentTimeMs)
                val latency = fixture.health.ping(timeoutMs = 5000L)
                assertEquals(120L, latency)
                assertEquals(120L, fixture.health.lastLatencyMs.value)
                assertEquals(1_120L, fixture.health.lastPongTimestamp)
                assertEquals(listOf(5000L), fixture.pingCalls)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun pingFailureLeavesPreviousLatencyUntouched() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingDelayMs = 50L,
                    initialTimeMs = 5_000L,
                )
            try {
                val firstLatency = fixture.health.ping(timeoutMs = 5000L)
                assertEquals(50L, firstLatency)
                assertEquals(50L, fixture.health.lastLatencyMs.value)

                fixture.pingShouldFail = true
                try {
                    fixture.health.ping(timeoutMs = 2000L)
                    fail("Expected ping failure")
                } catch (expected: IllegalStateException) {
                    // Expected
                }

                // Previous latency state is preserved
                assertEquals(50L, fixture.health.lastLatencyMs.value)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun stopResetsLatencyToNull() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingDelayMs = 40L,
                )
            try {
                fixture.health.ping()
                assertEquals(40L, fixture.health.lastLatencyMs.value)

                fixture.health.stop()
                assertNull(fixture.health.lastLatencyMs.value)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun onInboundFrameUpdatesPongTimestamp() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    initialTimeMs = 10_000L,
                )
            try {
                assertEquals(0L, fixture.health.lastPongTimestamp)

                fixture.health.onInboundFrame()
                assertEquals(10_000L, fixture.health.lastPongTimestamp)

                fixture.currentTimeMs = 25_000L
                fixture.health.onInboundFrame()
                assertEquals(25_000L, fixture.health.lastPongTimestamp)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun isHealthyStrictlyEnforcesStaleThresholdOf30000() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    connected = true,
                    initialTimeMs = 50_000L,
                )
            try {
                fixture.health.onInboundFrame()
                assertEquals(50_000L, fixture.health.lastPongTimestamp)

                // now - lastPong < 30000 is healthy
                fixture.currentTimeMs = 50_000L + 29_999L
                assertTrue(fixture.health.isHealthy)

                // now - lastPong == 30000 is NOT healthy (< 30000 required)
                fixture.currentTimeMs = 50_000L + 30_000L
                assertFalse(fixture.health.isHealthy)

                // now - lastPong > 30000 is NOT healthy
                fixture.currentTimeMs = 50_000L + 30_001L
                assertFalse(fixture.health.isHealthy)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun isHealthyDisconnectedGuard() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    connected = true,
                    initialTimeMs = 10_000L,
                )
            try {
                fixture.health.onInboundFrame()
                assertTrue(fixture.health.isHealthy)

                fixture.connected = false
                assertFalse(fixture.health.isHealthy)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun forceHealthCheckForTestWatchdogStrictThresholdCancelsSocket() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    connected = true,
                    initialTimeMs = 100_000L,
                )
            try {
                fixture.health.onInboundFrame()

                // Within stale threshold (30000ms), no cancellation
                fixture.health.forceHealthCheckForTest(30_000L)
                assertEquals(0, fixture.cancelCalls.get())

                // Exceeding stale threshold (> 30000ms), cancels socket
                fixture.health.forceHealthCheckForTest(30_001L)
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun periodicHeartbeatCancelsSocketWhenStaleThresholdExceeded() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    connected = true,
                    pingShouldFail = true,
                    pingDelayMs = 0L,
                    initialTimeMs = 10_000L,
                )
            try {
                fixture.health.start()
                runCurrent()
                fixture.health.onInboundFrame()

                // Advance by heartbeat interval (15000ms), still fresh
                advanceTimeBy(15_000L)
                fixture.currentTimeMs += 15_000L
                runCurrent()
                assertEquals(0, fixture.cancelCalls.get())

                // Advance another heartbeat interval to 30000ms from last inbound frame
                advanceTimeBy(15_000L)
                fixture.currentTimeMs += 15_000L
                runCurrent()
                assertEquals(0, fixture.cancelCalls.get())

                // Now time elapsed is > 30000ms (e.g. 30001ms)
                fixture.currentTimeMs += 1L
                advanceTimeBy(15_000L)
                fixture.currentTimeMs += 15_000L
                runCurrent()
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun setLastLatencyForTestSetsFlowDirectly() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                )
            try {
                assertNull(fixture.health.lastLatencyMs.value)
                fixture.health.setLastLatencyForTest(75L)
                assertEquals(75L, fixture.health.lastLatencyMs.value)
                fixture.health.setLastLatencyForTest(null)
                assertNull(fixture.health.lastLatencyMs.value)
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnTransportChangeSuccess() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingDelayMs = 25L,
                )
            try {
                var customActionCalled = false
                fixture.health.probeLivenessOnTransportChange(
                    timeoutMs = 5000L,
                    onFailureAction = { customActionCalled = true },
                )
                runCurrent()

                assertEquals(listOf(5000L), fixture.pingCalls)
                assertFalse(customActionCalled)
                assertEquals(0, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnTransportChangeFailureInvokesCustomAction() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                )
            try {
                var customActionCalled = false
                fixture.health.probeLivenessOnTransportChange(
                    timeoutMs = 4000L,
                    onFailureAction = { customActionCalled = true },
                )
                runCurrent()

                assertEquals(listOf(4000L), fixture.pingCalls)
                assertTrue(customActionCalled)
                assertEquals(0, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnTransportChangeFailureInvokesDefaultCancelSocket() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                )
            try {
                fixture.health.probeLivenessOnTransportChange(
                    timeoutMs = 5000L,
                )
                runCurrent()

                assertEquals(listOf(5000L), fixture.pingCalls)
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnWakeIdleCancelsImmediatelyOnFailure() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                    pendingReplyState = false,
                )
            try {
                fixture.health.probeLivenessOnWake(deferredOnce = false)
                runCurrent()

                assertEquals(listOf(5000L), fixture.pingCalls)
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnWakePendingReplyFirstFailureDefers3000MsAndRetriesOnce() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                    pendingReplyState = true,
                )
            try {
                fixture.health.probeLivenessOnWake(deferredOnce = false)
                runCurrent()

                // First probe fails, but because pendingReply=true, it does not cancel immediately.
                // It schedules a deferred retry after 3000ms.
                assertEquals(1, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())

                // Advance before 3000ms
                advanceTimeBy(2999L)
                runCurrent()
                assertEquals(1, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())

                // Advance to 3000ms - second ping is executed
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(2, fixture.pingCalls.size)
                // Second probe fails, now it cancels
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnWakePendingReplySecondProbeSucceedsLeavesSocketOpen() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                    pendingReplyState = true,
                    pingDelayMs = 10L,
                )
            try {
                fixture.health.probeLivenessOnWake(deferredOnce = false)
                runCurrent()

                // First attempt failed, pending deferred retry in 3000ms
                assertEquals(1, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())

                // Before retry fires, recovery occurs: pings now succeed
                fixture.pingShouldFail = false

                advanceTimeBy(3000L)
                runCurrent()

                assertEquals(2, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun cancelForegroundProbeCancelsPendingProbe() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                    pendingReplyState = true,
                )
            try {
                fixture.health.probeLivenessOnWake(deferredOnce = false)
                runCurrent()

                assertEquals(1, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())

                // Cancel the scheduled retry
                fixture.health.cancelForegroundProbe()

                advanceTimeBy(5000L)
                runCurrent()

                // No second probe should have been made, no cancellation
                assertEquals(1, fixture.pingCalls.size)
                assertEquals(0, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnTransportChangeSuccessLogsAtDebugLevelWithoutWarning() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingDelayMs = 25L,
                )
            try {
                fixture.health.probeLivenessOnTransportChange(timeoutMs = 5000L)
                runCurrent()
                advanceTimeBy(25L)
                runCurrent()

                assertEquals(listOf(5000L), fixture.pingCalls)
                assertEquals(
                    listOf(
                        "Network transport changed — probing WebSocket liveness",
                        "WebSocket liveness probe succeeded on new transport",
                    ),
                    fixture.debugLogs,
                )
                assertTrue(fixture.logs.isEmpty())
                assertEquals(0, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }

    @Test
    fun probeLivenessOnTransportChangeFailureLogsDebugAndWarningAndCancelsSocket() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    pingShouldFail = true,
                )
            try {
                fixture.health.probeLivenessOnTransportChange(timeoutMs = 5000L)
                runCurrent()

                assertEquals(listOf(5000L), fixture.pingCalls)
                assertEquals(
                    listOf("Network transport changed — probing WebSocket liveness"),
                    fixture.debugLogs,
                )
                assertEquals(
                    listOf("Transport change liveness probe failed — cancelling socket"),
                    fixture.logs,
                )
                assertEquals(1, fixture.cancelCalls.get())
            } finally {
                fixture.health.stop()
            }
        }
}
