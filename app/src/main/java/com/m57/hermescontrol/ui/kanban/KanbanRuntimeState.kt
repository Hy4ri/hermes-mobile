package com.m57.hermescontrol.ui.kanban

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.data.model.KanbanTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

/** One coarse clock per board screen, rather than one coroutine per task card. */
@Composable
fun rememberKanbanNowSeconds(
    enabled: Boolean,
    clock: () -> Long = { System.currentTimeMillis() / 1000L },
): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(
        initialValue = clock(),
        key1 = enabled,
        key2 = lifecycle,
    ) {
        if (enabled) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    value = clock()
                    delay(5_000L)
                }
            }
        }
    }.value
}
