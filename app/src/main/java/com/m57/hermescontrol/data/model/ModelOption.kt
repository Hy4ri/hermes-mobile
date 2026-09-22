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
    val capabilities: Map<String, ModelCapabilities>? = null,
)

@Serializable
data class ModelCapabilities(
    val fast: Boolean? = null,
    val reasoning: Boolean? = null,
    val can_disable_reasoning: Boolean? = null,
    val supports_tools: Boolean? = null,
    val supports_vision: Boolean? = null,
    val supports_reasoning: Boolean? = null,
    val context_window: Long? = null,
    val max_output_tokens: Long? = null,
    val model_family: String? = null,
)

val ModelCapabilities.reasoningSupport: Boolean?
    get() = supports_reasoning ?: reasoning

fun ModelCapabilities?.mergedWithCanonical(canonical: ModelCapabilities?): ModelCapabilities? {
    if (canonical == null) return this
    val catalog = this
    return ModelCapabilities(
        fast = catalog?.fast,
        reasoning = catalog?.reasoning,
        can_disable_reasoning = catalog?.can_disable_reasoning,
        supports_tools = canonical.supports_tools ?: catalog?.supports_tools,
        supports_vision = canonical.supports_vision ?: catalog?.supports_vision,
        supports_reasoning = canonical.supports_reasoning ?: catalog?.supports_reasoning,
        context_window = canonical.context_window ?: catalog?.context_window,
        max_output_tokens = canonical.max_output_tokens ?: catalog?.max_output_tokens,
        model_family = canonical.model_family ?: catalog?.model_family,
    )
}

fun List<ModelProvider>.withModelInfoCapabilities(info: ModelInfoResponse?): List<ModelProvider> {
    val providerSlug = info?.provider?.takeIf { it.isNotBlank() } ?: return this
    val modelName = info.model?.takeIf { it.isNotBlank() } ?: return this
    val canonical = info.capabilities ?: return this
    var changed = false
    val enriched =
        map { provider ->
            if (
                provider.slug.equals(providerSlug, ignoreCase = true) &&
                provider.models.orEmpty().contains(modelName)
            ) {
                changed = true
                provider.copy(
                    capabilities =
                        provider.capabilities.orEmpty() +
                            (
                                modelName to
                                    (provider.capabilities?.get(modelName).mergedWithCanonical(canonical) ?: canonical)
                            ),
                )
            } else {
                provider
            }
        }
    return if (changed) enriched else this
}

@Serializable
data class PinnedModel(
    val providerSlug: String,
    val modelName: String,
)
