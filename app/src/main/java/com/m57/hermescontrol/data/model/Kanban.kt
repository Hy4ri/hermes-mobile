package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class KanbanBoard(
    @SerializedName("slug") val id: String,
    val name: String,
    val description: String?,
)

data class KanbanTask(
    val id: String,
    val title: String,
    @SerializedName("body") val description: String?,
    val status: String,
    @SerializedName("assignee") val assignedTo: String?,
)

data class CreateTaskBody(
    val title: String,
    val body: String?,
)

data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask>,
)

data class KanbanBoardResponse(
    val columns: List<KanbanColumn>,
    val assignees: List<String>?,
    val tenants: List<String>?,
)

data class KanbanBoardsResponse(
    val boards: List<KanbanBoard>,
    val current: String? = null,
)
