package com.m57.hermescontrol.shortcuts

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo

internal class SessionShortcutCoordinator(
    private val currentScope: () -> DataScope?,
    private val loadSessions: suspend (DataScope) -> List<SessionInfo>,
    private val publish: (DataScope, List<SessionInfo>) -> Unit,
    private val clear: () -> Unit,
) {
    private var owner: DataScope? = null

    fun updateOwner(scope: DataScope?) {
        if (owner == scope) return
        owner = scope
        clear()
    }

    suspend fun refresh(scope: DataScope) {
        if (currentScope() != scope) return
        val sessions = selectShortcutSessions(loadSessions(scope), Int.MAX_VALUE)
        if (currentScope() != scope) return
        publish(scope, sessions)
    }

    /** Revalidate against live metadata, never resume an arbitrary id from an exported intent. */
    suspend fun resolve(
        scope: DataScope,
        scopeId: String,
        sessionId: String,
    ): Boolean {
        if (currentScope() != scope || shortcutScopeId(scope) != scopeId) return false
        val sessions = selectShortcutSessions(loadSessions(scope), Int.MAX_VALUE)
        return currentScope() == scope && sessions.any { it.id == sessionId }
    }
}
