package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import java.util.UUID

/**
 * Metadata for a [ChatMessage] that represents an approval request.
 * When present, the UI renders Approve/Deny buttons inline.
 * Transient — not persisted to SQLite.
 */
data class ApprovalInfo(
    val command: String?,
    val description: String?,
    val patternKeys: List<String>?,
)

/**
 * Risk metadata from the backend's [tool.output_risk] WS event.
 * Attached to the tool [ChatMessage] so the UI can render a security chip.
 * Transient — not persisted to SQLite.
 */
data class ToolOutputRiskData(
    val risk: String, // "low" | "medium" | "high"
    val findings: List<String>,
    val redacted: Boolean,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val reasoningText: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
    val approvalInfo: ApprovalInfo? = null,
    /** Files attached to this message — shown inline in the bubble. */
    val attachments: List<Attachment>? = null,
    /**
     * Risk metadata from [tool.output_risk] WS event.
     * When risk is "medium"/"high" or redacted is true, the UI shows a ⚠ chip.
     * Transient — not persisted to SQLite.
     */
    val toolOutputRiskData: ToolOutputRiskData? = null,
    /**
     * Live preview/progress text from the backend's [tool.progress] WS event.
     * Transient — not persisted to SQLite.
     */
    val progressPreview: String? = null,
)

/**
 * Representation of subagent execution details for transient UI indicators.
 *
 * Events: `subagent.spawn_requested`, `subagent.start`, `subagent.progress`, `subagent.complete`
 */
data class SubagentIndicator(
    val type: String, // subagent.spawn_requested / start / progress / complete
    val goal: String? = null,
    val taskIndex: Int? = null,
    val taskCount: Int? = null,
    val text: String? = null, // preview line
    val status: String? = null, // complete only
    val summary: String? = null, // complete only
    val subagentId: String? = null,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

enum class ToolStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}
