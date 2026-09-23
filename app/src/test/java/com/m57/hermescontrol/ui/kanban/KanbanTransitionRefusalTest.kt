package com.m57.hermescontrol.ui.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KanbanTransitionRefusalTest {
    @Test
    fun `ready refusal names blocking parent ids and statuses`() {
        val refusal =
            kanbanTransitionRefusal(
                "Cannot move to 'ready': blocked by parent(s) not done — 'Build' (t_a, status=ready), " +
                    "'Review' (t_b, status=review)",
            )
        assertEquals(listOf("t_a", "t_b"), refusal?.blockingParentIds)
        assertEquals(listOf("ready", "review"), refusal?.blockingParentStatuses)
    }

    @Test
    fun `done refusal identifies unsatisfied parent relationship`() {
        val refusal =
            kanbanTransitionRefusal(
                "cannot move t_c to 'done': unsatisfied parent dependencies: t_a (ready), t_b (running); " +
                    "complete the parents first (done or archived)",
            )
        assertEquals(listOf("t_a", "t_b"), refusal?.blockingParentIds)
        assertEquals(listOf("ready", "running"), refusal?.blockingParentStatuses)
    }

    @Test
    fun `generic conflict is not falsely classified as dependency refusal`() {
        assertNull(kanbanTransitionRefusal("status transition to 'done' not valid from current state"))
    }
}
