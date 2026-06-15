package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Singleton that manages encrypted storage of the Hermes dashboard token
 * and connection settings.
 *
 * Must call [init] with a Context before any other method.
 */
object AuthManager {
    private const val PREFS_FILE = "hermes_secure_prefs"

    private const val KEY_TOKEN = "auth_token"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"

    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 9119
    private const val DEFAULT_AUTO_RECONNECT = true

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Initialise the encrypted preferences.
     * Call this once from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val masterKey =
                MasterKey
                    .Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            prefs =
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException(
            "AuthManager not initialised – call AuthManager.init(context) first",
        )

    // ── Token ────────────────────────────────────────────────────────────

    fun getToken(): String? = requirePrefs().getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        requirePrefs().edit().putString(KEY_TOKEN, token).apply()
    }

    // ── Host ─────────────────────────────────────────────────────────────

    fun getHost(): String = requirePrefs().getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun setHost(host: String) {
        requirePrefs().edit().putString(KEY_HOST, host).apply()
    }

    // ── Port ─────────────────────────────────────────────────────────────

    fun getPort(): Int = requirePrefs().getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        requirePrefs().edit().putInt(KEY_PORT, port).apply()
    }

    // ── Auto-reconnect ───────────────────────────────────────────────────

    fun isAutoReconnect(): Boolean = requirePrefs().getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    fun setAutoReconnect(enabled: Boolean) {
        requirePrefs().edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    /** Convenience: build the base URL from current host + port. */
    fun baseUrl(): String = "http://${getHost()}:${getPort()}/"

    /** Convenience: build the WebSocket URL with token query param. */
    fun wsUrl(): String = "ws://${getHost()}:${getPort()}/api/ws?token=${getToken().orEmpty()}"
}
