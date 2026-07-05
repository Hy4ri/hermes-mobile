package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class McpServersResponse(
    val servers: List<McpServer>,
)

@Serializable
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

@Serializable
data class McpServerToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class AddMcpServerRequest(
    val name: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
)

@Serializable
data class McpCatalogResponse(
    val entries: List<McpCatalogEntry>,
)

@Serializable
data class McpCatalogEntry(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: List<McpCatalogEnvVar>? = null,
)

@Serializable
data class McpCatalogEnvVar(
    val key: String,
    val label: String? = null,
    val description: String? = null,
    val required: Boolean = false,
)

@Serializable
data class McpCatalogInstallRequest(
    val name: String,
    val env: Map<String, String>? = null,
)
