package com.m57.hermescontrol.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CronJobDisplayStateTest {
    @Test
    fun `scheduled and legacy active map to ACTIVE with pause only`() {
        listOf("scheduled", "active").forEach {
            val s = CronJobDisplayState.from(it)
            assertEquals(CronJobDisplayState.ACTIVE, s)
            assertTrue(s.canPause)
            assertFalse(s.canResume)
        }
    }

    @Test
    fun `completed has no pause or resume`() {
        val s = CronJobDisplayState.from("completed")
        assertEquals(CronJobDisplayState.COMPLETED, s)
        assertFalse(s.canPause)
        assertFalse(s.canResume)
    }

    @Test
    fun `error can be resumed`() {
        val s = CronJobDisplayState.from("error")
        assertEquals(CronJobDisplayState.ERROR, s)
        assertTrue(s.canResume)
    }

    @Test
    fun `paused null and unknown fall back to PAUSED with resume`() {
        listOf("paused", null, "weird").forEach {
            val s = CronJobDisplayState.from(it)
            assertEquals(CronJobDisplayState.PAUSED, s)
            assertTrue(s.canResume)
            assertFalse(s.canPause)
        }
    }
}
