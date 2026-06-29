package com.m57.hermescontrol.data.model

data class CronJobRepeat(
    val times: Int? = null,
    val completed: Int = 0,
)

data class CronJob(
    val id: String,
    val name: String,
    val schedule: Any?,
    val state: String?,
    val last_run_status: String?,
    val next_run: String?,
    val schedule_display: String? = null,
    val last_status: String? = null,
    val next_run_at: String? = null,
    val last_run_at: String? = null,
    val last_error: String? = null,
    val last_delivery_error: String? = null,
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
) {
    val scheduleText: String
        get() =
            when (schedule) {
                is String -> schedule
                is Map<*, *> -> (schedule["display"] as? String) ?: schedule_display ?: ""
                else -> schedule_display ?: ""
            }

    val lastRunStatus: String
        get() = last_run_status ?: last_status ?: ""

    val nextRunTime: String
        get() = next_run ?: next_run_at ?: ""
}
