package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.config.ConnectionProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TestContext(
    private val tempDir: java.io.File,
    baseContext: Context,
) : android.content.ContextWrapper(baseContext) {
    override fun getFilesDir(): java.io.File = tempDir

    override fun getApplicationContext(): Context = this
}

class AuthManagerTest {
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

        // Mock Log to prevent "Method d in android.util.Log not mocked"
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        // Mock filesDir to point to temporary directory for DataStore
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        testContext = TestContext(tempDir, mockContext)

        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        // Mock EncryptedSharedPreferences static methods
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

        // Reset prefs singleton instance using reflection
        val field = AuthManager::class.java.getDeclaredField("prefsDeferred")
        field.isAccessible = true
        field.set(AuthManager, null)

        val storeField = AuthManager::class.java.getDeclaredField("_serverStore")
        storeField.isAccessible = true
        storeField.set(AuthManager, null)

        AuthManager.resetAuthStateForTest()

        // Initialise AuthManager
        AuthManager.init(testContext)

        // Wait for async initialization to complete to prevent coroutine leaks
        kotlinx.coroutines.runBlocking {
            val deferred = field.get(AuthManager) as? kotlinx.coroutines.Deferred<*>
            deferred?.await()
        }

        // Wait for ServerStore initialization to complete
        AuthManager.serverStore.getLatestState()
    }

    @After
    fun tearDown() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        unmockkAll()
    }

    @Test
    fun testGetAndSetToken() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setToken("dummy_token_123")
        assertEquals("dummy_token_123", AuthManager.getToken())
        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "dummy_token_123") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetToken_null() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setToken(null)
        assertNull(AuthManager.getToken())
        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetHostAndPortFromBaseUrl() {
        AuthManager.setBaseUrl("https://10.0.0.1:9090/")
        assertEquals("10.0.0.1", AuthManager.getHost())
        assertEquals(9090, AuthManager.getPort())
    }

    @Test
    fun testGetAndSetAutoReconnect() {
        AuthManager.setAutoReconnect(false)
        assertEquals(false, AuthManager.isAutoReconnect())
    }

    @Test
    fun testBaseUrl() {
        AuthManager.setBaseUrl("https://hermes.local:1234/")
        assertEquals("https://hermes.local:1234/", AuthManager.getBaseUrl())
    }

    @Test
    fun testTokenCaching() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "first-token"
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        assertEquals("first-token", AuthManager.getToken())

        // Update the mockPrefs directly, but since getToken uses the cache, it should still return the first token
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "second-token"
        assertEquals("first-token", AuthManager.getToken())

        // Clear token initialized manually since this happens upon selecting profile id
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        assertEquals("second-token", AuthManager.getToken())

        // Test setToken
        AuthManager.setToken("third-token")
        assertEquals("third-token", AuthManager.getToken())
    }

    @Test
    fun testWsUrl() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns "token123"
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl("https://hermes.local:1234/")
        // Scheme tracks the canonical base URL (default https) → wss.
        assertEquals("wss://hermes.local:1234/api/ws?token=token123", AuthManager.wsUrl())
    }

    @Test
    fun testWsUrl_nullToken() {
        AuthManager.ensureDefaultProfile()
        every { mockPrefs.getString("token_${AuthManager.DEFAULT_PROFILE_ID}", null) } returns null
        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        AuthManager.setBaseUrl("https://hermes.local:1234/")
        assertEquals("wss://hermes.local:1234/api/ws?token=", AuthManager.wsUrl())
    }

    // ── TEST-08: Token routing through selected profile ─────────────────

    @Test
    fun testGetToken_usesProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile(id = "prof-1", name = "Profile 1", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns "profile-specific-token"
        AuthManager.setSelectedProfileId("prof-1")
        assertEquals("profile-specific-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_returnsNullWhenProfileHasNoToken() {
        val profile1 = ConnectionProfile(id = "prof-1", name = "Profile 1", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns null
        AuthManager.setSelectedProfileId("prof-1")
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testSetToken_writesToProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile(id = "prof-1", name = "Profile 1", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profile1))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setToken("new-profile-token")

        verify { mockEditor.putString("token_prof-1", "new-profile-token") }
        verify { mockEditor.apply() }
        // Should NOT write to a global token key
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_writesToDefaultProfileWhenNoSelection() {
        AuthManager.ensureDefaultProfile()

        AuthManager.setToken("default-token")

        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "default-token") }
        verify { mockEditor.apply() }
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testGetHost_usesProfileHostWhenSelected() {
        val profile = ConnectionProfile(id = "prof-1", name = "Work", baseUrl = "http://10.0.0.1:9220/")
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetPort_usesProfilePortWhenSelected() {
        val profile = ConnectionProfile(id = "prof-1", name = "Work", baseUrl = "http://10.0.0.1:9220/")
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals(9220, AuthManager.getPort())
    }

    @Test
    fun testGetHost_fallsBackToDefaultWhenSelectedProfileIdNotFound() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setSelectedProfileId("nonexistent")
        AuthManager.saveConnectionProfiles(emptyList())
        AuthManager.setBaseUrl("http://192.168.1.1:9119/")

        assertEquals("192.168.1.1", AuthManager.getHost())
    }

    @Test
    fun testSetBaseUrl_updatesProfileWhenSelected() {
        val profile = ConnectionProfile(id = "prof-1", name = "Home", baseUrl = "http://10.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setBaseUrl("http://10.0.0.99:9999/")

        val updated = AuthManager.getConnectionProfiles().first()
        assertEquals("http://10.0.0.99:9999/", updated.resolvedBaseUrl)
    }

    @Test
    fun testProfileTokenRoundTrip() {
        AuthManager.ensureDefaultProfile()
        AuthManager.setProfileToken("prof-a", "token-a")

        verify { mockEditor.putString("token_prof-a", "token-a") }
        verify { mockEditor.apply() }

        // Mock read-back
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        assertEquals("token-a", AuthManager.getProfileToken("prof-a"))
    }

    @Test
    fun testConnectionProfiles_roundTrip() {
        val profiles =
            listOf(
                ConnectionProfile(id = "a", name = "A", baseUrl = "http://10.0.0.1:9119/"),
                ConnectionProfile(id = "b", name = "B", baseUrl = "http://10.0.0.2:9220/"),
            )
        AuthManager.saveConnectionProfiles(profiles)

        val loaded = AuthManager.getConnectionProfiles()
        assertEquals(2, loaded.size)
        assertEquals("A", loaded[0].name)
        assertEquals("http://10.0.0.2:9220/", loaded[1].resolvedBaseUrl)
    }

    // ── TEST-11: Multi-profile token scenarios ─────────────────────────

    @Test
    fun testGetToken_switchesTokenWhenProfileChanges() {
        val profileA = ConnectionProfile(id = "prof-a", name = "Profile A", baseUrl = "http://127.0.0.1:9119/")
        val profileB = ConnectionProfile(id = "prof-b", name = "Profile B", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Profile A selected → returns token_a
        every { mockPrefs.getString("token_prof-a", null) } returns "token-for-a"
        AuthManager.setSelectedProfileId("prof-a")
        assertEquals("token-for-a", AuthManager.getToken())

        // Switch to Profile B → returns token_b
        every { mockPrefs.getString("token_prof-b", null) } returns "token-for-b"
        AuthManager.setSelectedProfileId("prof-b") // Required to clear token cache
        assertEquals("token-for-b", AuthManager.getToken())

        // Switch back to Profile A → still returns token_a
        every { mockPrefs.getString("token_prof-a", null) } returns "token-for-a"
        AuthManager.setSelectedProfileId("prof-a") // Required to clear token cache
        assertEquals("token-for-a", AuthManager.getToken())
    }

    @Test
    fun testGetToken_profileWithNoTokenReturnsNull() {
        val profileA = ConnectionProfile(id = "prof-a", name = "Profile A", baseUrl = "http://127.0.0.1:9119/")
        val profileB = ConnectionProfile(id = "prof-b", name = "Profile B", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Profile A is selected but has no token → null (no global fallback)
        every { mockPrefs.getString("token_prof-a", null) } returns null
        AuthManager.setSelectedProfileId("prof-a")
        assertNull(AuthManager.getToken())

        // Switch to Profile B, which also has no token → still null
        every { mockPrefs.getString("token_prof-b", null) } returns null
        AuthManager.setSelectedProfileId("prof-b")
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testGetToken_selectiveProfileTokens_someNull() {
        val profileA = ConnectionProfile(id = "prof-a", name = "Profile A", baseUrl = "http://127.0.0.1:9119/")
        val profileB = ConnectionProfile(id = "prof-b", name = "Profile B", baseUrl = "http://127.0.0.1:9119/")
        val profileC = ConnectionProfile(id = "prof-c", name = "Profile C", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB, profileC))

        // Profile A has a token
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        AuthManager.setSelectedProfileId("prof-a")
        assertEquals("token-a", AuthManager.getToken())

        // Profile B has no token → null
        every { mockPrefs.getString("token_prof-b", null) } returns null
        AuthManager.setSelectedProfileId("prof-b")
        assertNull(AuthManager.getToken())

        // Profile C has its own token too
        every { mockPrefs.getString("token_prof-c", null) } returns "token-c"
        AuthManager.setSelectedProfileId("prof-c")
        assertEquals("token-c", AuthManager.getToken())
    }

    @Test
    fun testSetToken_switchingProfiles_maintainsSeparateTokens() {
        val profileA = ConnectionProfile(id = "prof-a", name = "Profile A", baseUrl = "http://127.0.0.1:9119/")
        val profileB = ConnectionProfile(id = "prof-b", name = "Profile B", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Set a token while Profile A is selected
        AuthManager.setSelectedProfileId("prof-a")
        AuthManager.setToken("set-while-on-profile-a")
        verify { mockEditor.putString("token_prof-a", "set-while-on-profile-a") }
        verify { mockEditor.apply() }

        // Switch to Profile B
        AuthManager.setSelectedProfileId("prof-b")

        // Set a different token for Profile B
        AuthManager.setToken("set-while-on-profile-b")
        verify { mockEditor.putString("token_prof-b", "set-while-on-profile-b") }
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_toNull_clearsProfileToken() {
        val profileA = ConnectionProfile(id = "prof-a", name = "Profile A", baseUrl = "http://127.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(profileA))

        AuthManager.setSelectedProfileId("prof-a")

        AuthManager.setToken(null)

        verify { mockEditor.putString("token_prof-a", null) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetSelectedProfileId_returnsNullForEmptyString() {
        AuthManager.setSelectedProfileId("")
        assertNull(AuthManager.getSelectedProfileId())

        AuthManager.setSelectedProfileId("  ")
        assertNull(AuthManager.getSelectedProfileId())
    }

    @Test
    fun testEnsureDefaultProfile_createsDefaultWhenMissing() {
        AuthManager.saveConnectionProfiles(emptyList())
        AuthManager.setSelectedProfileId(null)

        AuthManager.ensureDefaultProfile()

        val profiles = AuthManager.getConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, profiles[0].id)
        assertEquals(AuthManager.DEFAULT_PROFILE_NAME, profiles[0].name)
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, AuthManager.getSelectedProfileId())
    }

    @Test
    fun testEnsureDefaultProfile_doesNotDuplicateExisting() {
        val existing = ConnectionProfile(id = "prof-1", name = "Work", baseUrl = "http://10.0.0.1:9119/")
        AuthManager.saveConnectionProfiles(listOf(existing))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.ensureDefaultProfile()

        val profiles = AuthManager.getConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals("prof-1", AuthManager.getSelectedProfileId())
    }

    @Test
    fun testMigrateLegacyToken_foldsIntoDefaultProfile() {
        // Simulate a legacy install: no profiles, a global auth_token, and a selected id of null.
        every { mockPrefs.getString("auth_token", null) } returns "legacy-standalone-token"
        every { mockPrefs.getBoolean("legacy_default_migrated", false) } returns false

        // Re-initialize so init() runs the one-time migration
        AuthManager.resetAuthStateForTest()
        AuthManager.init(testContext)
        kotlinx.coroutines.runBlocking {
            val field = AuthManager::class.java.getDeclaredField("prefsDeferred")
            field.isAccessible = true
            (field.get(AuthManager) as? kotlinx.coroutines.Deferred<*>)?.await()
        }

        // token should now be persisted under the default profile key, and the legacy key removed
        verify { mockEditor.putString("token_${AuthManager.DEFAULT_PROFILE_ID}", "legacy-standalone-token") }
        verify { mockEditor.remove("auth_token") }
        verify { mockEditor.putBoolean("legacy_default_migrated", true) }
    }
}
