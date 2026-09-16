package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KanbanLaneGroupingTest {
    @Test
    fun testGroupRunningTasksSeparatesProfilesDeterministically() {
        val tasks =
            listOf(
                KanbanTask(id = "t1", title = "Task 1", status = "running", assignee = "reviewer"),
                KanbanTask(id = "t2", title = "Task 2", status = "running", assignee = "coder"),
                KanbanTask(id = "t3", title = "Task 3", status = "running", assignee = null),
                KanbanTask(id = "t4", title = "Task 4", status = "running", assignee = "coder"),
            )

        val groups = KanbanLaneGrouping.groupRunningTasks(tasks)

        // Coder first (alphabetical), then Reviewer, then Unassigned
        assertEquals(3, groups.size)

        assertEquals("coder", groups[0].assignee)
        assertEquals(listOf("t2", "t4"), groups[0].tasks.map { it.id })

        assertEquals("reviewer", groups[1].assignee)
        assertEquals(listOf("t1"), groups[1].tasks.map { it.id })

        assertNull(groups[2].assignee)
        assertEquals(listOf("t3"), groups[2].tasks.map { it.id })
    }

    @Test
    fun testGroupRunningTasksWithoutUnassigned() {
        val tasks =
            listOf(
                KanbanTask(id = "t1", title = "Task 1", status = "running", assignee = "coder"),
            )
        val groups = KanbanLaneGrouping.groupRunningTasks(tasks)
        assertEquals(1, groups.size)
        assertEquals("coder", groups[0].assignee)
    }

    @Test
    fun testGroupRunningTasksEmpty() {
        val groups = KanbanLaneGrouping.groupRunningTasks(emptyList())
        assertEquals(0, groups.size)
    }
}
