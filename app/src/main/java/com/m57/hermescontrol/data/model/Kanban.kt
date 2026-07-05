package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KanbanBoard(
    @SerialName("slug") val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class KanbanTask(
    val id: String,
    val title: String,
    @SerialName("body") val description: String? = null,
    val status: String,
    @SerialName("assignee") val assignedTo: String? = null,
)

@Serializable
data class CreateTaskBody(
    val title: String,
    val body: String? = null,
)

@Serializable
data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask>,
)

@Serializable
data class KanbanBoardResponse(
    val columns: List<KanbanColumn>,
    val assignees: List<String>? = null,
    val tenants: List<String>? = null,
)

@Serializable
data class KanbanBoardsResponse(
    val boards: List<KanbanBoard>,
    val current: String? = null,
)
