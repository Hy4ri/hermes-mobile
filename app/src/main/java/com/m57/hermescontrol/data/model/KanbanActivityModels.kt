package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class KanbanComment(
    val id: Long,
    val author: String,
    val body: String,
    val createdAt: Long,
)

@Serializable
data class KanbanDetailEvent(
    val id: Long,
    val kind: String,
    val payload: JsonElement? = null,
    val createdAt: Long,
)

@Serializable
data class KanbanRun(
    val id: Long,
    val profile: String? = null,
    val status: String,
    val outcome: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val metadata: JsonElement? = null,
    val workerPid: Int? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

@Serializable
data class WorkerLog(
    val taskId: String,
    val path: String? = null,
    val exists: Boolean = false,
    val sizeBytes: Long = 0L,
    val content: String = "",
    val truncated: Boolean = false,
)

@Serializable
data class KanbanAttachment(
    val id: Long,
    val taskId: String? = null,
    val filename: String,
    val contentType: String? = null,
    val size: Long? = null,
    val uploadedBy: String? = null,
    val storedPath: String? = null,
    val createdAt: Long? = null,
)

@Serializable
data class AttachmentUploadResponse(
    val attachment: KanbanAttachment? = null,
)
