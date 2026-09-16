package com.m57.hermescontrol.data.local

/**
 * Local preferences for Kanban board viewing:
 * selected board slug, view mode (list vs board), archive visibility, and group running.
 */
interface KanbanPreferencesStore {
    fun getSelectedBoard(endpoint: String): String?

    fun setSelectedBoard(
        endpoint: String,
        slug: String,
    )

    fun clearSelectedBoard(endpoint: String)

    fun getViewMode(): String

    fun setViewMode(mode: String)

    fun getIncludeArchived(): Boolean

    fun setIncludeArchived(include: Boolean)

    fun getGroupRunning(): Boolean

    fun setGroupRunning(group: Boolean)

    companion object {
        const val MODE_LIST = "list"
        const val MODE_BOARD = "board"
    }
}

class InMemoryKanbanPreferencesStore : KanbanPreferencesStore {
    private val selectedBoards = mutableMapOf<String, String>()
    private var viewMode: String = KanbanPreferencesStore.MODE_LIST
    private var includeArchived: Boolean = false
    private var groupRunning: Boolean = false

    override fun getSelectedBoard(endpoint: String): String? = selectedBoards[endpoint]

    override fun setSelectedBoard(
        endpoint: String,
        slug: String,
    ) {
        selectedBoards[endpoint] = slug
    }

    override fun clearSelectedBoard(endpoint: String) {
        selectedBoards.remove(endpoint)
    }

    override fun getViewMode(): String = viewMode

    override fun setViewMode(mode: String) {
        viewMode = mode
    }

    override fun getIncludeArchived(): Boolean = includeArchived

    override fun setIncludeArchived(include: Boolean) {
        includeArchived = include
    }

    override fun getGroupRunning(): Boolean = groupRunning

    override fun setGroupRunning(group: Boolean) {
        groupRunning = group
    }
}
