package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronJobModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `isRunInFlight returns true when live_execution is present`() {
        val job =
            CronJob(
                id = "j1",
                name = "Test",
                live_execution = CronJobExecution(id = "exec-1", status = "running"),
            )
        assertTrue(job.isRunInFlight)
    }

    @Test
    fun `isRunInFlight returns true when latest_execution is running or claimed`() {
        val runningJob =
            CronJob(
                id = "j2",
                name = "Test",
                latest_execution = CronJobExecution(id = "exec-2", status = "running"),
            )
        assertTrue(runningJob.isRunInFlight)

        val claimedJob =
            CronJob(
                id = "j3",
                name = "Test",
                latest_execution = CronJobExecution(id = "exec-3", status = "claimed"),
            )
        assertTrue(claimedJob.isRunInFlight)
    }

    @Test
    fun `isRunInFlight returns false when idle`() {
        val completedJob =
            CronJob(
                id = "j4",
                name = "Test",
                latest_execution = CronJobExecution(id = "exec-4", status = "completed"),
            )
        assertFalse(completedJob.isRunInFlight)

        val noExecJob =
            CronJob(
                id = "j5",
                name = "Test",
            )
        assertFalse(noExecJob.isRunInFlight)
    }

    @Test
    fun `deserializes CronJob with live_execution and missed-run fields`() {
        val raw =
            """
            {
                "id": "job-100",
                "name": "Hourly sync",
                "timezone": "America/New_York",
                "grace_minutes": 45,
                "catch_up": true,
                "live_execution": {
                    "id": "e-10",
                    "status": "running",
                    "claimed_at": "2026-11-02T18:00:00Z"
                },
                "latest_execution": {
                    "id": "e-10",
                    "status": "running"
                }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<CronJob>(raw)
        assertEquals("job-100", parsed.id)
        assertEquals("America/New_York", parsed.timezone)
        assertEquals(45, parsed.grace_minutes)
        assertEquals(true, parsed.catch_up)
        assertNotNull(parsed.live_execution)
        assertEquals("e-10", parsed.live_execution?.id)
        assertEquals("running", parsed.live_execution?.status)
        assertTrue(parsed.isRunInFlight)
    }

    @Test
    fun `deserializes CronCancelResult`() {
        val raw =
            """
            {
                "cancelled": true,
                "execution_id": "e-10",
                "job_id": "job-100",
                "detail": "Cancel requested; the run stops at its next cancel check."
            }
            """.trimIndent()

        val parsed = json.decodeFromString<CronCancelResult>(raw)
        assertTrue(parsed.cancelled)
        assertEquals("e-10", parsed.execution_id)
        assertEquals("job-100", parsed.job_id)
        assertEquals("Cancel requested; the run stops at its next cancel check.", parsed.detail)
    }
}
