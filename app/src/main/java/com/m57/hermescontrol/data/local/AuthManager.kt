package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.m57.hermescontrol.theme.ThemePreference

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
    private const val KEY_THEME_PREFERENCE = "theme_preference"
    private const val KEY_BOTTOM_NAV_ITEMS = "bottom_nav_items"

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

    // ── Theme preference ──────────────────────────────────────────────────
    // B6 (Jun 18 2026, kanban t_86e9be9b): previously, SettingsScreen.kt let
    // the user pick SYSTEM/LIGHT/DARK in a SingleChoiceSegmentedButtonRow
    // (SettingsScreen.kt:213-238) and SettingsViewModel.onThemeChange
    // (SettingsViewModel.kt:62-64) updated in-memory state — but
    // SettingsViewModel.loadSettings() did NOT read the value back and
    // SettingsViewModel.save() did NOT persist it. As a result, every cold
    // start reset the theme to SYSTEM. Fix: round-trip via EncryptedSharedPreferences
    // (the same store already used by token / host / port / auto_reconnect).

    fun getThemePreference(): ThemePreference =
        requirePrefs().getString(KEY_THEME_PREFERENCE, ThemePreference.SYSTEM.name)?.let { name ->
            runCatching { ThemePreference.valueOf(name) }.getOrNull()
        } ?: ThemePreference.SYSTEM

    fun setThemePreference(theme: ThemePreference) {
        requirePrefs().edit().putString(KEY_THEME_PREFERENCE, theme.name).apply()
    }

    /** Convenience: build the base URL from current host + port.
     *  NOTE: Uses plain http:// — intended for trusted local network only.
     *  Exposing the host to a hostile LAN risks token interception. */
    fun baseUrl(): String = "http://${getHost()}:${getPort()}/"

    /** Convenience: build the WebSocket URL with token query param.
     *  NOTE: Token in query string — trusted local network only. */
    fun wsUrl(): String = "ws://${getHost()}:${getPort()}/api/ws?token=${getToken().orEmpty()}"

    // ── Bottom nav bar items ──────────────────────────────────────────────

    /** Default bottom-nav items (NavKey data-object names). */
    private val DEFAULT_BOTTOM_NAV_ITEMS =
        listOf("ChatScreen", "SkillsScreen", "CronJobsScreen", "SystemScreen", "SettingsScreen")

    /** Returns the list of selected bottom-nav item keys (data-object names). */
    fun getBottomNavItems(): List<String> {
        val raw = requirePrefs().getString(KEY_BOTTOM_NAV_ITEMS, null) ?: return DEFAULT_BOTTOM_NAV_ITEMS
        return raw.split(",").filter { it.isNotBlank() }
    }

    /** Persist the ordered list of bottom-nav item keys (max 5, data-object names). */
    fun setBottomNavItems(items: List<String>) {
        requirePrefs().edit().putString(KEY_BOTTOM_NAV_ITEMS, items.joinToString(",")).apply()
    }
}
