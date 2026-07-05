package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ModelOptionsResponse(
    val providers: List<ModelProvider>,
)

@Serializable
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

@Serializable
data class PinnedModel(
    val providerSlug: String,
    val modelName: String,
)
