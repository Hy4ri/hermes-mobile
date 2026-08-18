package com.m57.hermescontrol.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTreeTest {
    @Test
    fun nestsBranchesAndUsesDesktopFallbackNames() {
        val sessions =
            listOf(
                SessionInfo(id = "unrelated", title = "Recent root"),
                SessionInfo(id = "branch-b", parent_session_id = "parent"),
                SessionInfo(id = "parent", title = "Original"),
                SessionInfo(id = "branch-a", parent_session_id = "parent", preview = "copied prompt"),
                SessionInfo(id = "nested", parent_session_id = "branch-a", title = "Named nested branch"),
            )

        val tree = flattenSessionTree(sessions)

        assertEquals(listOf("unrelated", "parent", "branch-b", "branch-a", "nested"), tree.map { it.session.id })
        assertEquals(listOf(0, 0, 1, 1, 2), tree.map { it.depth })
        assertEquals(
            listOf("Recent root", "Original", "branch 1", "branch 2", "Named nested branch"),
            tree.map {
                it.displayTitle
            },
        )
        // Fork flag + nesting depth (1 = child of root, 2 = fork of a fork).
        assertEquals(listOf(false, false, true, true, true), tree.map { it.isFork })
        assertEquals(listOf(0, 0, 1, 1, 2), tree.map { it.forkDepth })
    }

    @Test
    fun deepNestingMarksEveryForkWithDepth() {
        // root → a (child 1) → a1 (child 1) ; root → b (child 2, last)
        // a1 is fork-depth 2 (fork of a fork), b is fork-depth 1.
        val sessions =
            listOf(
                SessionInfo(id = "root", title = "Root"),
                SessionInfo(id = "a", parent_session_id = "root"),
                SessionInfo(id = "b", parent_session_id = "root"),
                SessionInfo(id = "a1", parent_session_id = "a"),
            )

        val tree = flattenSessionTree(sessions)

        assertEquals(listOf("root", "a", "a1", "b"), tree.map { it.session.id })
        assertEquals(listOf(0, 1, 2, 1), tree.map { it.depth })
        assertEquals(listOf(false, true, true, true), tree.map { it.isFork })
        assertEquals(listOf(0, 1, 2, 1), tree.map { it.forkDepth })
    }

    @Test
    fun keepsMissingParentsAndCyclesVisible() {
        val sessions =
            listOf(
                SessionInfo(id = "orphan", parent_session_id = "missing", preview = "orphan preview"),
                SessionInfo(id = "a", parent_session_id = "b"),
                SessionInfo(id = "b", parent_session_id = "a"),
            )

        val tree = flattenSessionTree(sessions)

        assertEquals(setOf("orphan", "a", "b"), tree.map { it.session.id }.toSet())
        assertEquals("orphan preview", tree.first { it.session.id == "orphan" }.displayTitle)
    }
}
