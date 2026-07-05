package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class CuratorResponse(
    val enabled: Boolean? = null,
    val paused: Boolean? = null,
    val interval_hours: Int? = null,
    val last_run_at: String? = null,
    val min_idle_hours: Int? = null,
    val stale_after_days: Int? = null,
    val archive_after_days: Int? = null,
)
