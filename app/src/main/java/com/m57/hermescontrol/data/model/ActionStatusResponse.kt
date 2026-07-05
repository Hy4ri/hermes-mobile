package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ActionStatusResponse(
    val exit_code: Int? = null,
    val lines: List<String>? = null,
    val name: String? = null,
    val pid: Int? = null,
    val running: Boolean? = null,
)
