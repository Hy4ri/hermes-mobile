package com.m57.hermescontrol.data.model

data class ModelOptionsResponse(
    val providers: List<ModelProvider>,
)

data class ModelProvider(
    val slug: String,
    val name: String,
    val is_current: Boolean?,
    val is_user_defined: Boolean?,
    val models: List<String>?,
    val total_models: Int?,
    val source: String?,
    val authenticated: Boolean?,
    val auth_type: String?,
    val warning: String?,
)
