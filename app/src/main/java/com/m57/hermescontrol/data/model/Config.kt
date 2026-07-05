package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RawConfigResponse(
    val path: String?,
    val yaml: String?,
)

@Serializable
data class UpdateRawConfigRequest(
    val yaml_text: String,
    val profile: String? = null,
)

@Serializable
data class ConfigSchemaResponse(
    val fields: Map<String, SchemaField>,
    val category_order: List<String>,
)

@Serializable
data class SchemaField(
    val type: String,
    val description: String? = null,
    val category: String? = null,
    val options: List<String>? = null,
)

@Serializable
data class ConfigUpdateRequest(
    val config: Map<String, JsonElement>,
    val profile: String? = null,
)
