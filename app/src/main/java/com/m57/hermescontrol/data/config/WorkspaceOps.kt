package com.m57.hermescontrol.data.config

private const val DEFAULT_WORKSPACE_ID = "focus"
private const val MAX_OPEN_SESSION_TABS = 8

fun ServerStoreState.openSessionTab(sessionId: String): ServerStoreState {
    if (sessionId.isBlank()) return this
    val reordered = openSessionIds.filterNot { it == sessionId } + sessionId
    val pinnedIds = sessionPreferences.filter { it.pinned }.mapTo(mutableSetOf()) { it.sessionId }
    val trimmed = reordered.toMutableList()
    while (trimmed.size > MAX_OPEN_SESSION_TABS) {
        val removableIndex = trimmed.indexOfFirst { it !in pinnedIds && it != sessionId }
        if (removableIndex < 0) break
        trimmed.removeAt(removableIndex)
    }
    return copy(openSessionIds = trimmed, activeSessionId = sessionId)
}

fun ServerStoreState.closeSessionTab(sessionId: String): ServerStoreState {
    val remaining = openSessionIds.filterNot { it == sessionId }
    val nextActive = if (activeSessionId == sessionId) remaining.lastOrNull() else activeSessionId
    return copy(openSessionIds = remaining, activeSessionId = nextActive)
}

fun ServerStoreState.toggleSessionPin(sessionId: String): ServerStoreState {
    val existing = sessionPreferences.firstOrNull { it.sessionId == sessionId }
    val updated = (existing ?: SessionPreference(sessionId)).copy(pinned = !(existing?.pinned ?: false))
    return copy(sessionPreferences = sessionPreferences.filterNot { it.sessionId == sessionId } + updated)
}

fun ServerStoreState.setSessionModel(
    sessionId: String,
    modelAlias: String?,
): ServerStoreState {
    val existing = sessionPreferences.firstOrNull { it.sessionId == sessionId }
    val updated = (existing ?: SessionPreference(sessionId)).copy(modelAlias = modelAlias?.takeIf { it.isNotBlank() })
    return copy(sessionPreferences = sessionPreferences.filterNot { it.sessionId == sessionId } + updated)
}

fun ServerStoreState.setQueuedPrompt(
    sessionId: String,
    text: String?,
): ServerStoreState {
    val existing = sessionPreferences.firstOrNull { it.sessionId == sessionId }
    val updated =
        (existing ?: SessionPreference(sessionId)).copy(
            queuedPromptText = text?.trim()?.takeIf { it.isNotBlank() },
        )
    val preferences = sessionPreferences.filterNot { it.sessionId == sessionId }
    val keep = updated.pinned || updated.modelAlias != null || updated.queuedPromptText != null
    return copy(sessionPreferences = if (keep) preferences + updated else preferences)
}

fun ServerStoreState.createWorkspace(
    id: String,
    name: String,
): ServerStoreState {
    val safeId = id.trim()
    val safeName = name.trim().take(40)
    if (safeId.isBlank() || safeName.isBlank() || workspaces.any { it.id == safeId }) return this
    return copy(
        workspaces = workspaces + WorkspaceDefinition(id = safeId, name = safeName),
        selectedWorkspaceId = safeId,
    ).healWorkspaces()
}

fun ServerStoreState.renameWorkspace(
    id: String,
    name: String,
): ServerStoreState {
    val safeName = name.trim().take(40)
    if (safeName.isBlank()) return this
    return copy(workspaces = workspaces.map { if (it.id == id) it.copy(name = safeName) else it })
}

fun ServerStoreState.selectWorkspace(id: String): ServerStoreState =
    if (workspaces.any { it.id == id }) copy(selectedWorkspaceId = id) else this

fun ServerStoreState.removeWorkspace(id: String): ServerStoreState {
    if (workspaces.size <= 1) return this
    val removed = workspaces.firstOrNull { it.id == id } ?: return this
    val remaining = workspaces.filterNot { it.id == id }.toMutableList()
    remaining[0] = remaining[0].copy(sessionIds = (remaining[0].sessionIds + removed.sessionIds).distinct())
    return copy(
        workspaces = remaining,
        selectedWorkspaceId = if (selectedWorkspaceId == id) remaining.first().id else selectedWorkspaceId,
    ).healWorkspaces()
}

fun ServerStoreState.moveSessionToWorkspace(
    sessionId: String,
    workspaceId: String,
): ServerStoreState {
    if (workspaces.none { it.id == workspaceId }) return this
    return copy(
        workspaces =
            workspaces.map { workspace ->
                val without = workspace.sessionIds.filterNot { it == sessionId }
                if (workspace.id == workspaceId) {
                    workspace.copy(sessionIds = without + sessionId)
                } else {
                    workspace.copy(sessionIds = without)
                }
            },
    )
}

internal fun ServerStoreState.healWorkspaces(): ServerStoreState {
    val cleaned =
        workspaces
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map { it.copy(sessionIds = it.sessionIds.filter(String::isNotBlank).distinct()) }
    val finalWorkspaces =
        cleaned.ifEmpty {
            listOf(WorkspaceDefinition(id = DEFAULT_WORKSPACE_ID, name = "Focus"))
        }
    val selected =
        selectedWorkspaceId?.takeIf { id -> finalWorkspaces.any { it.id == id } } ?: finalWorkspaces.first().id
    val cleanOpen = openSessionIds.filter(String::isNotBlank).distinct().takeLast(MAX_OPEN_SESSION_TABS)
    val active = activeSessionId?.takeIf { it in cleanOpen } ?: cleanOpen.lastOrNull()
    return copy(
        workspaces = finalWorkspaces,
        selectedWorkspaceId = selected,
        openSessionIds = cleanOpen,
        activeSessionId = active,
        sessionPreferences = sessionPreferences.filter { it.sessionId.isNotBlank() }.distinctBy { it.sessionId },
    )
}
