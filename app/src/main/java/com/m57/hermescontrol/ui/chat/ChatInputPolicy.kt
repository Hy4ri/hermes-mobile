package com.m57.hermescontrol.ui.chat

/**
 * Pure, testable decision logic for the chat input bar.
 *
 * The gateway's `prompt.submit` busy-input policy queues any prompt that lands
 * while the agent is mid-turn (tui_gateway/server.py:_handle_busy_submit), so a
 * message sent while the agent is typing or awaiting approval is never dropped —
 * it runs as the next turn. These helpers keep that contract explicit and
 * unit-testable without a Compose/Android harness.
 */
object ChatInputPolicy {
    /**
     * Whether the send affordance should be enabled.
     *
     * Unlike slash commands (which were always allowed mid-turn), regular
     * prompts are now allowed too: the backend queues them. The only gates are
     * a non-empty input (or a pending attachment) and an active connection.
     */
    fun canSend(
        text: String,
        pendingAttachments: List<Any>,
        isConnected: Boolean,
    ): Boolean = (text.isNotBlank() || pendingAttachments.isNotEmpty()) && isConnected

    /**
     * Whether the input placeholder should read "queued" rather than the plain
     * "waiting" hint. Shown only while the agent is typing AND the user has
     * already typed something — i.e. a press of send would enqueue a turn.
     */
    fun showQueuePlaceholder(
        text: String,
        isAgentTyping: Boolean,
    ): Boolean = isAgentTyping && text.isNotBlank()
}
