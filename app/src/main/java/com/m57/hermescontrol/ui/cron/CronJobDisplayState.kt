package com.m57.hermescontrol.ui.cron

/** UI classification of the gateway's `effective_job_state` values. Unknown states fall back to [PAUSED]. */
enum class CronJobDisplayState {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ERROR,
    ;

    val canPause: Boolean get() = this == ACTIVE
    val canResume: Boolean get() = this == PAUSED || this == ERROR

    companion object {
        fun from(state: String?): CronJobDisplayState =
            when (state) {
                "scheduled", "active" -> ACTIVE
                "completed" -> COMPLETED
                "error" -> ERROR
                else -> PAUSED
            }
    }
}
