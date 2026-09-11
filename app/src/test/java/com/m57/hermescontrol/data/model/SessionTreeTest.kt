package com.m57.hermescontrol.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTreeTest {
    @Test
    fun nestsBranchesAndUsesDesktopFallbackNames() {
        val sessions =
            listOf(
                SessionInfo(id = "unrelated", title = "Recent root", last_active = 10.0),
                SessionInfo(id = "parent", title = "Original", last_active = 5.0),
                SessionInfo(id = "branch-b", parent_session_id = "parent", last_active = 6.0),
                SessionInfo(
                    id = "branch-a",
                    parent_session_id = "parent",
                    preview = "copied prompt",
                    last_active = 7.0,
                ),
                SessionInfo(
                    id = "nested",
                    parent_session_id = "branch-a",
                    title = "Named nested branch",
                    last_active = 8.0,
                ),
            )

        val tree = flattenSessionsWithBranches(sessions)

        // groupRecency of parent is max(5.0, 6.0, 7.0, 8.0) = 8.0
        // unrelated is 10.0 -> unrelated first, then parent family
        assertEquals(listOf("unrelated", "parent", "branch-a", "nested", "branch-b"), tree.map { it.session.id })
        assertEquals(listOf(0, 0, 1, 2, 1), tree.map { it.depth })
        assertEquals(
            listOf("Recent root", "Original", "branch 1", "Named nested branch", "branch 2"),
            tree.map { it.displayTitle },
        )
        // Fork flag + nesting depth
        assertEquals(listOf(false, false, true, true, true), tree.map { it.isFork })
        assertEquals(listOf(0, 0, 1, 2, 1), tree.map { it.forkDepth })
    }

    @Test
    fun activeForkLiftsEntireFamilyAboveFresherUnrelatedRoot() {
        // Parent has old recency (1.0). Fork has fresh recency (100.0). Unrelated has recency 50.0.
        val sessions =
            listOf(
                SessionInfo(id = "unrelated", title = "Unrelated", last_active = 50.0),
                SessionInfo(id = "parent", title = "Parent", last_active = 1.0),
                SessionInfo(id = "fork", parent_session_id = "parent", title = "Active Fork", last_active = 100.0),
            )

        val tree = flattenSessionsWithBranches(sessions)

        assertEquals(listOf("parent", "fork", "unrelated"), tree.map { it.session.id })
        assertEquals(listOf(0, 1, 0), tree.map { it.depth })
    }

    @Test
    fun aliasesLineageRootIdForCompressionProjectedTips() {
        val sessions =
            listOf(
                SessionInfo(id = "tip_parent", lineageRootId = "root_parent", title = "Parent Tip", last_active = 5.0),
                SessionInfo(id = "child", parent_session_id = "root_parent", title = "Child", last_active = 6.0),
            )

        val tree = flattenSessionsWithBranches(sessions)

        assertEquals(listOf("tip_parent", "child"), tree.map { it.session.id })
        assertEquals(listOf(0, 1), tree.map { it.depth })
    }

    @Test
    fun preservesOrderWhenRequested() {
        val sessions =
            listOf(
                SessionInfo(id = "pin1", title = "Pin 1", last_active = 10.0),
                SessionInfo(id = "pin2", title = "Pin 2", last_active = 90.0),
            )

        val tree = flattenSessionsWithBranches(sessions, preserveOrder = true)

        assertEquals(listOf("pin1", "pin2"), tree.map { it.session.id })
    }

    @Test
    fun keepsMissingParentsAndCyclesVisible() {
        val sessions =
            listOf(
                SessionInfo(id = "orphan", parent_session_id = "missing", preview = "orphan preview"),
                SessionInfo(id = "a", parent_session_id = "b"),
                SessionInfo(id = "b", parent_session_id = "a"),
            )

        val tree = flattenSessionsWithBranches(sessions)

        assertEquals(setOf("orphan", "a", "b"), tree.map { it.session.id }.toSet())
        val orphanItem = tree.first { it.session.id == "orphan" }
        assertEquals("orphan preview", orphanItem.displayTitle)
        // Orphan keeps fork identity even without parent in loaded set
        assertTrue(orphanItem.isFork)
        assertEquals(0, orphanItem.depth)
    }
}
