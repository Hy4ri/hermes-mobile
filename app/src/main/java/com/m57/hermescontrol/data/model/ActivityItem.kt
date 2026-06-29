package com.m57.hermescontrol.data.model

/**
 * Represents a single item in the Activity feed — a cron job that has delivered output.
 * Mapped from GET /api/cron/jobs by filtering lastRunStatus == "ok".
 */
data class ActivityItem(
    val id: String,
    val name: String,
    val status: String,
    val lastRunAt: String?,
    val lastError: String?,
)
