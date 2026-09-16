package com.m57.hermescontrol.ui.kanban

/**
 * Shared constraints for Kanban target columns.
 * Mirrors desktop's LOCKED_COLUMNS: system-owned columns that users can drag/move
 * cards OUT of, but never manually target for moves or creation.
 */
object KanbanStatusConstraints {
    val LOCKED_COLUMNS = setOf("running", "review", "scheduled")

    fun isLockedTarget(status: String): Boolean = status.lowercase() in LOCKED_COLUMNS

    fun isUserWritableTarget(status: String): Boolean =
        !isLockedTarget(status) && !status.equals("archived", ignoreCase = true)
}
