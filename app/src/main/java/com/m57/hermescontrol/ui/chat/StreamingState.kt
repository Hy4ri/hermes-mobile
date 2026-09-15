package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.UsageSnapshotResponse

data class StreamingState(
    val streamingMessage: ChatMessage? = null,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isReasoning: Boolean = false,
    val reasoningText: String = "",
    /**
     * Ids of assistant messages sealed at tool.start during the CURRENT turn
     * (interim commentary before a tool call). Consumed by
     * [com.m57.hermescontrol.ui.chat.ChatWsEventReducer.onMessageComplete] to
     * strip the repeated prefix from the final text (issue #842). Reset on
     * every fresh StreamingState (message.start / interrupt / session switch).
     */
    val sealedOrphanIds: List<String> = emptyList(),
    /** Cumulative usage before the current turn; retained across tool-loop message segments. */
    val turnUsageBaseline: UsageSnapshotResponse? = null,
    /** Distinguishes a captured-but-unavailable baseline from a not-yet-captured one. */
    val turnUsageBaselineCaptured: Boolean = false,
)
