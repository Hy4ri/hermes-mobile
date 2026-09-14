package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class KanbanBoard(
    @SerialName("slug") val id: String,
    val name: String? = null,
    val description: String? = null,
    val isCurrent: Boolean = false,
    val total: Int? = null,
    val counts: Map<String, Int> = emptyMap(),
    val defaultWorkdir: String? = null,
    val defaultWorkspaceKind: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val createdAt: Long? = null,
    val archived: Boolean = false,
) {
    val slug: String get() = id
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

@Serializable
data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask> = emptyList(),
)

@Serializable
data class KanbanBoardResponse(
    val columns: List<KanbanColumn> = emptyList(),
    val assignees: List<String>? = null,
    val tenants: List<String>? = null,
    val latestEventId: Long = 0L,
    val now: Long = 0L,
)

@Serializable
data class KanbanBoardsResponse(
    val boards: List<KanbanBoard> = emptyList(),
    val current: String? = null,
)

@Serializable
data class KanbanProject(
    val id: String,
    val slug: String,
    val name: String,
    val primaryPath: String? = null,
    val icon: String? = null,
    val color: String? = null,
)

@Serializable
data class KanbanProjectsResponse(
    val projects: List<KanbanProject> = emptyList(),
)

@Serializable
data class CreateBoardBody(
    val slug: String,
    val name: String? = null,
    val description: String? = null,
    val projectId: String? = null,
    val switch: Boolean = false,
)

@Serializable
data class CreateBoardResponse(
    val board: KanbanBoard? = null,
    val current: String? = null,
)

@Serializable
data class RenameBoardBody(
    val name: String? = null,
    val description: String? = null,
    val defaultWorkdir: String? = null,
    val projectId: String? = null,
)

@Serializable
data class RenameBoardResponse(
    val board: KanbanBoard? = null,
)

@Serializable
data class DeleteBoardResponse(
    val result: JsonElement? = null,
    val current: String? = null,
)
