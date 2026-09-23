package com.m57.hermescontrol.ui.kanban

/** The dashboard currently sends dependency refusals as a 409 string detail, not JSON fields. */
data class KanbanTransitionRefusal(
    val blockingParentIds: List<String>,
    val blockingParentStatuses: List<String>,
)

fun kanbanTransitionRefusal(message: String): KanbanTransitionRefusal? {
    val (parentList, pattern) =
        when {
            message.contains("blocked by parent(s) not done") -> {
                message.substringAfter("blocked by parent(s) not done") to Regex("\\(([^(),\\s]+), status=([^()]*)\\)")
            }

            message.contains("unsatisfied parent dependencies:") -> {
                message.substringAfter("unsatisfied parent dependencies:").substringBefore(';') to
                    Regex("([^,;()\\s]+) \\(([^()]*)\\)")
            }

            else -> {
                return null
            }
        }
    val blockers =
        pattern.findAll(parentList).map { match -> match.groupValues[1] to match.groupValues[2] }.toList()
    return blockers.takeIf { it.isNotEmpty() }?.let {
        KanbanTransitionRefusal(it.map { pair -> pair.first }, it.map { pair -> pair.second })
    }
}
