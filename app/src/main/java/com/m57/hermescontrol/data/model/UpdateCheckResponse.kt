package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class UpdateCheckResponse(
    val install_method: String? = null,
    val current_version: String? = null,
    val behind: Int? = null,
    val update_available: Boolean? = null,
    val can_apply: Boolean? = null,
    val update_command: String? = null,
    val message: String? = null,
)
