package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import java.util.UUID

/**
 * Metadata for a [ChatMessage] that represents an approval request.
 * When present, the UI renders approval buttons inline.
 * Transient — not persisted to SQLite.
 */
data class ApprovalInfo(
    val command: String?,
    val description: String?,
    val patternKeys: List<String>?,
    /** Backend `request_id` — sent back in `approval.respond` to pin the exact pending. */
    val requestId: String? = null,
    /** JSON-RPC server-request id (`srq-*`) used for the response frame. */
    val serverRequestId: String? = null,
    /** Backend-advertised choices (`once`/`session`/`always`/`deny`). Null = legacy Run/Deny. */
    val choices: List<String>? = null,
    /** False → hide "Always allow" (tirith warning, desktop parity). */
    val allowPermanent: Boolean? = null,
    /** True → Smart DENY override, once/deny only. */
    val smartDenied: Boolean? = null,
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

/** Durable evidence about a locally-created row; UNKNOWN must not be treated as proof of delivery. */
enum class MessageProvenance {
    UNKNOWN,
    LOCAL_PENDING,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val reasoningText: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
    /**
     * Gateway tool call id (`call_00_...`, from `tool.start` payload `tool_id`
     * / REST `tool_call_id`). The REST transcript and the WS stream carry the
     * SAME id for one tool call, so it is the robust 1:1 identity for
     * deduplicating tool rows (issue #842) — result-content canonicalization
     * fails for MCP/web tools because the REST side stores the payload as raw
     * `<untrusted_tool_result>` text, not JSON.
     */
    val toolCallId: String = "",
    val approvalInfo: ApprovalInfo? = null,
    /** Transient clarify request data — when present, renders [ClarifyBubble] inline. */
    val clarifyInfo: ClarifyUi? = null,
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
    /**
     * Timeline-marker tag carried from the backend's `display_kind`
     * (issue #904): `model_switch` / `personality_switch` / `auto_continue`
     * ride as role=user rows but are NOT user input — the chat list renders
     * them as centered timeline chips instead of user bubbles.
     */
    val displayKind: String? = null,
    /**
     * Timestamp (ms) when the assistant message finished streaming.
     * When present, rendered at the bottom beside the copy button.
     */
    val finishTimestamp: Long? = null,
    /** Token count used by this message (persisted to SQLite). */
    val tokenCount: Int? = null,
    /** Tokens per second generation speed (persisted to SQLite). */
    val tps: Double? = null,
    /** Stable completion identity correlating with reply notifications for read tracking. */
    val completionId: String? = null,
    /** Confirmed transcript identity (#859); [id] remains the stable live/render key. */
    val restId: String? = null,
    /** Cache insertion sequence for unconfirmed local rows; null before first persistence. */
    val localOrder: Long? = null,
    /** Persisted before prompt submission so process death cannot turn an unsent prompt into old history. */
    val messageProvenance: MessageProvenance = MessageProvenance.UNKNOWN,
    /** Read from history, not observed live in this view. Never persisted as delivery state. */
    val isHistoricalCache: Boolean = false,
    /**
     * Legacy USER restored without a canonical identity or a current send receipt.
     * Placement only: keep it outside the live tail without asserting delivery.
     * Transient; UNKNOWN provenance and the persisted row remain unchanged.
     */
    val isRestoredUnconfirmed: Boolean = false,
)

/** Cached REST rows already carry their canonical identity in the persisted primary key. */
internal val ChatMessage.canonicalRestId: String?
    get() = restId ?: id.takeIf { it.startsWith("rest-") }

/** A persisted RUNNING snapshot is not evidence of current tool activity. */
internal val ChatMessage.isToolRunning: Boolean
    get() = toolStatus == ToolStatus.RUNNING && !isHistoricalCache

/**
 * Single live transcript log entry for subagent execution.
 */
data class SubagentLogLine(
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val isError: Boolean = false,
    val isSummary: Boolean = false,
)

/**
 * Representation of a single task/todo item in the agent's plan.
 */
data class TodoItem(
    val id: String,
    val content: String,
    val status: String = "pending", // "pending" | "in_progress" | "completed" | "cancelled"
    val parent: String? = null,
) {
    val isCompleted: Boolean get() = status == "completed" || status == "done"
    val isInProgress: Boolean get() = status == "in_progress" || status == "running"
    val isCancelled: Boolean get() = status == "cancelled" || status == "failed"
    val isPending: Boolean get() = status == "pending" || status == "queued"
    val isSubtask: Boolean get() = !parent.isNullOrBlank()
}

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
    val status: String? = null, // running / queued / completed / failed
    val summary: String? = null, // complete only
    val subagentId: String? = null,
    val logs: List<SubagentLogLine> = emptyList(),
    val durationSeconds: Double? = null,
    val model: String? = null,
    val lastEventTimestamp: Long = 0L,
) {
    val isComplete: Boolean get() = type == "subagent.complete" || status == "completed" || status == "done"
    val isFailed: Boolean get() = status == "failed" || status == "interrupted"
    val isCancelled: Boolean get() = status == "cancelled" || status == "stopped" || status == "canceled"
    val isSteered: Boolean get() = status == "steered"
    val isQueued: Boolean get() = status == "queued"
    val isRunning: Boolean get() = !isComplete && !isFailed && !isCancelled && !isQueued
}

/**
 * State for inspecting a live subagent's rolling execution transcript (issue #1089).
 */
data class SubagentTranscriptUiState(
    val subagentId: String,
    val text: String = "",
    val isLoading: Boolean = false,
    val isTruncated: Boolean = false,
    val bytesRead: Long? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = text.isEmpty() && !isLoading && error == null
}

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
