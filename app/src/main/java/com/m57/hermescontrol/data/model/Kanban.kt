package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KanbanBoard(
    @SerialName("slug") val id: String,
    val name: String,
    val description: String?,
)

@Serializable
data class KanbanTask(
    val id: String,
    val title: String,
    @SerialName("body") val description: String?,
    val status: String,
    @SerialName("assignee") val assignedTo: String?,
)

@Serializable
data class CreateTaskBody(
    val title: String,
    val body: String?,
)

@Serializable
data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask>,
)

@Serializable
data class KanbanBoardResponse(
    val columns: List<KanbanColumn>,
    val assignees: List<String>?,
    val tenants: List<String>?,
)

@Serializable
data class KanbanBoardsResponse(
    val boards: List<KanbanBoard>,
    val current: String? = null,
)
