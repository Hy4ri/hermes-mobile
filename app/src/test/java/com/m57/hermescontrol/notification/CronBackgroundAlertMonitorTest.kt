package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.model.CronJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CronBackgroundAlertMonitorTest {
    @Test
    fun `first snapshot is silent and only a newer completion alerts`() {
        val tracker = CronCompletionTracker()

        assertEquals(emptyList<CronRunAlert>(), tracker.observe(listOf(job("2026-11-22T22:00:00Z"))))
        val next = job("2026-11-23T01:01:00+03:00")
        assertEquals(listOf(CronRunAlert("report", "Triage", CronRunOutcome.Completed)), tracker.observe(listOf(next)))
        // An edit, an equivalent timestamp, and an older result are not new runs.
        assertEquals(emptyList<CronRunAlert>(), tracker.observe(listOf(next.copy(name = "Renamed"))))
        assertEquals(emptyList<CronRunAlert>(), tracker.observe(listOf(job("2026-11-22T22:01:00Z"))))
        assertEquals(emptyList<CronRunAlert>(), tracker.observe(listOf(job("2026-11-22T21:00:00Z"))))
    }

    @Test
    fun `new jobs and terminal outcomes are picked up but skipped work is silent`() {
        val tracker = CronCompletionTracker()
        tracker.observe(listOf(job(null)))

        val results =
            tracker.observe(
                listOf(
                    job("2026-11-22T22:00:00Z", "error"),
                    job("2026-11-22T22:01:00.123456Z", "delivery_failed").copy(id = "delivery"),
                    job("2026-11-22T22:02:00Z", "no_change").copy(id = "skipped"),
                    job("bad timestamp").copy(id = "invalid"),
                ),
            )

        assertEquals(
            listOf(
                CronRunAlert("report", "Triage", CronRunOutcome.Failed),
                CronRunAlert("delivery", "Triage", CronRunOutcome.DeliveryFailed),
            ),
            results,
        )
    }

    @Test
    fun `offline checks and failed reads do not replace the baseline`() =
        runTest {
            val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var connected = true
            var snapshot: List<CronJob>? = listOf(job("2026-11-22T22:00:00Z"))
            var reads = 0
            val alerts = mutableListOf<CronRunAlert>()
            backgroundScope.launch {
                watchCronCompletions(refreshes, { true }, { connected }, {
                    reads++
                    snapshot
                }, { alerts += it })
            }
            runCurrent()
            refreshes.tryEmit(Unit)
            runCurrent()

            connected = false
            refreshes.tryEmit(Unit)
            runCurrent()
            assertEquals(1, reads)

            connected = true
            snapshot = null
            refreshes.tryEmit(Unit)
            runCurrent()
            assertEquals(2, reads)

            snapshot = listOf(job("2026-11-22T22:01:00Z"))
            repeat(2) {
                refreshes.tryEmit(Unit)
                runCurrent()
            }
            assertEquals(listOf(CronRunAlert("report", "Triage", CronRunOutcome.Completed)), alerts)
        }

    @Test
    fun `an in flight read cannot alert after the owner or opt in changes`() =
        runTest {
            val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var enabled = true
            var reads = 0
            val inFlight = CompletableDeferred<List<CronJob>>()
            val alerts = mutableListOf<CronRunAlert>()
            backgroundScope.launch {
                watchCronCompletions(
                    refreshes,
                    canMonitor = { enabled },
                    isConnected = { true },
                    loadJobs = { if (++reads == 1) listOf(job("2026-11-22T22:00:00Z")) else inFlight.await() },
                    notify = { alerts += it },
                )
            }
            runCurrent()
            refreshes.tryEmit(Unit)
            runCurrent()
            refreshes.tryEmit(Unit)
            runCurrent()
            assertEquals(2, reads)

            enabled = false
            inFlight.complete(listOf(job("2026-11-22T22:01:00Z")))
            runCurrent()
            assertEquals(emptyList<CronRunAlert>(), alerts)

            // The unmuted monitor establishes a fresh baseline, not a delayed alert.
            enabled = true
            refreshes.tryEmit(Unit)
            runCurrent()
            assertEquals(emptyList<CronRunAlert>(), alerts)
        }

    private fun job(
        stamp: String?,
        status: String = "ok",
    ): CronJob = CronJob(id = "report", name = "Triage", last_run_at = stamp, last_status = status)
}
