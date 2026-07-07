package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreMigration
import com.m57.hermescontrol.data.config.ServerStoreSerializer
import com.m57.hermescontrol.data.config.resolvedHost
import com.m57.hermescontrol.data.config.resolvedPort
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private const val KEY_SESSION_COOKIE = "session_cookie"

    @Volatile
    private var prefsDeferred: Deferred<SharedPreferences>? = null

    @Volatile
    private var _serverStore: ServerStore? = null

    @Volatile
    private var appScope: CoroutineScope? = null

    val serverStore: ServerStore
        get() =
            _serverStore ?: throw IllegalStateException(
                "AuthManager not initialized. Call init(context) first.",
            )

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

    /**
     * Initialise the encrypted preferences.
     * Call this once from Application.onCreate() or MainActivity.onCreate().
     */
    fun init(context: Context) {
        if (_serverStore != null) return
        synchronized(this) {
            if (_serverStore != null) return

            val dataStore =
                androidx.datastore.core.DataStoreFactory.create(
                    serializer = ServerStoreSerializer,
                    migrations = listOf(ServerStoreMigration(context)),
                ) {
                    context.filesDir.resolve("server_store.json")
                }

            val scope = CoroutineScope(Dispatchers.IO)
            appScope = scope
            val store = ServerStore(dataStore, scope)
            _serverStore = store

            scope.launch {
                store.stateFlow.collect { state ->
                    _bottomNavItemsFlow.value = state.bottomNavItems
                    _themePreferenceFlow.value = state.themePreference
                    _useDynamicColorsFlow.value = state.useDynamicColors
                    _themePresetFlow.value = state.themePreset
                    _bottomNavDisplayModeFlow.value = state.bottomNavDisplayMode
                }
            }

            if (prefsDeferred == null) {
                prefsDeferred =
                    CoroutineScope(Dispatchers.IO).async {
                        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                        val p =
                            EncryptedSharedPreferences.create(
                                PREFS_FILE,
                                masterKey,
                                context.applicationContext,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                            )
                        _tokenFlow.value = getTokenInternal(p)
                        p
                    }
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
        serverStore.update { it.copy(wsAuthParam = param) }
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

    fun getConnectionProfiles(): List<ConnectionProfile> = serverStore.getLatestState().connectionProfiles

    fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        serverStore.update { it.copy(connectionProfiles = profiles) }
    }

    // ── Pinned Models ────────────────────────────────────────────────────

    fun getPinnedModels(): List<PinnedModel> = serverStore.getLatestState().pinnedModels

    fun savePinnedModels(pinned: List<PinnedModel>) {
        serverStore.update { it.copy(pinnedModels = pinned) }
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
        val id = serverStore.getLatestState().selectedProfileId
        return if (id.isNullOrBlank()) null else id
    }

    fun setSelectedProfileId(id: String?) {
        serverStore.update { it.copy(selectedProfileId = id) }
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
        synchronized(this) {
            cachedToken = null
            tokenInitialized = false
        }
    }

    // For testing purposes
    fun resetAuthStateForTest() {
        synchronized(this) {
            cachedToken = null
            tokenInitialized = false
            _serverStore = null
            prefsDeferred = null
            appScope?.let {
                try {
                    it.cancel()
                } catch (e: Exception) {
                }
            }
            appScope = null
        }
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
        val selectedId = serverStore.getLatestState().selectedProfileId?.takeIf { it.isNotBlank() }
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

    fun getHost(): String = serverStore.getLatestState().resolvedHost

    fun setHost(host: String) {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            serverStore.update { state ->
                val profiles =
                    state.connectionProfiles.map {
                        if (it.id == selectedId) it.copy(host = host) else it
                    }
                state.copy(connectionProfiles = profiles)
            }
        } else {
            serverStore.update { it.copy(host = host) }
        }
    }

    // ── Port ─────────────────────────────────────────────────────────────

    fun getPort(): Int = serverStore.getLatestState().resolvedPort

    fun setPort(port: Int) {
        val selectedId = getSelectedProfileId()
        if (selectedId != null) {
            serverStore.update { state ->
                val profiles =
                    state.connectionProfiles.map {
                        if (it.id == selectedId) it.copy(port = port) else it
                    }
                state.copy(connectionProfiles = profiles)
            }
        } else {
            serverStore.update { it.copy(port = port) }
        }
    }

    // ── Auto-reconnect ───────────────────────────────────────────────────

    fun isAutoReconnect(): Boolean = serverStore.getLatestState().autoReconnect

    fun setAutoReconnect(enabled: Boolean) {
        serverStore.update { it.copy(autoReconnect = enabled) }
    }

    // ── Theme preference ──────────────────────────────────────────────────

    fun getThemePreference(): ThemePreference = serverStore.getLatestState().themePreference

    fun setThemePreference(theme: ThemePreference) {
        serverStore.update { it.copy(themePreference = theme) }
    }

    fun isUseDynamicColors(): Boolean = serverStore.getLatestState().useDynamicColors

    fun setUseDynamicColors(value: Boolean) {
        serverStore.update { it.copy(useDynamicColors = value) }
    }

    fun getThemePreset(): ThemePreset = serverStore.getLatestState().themePreset

    fun setThemePreset(preset: ThemePreset) {
        serverStore.update { it.copy(themePreset = preset) }
    }

    /** Convenience: build the base URL from current host + port.
     *  NOTE: Uses plain http:// — intended for trusted local network only.
     *  Exposing the host to a hostile LAN risks token interception. */
    fun baseUrl(): String = "http://${getHost()}:${getPort()}/"

    /** Convenience: build the WebSocket URL with token query param.
     *  NOTE: Token in query string — trusted local network only. */
    fun wsUrl(): String {
        val raw = serverStore.getLatestState().wsAuthParam
        val authParam = if (raw.isNullOrBlank()) "token" else raw
        val credential = getToken().orEmpty()
        return "ws://${getHost()}:${getPort()}/api/ws?$authParam=$credential"
    }

    // ── Bottom nav bar items ──────────────────────────────────────────────

    /** Returns the list of selected bottom-nav item keys (data-object names). */
    fun getBottomNavItems(): List<String> = serverStore.getLatestState().bottomNavItems

    /** Persist the ordered list of bottom-nav item keys (max 5, data-object names). */
    fun setBottomNavItems(items: List<String>) {
        serverStore.update { it.copy(bottomNavItems = items) }
    }

    // ── Typing Effect ───────────────────────────────────────────────────

    fun isTypingEffectEnabled(): Boolean = serverStore.getLatestState().typingEffectEnabled

    fun setTypingEffectEnabled(enabled: Boolean) {
        serverStore.update { it.copy(typingEffectEnabled = enabled) }
    }

    fun getTypingEffectDelayMs(): Int = serverStore.getLatestState().typingEffectDelayMs

    fun setTypingEffectDelayMs(delayMs: Int) {
        serverStore.update { it.copy(typingEffectDelayMs = delayMs) }
    }

    // ── Bottom Nav Display Mode ──────────────────────────────────────────

    fun getBottomNavDisplayMode(): BottomNavDisplayMode = serverStore.getLatestState().bottomNavDisplayMode

    fun setBottomNavDisplayMode(mode: BottomNavDisplayMode) {
        serverStore.update { it.copy(bottomNavDisplayMode = mode) }
    }
}
