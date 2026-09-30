package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class AuxiliaryTaskAssignment(
    val task: String,
    val provider: String,
    val model: String,
    val base_url: String? = null,
)

@Serializable
data class AuxiliaryModelsResponse(
    val tasks: List<AuxiliaryTaskAssignment>,
    val main: MainModelAssignment,
)

@Serializable
data class MainModelAssignment(
    val provider: String,
    val model: String,
)

@Serializable
data class MoaModelSlot(
    val provider: String,
    val model: String,
    val reasoning_effort: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class MoaConfigPreset(
    val reference_models: List<MoaModelSlot>,
    val aggregator: MoaModelSlot,
    // null = omitted from API calls (provider default); the backend sends null when unset.
    val reference_temperature: Double? = null,
    val aggregator_temperature: Double? = null,
    val reference_timeout: Double? = null,
    val degraded_reference_policy: String = "loud",
    val fanout: String? = null,
    val max_tokens: Int = 4096,
    val enabled: Boolean = true,
)

@Serializable
data class MoaConfigResponse(
    val default_preset: String = "",
    val active_preset: String = "",
    val presets: Map<String, MoaConfigPreset> = emptyMap(),
    val reference_models: List<MoaModelSlot> = emptyList(),
    val aggregator: MoaModelSlot = MoaModelSlot("", ""),
    // null = omitted from API calls (provider default); the backend sends null when unset.
    val reference_temperature: Double? = null,
    val aggregator_temperature: Double? = null,
    val reference_timeout: Double? = null,
    val degraded_reference_policy: String = "loud",
    val fanout: String? = null,
    val max_tokens: Int = 4096,
    val enabled: Boolean = true,
)

@Serializable
data class ModelAssignmentRequest(
    val confirm_expensive_model: Boolean = false,
    val scope: String,
    val provider: String,
    val model: String,
    val base_url: String? = null,
    val task: String? = null,
)

@Serializable
data class ModelAssignmentResponse(
    val confirm_message: String? = null,
    val confirm_required: Boolean? = null,
    val ok: Boolean = false,
    val scope: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val tasks: List<String>? = null,
    val reset: Boolean? = null,
)
