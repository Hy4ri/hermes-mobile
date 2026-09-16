package com.m57.hermescontrol.ui.kanban

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Own only confirmed uploads; cancellation must not strand their temporary archive (#1137). */
internal suspend fun <T> withKanbanImportCleanup(
    cleanup: suspend () -> Unit,
    onCleanupFailure: (Exception) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } finally {
        withContext(NonCancellable + dispatcher) {
            try {
                withTimeout(10_000L) { cleanup() }
            } catch (e: Exception) {
                onCleanupFailure(e)
            }
        }
    }
