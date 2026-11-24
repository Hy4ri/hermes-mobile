package com.m57.hermescontrol.ui.chat

/**
 * Gateway turn-outcome sentinels that arrive as assistant text but are notices, not prose (#1433).
 *
 * Sources in hermes-agent: `agent/repetition_guard.py` (`REPETITION_LOOP_INTERRUPTED`) and the
 * `interrupt_text` / `close_interrupted_tool_sequence` exits ("Operation interrupted…"). They have no
 * matching visible REST row (or a model-facing closing row), so as assistant prose they never get a
 * canonical position and jump to the transcript tail on every merge. They render as system rows instead.
 */
internal object GatewayNotice {
    /**
     * Live-only marker row for a turn a host plugin paused to continue itself (`message.complete` `paused`).
     * [SystemBubble] draws it as localized "Continuing…"; it is never persisted.
     */
    const val CONTINUING = "Continuing…"

    /** What a never-continued paused reply reads as again: the gateway's own stop notice. */
    const val OPERATION_INTERRUPTED_TEXT = "Operation interrupted."

    const val REPETITION_LOOP_INTERRUPTED = "[the reply degenerated into a repetition loop and was interrupted]"

    // "Operation interrupted." / "Operation interrupted: <reason>." / "Operation interrupted during retry (…)."
    private val OPERATION_INTERRUPTED = Regex("""^Operation interrupted(?:\.|(?::| during) [^\n]*\.)$""")

    fun isInterruptSentinel(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed == REPETITION_LOOP_INTERRUPTED || OPERATION_INTERRUPTED.matches(trimmed)
    }
}

/**
 * The gateway persists an interrupted turn's closing row as `role=assistant` with the sentinel text. Map it
 * (REST and Room alike) to the same SYSTEM notice the live path appends, so every copy matches one row.
 */
internal fun ChatMessage.normalizedGatewayNotice(): ChatMessage =
    if (role == MessageRole.ASSISTANT && attachments.isNullOrEmpty() && GatewayNotice.isInterruptSentinel(content)) {
        copy(role = MessageRole.SYSTEM, content = content.trim(), reasoningText = "", completionId = null)
    } else {
        this
    }
