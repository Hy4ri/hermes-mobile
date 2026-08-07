package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class CuratorResponse(
    val enabled: Boolean? = null,
    val paused: Boolean? = null,
    val interval_hours: Int? = null,
    val last_run_at: String? = null,
    // Backend sends a float (e.g. 2.0) — Int here broke JSON parsing (logcat
    // "Unexpected symbol '.' in numeric literal at path: $.min_idle_hours").
    val min_idle_hours: Double? = null,
    val stale_after_days: Int? = null,
    val archive_after_days: Int? = null,
)
