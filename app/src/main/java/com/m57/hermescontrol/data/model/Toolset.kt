package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class Toolset(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean,
    val available: Boolean? = null,
    val configured: Boolean? = null,
    val tools: List<String>? = null,
)

@Serializable
data class ToolsetToggleRequest(
    val enabled: Boolean,
    val profile: String? = null,
)
