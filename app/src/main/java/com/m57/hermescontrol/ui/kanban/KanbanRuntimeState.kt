package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanTask

enum class KanbanArcState {
    QUEUED,
    RUNNING,
    STALE,
}

object KanbanRuntimeHelper {
    fun arcState(
        task: KanbanTask,
        fallbackAssignee: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): KanbanArcState? {
        val normalized = task.status.lowercase()
        if (normalized == "running") {
            val isStale = task.lastHeartbeatAt?.let { (nowSeconds - it) > 120L } ?: false
            return if (isStale) KanbanArcState.STALE else KanbanArcState.RUNNING
        }
        val isQueued =
            normalized == "triage" ||
                normalized == "review" ||
                (normalized == "ready" && (!task.assignee.isNullOrBlank() || !fallbackAssignee.isNullOrBlank()))
        return if (isQueued) KanbanArcState.QUEUED else null
    }

    fun isWontRun(
        task: KanbanTask,
        fallbackAssignee: String? = null,
    ): Boolean =
        task.status.equals("ready", ignoreCase = true) &&
            task.assignee.isNullOrBlank() &&
            fallbackAssignee.isNullOrBlank()

    fun formatElapsed(
        startSeconds: Long?,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String? {
        if (startSeconds == null || startSeconds <= 0L || nowSeconds < startSeconds) return null
        val diff = nowSeconds - startSeconds
        return when {
            diff < 60L -> "${diff}s"
            diff < 3600L -> "${diff / 60L}m"
            diff < 86400L -> "${diff / 3600L}h"
            else -> "${diff / 86400L}d"
        }
    }
}
