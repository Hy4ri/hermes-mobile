package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val version: String? = null,
    val gateway_running: Boolean? = null,
    val active_sessions: Int? = null,
    val auth_required: Boolean? = null,
    val gateway_platforms: Map<String, PlatformStatus?>? = null,
)

@Serializable
data class PlatformStatus(
    val state: String? = null,
    val error_code: String? = null,
)
