package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class Toolset(
    val name: String,
    val label: String?,
    val description: String?,
    val enabled: Boolean,
    val available: Boolean?,
    val configured: Boolean?,
    val tools: List<String>?,
)

@Serializable
data class ToolsetToggleRequest(
    val enabled: Boolean,
    val profile: String? = null,
)
