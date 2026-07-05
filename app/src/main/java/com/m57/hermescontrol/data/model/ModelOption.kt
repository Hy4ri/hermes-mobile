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
    val is_current: Boolean? = null,
    val is_user_defined: Boolean? = null,
    val models: List<String>? = null,
    val total_models: Int? = null,
    val source: String? = null,
    val authenticated: Boolean? = null,
    val auth_type: String? = null,
    val warning: String? = null,
)

@Serializable
data class PinnedModel(
    val providerSlug: String,
    val modelName: String,
)
