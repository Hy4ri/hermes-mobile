package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

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
    private const val KEY_TYPING_EFFECT_ENABLED = "typing_effect_enabled"
    private const val KEY_TYPING_EFFECT_DELAY_MS = "typing_effect_delay_ms"
    private const val KEY_CONNECTION_PROFILES = "connection_profiles"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_WS_AUTH_PARAM = "ws_auth_param"
    private const val KEY_SESSION_COOKIE = "session_cookie"
    private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
    private const val KEY_THEME_PRESET = "theme_preset"
    private const val KEY_BOTTOM_NAV_DISPLAY_MODE = "bottom_nav_display_mode"
    private const val KEY_PINNED_MODELS = "pinned_models"

    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 9119
    private const val DEFAULT_AUTO_RECONNECT = true
    private const val DEFAULT_TYPING_EFFECT_ENABLED = true
    private const val DEFAULT_TYPING_EFFECT_DELAY_MS = 30

    @Volatile
    private var prefsDeferred: Deferred<SharedPreferences>? = null

    private val _bottomNavItemsFlow = MutableStateFlow<List<String>>(emptyList())
    val bottomNavItemsFlow: StateFlow<List<String>> = _bottomNavItemsFlow.asStateFlow()

    private val _themePreferenceFlow = MutableStateFlow<ThemePreference>(ThemePreference.SYSTEM)
    val themePreferenceFlow: StateFlow<ThemePreference> = _themePreferenceFlow.asStateFlow()

    private val _useDynamicColorsFlow = MutableStateFlow<Boolean>(true)
    val useDynamicColorsFlow: StateFlow<Boolean> = _useDynamicColorsFlow.asStateFlow()

    private val _themePresetFlow = MutableStateFlow<ThemePreset>(ThemePreset.DEFAULT)
    val themePresetFlow: StateFlow<ThemePreset> = _themePresetFlow.asStateFlow()

    private val _bottomNavDisplayModeFlow = MutableStateFlow<BottomNavDisplayMode>(BottomNavDisplayMode.ICON_AND_TEXT)
    val bottomNavDisplayModeFlow: StateFlow<BottomNavDisplayMode> = _bottomNavDisplayModeFlow.asStateFlow()

    private val _tokenFlow = MutableStateFlow<String?>(null)
    val tokenFlow: StateFlow<String?> = _tokenFlow.asStateFlow()

    private val gson = com.m57.hermescontrol.data.remote.OkHttpProvider.gson

    /**
     * Initialise the encrypted preferences.
     * Call this once from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        if (prefsDeferred != null) return
        synchronized(this) {
            if (prefsDeferred != null) return
            prefsDeferred =
                CoroutineScope(Dispatchers.IO).async {
                    val masterKey =
                        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

                    val p =
                        EncryptedSharedPreferences.create(
                            PREFS_FILE,
                            masterKey,
                            context.applicationContext,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                        )

                    // Initialize flows with values from prefs once loaded
                    _bottomNavItemsFlow.value = getBottomNavItemsInternal(p)
                    _themePreferenceFlow.value = getThemePreferenceInternal(p)
                    _useDynamicColorsFlow.value = isUseDynamicColorsInternal(p)
                    _themePresetFlow.value = getThemePresetInternal(p)
                    _bottomNavDisplayModeFlow.value = getBottomNavDisplayModeInternal(p)
                    _tokenFlow.value = getTokenInternal(p)
                    p
                }
        }
    }

    /**
     * Retrieves the initialized [SharedPreferences] instance.
     *
     * WARNING: This method is synchronous and will block the caller thread (using [runBlocking])
     * if the asynchronous initialization is still in progress. Callers should avoid invoking this
     * on the main thread during early startup to prevent frame drops or potential ANRs.
     *
     * Times out and throws [IllegalStateException] if initialization takes longer than 2 seconds.
     */
    private fun requirePrefs(): SharedPreferences =
        runBlocking {
            val deferred =
                prefsDeferred ?: throw IllegalStateException(
                    "AuthManager not initialized. Call init(context) first.",
                )
            kotlinx.coroutines.withTimeoutOrNull(2000) {
                deferred.await()
            } ?: throw IllegalStateException("AuthManager initialization timed out after 2 seconds.")
        }

    fun setWsAuthParam(param: String) {
        requirePrefs().edit().putString(KEY_WS_AUTH_PARAM, param).apply()
    }

    // ── Session Cookie (for gated/dashboard REST API) ────────────────────

    /**
     * In gated mode (basic auth), the dashboard authenticates REST API
     * requests via the `hermes_session_at` cookie, not via
     * `Authorization: *** We store it here so [ApiClient]'s
     * authInterceptor can add it as a `Cookie` header.
     */
    fun getSessionCookie(): String? = requirePrefs().getString(KEY_SESSION_COOKIE, null)

    fun setSessionCookie(cookie: String?) {
        requirePrefs().edit().putString(KEY_SESSION_COOKIE, cookie).apply()
    }

    // ── Database Master Password ─────────────────────────────────────────

    fun getDatabasePassword(): ByteArray {
        val prefs = requirePrefs()
        var dbPasswordBase64 = prefs.getString("db_password", null)
        if (dbPasswordBase64 == null) {
            val random = java.security.SecureRandom()
            val newPassword = ByteArray(32)
            random.nextBytes(newPassword)
            dbPasswordBase64 = android.util.Base64.encodeToString(newPassword, android.util.Base64.NO_WRAP)
            prefs.edit().putString("db_password", dbPasswordBase64).apply()
        }
        return android.util.Base64.decode(dbPasswordBase64, android.util.Base64.NO_WRAP)
    }

    // ── Connection Profiles ──────────────────────────────────────────────

    fun getConnectionProfiles(): List<com.m57.hermescontrol.data.model.ConnectionProfile> {
        val json = requirePrefs().getString(KEY_CONNECTION_PROFILES, null) ?: return emptyList()
        return try {
            val type =
                object : com.google.gson.reflect.TypeToken<
                    List<com.m57.hermescontrol.data.model.ConnectionProfile>,
                >() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            if (com.m57.hermescontrol.BuildConfig.DEBUG) {
                android.util.Log.w("AuthManager", "Failed to parse connection profiles", e)
            }
            emptyList()
        }
    }

    fun saveConnectionProfiles(profiles: List<com.m57.hermescontrol.data.model.ConnectionProfile>) {
        val json = gson.toJson(profiles)
        requirePrefs().edit().putString(KEY_CONNECTION_PROFILES, json).apply()
    }

    // ── Pinned Models ────────────────────────────────────────────────────

    fun getPinnedModels(): List<com.m57.hermescontrol.data.model.PinnedModel> {
        val json = requirePrefs().getString(KEY_PINNED_MODELS, null) ?: return emptyList()
        return try {
            val type =
                object : com.google.gson.reflect.TypeToken<
                    List<com.m57.hermescontrol.data.model.PinnedModel>,
                >() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            if (com.m57.hermescontrol.BuildConfig.DEBUG) {
                android.util.Log.w("AuthManager", "Failed to parse pinned models", e)
            }
            emptyList()
        }
    }

    fun savePinnedModels(pinned: List<com.m57.hermescontrol.data.model.PinnedModel>) {
        val json = gson.toJson(pinned)
        requirePrefs().edit().putString(KEY_PINNED_MODELS, json).apply()
    }

    fun getProfileToken(profileId: String): String? = requirePrefs().getString("token_$profileId", null)

    fun setProfileToken(
        profileId: String,
        token: String?,
    ) {
        requirePrefs().edit().putString("token_$profileId", token).apply()
        if (getSelectedProfileId() == profileId) {
            _tokenFlow.value = token
        }
    }

    fun getSelectedProfileId(): String? {
        val id = requirePrefs().getString(KEY_SELECTED_PROFILE_ID, null)
        return if (id.isNullOrBlank()) null else id
    }

    fun setSelectedProfileId(id: String?) {
        requirePrefs().edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
        synchronized(this) {
            tokenInitialized = false
        }
        _tokenFlow.value = getToken()
    }

    // ── Token ────────────────────────────────────────────────────────────

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenInitialized: Boolean = false

    // For testing purposes
    fun resetTokenCacheForTest() {
        cachedToken = null
        tokenInitialized = false
    }

    fun getToken(): String? {
        if (tokenInitialized) return cachedToken
        synchronized(this) {
            if (tokenInitialized) return cachedToken
            val selectedId = getSelectedProfileId()
            val token =
                if (selectedId != null) {
                    getProfileToken(selectedId) ?: requirePrefs().getString(KEY_TOKEN, null)
                } else {
                    requirePrefs().getString(KEY_TOKEN, null)
                }
            cachedToken = token
            tokenInitialized = true
            return token
        }
    }

    private fun getTokenInternal(p: SharedPreferences): String? {
        val selectedId = p.getString(KEY_SELECTED_PROFILE_ID, null)?.takeIf { it.isNotBlank() }
        return if (selectedId != null) {
            p.getString("token_$selectedId", null) ?: p.getString(KEY_TOKEN, null)
        } else {
            p.getString(KEY_TOKEN, null)
        }
    }

    fun setToken(token: String?) {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            setProfileToken(selectedId, token)
        } else {
            requirePrefs().edit().putString(KEY_TOKEN, token).apply()
            _tokenFlow.value = token
        }
        synchronized(this) {
            cachedToken = token
            tokenInitialized = true
        }
    }

    // ── Host ─────────────────────────────────────────────────────────────

    fun getHost(): String {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            val profile = getConnectionProfiles().firstOrNull { it.id == selectedId }
            if (profile != null) return profile.host
        }
        return requirePrefs().getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    }

    fun setHost(host: String) {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            val profiles =
                getConnectionProfiles().map {
                    if (it.id == selectedId) it.copy(host = host) else it
                }
            saveConnectionProfiles(profiles)
        } else {
            requirePrefs().edit().putString(KEY_HOST, host).apply()
        }
    }

    // ── Port ─────────────────────────────────────────────────────────────

    fun getPort(): Int {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            val profile = getConnectionProfiles().firstOrNull { it.id == selectedId }
            if (profile != null) return profile.port
        }
        return requirePrefs().getInt(KEY_PORT, DEFAULT_PORT)
    }

    fun setPort(port: Int) {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            val profiles =
                getConnectionProfiles().map {
                    if (it.id == selectedId) it.copy(port = port) else it
                }
            saveConnectionProfiles(profiles)
        } else {
            requirePrefs().edit().putInt(KEY_PORT, port).apply()
        }
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

    fun getThemePreference(): ThemePreference = getThemePreferenceInternal(requirePrefs())

    private fun getThemePreferenceInternal(p: SharedPreferences): ThemePreference =
        p.getString(KEY_THEME_PREFERENCE, ThemePreference.SYSTEM.name)?.let { name ->
            runCatching { ThemePreference.valueOf(name) }.getOrNull()
        } ?: ThemePreference.SYSTEM

    fun setThemePreference(theme: ThemePreference) {
        requirePrefs().edit().putString(KEY_THEME_PREFERENCE, theme.name).apply()
        _themePreferenceFlow.value = theme
    }

    fun isUseDynamicColors(): Boolean = isUseDynamicColorsInternal(requirePrefs())

    private fun isUseDynamicColorsInternal(p: SharedPreferences): Boolean = p.getBoolean(KEY_USE_DYNAMIC_COLORS, true)

    fun setUseDynamicColors(value: Boolean) {
        requirePrefs().edit().putBoolean(KEY_USE_DYNAMIC_COLORS, value).apply()
        _useDynamicColorsFlow.value = value
    }

    fun getThemePreset(): ThemePreset = getThemePresetInternal(requirePrefs())

    private fun getThemePresetInternal(p: SharedPreferences): ThemePreset =
        p.getString(KEY_THEME_PRESET, ThemePreset.DEFAULT.name)?.let { name ->
            runCatching { ThemePreset.valueOf(name) }.getOrNull()
        } ?: ThemePreset.DEFAULT

    fun setThemePreset(preset: ThemePreset) {
        requirePrefs().edit().putString(KEY_THEME_PRESET, preset.name).apply()
        _themePresetFlow.value = preset
    }

    /** Convenience: build the base URL from current host + port.
     *  NOTE: Uses plain http:// — intended for trusted local network only.
     *  Exposing the host to a hostile LAN risks token interception. */
    fun baseUrl(): String = "http://${getHost()}:${getPort()}/"

    /** Convenience: build the WebSocket URL with token query param.
     *  NOTE: Token in query string — trusted local network only. */
    fun wsUrl(): String {
        val raw = requirePrefs().getString(KEY_WS_AUTH_PARAM, "token")
        val authParam = if (raw.isNullOrBlank()) "token" else raw
        val credential = getToken().orEmpty()
        return "ws://${getHost()}:${getPort()}/api/ws?$authParam=$credential"
    }

    // ── Bottom nav bar items ──────────────────────────────────────────────

    /** Default bottom-nav items (NavKey data-object names). */
    private val DEFAULT_BOTTOM_NAV_ITEMS =
        listOf("ChatScreen", "SkillsScreen", "CronJobsScreen", "SystemScreen", "SettingsScreen")

    /** Returns the list of selected bottom-nav item keys (data-object names). */
    fun getBottomNavItems(): List<String> = getBottomNavItemsInternal(requirePrefs())

    private fun getBottomNavItemsInternal(p: SharedPreferences): List<String> {
        val raw = p.getString(KEY_BOTTOM_NAV_ITEMS, null) ?: return DEFAULT_BOTTOM_NAV_ITEMS
        return raw.split(",").filter { it.isNotBlank() }
    }

    /** Persist the ordered list of bottom-nav item keys (max 5, data-object names). */
    fun setBottomNavItems(items: List<String>) {
        requirePrefs().edit().putString(KEY_BOTTOM_NAV_ITEMS, items.joinToString(",")).apply()
        _bottomNavItemsFlow.value = items
    }

    // ── Typing Effect ───────────────────────────────────────────────────

    fun isTypingEffectEnabled(): Boolean =
        requirePrefs().getBoolean(KEY_TYPING_EFFECT_ENABLED, DEFAULT_TYPING_EFFECT_ENABLED)

    fun setTypingEffectEnabled(enabled: Boolean) {
        requirePrefs().edit().putBoolean(KEY_TYPING_EFFECT_ENABLED, enabled).apply()
    }

    fun getTypingEffectDelayMs(): Int =
        requirePrefs().getInt(KEY_TYPING_EFFECT_DELAY_MS, DEFAULT_TYPING_EFFECT_DELAY_MS)

    fun setTypingEffectDelayMs(delayMs: Int) {
        requirePrefs().edit().putInt(KEY_TYPING_EFFECT_DELAY_MS, delayMs).apply()
    }

    // ── Bottom Nav Display Mode ──────────────────────────────────────────

    fun getBottomNavDisplayMode(): BottomNavDisplayMode = getBottomNavDisplayModeInternal(requirePrefs())

    private fun getBottomNavDisplayModeInternal(p: SharedPreferences): BottomNavDisplayMode =
        p.getString(KEY_BOTTOM_NAV_DISPLAY_MODE, BottomNavDisplayMode.ICON_AND_TEXT.name)?.let { name ->
            runCatching { BottomNavDisplayMode.valueOf(name) }.getOrNull()
        } ?: BottomNavDisplayMode.ICON_AND_TEXT

    fun setBottomNavDisplayMode(mode: BottomNavDisplayMode) {
        requirePrefs().edit().putString(KEY_BOTTOM_NAV_DISPLAY_MODE, mode.name).apply()
        _bottomNavDisplayModeFlow.value = mode
    }
}
