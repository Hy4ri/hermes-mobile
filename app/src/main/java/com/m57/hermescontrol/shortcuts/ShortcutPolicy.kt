package com.m57.hermescontrol.shortcuts

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.forkParentId
import java.security.MessageDigest
import java.util.Locale

internal fun selectShortcutSessions(
    sessions: List<SessionInfo>,
    capacity: Int,
): List<SessionInfo> =
    sessions
        // Policy: no hidden/archived rows, cron, bot rooms, or internal child agents (#1602).
        .filter {
            it.id.isNotBlank() && it.hidden != true && it.archived != true &&
                it.forkParentId() == null &&
                it.source?.lowercase(Locale.ROOT) !in setOf("cron", "bot_room", "subagent")
        }.distinctBy { it.id }
        .sortedWith(
            compareByDescending<SessionInfo> { it.pinned == true }
                .thenByDescending { it.last_active ?: it.started_at ?: 0.0 }
                .thenBy { it.id },
        ).take(capacity.coerceAtLeast(0))

// Opaque, length-prefixed identities: no URLs, credentials, or titles in launcher IDs.
internal fun shortcutScopeId(scope: DataScope): String = digest(scope.persistentKey())

internal fun sessionShortcutId(
    scope: DataScope,
    sessionId: String,
): String = "session-${digest(scope.persistentKey(sessionId))}"

private fun digest(value: String): String =
    MessageDigest
        .getInstance(
            "SHA-256",
        ).digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
