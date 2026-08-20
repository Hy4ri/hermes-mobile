package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.components.computeChipDisplay
import com.m57.hermescontrol.ui.chat.components.shouldShowProgressChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused coverage for the sticky progress chip surface (#942):
 *  - correct current-task progress (active index + 1, not completed count)
 *  - hidden after all todos complete / cancel
 *  - subagent-only state
 *  - resume hydration from a transcript of messages
 */
class TaskProgressChipTest {
    private fun todo(
        content: String,
        status: String,
    ) = TodoItem(id = content, content = content, status = status)

    @Test
    fun `current task number is active index plus one, not completed count`() {
        // task 1 done, task 2 in progress -> "2/5", not "1/5"
        val todos =
            listOf(
                todo("a", "completed"),
                todo("b", "in_progress"),
                todo("c", "pending"),
                todo("d", "pending"),
                todo("e", "pending"),
            )
        val display = computeChipDisplay(todos, emptyList())
        assertEquals(5, display.total)
        assertEquals(2, display.currentTaskNumber)
        assertEquals("b", display.currentTaskContent)
    }

    @Test
    fun `all completed shows completed count and still has todos`() {
        val todos = listOf(todo("a", "completed"), todo("b", "completed"))
        val display = computeChipDisplay(todos, emptyList())
        assertEquals(2, display.total)
        assertEquals(2, display.currentTaskNumber)
        assertNull(display.currentTaskContent)
    }

    @Test
    fun `number and content derive from the same selected task`() {
        // task 1 pending, task 2 in_progress: the in_progress task is surfaced,
        // so both the number and the content come from task 2 (not 1/N · task2).
        val todos = listOf(todo("first", "pending"), todo("second", "in_progress"))
        val display = computeChipDisplay(todos, emptyList())
        assertEquals(2, display.total)
        assertEquals(2, display.currentTaskNumber)
        assertEquals("second", display.currentTaskContent)
    }

    @Test
    fun `falls back to first pending task when none in progress`() {
        val todos = listOf(todo("first", "pending"), todo("second", "pending"))
        val display = computeChipDisplay(todos, emptyList())
        assertEquals(1, display.currentTaskNumber)
        assertEquals("first", display.currentTaskContent)
    }

    @Test
    fun `cancelled tasks are skipped when finding the active task`() {
        // task 1 completed, task 2 cancelled, task 3 in progress -> active = 3
        val todos =
            listOf(
                todo("a", "completed"),
                todo("b", "cancelled"),
                todo("c", "in_progress"),
            )
        val display = computeChipDisplay(todos, emptyList())
        assertEquals(3, display.currentTaskNumber)
        assertEquals("c", display.currentTaskContent)
    }

    @Test
    fun `chip hides when all todos are completed`() {
        val todos = listOf(todo("a", "completed"), todo("b", "completed"))
        assertFalse(shouldShowProgressChip(todos, emptyList()))
    }

    @Test
    fun `chip hides when all todos are cancelled`() {
        val todos = listOf(todo("a", "cancelled"), todo("b", "cancelled"))
        assertFalse(shouldShowProgressChip(todos, emptyList()))
    }

    @Test
    fun `chip shows while a todo is in progress`() {
        val todos = listOf(todo("a", "completed"), todo("b", "in_progress"))
        assertTrue(shouldShowProgressChip(todos, emptyList()))
    }

    @Test
    fun `chip shows with a running subagent even when no todos`() {
        val indicators = listOf(SubagentIndicator(type = "subagent.start", status = "running"))
        assertTrue(shouldShowProgressChip(emptyList(), indicators))
    }

    @Test
    fun `subagent only state reports zero todos and running agents`() {
        val indicators = listOf(SubagentIndicator(type = "subagent.start", status = "running"))
        val display = computeChipDisplay(emptyList(), indicators)
        assertEquals(false, display.hasTodos)
        assertEquals(1, display.activeAgents)
    }

    @Test
    fun `resume hydration recovers todos from a transcript message`() {
        // A transcript message whose content carries the todos JSON (as the WS
        // reducer path would have produced) must be picked up directly, so a
        // resumed session shows the chip without waiting for another event.
        val json =
            """{"todos":[{"id":"1","content":"first","status":"completed"},{"id":"2","content":"second","status":"in_progress"}]}"""
        val messages =
            listOf(
                ChatMessage(
                    id = "m1",
                    role = MessageRole.TOOL,
                    toolName = "todo",
                    content = json,
                ),
            )
        val hydrated = hydrateTodosFromMessages(messages)
        assertEquals(2, hydrated.size)
        assertEquals("second", hydrated[1].content)
        assertEquals(true, hydrated[1].isInProgress)
    }

    @Test
    fun `resume hydration returns empty when no todo message present`() {
        val messages =
            listOf(
                ChatMessage(id = "m1", role = MessageRole.USER, content = "hi"),
            )
        assertTrue(hydrateTodosFromMessages(messages).isEmpty())
    }
}
