package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ActionResponse(
    val archive: String? = null,
    val name: String? = null,
    val ok: Boolean? = null,
    val pid: Int? = null,
    val error: String? = null,
    val message: String? = null,
    val uploaded_bytes: Long? = null,
    val update_command: String? = null,
)

/**
 * Body for POST /api/ops/backup — the backend requires a JSON body
 * (BackupRequest; `output` optional). Sending no body at all gets a 422.
 * Empty object (default) matches the desktop's `{"output": ...}` contract.
 */
@Serializable
data class BackupTriggerRequest(
    val output: String? = null,
)
