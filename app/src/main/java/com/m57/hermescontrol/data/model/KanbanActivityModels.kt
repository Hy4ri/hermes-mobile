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
    val taskId: String? = null,
    val profile: String? = null,
    val stepKey: String? = null,
    val status: String,
    val claimLock: String? = null,
    val claimExpires: Long? = null,
    val maxRuntimeSeconds: Int? = null,
    val lastHeartbeatAt: Long? = null,
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

@Serializable
data class AttachmentListResponse(
    val attachments: List<KanbanAttachment> = emptyList(),
)

@Serializable
data class DeleteAttachmentResponse(
    val ok: Boolean,
    val id: Long,
)

@Serializable
data class KanbanRunResponse(
    val run: KanbanRun,
)

@Serializable
data class KanbanRunInspection(
    val runId: Long,
    val alive: Boolean,
    val pid: Int? = null,
    val reason: String? = null,
    val cpuPercent: Double? = null,
    val memoryRssBytes: Long? = null,
    val memoryVmsBytes: Long? = null,
    val numThreads: Int? = null,
    val numFds: Int? = null,
    val status: String? = null,
    val createTime: Double? = null,
    val cmdline: List<String>? = null,
    val error: String? = null,
)

@Serializable
data class TerminateRunBody(
    val reason: String? = null,
)

@Serializable
data class TerminateRunResponse(
    val ok: Boolean,
    val runId: Long,
    val taskId: String,
)

@Serializable
data class KanbanActiveWorker(
    val runId: Long,
    val taskId: String,
    val taskTitle: String,
    val taskStatus: String,
    val taskAssignee: String? = null,
    val profile: String? = null,
    val workerPid: Int,
    val startedAt: Long? = null,
    val claimLock: String? = null,
    val claimExpires: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val maxRuntimeSeconds: Int? = null,
)

@Serializable
data class ActiveWorkersResponse(
    val workers: List<KanbanActiveWorker> = emptyList(),
    val count: Int = workers.size,
    val checkedAt: Long,
)
