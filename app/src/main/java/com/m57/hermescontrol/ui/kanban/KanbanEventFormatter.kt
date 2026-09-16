package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanDetailEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FormattedKanbanEvent(
    val label: String,
    val detail: String? = null,
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
                FormattedKanbanEvent(label = label)
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
                )
            }

            "assigned" -> {
                val assignee = str("assignee")
                FormattedKanbanEvent(
                    label = if (assignee != null) "Assigned to $assignee" else "Unassigned",
                )
            }

            "commented" -> {
                val author = str("author") ?: "someone"
                FormattedKanbanEvent(label = "Comment by $author")
            }

            "claimed" -> {
                val source = str("source_status")
                FormattedKanbanEvent(
                    label = if (source == "review") "Claimed for review" else "Claimed by worker",
                )
            }

            "spawned" -> {
                val pid = str("pid") ?: payloadMap["pid"]?.toString()
                FormattedKanbanEvent(
                    label = "Worker started",
                    detail = if (pid != null && pid != "null") "PID $pid" else null,
                )
            }

            "completed" -> {
                FormattedKanbanEvent(label = "Completed")
            }

            "blocked" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Blocked", detail = reason)
            }

            "unblocked" -> {
                val status = col("status")
                val label = if (status != null) "Unblocked ($status)" else "Unblocked"
                FormattedKanbanEvent(label = label)
            }

            "reclaimed" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Reclaimed", detail = reason)
            }

            "specified" -> {
                FormattedKanbanEvent(label = "Specified")
            }

            "promoted" -> {
                FormattedKanbanEvent(label = "Promoted to ready")
            }

            "scheduled" -> {
                val reason = str("reason")
                FormattedKanbanEvent(label = "Scheduled", detail = reason)
            }

            "archived" -> {
                FormattedKanbanEvent(label = "Archived")
            }

            "reprioritized" -> {
                val priority = str("priority") ?: payloadMap["priority"]?.toString() ?: "?"
                FormattedKanbanEvent(label = "Priority set to $priority")
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
