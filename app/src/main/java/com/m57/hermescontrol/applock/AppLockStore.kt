package com.m57.hermescontrol.applock

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal data class AppLockSettings(
    val enabled: Boolean = false,
    val timeoutMs: Long = 30_000L,
)

/**
 * Security policy only, never credentials. Separate from best-effort dashboard preferences:
 * read errors fail closed and a checked, synchronous commit precedes any UI acknowledgement (#1604).
 */
internal class AppLockStore(
    private val prefs: SharedPreferences,
    private val previouslyStored: Boolean = false,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").exists() ||
            File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml.bak").exists(),
    )

    fun read(): AppLockSettings =
        try {
            AppLockSettings(
                // Android can turn malformed preference XML into an empty map without throwing.
                enabled =
                    if (previouslyStored &&
                        !prefs.contains(KEY_ENABLED)
                    ) {
                        true
                    } else {
                        prefs.getBoolean(KEY_ENABLED, false)
                    },
                timeoutMs = prefs.getLong(KEY_TIMEOUT, 30_000L).takeIf { it in AppLockPolicy.TIMEOUTS } ?: 30_000L,
            )
        } catch (_: Exception) {
            AppLockSettings(enabled = true)
        }

    fun save(settings: AppLockSettings): Boolean {
        val previous = read()
        val committed = runCatching { commit(settings) }.getOrDefault(false)
        if (!committed) {
            // commit(false) still changes SharedPreferences' in-memory map. Restore it so stale
            // notification actions cannot observe a failed disable as a successful policy change.
            runCatching { commit(previous) }
        }
        return committed
    }

    private fun commit(settings: AppLockSettings): Boolean =
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putLong(KEY_TIMEOUT, settings.timeoutMs)
            .commit()

    companion object {
        private const val FILE_NAME = "app_lock_policy"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TIMEOUT = "background_timeout_ms"

        /** Stale notification PendingIntents must not bypass an enabled lock, even after process death. */
        fun blocksNotificationReplies(context: Context): Boolean =
            try {
                AppLockStore(context).read().enabled
            } catch (_: Exception) {
                true
            }
    }
}
