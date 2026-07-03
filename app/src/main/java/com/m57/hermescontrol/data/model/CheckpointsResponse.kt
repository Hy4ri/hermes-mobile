package com.m57.hermescontrol.data.model

data class CheckpointsResponse(
    val sessions: List<CheckpointSession>? = null,
    val total_bytes: Long? = null,
)

data class CheckpointSession(
    val session: String? = null,
    val files: Int? = null,
    val bytes: Long? = null,
)
