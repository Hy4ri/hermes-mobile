package com.m57.hermescontrol.data.model

data class Toolset(
    val name: String,
    val label: String?,
    val description: String?,
    val enabled: Boolean,
    val available: Boolean?,
    val configured: Boolean?,
    val tools: List<String>?,
)

data class ToolsetToggleRequest(
    val enabled: Boolean,
    val profile: String? = null,
)
