package com.m57.hermescontrol.data.model

data class AuxiliaryTaskAssignment(
    val task: String,
    val provider: String,
    val model: String,
    val base_url: String? = null,
)

data class AuxiliaryModelsResponse(
    val tasks: List<AuxiliaryTaskAssignment>,
    val main: MainModelAssignment,
)

data class MainModelAssignment(
    val provider: String,
    val model: String,
)

data class MoaModelSlot(
    val provider: String,
    val model: String,
)

data class MoaConfigPreset(
    val reference_models: List<MoaModelSlot>,
    val aggregator: MoaModelSlot,
    val reference_temperature: Double = 0.7,
    val aggregator_temperature: Double = 0.3,
    val max_tokens: Int = 4096,
    val enabled: Boolean = true,
)

data class MoaConfigResponse(
    val default_preset: String = "",
    val active_preset: String = "",
    val presets: Map<String, MoaConfigPreset> = emptyMap(),
    val reference_models: List<MoaModelSlot> = emptyList(),
    val aggregator: MoaModelSlot = MoaModelSlot("", ""),
    val reference_temperature: Double = 0.7,
    val aggregator_temperature: Double = 0.3,
    val max_tokens: Int = 4096,
    val enabled: Boolean = true,
)

data class ModelAssignmentRequest(
    val confirm_expensive_model: Boolean = false,
    val scope: String,
    val provider: String,
    val model: String,
    val base_url: String? = null,
    val task: String? = null,
)

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
