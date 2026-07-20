package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.config.resolvedBaseUrl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class ReproContext(
    private val tempDir: java.io.File,
    baseContext: Context,
) : android.content.ContextWrapper(baseContext) {
    override fun getFilesDir(): java.io.File = tempDir

    override fun getApplicationContext(): Context = this
}

/**
 * Regression tests for issue #647: the Default connection profile must show the
 * URL actually used to log in, never the hardcoded `127.0.0.1:9119` loopback.
 *
 * Two layers are covered:
 *  1. The login path stamps the selected profile's `baseUrl` with the login URL
 *     (so the profile carries the real URL on its own).
 *  2. Defensive layer: even when a profile's own `baseUrl` is null, resolution
 *     prefers the top-level login URL over the legacy loopback fallback.
 */
class Issue647ProfileUrlTest {
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockPrefs.getBoolean("migrated_to_datastore", any()) } returns true

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        testContext = ReproContext(tempDir, mockContext)

        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) tempFile.delete()

        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<String>(),
                any<String>(),
                any<Context>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns mockPrefs

        mockkStatic(MasterKeys::class)
        every { MasterKeys.getOrCreate(any()) } returns "mockMasterKey"

        val field = AuthManager::class.java.getDeclaredField("prefsDeferred")
        field.isAccessible = true
        field.set(AuthManager, null)

        val storeField = AuthManager::class.java.getDeclaredField("_serverStore")
        storeField.isAccessible = true
        storeField.set(AuthManager, null)

        AuthManager.resetAuthStateForTest()
        AuthManager.init(testContext)

        kotlinx.coroutines.runBlocking {
            val deferred = field.get(AuthManager) as? kotlinx.coroutines.Deferred<*>
            deferred?.await()
        }
        AuthManager.serverStore.getLatestState()
    }

    @After
    fun tearDown() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) tempFile.delete()
        unmockkAll()
    }

    @Test
    fun defaultProfile_afterLogin_reflectsLoginUrl() {
        // Mirror ConnectViewModel.connect() success path (no saveProfile branch).
        val customUrl = "http://192.1.1.190:9119/"
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl(customUrl)
        AuthManager.setProfileToken(AuthManager.DEFAULT_PROFILE_ID, "tok")

        val default =
            AuthManager.getConnectionProfiles().first {
                it.id == AuthManager.DEFAULT_PROFILE_ID
            }
        assertEquals(customUrl, default.resolveBaseUrl(null))
        // Top-level + store-level (the profile-list display) must match too.
        assertEquals(customUrl, AuthManager.getBaseUrl())
        assertEquals(customUrl, AuthManager.serverStore.getLatestState().resolvedBaseUrl)
    }

    @Test
    fun authLogin_connect_sequence_resolvesToLoginUrl_notLoopback() {
        // Mirror the exact AuthLoginViewModel.connect() sequence the user hits:
        // the Default profile is seeded (with the loopback default) and the user
        // logs in via a custom LAN URL. After connect the Default profile, the
        // top-level base URL, and the store-level display must all reflect the
        // login URL — never the hardcoded 127.0.0.1:9119 (issue #647).
        val loginUrl = "http://192.168.1.57:9119/"
        val loopback = "https://127.0.0.1:9119/"

        // Seed the Default profile the way the app does (host/port -> loopback).
        AuthManager.ensureDefaultProfile()
        // Confirm the initial seed really is the loopback default.
        val seeded =
            AuthManager.getConnectionProfiles().first {
                it.id == AuthManager.DEFAULT_PROFILE_ID
            }
        assertEquals(loopback, seeded.resolveBaseUrl(null))

        // Replicate AuthLoginViewModel.connect(): select -> setBaseUrl(login) -> token.
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl(loginUrl)
        AuthManager.setToken("ws-cred")

        val default =
            AuthManager.getConnectionProfiles().first {
                it.id == AuthManager.DEFAULT_PROFILE_ID
            }
        assertEquals(loginUrl, default.resolveBaseUrl(null))
        assertEquals(loginUrl, AuthManager.getBaseUrl())
        assertEquals(loginUrl, AuthManager.serverStore.getLatestState().resolvedBaseUrl)
        assertNotEquals(loopback, AuthManager.getBaseUrl())
    }

    @Test
    fun defaultProfile_withNullBaseUrl_fallsBackToLoginUrlNotLoopback() {
        // A Default profile whose own baseUrl was never stamped must still show
        // the top-level login URL, never the hardcoded loopback (issue #647).
        val customUrl = "http://192.1.1.190:9119/"
        val defaultNoUrl =
            ConnectionProfile(
                id = AuthManager.DEFAULT_PROFILE_ID,
                name = AuthManager.DEFAULT_PROFILE_NAME,
                baseUrl = null,
            )
        AuthManager.saveConnectionProfiles(listOf(defaultNoUrl))
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        // Top-level login URL is set, but the profile's own baseUrl remains null.
        AuthManager.setBaseUrl(customUrl)

        assertEquals(customUrl, defaultNoUrl.resolveBaseUrl(customUrl))
        assertEquals(customUrl, AuthManager.getBaseUrl())
    }

    @Test
    fun profileWithBaseUrl_ignoresTopLevelLoginUrl() {
        // When the profile has its own URL, that wins — no leakage from the
        // top-level field.
        val own = "http://10.0.0.5:9119/"
        val top = "http://192.1.1.190:9119/"
        val profile =
            ConnectionProfile(
                id = "prof-x",
                name = "X",
                baseUrl = own,
            )
        assertEquals(own, profile.resolveBaseUrl(top))
    }

    @Test
    fun freshInstall_withoutSavedUrl_defaultsToLoopback() {
        // No login URL anywhere: a null-baseUrl profile falls back to the
        // legacy loopback (http://127.0.0.1:9119/), preserving the fresh-install
        // default (issue #647 acceptance criteria). In practice the Default
        // profile is seeded from the top-level https default, but the pure
        // fallback must remain loopback.
        val fresh =
            ConnectionProfile(
                id = AuthManager.DEFAULT_PROFILE_ID,
                name = AuthManager.DEFAULT_PROFILE_NAME,
                baseUrl = null,
            )
        assertEquals(
            "http://127.0.0.1:9119/",
            fresh.resolveBaseUrl(null),
        )
    }

    @Test
    fun serverStoreState_selectedProfileNullBaseUrl_usesTopLevel() {
        // Store-level resolution: selected profile with null baseUrl uses the
        // top-level login URL, not loopback.
        val customUrl = "http://192.1.1.190:9119/"
        val state =
            ServerStoreState(
                baseUrl = customUrl,
                connectionProfiles =
                    listOf(
                        ConnectionProfile(
                            id = AuthManager.DEFAULT_PROFILE_ID,
                            name = AuthManager.DEFAULT_PROFILE_NAME,
                            baseUrl = null,
                        ),
                    ),
                selectedProfileId = AuthManager.DEFAULT_PROFILE_ID,
            )
        assertEquals(customUrl, state.resolvedBaseUrl)
    }
}
