package com.m57.hermescontrol.data.model

data class PluginInfo(
    val name: String,
    val description: String?,
    val version: String?,
    val enabled: Boolean,
    val installed: Boolean,
)

data class TogglePluginRequest(
    val enabled: Boolean,
)
