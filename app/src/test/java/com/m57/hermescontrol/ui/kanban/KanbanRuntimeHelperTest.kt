package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanRuntimeHelperTest {
    @Test
    fun testRunningTaskWithFreshHeartbeat() {
        val now = 1000L
        val task = KanbanTask(id = "t1", title = "Task 1", status = "running", lastHeartbeatAt = now - 30)
        val state = KanbanRuntimeHelper.arcState(task, nowSeconds = now)
        assertEquals(KanbanArcState.RUNNING, state)
    }

    @Test
    fun testRunningTaskWithStaleHeartbeat() {
        val now = 1000L
        val task = KanbanTask(id = "t1", title = "Task 1", status = "running", lastHeartbeatAt = now - 150)
        val state = KanbanRuntimeHelper.arcState(task, nowSeconds = now)
        assertEquals(KanbanArcState.STALE, state)
    }

    @Test
    fun testQueuedTask() {
        val triageTask = KanbanTask(id = "t1", title = "Task 1", status = "triage")
        assertEquals(KanbanArcState.QUEUED, KanbanRuntimeHelper.arcState(triageTask))

        val reviewTask = KanbanTask(id = "t2", title = "Task 2", status = "review")
        assertEquals(KanbanArcState.QUEUED, KanbanRuntimeHelper.arcState(reviewTask))

        val readyAssigned = KanbanTask(id = "t3", title = "Task 3", status = "ready", assignee = "coder")
        assertEquals(KanbanArcState.QUEUED, KanbanRuntimeHelper.arcState(readyAssigned))

        val readyWithFallback = KanbanTask(id = "t4", title = "Task 4", status = "ready", assignee = null)
        assertEquals(
            KanbanArcState.QUEUED,
            KanbanRuntimeHelper.arcState(readyWithFallback, fallbackAssignee = "default"),
        )

        val readyUnassigned = KanbanTask(id = "t5", title = "Task 5", status = "ready", assignee = null)
        assertNull(KanbanRuntimeHelper.arcState(readyUnassigned, fallbackAssignee = null))
    }

    @Test
    fun testIsWontRun() {
        val readyUnassigned = KanbanTask(id = "t1", title = "Task 1", status = "ready", assignee = null)
        assertTrue(KanbanRuntimeHelper.isWontRun(readyUnassigned, fallbackAssignee = null))
        assertFalse(KanbanRuntimeHelper.isWontRun(readyUnassigned, fallbackAssignee = "default"))

        val readyAssigned = KanbanTask(id = "t2", title = "Task 2", status = "ready", assignee = "coder")
        assertFalse(KanbanRuntimeHelper.isWontRun(readyAssigned, fallbackAssignee = null))

        val todoUnassigned = KanbanTask(id = "t3", title = "Task 3", status = "todo", assignee = null)
        assertFalse(KanbanRuntimeHelper.isWontRun(todoUnassigned))
    }

    @Test
    fun testFormatElapsed() {
        val now = 200_000L
        assertEquals("45s", KanbanRuntimeHelper.formatElapsed(now - 45, nowSeconds = now))
        assertEquals("5m", KanbanRuntimeHelper.formatElapsed(now - 300, nowSeconds = now))
        assertEquals("2h", KanbanRuntimeHelper.formatElapsed(now - 7200, nowSeconds = now))
        assertEquals("1d", KanbanRuntimeHelper.formatElapsed(now - 90000, nowSeconds = now))
        assertNull(KanbanRuntimeHelper.formatElapsed(null))
        assertNull(KanbanRuntimeHelper.formatElapsed(now + 10, nowSeconds = now))
    }
}
