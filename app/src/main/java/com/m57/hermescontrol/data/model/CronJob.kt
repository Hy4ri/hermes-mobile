package com.m57.hermescontrol.data.model

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
