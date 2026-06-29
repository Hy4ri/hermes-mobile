package com.m57.hermescontrol.data.model

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

data class UpdateCronJobRequest(
    val updates: Map<String, Any?>,
)
