package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreMigration
import com.m57.hermescontrol.data.config.ServerStoreSerializer
import com.m57.hermescontrol.data.config.ServerUrlMigration
import com.m57.hermescontrol.data.config.resolvedBaseUrl
import com.m57.hermescontrol.data.config.resolvedHost
import com.m57.hermescontrol.data.config.resolvedPort
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
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

    const val DEFAULT_PROFILE_ID = "default"
    const val DEFAULT_PROFILE_NAME = "Default"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_SESSION_COOKIE = "session_cookie"
    private const val KEY_LEGACY_TOKEN = "auth_token"
    private const val KEY_LEGACY_DEFAULT_MIGRATED = "legacy_default_migrated"

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
                    migrations =
                        listOf(
                            ServerStoreMigration(context),
                            ServerUrlMigration(),
                        ),
                ) {
                    context.filesDir.resolve("server_store.json")
                }

            val scope = CoroutineScope(Dispatchers.IO)
            appScope = scope
            val store = ServerStore(dataStore, scope)
            _serverStore = store

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
                        migrateLegacyDefaultIfNeeded(p)
                        _tokenFlow.value = getTokenInternal(p)
                        p
                    }
            }

            // Initialize the encrypted cookie store (issue #470). The legacy
            // session-cookie prefs are passed as a Deferred so existing gated
            // sessions can be migrated on first load WITHOUT blocking startup.
            // The Deferred is created above, before this call.
            val initialProfileId =
                store.getLatestState().selectedProfileId?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_ID
            CookieManager.initialize(context, prefsDeferred, initialProfileId)

            scope.launch {
                store.stateFlow.collect { state ->
                    _bottomNavItemsFlow.value = state.bottomNavItems
                    _themePreferenceFlow.value = state.themePreference
                    _useDynamicColorsFlow.value = state.useDynamicColors
                    _themePresetFlow.value = state.themePreset
                    _bottomNavDisplayModeFlow.value = state.bottomNavDisplayMode
                    // B7 (Jul 08 2026, kanban t_470): keep cookie scope aligned with active profile.
                    syncCookieStoreForProfile(state.selectedProfileId)
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
     * `Authorization: Bearer`. The cookie is now owned by the shared
     * [CookieManager]/[PersistentCookieJar] (issue #470) which attaches it
     * automatically on every REST call, follows redirects, and persists it
     * encrypted. This accessor is a thin read-through to that store.
     */
    fun getSessionCookie(): String? = CookieManager.getSessionCookie()

    fun setSessionCookie(cookie: String?) {
        CookieManager.setSessionCookie(cookie, endpoint())
    }

    /**
     * Evict expired (non-session) cookies for the active server scope to
     * bound cookie growth (issue #470 step 7).
     */
    fun pruneServerCache() {
        CookieManager.pruneServerCache()
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

    /**
     * Guarantees the selected profile is never null (issue #478).
     *
     * - If a profile is already selected (default or otherwise), nothing is changed.
     * - If nothing is selected but other profiles exist, the first one is selected.
     * - If there are no profiles at all (fresh install / legacy standalone), a [DEFAULT_PROFILE_ID]
     *   profile is created from the current top-level host/port and selected.
     *
     * This never injects a Default profile into an existing user's profile list, and never
     * clobbers a user's explicit selection.
     */
    fun ensureDefaultProfile() {
        val state = serverStore.getLatestState()
        val hasDefault = state.connectionProfiles.any { it.id == DEFAULT_PROFILE_ID }
        val needsSelection = state.selectedProfileId.isNullOrBlank()

        if (!needsSelection && (hasDefault || state.connectionProfiles.isNotEmpty())) return

        if (state.connectionProfiles.isEmpty()) {
            // Fresh install / legacy standalone: create the Default profile and select it.
            serverStore.update { s ->
                s.copy(
                    connectionProfiles =
                        listOf(
                            ConnectionProfile(
                                id = DEFAULT_PROFILE_ID,
                                name = DEFAULT_PROFILE_NAME,
                                baseUrl = s.resolvedBaseUrl,
                            ),
                        ),
                    selectedProfileId = DEFAULT_PROFILE_ID,
                )
            }
            return
        }

        // Profiles exist but nothing is selected: pick the first one so selection is non-null.
        if (needsSelection) {
            serverStore.update { s -> s.copy(selectedProfileId = s.connectionProfiles.first().id) }
        }
    }

    /** Ensure a profile is selected (the default one if nothing else), so callers never see null. */
    fun ensureDefaultSelected() {
        ensureDefaultProfile()
        if (getSelectedProfileId().isNullOrBlank()) {
            setSelectedProfileId(DEFAULT_PROFILE_ID)
        }
    }

    /**
     * One-time migration: fold the legacy standalone ([KEY_LEGACY_TOKEN]) credentials into the
     * new default [ConnectionProfile]. Runs once per install, guarded by
     * [KEY_LEGACY_DEFAULT_MIGRATED].
     */
    private fun migrateLegacyDefaultIfNeeded(p: SharedPreferences) {
        if (p.getBoolean(KEY_LEGACY_DEFAULT_MIGRATED, false)) return
        val legacyToken = p.getString(KEY_LEGACY_TOKEN, null)
        ensureDefaultProfile()
        p
            .edit()
            .apply {
                if (!legacyToken.isNullOrBlank()) {
                    putString("token_$DEFAULT_PROFILE_ID", legacyToken)
                }
                remove(KEY_LEGACY_TOKEN)
                putBoolean(KEY_LEGACY_DEFAULT_MIGRATED, true)
            }.apply()
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
            // B7 (Jul 08 2026, kanban t_470): sync in-memory cachedToken
            // to prevent stale tokens during ticket refresh
            synchronized(this) {
                cachedToken = token
                tokenInitialized = true
            }
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
        // B7 (Jul 08 2026, kanban t_470): keep cookie scope aligned with active profile.
        syncCookieStoreForProfile(id)
    }

    private fun normalizedProfileId(profileId: String?): String =
        profileId?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_ID

    private fun syncCookieStoreForProfile(profileId: String?) {
        if (!CookieManager.isInitialized()) return
        val normalizedId = normalizedProfileId(profileId)
        if (CookieManager.cookieJar.currentServer() != normalizedId) {
            CookieManager.useStore(normalizedId)
        }
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
                } catch (_: Exception) {
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
            val token = if (selectedId != null) getProfileToken(selectedId) else null
            cachedToken = token
            tokenInitialized = true
            return token
        }
    }

    private fun getTokenInternal(p: SharedPreferences): String? {
        val selectedId = serverStore.getLatestState().selectedProfileId?.takeIf { it.isNotBlank() }
        return if (selectedId != null) {
            p.getString("token_$selectedId", null)
        } else {
            null
        }
    }

    fun setToken(token: String?) {
        val selectedId =
            getSelectedProfileId() ?: run {
                ensureDefaultSelected()
                DEFAULT_PROFILE_ID
            }
        setProfileToken(selectedId, token)
        synchronized(this) {
            cachedToken = token
            tokenInitialized = true
        }
    }

    // ── Server endpoint ──────────────────────────────────────────────────

    fun getBaseUrl(): String = serverStore.getLatestState().resolvedBaseUrl

    fun endpoint(): ServerEndpoint =
        ServerEndpoint.parse(
            getBaseUrl(),
            CleartextPolicy.ALLOW_WITH_WARNING,
        )

    fun endpointForBuild(): ServerEndpoint = ServerEndpoint.parseForBuild(getBaseUrl())

    fun setBaseUrl(baseUrl: String) {
        val normalized =
            ServerEndpoint.parse(
                baseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl.toString()
        val selectedId =
            getSelectedProfileId() ?: run {
                ensureDefaultSelected()
                DEFAULT_PROFILE_ID
            }
        serverStore.update { state ->
            val profiles =
                state.connectionProfiles.map { profile ->
                    if (profile.id == selectedId) {
                        profile.copy(
                            host = "",
                            port = 0,
                            baseUrl = normalized,
                        )
                    } else {
                        profile
                    }
                }
            // Also cache the login URL at the top level so that getBaseUrl() and
            // the store-level resolvedBaseUrl (used by the profile list display)
            // reflect the URL actually authenticated against, never the hardcoded
            // loopback default. Fixes issue #647: a Default profile whose own
            // baseUrl was never stamped must still resolve to this URL, not
            // 127.0.0.1:9119.
            state.copy(
                baseUrl = normalized,
                connectionProfiles = profiles,
            )
        }
    }

    /** Compatibility accessors for legacy pairing payloads. */
    fun getHost(): String = serverStore.getLatestState().resolvedHost

    fun getPort(): Int = serverStore.getLatestState().resolvedPort

    fun setHost(host: String) {
        val endpoint = endpoint()
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        setBaseUrl(endpoint.baseUrl.newBuilder().host(normalizedHost).build().toString())
    }

    fun setPort(port: Int) {
        require(port in 1..65535) { "Port is out of range" }
        val endpoint = endpoint()
        setBaseUrl(endpoint.baseUrl.newBuilder().port(port).build().toString())
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

    /** Canonical build-allowed Retrofit base URL. */
    fun baseUrl(): String = endpointForBuild().baseUrl.toString()

    /** Canonical WebSocket URL with an encoded token or short-lived ticket. */
    fun wsUrl(): String {
        val raw = serverStore.getLatestState().wsAuthParam
        val authParam = if (raw.isBlank()) "token" else raw
        return endpointForBuild().webSocketUrl(
            authParameter = authParam,
            credential = getToken().orEmpty(),
        )
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

    // ── App display language ───────────────────────────────────────────
    // "system" follows the device locale; any other value is a BCP-47 code.

    fun getAppLanguage(): String = serverStore.getLatestState().appLanguage

    fun setAppLanguage(code: String) {
        serverStore.update { it.copy(appLanguage = code) }
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
