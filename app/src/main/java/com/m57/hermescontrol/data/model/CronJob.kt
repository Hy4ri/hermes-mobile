package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CronJobRepeat(
    val times: Int? = null,
    val completed: Int = 0,
)

/**
 * Missed scheduled-fire record stamped on the job when the scheduler cannot
 * forward a fire to the gateway loopback (gateway down / api_server not bound).
 * `{at: iso, detail: str}` — see hermes-agent cron/jobs.py `_stamp_fire_error`.
 * A successful run clears it.
 */
@Serializable
data class CronJobFireError(
    val at: String? = null,
    val detail: String? = null,
)

@Serializable
data class CronJobExecution(
    val id: String? = null,
    val status: String? = null,
    val error: String? = null,
    val claimed_at: String? = null,
)

/**
 * Result of POST /api/cron/jobs/{id}/cancel.
 * cancelled is false when no run was in flight.
 */
@Serializable
data class CronCancelResult(
    val cancelled: Boolean,
    val execution_id: String? = null,
    val detail: String? = null,
    val job_id: String? = null,
    val success: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class CronJob(
    val id: String,
    val name: String,
    val schedule: JsonElement? = null,
    val state: String? = null,
    val last_run_status: String? = null,
    val next_run: String? = null,
    val schedule_display: String? = null,
    val last_status: String? = null,
    val next_run_at: String? = null,
    val last_run_at: String? = null,
    val last_error: String? = null,
    val last_delivery_error: String? = null,
    // Missed scheduled-fire stamp (gateway unreachable / api_server not bound).
    // Nested {at, detail} object — see CronJobFireError.
    val last_fire_error: CronJobFireError? = null,
    val latest_execution: CronJobExecution? = null,
    val live_execution: CronJobExecution? = null,
    // Missed-run policy & timezone knobs (v0.22.6)
    val timezone: String? = null,
    val grace_minutes: Int? = null,
    val catch_up: Boolean? = null,
    // Full editor fields — all optional with defaults for backward compat
    val enabled: Boolean? = null,
    val prompt: String? = null,
    val deliver: String? = null,
    val skills: List<String>? = null,
    val model: String? = null,
    val provider: String? = null,
    val base_url: String? = null,
    val script: String? = null,
    val context_from: List<String>? = null,
    val enabled_toolsets: List<String>? = null,
    val workdir: String? = null,
    val no_agent: Boolean? = null,
    val repeat: CronJobRepeat? = null,
    // Monitor-mode jobs (backend cron/monitor.py): a source script/URL whose
    // output is hashed each tick — the agent runs only when it changes.
    val monitor_script: String? = null,
    val monitor_url: String? = null,
) {
    val scheduleText: String
        get() =
            when (schedule) {
                is JsonPrimitive -> schedule.content
                is JsonObject -> (schedule["display"] as? JsonPrimitive)?.content ?: schedule_display ?: ""
                else -> schedule_display ?: ""
            }

    val lastRunStatus: String
        get() = last_run_status ?: last_status ?: ""

    val nextRunTime: String
        get() = next_run ?: next_run_at ?: ""

    /** The configured monitor source (script or URL), if any. */
    val monitorSource: String?
        get() = monitor_script ?: monitor_url

    /**
     * Whether this job has an execution currently in flight (claimed or running).
     * Sourced from either live_execution or latest_execution.
     */
    val isRunInFlight: Boolean
        get() {
            val liveStatus = live_execution?.status?.lowercase()
            val latestStatus = latest_execution?.status?.lowercase()
            return live_execution != null ||
                liveStatus == "claimed" || liveStatus == "running" ||
                latestStatus == "claimed" || latestStatus == "running"
        }
}
