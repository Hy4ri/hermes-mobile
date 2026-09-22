package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CronRunHistoryResponse(
    val runs: List<CronRun> = emptyList(),
    val limit: Int = 20,
)

/**
 * One Hermes Agent cron execution.
 *
 * The backend exposes cron runs using the same enriched row shape as
 * `/api/sessions`, then annotates each row with its owning profile and
 * active/archive state. Only [id] is required; older backend rows can omit any
 * of the enrichment fields without making the whole history unusable.
 */
@Serializable
data class CronRun(
    val id: String,
    val source: String? = null,
    val model: String? = null,
    val title: String? = null,
    val started_at: Double? = null,
    val ended_at: Double? = null,
    val last_active: Double? = null,
    val is_active: Boolean = false,
    val archived: Boolean = false,
    val message_count: Int? = null,
    val tool_call_count: Int? = null,
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val preview: String? = null,
    val parent_session_id: String? = null,
    val profile: String? = null,
)
