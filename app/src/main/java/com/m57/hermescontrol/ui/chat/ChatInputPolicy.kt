package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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

    /**
     * Builds the [TextFieldValue] for a slash command inserted via the
     * suggestion dropdown. The cursor is placed at the END of the text, not the
     * middle (issue #599): a plain-String [OutlinedTextField] value leaves the
     * cursor at the end of the shared prefix when an external update replaces
     * `/h` with `/help`, so the dropdown click must carry an explicit end
     * selection.
     */
    fun commandFieldValue(command: String): TextFieldValue = TextFieldValue(command, TextRange(command.length))

    /** Restores a rejected prompt without discarding text typed since dispatch. */
    fun restoreRejectedText(
        rejectedText: String,
        current: TextFieldValue,
    ): TextFieldValue {
        val restored =
            when {
                current.text.isEmpty() -> rejectedText
                rejectedText.isEmpty() -> current.text
                else -> "$rejectedText\n${current.text}"
            }
        return commandFieldValue(restored)
    }

    /**
     * Rank slash-command suggestions by how often the user has dispatched them
     * (issue #865): most-used first, ties broken by the current (catalog)
     * order via the stable sort. Commands with no recorded usage keep their
     * catalog position, so a fresh install behaves exactly as before.
     */
    fun sortSlashSuggestions(
        commands: List<String>,
        usageCounts: Map<String, Int>,
    ): List<String> = commands.sortedByDescending { usageCounts[it.lowercase()] ?: 0 }

    /**
     * Extracts the active mention query (without the '@') if the cursor is currently
     * positioned within an '@' mention token (e.g. "hello @res" -> "res").
     * Returns null if not in a mention context.
     */
    fun extractMentionQuery(
        text: String,
        cursorPosition: Int,
    ): String? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        val prefix = text.substring(0, cursorPosition)
        val lastAt = prefix.lastIndexOf('@')
        if (lastAt == -1) return null

        // Ensure '@' is at start of string or preceded by whitespace
        if (lastAt > 0 && !prefix[lastAt - 1].isWhitespace()) return null

        val query = prefix.substring(lastAt + 1)
        // Mention handle cannot contain whitespace
        if (query.any { it.isWhitespace() }) return null
        return query
    }

    /**
     * Inserts a selected bot handle into the TextFieldValue at the active '@' mention position.
     */
    fun applyMention(
        current: TextFieldValue,
        botName: String,
    ): TextFieldValue {
        val text = current.text
        val cursor = current.selection.end.coerceIn(0, text.length)
        val prefix = text.substring(0, cursor)
        val lastAt = prefix.lastIndexOf('@')
        if (lastAt == -1) return current

        val beforeAt = text.substring(0, lastAt)
        val afterCursor = text.substring(cursor)
        val inserted = "@$botName "
        val newText = "$beforeAt$inserted$afterCursor"
        val newCursor = beforeAt.length + inserted.length
        return TextFieldValue(newText, TextRange(newCursor))
    }
}
