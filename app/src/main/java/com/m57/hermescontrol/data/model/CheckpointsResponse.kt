package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class CheckpointsResponse(
    val sessions: List<CheckpointSession>? = null,
    val total_bytes: Long? = null,
)

@Serializable
data class CheckpointSession(
    val session: String? = null,
    val files: Int? = null,
    val bytes: Long? = null,
)
