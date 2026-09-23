package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListEntriesTest {
    private fun toolMessage() = ChatMessage(role = MessageRole.TOOL, content = "")

    private fun textMessage() = ChatMessage(role = MessageRole.ASSISTANT, content = "hello")

    private fun userMessage() = ChatMessage(role = MessageRole.USER, content = "hi")

    @Test
    fun emptyListHasNoMilestones() {
        assertTrue(toolCallMilestones(emptyList()).isEmpty())
    }

    @Test
    fun noToolMessagesHasNoMilestones() {
        val messages = List(8) { textMessage() }
        assertTrue(toolCallMilestones(messages).isEmpty())
    }

    @Test
    fun fewerThanFiveToolCallsHasNoMilestones() {
        val messages = List(4) { toolMessage() }
        assertTrue(toolCallMilestones(messages).isEmpty())
    }

    @Test
    fun fifthToolCallIsAMilestone() {
        val messages = List(5) { toolMessage() }
        assertEquals(mapOf(messages[4].id to 5), toolCallMilestones(messages))
        assertEquals(mapOf(4 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun milestoneAtEveryFifthToolCallWithinTurn() {
        val messages = List(12) { toolMessage() }
        assertEquals(
            mapOf(messages[4].id to 5, messages[9].id to 10),
            toolCallMilestones(messages),
        )
        assertEquals(mapOf(4 to 5, 9 to 10), toolCallMilestoneIndices(messages))
    }

    @Test
    fun counterResetsAtEachUserMessage() {
        val messages =
            listOf(
                userMessage(), // index 0 — turn 1 starts
                toolMessage(), // 1
                toolMessage(), // 2
                toolMessage(), // 3
                toolMessage(), // 4
                toolMessage(), // 5 ← 5th tool call of turn 1
                userMessage(), // 6 — turn 2 starts, counter resets
                toolMessage(), // 7
                toolMessage(), // 8
                toolMessage(), // 9
                toolMessage(), // 10
                toolMessage(), // 11 ← 5th tool call of turn 2 (not 10)
            )
        assertEquals(
            mapOf(messages[5].id to 5, messages[11].id to 5),
            toolCallMilestones(messages),
        )
        assertEquals(mapOf(5 to 5, 11 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun timelineMarkerDoesNotResetToolCounter() {
        val marker =
            ChatMessage(
                role = MessageRole.USER,
                content = "[System: The active model has changed to gpt-5]",
                displayKind = "model_switch",
            )
        val messages =
            listOf(
                userMessage(),
                toolMessage(),
                toolMessage(),
                marker,
                toolMessage(),
                toolMessage(),
                toolMessage(), // 5th tool call despite intervening marker
            )
        assertEquals(mapOf(messages[6].id to 5), toolCallMilestones(messages))
        assertEquals(mapOf(6 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun steerMessageResetsToolCounterAsUserTurnBoundary() {
        val steer =
            ChatMessage(
                role = MessageRole.USER,
                content = "Stop and run tests",
                displayKind = "steer",
            )
        val messages =
            listOf(
                userMessage(),
                toolMessage(),
                toolMessage(),
                steer,
                toolMessage(),
                toolMessage(),
                toolMessage(),
            )
        // Only 3 tool calls after the steer boundary, so no 5-call milestone reached
        assertTrue(toolCallMilestones(messages).isEmpty())
    }

    @Test
    fun syntheticMaxIterationsNudgeDoesNotResetToolCounter() {
        val nudge =
            ChatMessage(
                role = MessageRole.USER,
                content =
                    "You've reached the maximum number of tool-calling iterations allowed. " +
                        "Please provide a summary.",
            )
        val messages =
            listOf(
                userMessage(),
                toolMessage(),
                toolMessage(),
                toolMessage(),
                nudge,
                toolMessage(),
                toolMessage(), // 5th tool call despite intervening nudge
            )
        assertEquals(mapOf(messages[6].id to 5), toolCallMilestones(messages))
        assertEquals(mapOf(6 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun nonToolMessagesDoNotCountAndShiftIndices() {
        val messages =
            listOf(
                textMessage(), // index 0
                toolMessage(), // 1
                toolMessage(), // 2
                textMessage(), // 3
                toolMessage(), // 4
                toolMessage(), // 5
                textMessage(), // 6
                toolMessage(), // 7 ← 5th tool call
            )
        assertEquals(mapOf(messages[7].id to 5), toolCallMilestones(messages))
        assertEquals(mapOf(7 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun toolMessagesWithoutToolNameStillCount() {
        val messages = List(5) { ChatMessage(role = MessageRole.TOOL, content = "", toolName = null) }
        assertEquals(mapOf(messages[4].id to 5), toolCallMilestones(messages))
        assertEquals(mapOf(4 to 5), toolCallMilestoneIndices(messages))
    }

    @Test
    fun labelIncludesRealMaxWhenKnown() {
        assertEquals("5/90", toolCallDividerLabel(count = 5, maxPerTurn = 90))
        assertEquals("10/90", toolCallDividerLabel(count = 10, maxPerTurn = 90))
    }

    @Test
    fun labelDegradesToBareCountWhenMaxUnknown() {
        assertEquals("5", toolCallDividerLabel(count = 5, maxPerTurn = null))
        assertEquals("5", toolCallDividerLabel(count = 5, maxPerTurn = 0))
        assertEquals("5", toolCallDividerLabel(count = 5, maxPerTurn = -1))
    }
}
