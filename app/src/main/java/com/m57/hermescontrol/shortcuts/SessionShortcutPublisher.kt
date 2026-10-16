package com.m57.hermescontrol.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo

/** Owns only session-* shortcuts; immutable launcher actions always remain available. */
internal class SessionShortcutPublisher(
    private val context: Context,
) {
    private val manager = context.getSystemService(ShortcutManager::class.java)
    private val activity = ComponentName(context, MainActivity::class.java)

    fun publish(
        scope: DataScope,
        sessions: List<SessionInfo>,
    ) {
        val service = manager ?: return
        val capacity = (service.maxShortcutCountPerActivity - service.manifestShortcuts.size).coerceAtLeast(0)
        val selected = selectShortcutSessions(sessions, capacity)
        val desiredIds = selected.map { sessionShortcutId(scope, it.id) }.toSet()
        retire(knownSessionIds() - desiredIds)
        if (service.isRateLimitingActive) return
        val shortcuts =
            selected.mapIndexed { index, session ->
                val title = session.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.history_untitled)
                ShortcutInfo
                    .Builder(context, sessionShortcutId(scope, session.id))
                    .setActivity(activity)
                    .setShortLabel(title)
                    .setLongLabel(title)
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_chat))
                    .setRank(index)
                    .setIntent(sessionIntent(scope, session.id))
                    .build()
            }
        service.enableShortcuts(desiredIds.toList())
        service.dynamicShortcuts = shortcuts
    }

    fun clear() {
        retire(knownSessionIds())
    }

    internal fun sessionIntent(
        scope: DataScope,
        sessionId: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(LauncherShortcuts.ACTION_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LauncherShortcuts.EXTRA_SCOPE, shortcutScopeId(scope))
            .putExtra(LauncherShortcuts.EXTRA_SESSION, sessionId)

    private fun knownSessionIds(): Set<String> =
        manager
            ?.let { it.dynamicShortcuts + it.pinnedShortcuts }
            .orEmpty()
            .map { it.id }
            .filter { it.startsWith("session-") }
            .toSet()

    private fun retire(ids: Set<String>) {
        val service = manager ?: return
        if (ids.isEmpty()) return
        // Pinned home-screen icons cannot be deleted by apps. Scrub their labels/intents and disable them.
        service.updateShortcuts(
            ids.map { id ->
                ShortcutInfo
                    .Builder(context, id)
                    .setShortLabel(context.getString(R.string.app_name))
                    .setLongLabel(context.getString(R.string.app_name))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .build()
            },
        )
        service.removeDynamicShortcuts(ids.toList())
        service.disableShortcuts(ids.toList(), context.getString(R.string.shortcut_unavailable))
    }
}
