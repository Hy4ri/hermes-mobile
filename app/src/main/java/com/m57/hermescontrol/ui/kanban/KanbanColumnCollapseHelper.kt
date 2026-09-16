package com.m57.hermescontrol.ui.kanban

object KanbanColumnCollapseHelper {
    fun isColumnCollapsed(
        columnName: String,
        columnTaskCount: Int,
        boardHasWork: Boolean,
        overrides: Map<String, Boolean>,
    ): Boolean {
        overrides[columnName]?.let { return it }
        if (!boardHasWork) return false
        return columnTaskCount == 0
    }
}
