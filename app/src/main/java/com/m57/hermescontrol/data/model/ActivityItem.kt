package com.m57.hermescontrol.data.model

/**
 * Represents a single item in the Activity feed — a cron job run that produced output.
 * Mapped from cron run sessions returned by GET /api/sessions?source=cron.
 */
data class ActivityItem(
    val id: String,
    val jobName: String,
    val preview: String?,
    val status: String,
    val formattedTime: String,
    val messageCount: Int,
    val timestamp: Double,
)
