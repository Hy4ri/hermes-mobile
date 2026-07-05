package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CreateCronJobRequest(
    val name: String = "",
    val schedule: String,
    val prompt: String = "",
    val deliver: String = "local",
    val skills: List<String>? = null,
    val model: String? = null,
    val provider: String? = null,
    val base_url: String? = null,
    val script: String? = null,
    val context_from: List<String>? = null,
    val enabled_toolsets: List<String>? = null,
    val workdir: String? = null,
    val no_agent: Boolean = false,
    val repeat: Int? = null,
)

@Serializable
data class UpdateCronJobRequest(
    val updates: Map<String, JsonElement>,
)
