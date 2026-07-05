package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val version: String?,
    val gateway_running: Boolean?,
    val active_sessions: Int?,
    val auth_required: Boolean?,
    val gateway_platforms: Map<String, PlatformStatus>?,
)

@Serializable
data class PlatformStatus(
    val state: String?,
    val error_code: String? = null,
)
