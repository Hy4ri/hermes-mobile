package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserReturnTrackerTest {
    @Test
    fun `browser return requires a departure and consumes the lease once`() {
        val tracker = BrowserReturnTracker()
        tracker.start("operation-1")
        assertNull(tracker.onResume())
        tracker.onPause()
        assertEquals("operation-1", tracker.onResume()?.operationId)
        assertNull(tracker.onResume())
        assertFalse(tracker.abandon())
    }

    @Test
    fun `legacy browser without operation id still reports return`() {
        val tracker = BrowserReturnTracker()
        tracker.start()
        tracker.onPause()
        assertTrue(tracker.onResume() != null)
        assertNull(tracker.onResume())
    }

    @Test
    fun `failed launch clears tracking and does not release a departed lease`() {
        val tracker = BrowserReturnTracker()
        tracker.start("operation-1")
        tracker.onPause()
        tracker.cancel()
        assertNull(tracker.onResume())
        assertFalse(tracker.abandon())
    }

    @Test
    fun `non configuration disposal releases only a departed lease and clears the id`() {
        val tracker = BrowserReturnTracker()
        tracker.start("operation-1")
        assertFalse(tracker.abandon())
        tracker.start("operation-2")
        tracker.onPause()
        assertTrue(tracker.abandon())
        assertFalse(tracker.abandon())
        assertNull(tracker.onResume())
    }

    @Test
    fun `new launch resets departed state from the previous launch`() {
        val tracker = BrowserReturnTracker()
        tracker.start("first")
        tracker.onPause()
        tracker.start("second")
        assertNull(tracker.onResume())
        tracker.onPause()
        assertEquals("second", tracker.onResume()?.operationId)
    }
}
