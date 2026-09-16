package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class KanbanProfile(
    val name: String,
    val isDefault: Boolean = false,
    val description: String = "",
    val descriptionAuto: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val skillCount: Int = 0,
)

@Serializable
data class KanbanProfilesResponse(
    val profiles: List<KanbanProfile> = emptyList(),
)

@Serializable
data class OrchestrationSettings(
    val orchestratorProfile: String = "",
    val defaultAssignee: String = "",
    val autoDecompose: Boolean = true,
    val autoPromoteChildren: Boolean = true,
    val resolvedOrchestratorProfile: String = "",
    val resolvedDefaultAssignee: String = "",
    val activeProfile: String = "default",
)

@Serializable
data class OrchestrationSettingsUpdate(
    val orchestratorProfile: String? = null,
    val defaultAssignee: String? = null,
    val autoDecompose: Boolean? = null,
    val autoPromoteChildren: Boolean? = null,
)

@Serializable
data class AutoDescribeResponse(
    val ok: Boolean = false,
    val profile: String? = null,
    val reason: String? = null,
    val description: String? = null,
)

@Serializable
data class BoardExportResult(
    val board: String,
    val archive: String,
    val size: Long = 0L,
)

@Serializable
data class BoardImportResult(
    val board: String,
    val name: String = "",
    val renamed: Boolean = false,
    val requestedBoard: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val current: String? = null,
)

@Serializable
data class DispatchResult(
    val spawned: List<JsonElement> = emptyList(),
)
