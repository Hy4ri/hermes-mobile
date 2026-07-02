package com.m57.hermescontrol.data.model

import com.google.gson.JsonElement

data class RawConfigResponse(
    val path: String?,
    val yaml: String?,
)

data class UpdateRawConfigRequest(
    val yaml_text: String,
    val profile: String? = null,
)

data class ConfigSchemaResponse(
    val fields: Map<String, SchemaField>,
    val category_order: List<String>,
)

data class SchemaField(
    val type: String,
    val description: String? = null,
    val category: String? = null,
    val options: List<String>? = null,
)

data class ConfigUpdateRequest(
    val config: Map<String, JsonElement>,
    val profile: String? = null,
)
