package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TaskLinkCounts(
    val parents: Int = 0,
    val children: Int = 0,
)

@Serializable
data class TaskProgress(
    val done: Int = 0,
    val total: Int = 0,
)

@Serializable
data class TaskWarnings(
    val count: Int = 0,
    val highestSeverity: String? = null,
)

@Serializable
data class TaskDiagnosticAction(
    val kind: String,
    val label: String,
    val payload: Map<String, JsonElement> = emptyMap(),
    val suggested: Boolean = false,
)

@Serializable
data class TaskDiagnostic(
    val kind: String,
    val severity: String,
    val title: String,
    val detail: String,
    val actions: List<TaskDiagnosticAction> = emptyList(),
    val count: Int = 1,
    val lastSeenAt: Long = 0L,
    val data: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class TaskLinks(
    val parents: List<String> = emptyList(),
    val children: List<String> = emptyList(),
)

@Serializable
data class ChildTaskResult(
    val id: String,
    val title: String,
    val status: String,
    val latestSummary: String? = null,
    val result: String? = null,
)

@Serializable
data class KanbanTask(
    val id: String,
    val title: String,
    val body: String? = null,
    val status: String,
    val assignee: String? = null,
    val priority: Int? = null,
    val tenant: String? = null,
    val createdAt: Long? = null,
    val latestSummary: String? = null,
    val commentCount: Int = 0,
    val linkCounts: TaskLinkCounts? = null,
    val progress: TaskProgress? = null,
    val warnings: TaskWarnings? = null,
    val startedAt: Long? = null,
    val workerPid: Int? = null,
    val lastHeartbeatAt: Long? = null,
) {
    val description: String? get() = body
    val assignedTo: String? get() = assignee

    constructor(
        id: String,
        title: String,
        description: String? = null,
        status: String,
        assignedTo: String? = null,
    ) : this(
        id = id,
        title = title,
        body = description,
        status = status,
        assignee = assignedTo,
    )
}

@Serializable
data class KanbanTaskFull(
    val id: String,
    val title: String,
    val body: String? = null,
    val status: String,
    val assignee: String? = null,
    val priority: Int? = null,
    val tenant: String? = null,
    val createdAt: Long? = null,
    val latestSummary: String? = null,
    val commentCount: Int = 0,
    val linkCounts: TaskLinkCounts? = null,
    val progress: TaskProgress? = null,
    val warnings: TaskWarnings? = null,
    val startedAt: Long? = null,
    val workerPid: Int? = null,
    val lastHeartbeatAt: Long? = null,
    val result: String? = null,
    val createdBy: String? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val reasoningEffort: String? = null,
    val completedAt: Long? = null,
    val lastFailureError: String? = null,
    val workspaceKind: String? = null,
    val workspacePath: String? = null,
    val branchName: String? = null,
    val consecutiveFailures: Int = 0,
    val diagnostics: List<TaskDiagnostic> = emptyList(),
) {
    val description: String? get() = body
    val assignedTo: String? get() = assignee
}

@Serializable
data class KanbanTaskDetailResponse(
    val task: KanbanTaskFull,
    val comments: List<KanbanComment> = emptyList(),
    val events: List<KanbanDetailEvent> = emptyList(),
    val attachments: List<KanbanAttachment>? = null,
    val links: TaskLinks = TaskLinks(),
    val childResults: List<ChildTaskResult> = emptyList(),
    val runs: List<KanbanRun> = emptyList(),
)

@Serializable
data class CreateTaskBody(
    val title: String,
    val body: String? = null,
    val assignee: String? = null,
    val tenant: String? = null,
    val priority: Int = 0,
    val workspaceKind: String? = null,
    val workspacePath: String? = null,
    val parents: List<String> = emptyList(),
    val triage: Boolean = false,
    val maxRuntimeSeconds: Int? = null,
    val skills: List<String>? = null,
    val goalMode: Boolean = false,
    val goalMaxTurns: Int? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val reasoningEffort: String? = null,
    val projectId: String? = null,
)

@Serializable
data class CreateTaskResponse(
    val task: KanbanTask? = null,
    val warning: String? = null,
)

@Serializable
data class UpdateTaskBody(
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val result: String? = null,
    val blockReason: String? = null,
    val summary: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val clearModelOverride: Boolean = false,
    val reasoningEffort: String? = null,
    val clearReasoningEffort: Boolean = false,
)

@Serializable
data class UpdateTaskResponse(
    val task: KanbanTask? = null,
)

@Serializable
data class BulkTasksBody(
    val ids: List<String>,
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
    val archive: Boolean = false,
    val result: String? = null,
    val summary: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    val reclaimFirst: Boolean = false,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val clearModelOverride: Boolean = false,
    val reasoningEffort: String? = null,
    val clearReasoningEffort: Boolean = false,
)

@Serializable
data class BulkTaskResult(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class BulkTasksResponse(
    val results: List<BulkTaskResult> = emptyList(),
)

@Serializable
data class ReassignTaskBody(
    val profile: String? = null,
    val reclaimFirst: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class ReassignTaskResponse(
    val ok: Boolean = true,
    val taskId: String,
    val assignee: String? = null,
)

@Serializable
data class ReclaimTaskBody(
    val reason: String? = null,
)

@Serializable
data class ReclaimTaskResponse(
    val ok: Boolean = true,
    val taskId: String,
)

@Serializable
data class TaskEstimate(
    val ok: Boolean,
    val reason: String? = null,
    val estTokens: Int? = null,
    val complexity: String? = null,
    val rationale: String? = null,
    val model: String? = null,
)
