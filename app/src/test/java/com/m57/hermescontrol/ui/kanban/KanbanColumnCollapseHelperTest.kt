package com.m57.hermescontrol.ui.kanban

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanColumnCollapseHelperTest {
    @Test
    fun testBoardWithoutWorkDoesNotCollapse() {
        assertFalse(
            KanbanColumnCollapseHelper.isColumnCollapsed(
                columnName = "todo",
                columnTaskCount = 0,
                boardHasWork = false,
                overrides = emptyMap(),
            ),
        )
    }

    @Test
    fun testBoardWithWorkAutoCollapsesEmptyColumn() {
        assertTrue(
            KanbanColumnCollapseHelper.isColumnCollapsed(
                columnName = "todo",
                columnTaskCount = 0,
                boardHasWork = true,
                overrides = emptyMap(),
            ),
        )

        assertFalse(
            KanbanColumnCollapseHelper.isColumnCollapsed(
                columnName = "ready",
                columnTaskCount = 3,
                boardHasWork = true,
                overrides = emptyMap(),
            ),
        )
    }

    @Test
    fun testManualOverrideTakesPrecedence() {
        // Force expand an empty column
        assertFalse(
            KanbanColumnCollapseHelper.isColumnCollapsed(
                columnName = "todo",
                columnTaskCount = 0,
                boardHasWork = true,
                overrides = mapOf("todo" to false),
            ),
        )

        // Force collapse an occupied column
        assertTrue(
            KanbanColumnCollapseHelper.isColumnCollapsed(
                columnName = "ready",
                columnTaskCount = 3,
                boardHasWork = true,
                overrides = mapOf("ready" to true),
            ),
        )
    }
}
