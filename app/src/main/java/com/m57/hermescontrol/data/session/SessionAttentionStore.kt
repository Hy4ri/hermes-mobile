package com.m57.hermescontrol.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.data.model.SessionLiveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1642: sessions whose turn ended while the user was not looking at them.
 *
 * Each entry is [SessionLiveStatus.DONE] or [SessionLiveStatus.FAILED], keyed by data scope
 * ([com.m57.hermescontrol.data.local.DataScope.persistentKey]) and then by stored session id. The
 * marks are persisted so they survive an app restart. Opening the session in Chat clears its mark;
 * a completion for the session that is open on screen never creates one.
 */
object SessionAttentionStore {
    private const val PREFS_NAME = "hermes_session_attention"
    private const val DONE = "done"
    private const val FAILED = "failed"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _marks = MutableStateFlow<Map<String, Map<String, SessionLiveStatus>>>(emptyMap())

    /** All marks: scope key → (stored session id → [SessionLiveStatus.DONE] / [SessionLiveStatus.FAILED]). */
    val marks: StateFlow<Map<String, Map<String, SessionLiveStatus>>> = _marks.asStateFlow()

    /** (scope key, stored session id) open in Chat right now, or null. */
    private var viewing: Pair<String, String>? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _marks.value = load(p)
        }
    }

    /** Marks for one scope. */
    fun marksFor(scopeKey: String?): Map<String, SessionLiveStatus> = scopeKey?.let { _marks.value[it] }.orEmpty()

    /**
     * Record that [storedId]'s turn ended. [failed] wins over an earlier success mark; a mark from
     * an event is never downgraded by a later snapshot guess ([overwrite] = false keeps the old one).
     * Does nothing for the session that is open on screen.
     */
    @Synchronized
    fun markFinished(
        scopeKey: String,
        storedId: String,
        failed: Boolean,
        overwrite: Boolean = true,
    ) {
        if (storedId.isBlank() || viewing == (scopeKey to storedId)) return
        val scoped = _marks.value[scopeKey].orEmpty()
        if (!overwrite && storedId in scoped) return
        val status = if (failed) SessionLiveStatus.FAILED else SessionLiveStatus.DONE
        if (scoped[storedId] == status) return
        write(scopeKey, scoped + (storedId to status))
    }

    /** Clear [storedId]'s mark: the user opened it, deleted it, or it no longer needs attention. */
    @Synchronized
    fun clear(
        scopeKey: String,
        storedId: String,
    ) {
        val scoped = _marks.value[scopeKey] ?: return
        if (storedId !in scoped) return
        write(scopeKey, scoped - storedId)
    }

    /**
     * The stored session the user is looking at in Chat, or null ids when Chat is not on screen.
     * Setting it clears that session's mark: the user has now seen the result.
     */
    @Synchronized
    fun setViewing(
        scopeKey: String?,
        storedId: String?,
    ) {
        val id = storedId?.takeIf { it.isNotBlank() }
        viewing = if (scopeKey != null && id != null) scopeKey to id else null
        if (scopeKey != null && id != null) clear(scopeKey, id)
    }

    /** Forget everything (logout). */
    @Synchronized
    fun clearAll() {
        _marks.value = emptyMap()
        prefs?.edit()?.clear()?.apply()
    }

    private fun write(
        scopeKey: String,
        scoped: Map<String, SessionLiveStatus>,
    ) {
        _marks.value = if (scoped.isEmpty()) _marks.value - scopeKey else _marks.value + (scopeKey to scoped)
        val editor = prefs?.edit() ?: return
        if (scoped.isEmpty()) {
            editor.remove(scopeKey)
        } else {
            editor.putStringSet(
                scopeKey,
                scoped
                    .map { (id, status) ->
                        (
                            if (status ==
                                SessionLiveStatus.FAILED
                            ) {
                                FAILED
                            } else {
                                DONE
                            }
                        ) + ":" + id
                    }.toSet(),
            )
        }
        editor.apply()
    }

    private fun load(p: SharedPreferences): Map<String, Map<String, SessionLiveStatus>> =
        p.all
            .mapNotNull { (scope, value) ->
                val entries =
                    (value as? Set<*>)
                        ?.mapNotNull { raw ->
                            val text = raw as? String ?: return@mapNotNull null
                            val kind = text.substringBefore(':', "")
                            val id = text.substringAfter(':', "")
                            val status =
                                when (kind) {
                                    DONE -> SessionLiveStatus.DONE
                                    FAILED -> SessionLiveStatus.FAILED
                                    else -> null
                                }
                            if (status == null || id.isBlank()) null else id to status
                        }?.toMap()
                        .orEmpty()
                if (entries.isEmpty()) null else scope to entries
            }.toMap()

    @VisibleForTesting
    internal fun resetForTesting(testPrefs: SharedPreferences? = null) {
        synchronized(this) {
            prefs = testPrefs
            viewing = null
            _marks.value = testPrefs?.let(::load).orEmpty()
        }
    }
}
