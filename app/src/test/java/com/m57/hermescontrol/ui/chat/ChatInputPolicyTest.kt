package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #589-followup — Queue prompts while the agent is running.
 *
 * Asserts the input-bar decision contract the backend relies on: a prompt sent
 * while the agent is typing or awaiting approval must be allowed (the gateway
 * queues it via prompt.submit's busy-input policy), and the placeholder should
 * signal "queued" when the user has text ready during a busy turn.
 */
class ChatInputPolicyTest {
    @Test
    fun canSend_allowsRegularPromptWhileAgentTyping() {
        assertTrue(
            "regular prompt must be sendable while agent is typing",
            ChatInputPolicy.canSend("next task", emptyList(), isConnected = true),
        )
    }

    @Test
    fun canSend_allowsRegularPromptWhileApprovalPending() {
        // Approval-pending surfaces as isAgentTyping=false in this client, but
        // the gateway still queues the prompt.submit — connection + non-empty
        // text are the only gates, so it must be allowed.
        assertTrue(
            "regular prompt must be sendable while an approval is pending",
            ChatInputPolicy.canSend("yes, proceed", emptyList(), isConnected = true),
        )
    }

    @Test
    fun canSend_blocksWhenDisconnected() {
        assertFalse(
            "no sends while disconnected",
            ChatInputPolicy.canSend("next task", emptyList(), isConnected = false),
        )
    }

    @Test
    fun canSend_blocksWhenInputEmptyAndNoAttachments() {
        assertFalse(
            "empty input with no attachments must not enable send",
            ChatInputPolicy.canSend("", emptyList(), isConnected = true),
        )
    }

    @Test
    fun canSend_allowsAttachmentWithEmptyText() {
        assertTrue(
            "a pending attachment enables send even with empty text",
            ChatInputPolicy.canSend("", listOf("att"), isConnected = true),
        )
    }

    @Test
    fun showQueuePlaceholder_trueWhenTypingAndHasText() {
        assertTrue(
            "placeholder should read 'queued' while typing with text present",
            ChatInputPolicy.showQueuePlaceholder("next task", isAgentTyping = true),
        )
    }

    @Test
    fun showQueuePlaceholder_falseWhenTypingButNoText() {
        assertFalse(
            "plain 'waiting' hint when typing but input empty",
            ChatInputPolicy.showQueuePlaceholder("", isAgentTyping = true),
        )
    }

    @Test
    fun showQueuePlaceholder_falseWhenIdle() {
        assertFalse(
            "no 'queued' hint when the agent is idle",
            ChatInputPolicy.showQueuePlaceholder("next task", isAgentTyping = false),
        )
    }

    @Test
    fun slashCommandStillRoutesThroughSendPath() {
        // Slash commands were already allowed mid-turn; ensure the policy does
        // not regress that by blocking a non-blank slash command.
        assertTrue(
            "/stop must remain sendable while typing",
            ChatInputPolicy.canSend("/stop", emptyList(), isConnected = true),
        )
    }
}
