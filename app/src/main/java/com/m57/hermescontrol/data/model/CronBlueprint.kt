package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CronBlueprintListResponse(
    val blueprints: List<CronBlueprint> = emptyList(),
)

@Serializable
data class CronBlueprint(
    val key: String,
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val fields: List<CronBlueprintField> = emptyList(),
    val schedule: String = "",
    val scheduleHuman: String = "",
    val command: String = "",
    val appUrl: String = "",
)

@Serializable
data class CronBlueprintField(
    val name: String,
    val type: String = "text",
    val label: String = "",
    val default: JsonElement? = null,
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
    val strict: Boolean = true,
    val help: String = "",
) {
    val defaultText: String
        get() = (default as? JsonPrimitive)?.content.orEmpty()
}

@Serializable
data class DeliveryTargetsResponse(
    val targets: List<DeliveryTarget> = emptyList(),
)

@Serializable
data class DeliveryTarget(
    val id: String,
    val name: String = "",
    val home_target_set: Boolean = false,
    val home_env_var: String? = null,
)

@Serializable
data class InstantiateBlueprintRequest(
    val blueprint: String,
    val values: Map<String, String>,
)
