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
    // Monitor mode. NOTE: the backend REST create model does not carry these
    // yet (they are silently dropped on POST) — the ViewModel applies them
    // via a follow-up PUT; keep them here for forward compatibility.
    val monitor_script: String? = null,
    val monitor_url: String? = null,
)

@Serializable
data class UpdateCronJobRequest(
    val updates: Map<String, JsonElement>,
)
