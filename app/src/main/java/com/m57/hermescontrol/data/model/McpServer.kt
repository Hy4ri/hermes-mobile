package com.m57.hermescontrol.data.model

data class McpServersResponse(
    val servers: List<McpServer>,
)

data class McpServer(
    val name: String,
    val transport: String?,
    val url: String?,
    val command: String?,
    val args: List<String>?,
    val env: Map<String, String>?,
    val enabled: Boolean,
)

data class McpServerToggleRequest(
    val enabled: Boolean,
)
