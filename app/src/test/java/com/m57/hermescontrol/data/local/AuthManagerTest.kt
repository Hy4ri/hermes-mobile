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
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val testContext = TestContext(tempDir, mockContext)

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

        AuthManager.resetTokenCacheForTest()

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
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val tempFile = java.io.File(tempDir, "server_store.json")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        unmockkAll()
    }

    @Test
    fun testGetAndSetToken() {
        every { mockPrefs.getString("auth_token", null) } returns "dummy_token_123"
        assertEquals("dummy_token_123", AuthManager.getToken())

        AuthManager.setToken("new_token")
        verify { mockEditor.putString("auth_token", "new_token") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetToken_null() {
        every { mockPrefs.getString("auth_token", null) } returns null
        assertNull(AuthManager.getToken())

        AuthManager.setToken(null)
        verify { mockEditor.putString("auth_token", null) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetHost() {
        AuthManager.setHost("10.0.0.1")
        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetAndSetPort() {
        AuthManager.setPort(9090)
        assertEquals(9090, AuthManager.getPort())
    }

    @Test
    fun testGetAndSetAutoReconnect() {
        AuthManager.setAutoReconnect(false)
        assertEquals(false, AuthManager.isAutoReconnect())
    }

    @Test
    fun testBaseUrl() {
        AuthManager.setHost("hermes.local")
        AuthManager.setPort(1234)
        assertEquals("http://hermes.local:1234/", AuthManager.baseUrl())
    }

    @Test
    fun testTokenCaching() {
        every { mockPrefs.getString("auth_token", null) } returns "first-token"
        assertEquals("first-token", AuthManager.getToken())

        // Update the mockPrefs directly, but since getToken uses the cache, it should still return the first token
        every { mockPrefs.getString("auth_token", null) } returns "second-token"
        assertEquals("first-token", AuthManager.getToken())

        // Clear token initialized manually since this happens upon selecting profile id
        AuthManager.setSelectedProfileId(null)
        assertEquals("second-token", AuthManager.getToken())

        // Test setToken
        AuthManager.setToken("third-token")
        assertEquals("third-token", AuthManager.getToken())
    }

    @Test
    fun testWsUrl() {
        AuthManager.setHost("hermes.local")
        AuthManager.setPort(1234)
        every { mockPrefs.getString("auth_token", null) } returns "token123"
        assertEquals("ws://hermes.local:1234/api/ws?token=token123", AuthManager.wsUrl())
    }

    @Test
    fun testWsUrl_nullToken() {
        AuthManager.setHost("hermes.local")
        AuthManager.setPort(1234)
        every { mockPrefs.getString("auth_token", null) } returns null
        assertEquals("ws://hermes.local:1234/api/ws?token=", AuthManager.wsUrl())
    }

    // ── TEST-08: Token routing through selected profile ─────────────────

    @Test
    fun testGetToken_usesProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns "profile-specific-token"
        AuthManager.setSelectedProfileId("prof-1")
        assertEquals("profile-specific-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_fallsBackToGlobalWhenProfileTokenMissing() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        every { mockPrefs.getString("token_prof-1", null) } returns null // no profile token
        every { mockPrefs.getString("auth_token", null) } returns "global-token"
        AuthManager.setSelectedProfileId("prof-1")
        assertEquals("global-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_returnsNullWhenNoProfileAndNoGlobal() {
        every { mockPrefs.getString("auth_token", null) } returns null
        AuthManager.setSelectedProfileId(null)
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testSetToken_writesToProfileTokenWhenSelected() {
        val profile1 = ConnectionProfile("prof-1", "Profile 1", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile1))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setToken("new-profile-token")

        verify { mockEditor.putString("token_prof-1", "new-profile-token") }
        verify { mockEditor.apply() }
        // Should NOT write to global token
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_writesToGlobalWhenNoSelectedProfile() {
        AuthManager.setSelectedProfileId(null)

        AuthManager.setToken("global-token")

        verify { mockEditor.putString("auth_token", "global-token") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetHost_usesProfileHostWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Work", "10.0.0.1", 9220)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetPort_usesProfilePortWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Work", "10.0.0.1", 9220)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        assertEquals(9220, AuthManager.getPort())
    }

    @Test
    fun testGetHost_fallsBackToDefaultWhenSelectedProfileIdNotFound() {
        AuthManager.setSelectedProfileId("nonexistent")
        AuthManager.saveConnectionProfiles(emptyList())
        AuthManager.setHost("192.168.1.1")

        assertEquals("192.168.1.1", AuthManager.getHost())
    }

    @Test
    fun testSetHost_updatesProfileWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Home", "10.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setHost("10.0.0.99")

        val updated = AuthManager.getConnectionProfiles().first()
        assertEquals("10.0.0.99", updated.host)
    }

    @Test
    fun testSetPort_updatesProfileWhenSelected() {
        val profile = ConnectionProfile("prof-1", "Home", "10.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profile))
        AuthManager.setSelectedProfileId("prof-1")

        AuthManager.setPort(9999)

        val updated = AuthManager.getConnectionProfiles().first()
        assertEquals(9999, updated.port)
    }

    @Test
    fun testProfileTokenRoundTrip() {
        AuthManager.setSelectedProfileId(null)
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
                ConnectionProfile("a", "A", "10.0.0.1", 9119),
                ConnectionProfile("b", "B", "10.0.0.2", 9220),
            )
        AuthManager.saveConnectionProfiles(profiles)

        val loaded = AuthManager.getConnectionProfiles()
        assertEquals(2, loaded.size)
        assertEquals("A", loaded[0].name)
        assertEquals("10.0.0.2", loaded[1].host)
    }

    // ── TEST-11: Multi-profile token scenarios ─────────────────────────

    @Test
    fun testGetToken_switchesTokenWhenProfileChanges() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
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
    fun testGetToken_fallsBackToGlobalWhenSelectedProfileLacksToken() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB))

        // Profile A is selected but has no token → falls back to global
        every { mockPrefs.getString("token_prof-a", null) } returns null
        every { mockPrefs.getString("auth_token", null) } returns "shared-global-token"
        AuthManager.setSelectedProfileId("prof-a")

        assertEquals("shared-global-token", AuthManager.getToken())

        // Switch to Profile B, which also has no token → same global fallback
        every { mockPrefs.getString("token_prof-b", null) } returns null
        AuthManager.setSelectedProfileId("prof-b")
        assertEquals("shared-global-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_selectiveProfileTokens_someNull() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
        val profileC = ConnectionProfile("prof-c", "Profile C", "127.0.0.1", 9119)
        AuthManager.saveConnectionProfiles(listOf(profileA, profileB, profileC))

        // Profile A has a token
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        AuthManager.setSelectedProfileId("prof-a")
        assertEquals("token-a", AuthManager.getToken())

        // Profile B has no token, but global exists
        every { mockPrefs.getString("token_prof-b", null) } returns null
        every { mockPrefs.getString("auth_token", null) } returns "fallback"
        AuthManager.setSelectedProfileId("prof-b")
        assertEquals("fallback", AuthManager.getToken())

        // Profile C has its own token too
        every { mockPrefs.getString("token_prof-c", null) } returns "token-c"
        AuthManager.setSelectedProfileId("prof-c")
        assertEquals("token-c", AuthManager.getToken())
    }

    @Test
    fun testSetToken_switchingProfiles_maintainsSeparateTokens() {
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
        val profileB = ConnectionProfile("prof-b", "Profile B", "127.0.0.1", 9119)
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
        val profileA = ConnectionProfile("prof-a", "Profile A", "127.0.0.1", 9119)
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
}
