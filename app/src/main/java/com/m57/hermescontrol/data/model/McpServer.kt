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
    val status: String? = null,
    val error: String? = null,
)

data class McpServerToggleRequest(
    val enabled: Boolean,
)

data class AddMcpServerRequest(
    val name: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
)

data class McpCatalogResponse(
    val entries: List<McpCatalogEntry>,
)

data class McpCatalogEntry(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: List<McpCatalogEnvVar>? = null,
)

data class McpCatalogEnvVar(
    val key: String,
    val label: String? = null,
    val description: String? = null,
    val required: Boolean = false,
)

data class McpCatalogInstallRequest(
    val name: String,
    val env: Map<String, String>? = null,
)

data class McpServerTestResponse(
    val ok: Boolean? = null,
    val tools: List<String>? = null,
    val error: String? = null,
)
