package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Item returned in the [SubagentListResponse] from `subagent.list` (issue #1089).
 */
@Serializable
data class SubagentListItem(
    @SerialName("subagent_id") val subagentId: String = "",
    val goal: String? = null,
    val status: String? = null,
    val model: String? = null,
    @SerialName("elapsed_seconds") val elapsedSeconds: Double? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val depth: Int? = null,
    @SerialName("delegation_id") val delegationId: String? = null,
    @SerialName("tool_count") val toolCount: Int? = null,
    @SerialName("last_tool") val lastTool: String? = null,
    @SerialName("accepting_steer") val acceptingSteer: Boolean? = null,
)

/**
 * Response returned from `subagent.list` (issue #1089).
 */
@Serializable
data class SubagentListResponse(
    val subagents: List<SubagentListItem> = emptyList(),
    val delegations: List<JsonElement> = emptyList(),
)

/**
 * Response returned from `subagent.tail` (issue #1089).
 *
 * Backend returns either `text` (desktop/TUI gateway) or `tail` (contract).
 * [content] returns the authoritative transcript text from whichever field is present.
 */
@Serializable
data class SubagentTailResponse(
    @SerialName("subagent_id") val subagentId: String = "",
    val tail: String? = null,
    val text: String? = null,
    val available: Boolean? = null,
    val truncated: Boolean? = null,
    @SerialName("bytes_read") val bytesRead: Long? = null,
) {
    /** Returns authoritative transcript text across backend schema variants. */
    fun content(): String = text ?: tail ?: ""
}

/** Live work owned by one stored conversation, from `subagent.roster` (#387). */
data class LiveWorkCounts(
    val subagents: Int = 0,
    val background: Int = 0,
) {
    val isEmpty: Boolean get() = subagents <= 0 && background <= 0
}

/** Outcome of one `subagent.roster` read (#387). */
sealed interface LiveWorkRoster {
    /** Counts keyed by stored session id; a conversation with no live work is absent. */
    data class Counts(
        val bySessionId: Map<String, LiveWorkCounts>,
    ) : LiveWorkRoster

    /** The gateway has no `subagent.roster` (-32601): stop asking on this connection. */
    data object Unsupported : LiveWorkRoster

    /** Any other failure (timeout, disconnect, malformed reply): counts are unknown, not zero. */
    data object Unavailable : LiveWorkRoster
}
