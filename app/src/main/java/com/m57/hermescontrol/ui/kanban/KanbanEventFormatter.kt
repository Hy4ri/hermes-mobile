package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanDetailEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class KanbanEventMessage {
    CREATED,
    CREATED_BY,
    MOVED,
    ASSIGNED,
    UNASSIGNED,
    COMMENTED,
    CLAIMED_REVIEW,
    CLAIMED_WORKER,
    WORKER_STARTED,
    COMPLETED,
    BLOCKED,
    UNBLOCKED,
    RECLAIMED,
    SPECIFIED,
    PROMOTED,
    SCHEDULED,
    ARCHIVED,
    PRIORITY,
    UNKNOWN,
}

data class FormattedKanbanEvent(
    val label: String,
    val detail: String? = null,
    val message: KanbanEventMessage = KanbanEventMessage.UNKNOWN,
    val arguments: List<String> = emptyList(),
)

object KanbanEventFormatter {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun format(event: KanbanDetailEvent): FormattedKanbanEvent {
        val payloadMap = extractPayloadMap(event.payload)

        fun str(key: String): String? {
            val element = payloadMap[key] ?: return null
            return when {
                element is JsonPrimitive && element.isString -> element.content.takeIf { it.isNotBlank() }
                element is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                else -> null
            }
        }

        fun col(key: String): String? {
            val raw = str(key) ?: return null
            return when (raw.lowercase()) {
                "triage" -> "Triage"
                "todo" -> "Todo"
                "scheduled" -> "Scheduled"
                "ready" -> "Ready"
                "running" -> "Running"
                "blocked" -> "Blocked"
                "review" -> "Review"
                "done" -> "Done"
                "archived" -> "Archived"
                else -> raw.replaceFirstChar { it.uppercase() }
            }
        }

        return when (event.kind.lowercase()) {
            "created" -> {
                val status = col("status") ?: "Todo"
                val assignee = str("assignee")
                val label = if (assignee != null) "Created in $status by $assignee" else "Created in $status"
                FormattedKanbanEvent(
                    label = label,
                    message = if (assignee != null) KanbanEventMessage.CREATED_BY else KanbanEventMessage.CREATED,
                    arguments = listOfNotNull(status, assignee),
                )
            }

            "status" -> {
                val targetStatus = col("status") ?: "?"
                val reason = str("reason")
                val parent = str("parent")
                val detail =
                    when (reason) {
                        "parent_reopened" -> if (parent != null) "Parent $parent reopened" else "Parent reopened"
                        else -> reason
                    }
                FormattedKanbanEvent(
                    label = "Moved to $targetStatus",
                    detail = detail,
                    message = KanbanEventMessage.MOVED,
                    arguments = listOf(targetStatus),
                )
            }

            "assigned" -> {
                val assignee = str("assignee")
                FormattedKanbanEvent(
                    label = if (assignee != null) "Assigned to $assignee" else "Unassigned",
                    message = if (assignee != null) KanbanEventMessage.ASSIGNED else KanbanEventMessage.UNASSIGNED,
                    arguments = listOfNotNull(assignee),
                )
            }

            "commented" -> {
                val author = str("author") ?: "someone"
                FormattedKanbanEvent(
                    label = "Comment by $author",
                    message = KanbanEventMessage.COMMENTED,
                    arguments = listOf(author),
                )
            }

            "claimed" -> {
                val source = str("source_status")
                FormattedKanbanEvent(
                    label = if (source == "review") "Claimed for review" else "Claimed by worker",
                    message =
                        if (source ==
                            "review"
                        ) {
                            KanbanEventMessage.CLAIMED_REVIEW
                        } else {
                            KanbanEventMessage.CLAIMED_WORKER
                        },
                )
            }

            "spawned" -> {
                val pid = str("pid") ?: payloadMap["pid"]?.toString()
                FormattedKanbanEvent(
                    label = "Worker started",
                    detail = if (pid != null && pid != "null") "PID $pid" else null,
                    message = KanbanEventMessage.WORKER_STARTED,
                    arguments = listOfNotNull(pid?.takeIf { it != "null" }),
                )
            }

            "completed" -> {
                FormattedKanbanEvent(label = "Completed", message = KanbanEventMessage.COMPLETED)
            }

            "blocked" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Blocked", detail = reason, message = KanbanEventMessage.BLOCKED)
            }

            "unblocked" -> {
                val status = col("status")
                val label = if (status != null) "Unblocked ($status)" else "Unblocked"
                FormattedKanbanEvent(
                    label = label,
                    message = KanbanEventMessage.UNBLOCKED,
                    arguments = listOfNotNull(status),
                )
            }

            "reclaimed" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Reclaimed", detail = reason, message = KanbanEventMessage.RECLAIMED)
            }

            "specified" -> {
                FormattedKanbanEvent(label = "Specified", message = KanbanEventMessage.SPECIFIED)
            }

            "promoted" -> {
                FormattedKanbanEvent(label = "Promoted to ready", message = KanbanEventMessage.PROMOTED)
            }

            "scheduled" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Scheduled", detail = reason, message = KanbanEventMessage.SCHEDULED)
            }

            "archived" -> {
                FormattedKanbanEvent(label = "Archived", message = KanbanEventMessage.ARCHIVED)
            }

            "reprioritized" -> {
                val priority = str("priority") ?: payloadMap["priority"]?.toString() ?: "?"
                FormattedKanbanEvent(
                    label = "Priority set to $priority",
                    message = KanbanEventMessage.PRIORITY,
                    arguments = listOf(priority),
                )
            }

            else -> {
                val label = event.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                val detail =
                    payloadMap.entries
                        .filter { (_, v) -> v is JsonPrimitive }
                        .joinToString(" ") { (k, v) -> "$k=${(v as JsonPrimitive).content}" }
                        .ifBlank { null }
                FormattedKanbanEvent(label = label, detail = detail)
            }
        }
    }

    private fun extractPayloadMap(element: JsonElement?): Map<String, JsonElement> {
        if (element == null) return emptyMap()
        if (element is JsonObject) return element
        if (element is JsonPrimitive && element.isString) {
            val content = element.content.trim()
            if (content.startsWith("{") && content.endsWith("}")) {
                runCatching {
                    val parsed = json.parseToJsonElement(content)
                    if (parsed is JsonObject) return parsed
                }
            }
            return if (content.isNotEmpty()) mapOf("raw" to element) else emptyMap()
        }
        return emptyMap()
    }
}
