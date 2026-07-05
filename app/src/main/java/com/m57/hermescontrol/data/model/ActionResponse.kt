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
