package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider

/**
 * Lightweight disk cache for the initial page of sessions.
 * Persists the first page JSON to SharedPreferences so the History screen
 * can render instantly even after app kill / process death.
 */
object SessionListCacheStore {
    private const val PREFS_NAME = "hermes_sessions_cache"
    private const val KEY_PREFIX = "sessions_page_"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun get(key: String): SessionListResponse? {
        val p = prefs ?: return null
        val raw = p.getString(KEY_PREFIX + key, null) ?: return null
        return try {
            OkHttpProvider.json.decodeFromString<SessionListResponse>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun put(
        key: String,
        data: SessionListResponse,
    ) {
        val p = prefs ?: return
        try {
            val json = OkHttpProvider.json.encodeToString(SessionListResponse.serializer(), data)
            p.edit().putString(KEY_PREFIX + key, json).apply()
        } catch (_: Exception) {
            // Ignore serialization issues on cache save
        }
    }

    fun remove(key: String) {
        prefs?.edit()?.remove(KEY_PREFIX + key)?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
