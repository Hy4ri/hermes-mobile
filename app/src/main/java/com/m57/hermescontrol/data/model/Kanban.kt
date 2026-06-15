package com.m57.hermescontrol.data.model

data class KanbanBoard(
    val id: String,
    val name: String,
    val description: String?,
)

data class KanbanTask(
    val id: String,
    val boardId: String,
    val title: String,
    val description: String?,
    val status: String,
    val assignedTo: String?,
)

data class MoveTaskRequest(
    val status: String,
)

data class CreateTaskRequest(
    val boardId: String,
    val title: String,
    val description: String?,
    val status: String,
)
