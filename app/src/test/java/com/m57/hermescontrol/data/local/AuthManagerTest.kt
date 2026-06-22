package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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

        // Mock EncryptedSharedPreferences static methods
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns mockPrefs

        // Mock MasterKey.Builder constructor and its build method
        mockkConstructor(MasterKey.Builder::class)
        val mockMasterKey = mockk<MasterKey>(relaxed = true)
        every { anyConstructed<MasterKey.Builder>().build() } returns mockMasterKey

        // Reset prefs singleton instance using reflection
        val field = AuthManager::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AuthManager, null)

        // Initialise AuthManager
        AuthManager.init(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testGetAndSetToken() {
        every { mockPrefs.getString("auth_token", null) } returns "my_secret_token"
        assertEquals("my_secret_token", AuthManager.getToken())

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
        every { mockPrefs.getString("host", "127.0.0.1") } returns "192.168.1.1"
        assertEquals("192.168.1.1", AuthManager.getHost())

        AuthManager.setHost("10.0.0.1")
        verify { mockEditor.putString("host", "10.0.0.1") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetPort() {
        every { mockPrefs.getInt("port", 9119) } returns 8080
        assertEquals(8080, AuthManager.getPort())

        AuthManager.setPort(9090)
        verify { mockEditor.putInt("port", 9090) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetAndSetAutoReconnect() {
        every { mockPrefs.getBoolean("auto_reconnect", true) } returns false
        assertEquals(false, AuthManager.isAutoReconnect())

        AuthManager.setAutoReconnect(true)
        verify { mockEditor.putBoolean("auto_reconnect", true) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testBaseUrl() {
        every { mockPrefs.getString("host", "127.0.0.1") } returns "hermes.local"
        every { mockPrefs.getInt("port", 9119) } returns 1234
        assertEquals("http://hermes.local:1234/", AuthManager.baseUrl())
    }

    @Test
    fun testWsUrl() {
        every { mockPrefs.getString("host", "127.0.0.1") } returns "hermes.local"
        every { mockPrefs.getInt("port", 9119) } returns 1234
        every { mockPrefs.getString("auth_token", null) } returns "token123"
        assertEquals("ws://hermes.local:1234/api/ws?token=token123", AuthManager.wsUrl())
    }

    @Test
    fun testWsUrl_nullToken() {
        every { mockPrefs.getString("host", "127.0.0.1") } returns "hermes.local"
        every { mockPrefs.getInt("port", 9119) } returns 1234
        every { mockPrefs.getString("auth_token", null) } returns null
        assertEquals("ws://hermes.local:1234/api/ws?token=", AuthManager.wsUrl())
    }

    // ── TEST-08: Token routing through selected profile ─────────────────

    @Test
    fun testGetToken_usesProfileTokenWhenSelected() {
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("token_prof-1", null) } returns "profile-specific-token"
        assertEquals("profile-specific-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_fallsBackToGlobalWhenProfileTokenMissing() {
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("token_prof-1", null) } returns null // no profile token
        every { mockPrefs.getString("auth_token", null) } returns "global-token"
        assertEquals("global-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_returnsNullWhenNoProfileAndNoGlobal() {
        every { mockPrefs.getString("selected_profile_id", null) } returns null
        every { mockPrefs.getString("auth_token", null) } returns null
        assertNull(AuthManager.getToken())
    }

    @Test
    fun testSetToken_writesToProfileTokenWhenSelected() {
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"

        AuthManager.setToken("new-profile-token")

        verify { mockEditor.putString("token_prof-1", "new-profile-token") }
        verify { mockEditor.apply() }
        // Should NOT write to global token
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_writesToGlobalWhenNoSelectedProfile() {
        every { mockPrefs.getString("selected_profile_id", null) } returns null

        AuthManager.setToken("global-token")

        verify { mockEditor.putString("auth_token", "global-token") }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetHost_usesProfileHostWhenSelected() {
        val profileJson = """[{"id":"prof-1","name":"Work","host":"10.0.0.1","port":9220}]"""
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("connection_profiles", null) } returns profileJson

        assertEquals("10.0.0.1", AuthManager.getHost())
    }

    @Test
    fun testGetPort_usesProfilePortWhenSelected() {
        val profileJson = """[{"id":"prof-1","name":"Work","host":"10.0.0.1","port":9220}]"""
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("connection_profiles", null) } returns profileJson

        assertEquals(9220, AuthManager.getPort())
    }

    @Test
    fun testGetHost_fallsBackToDefaultWhenSelectedProfileIdNotFound() {
        every { mockPrefs.getString("selected_profile_id", null) } returns "nonexistent"
        every { mockPrefs.getString("connection_profiles", null) } returns null
        every { mockPrefs.getString("host", "127.0.0.1") } returns "192.168.1.1"

        assertEquals("192.168.1.1", AuthManager.getHost())
    }

    @Test
    fun testSetHost_updatesProfileWhenSelected() {
        val profileJson = """[{"id":"prof-1","name":"Home","host":"10.0.0.1","port":9119}]"""
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("connection_profiles", null) } returns profileJson

        AuthManager.setHost("10.0.0.99")

        // Should have saved the updated profile with the new host
        verify { mockEditor.putString(eq("connection_profiles"), any()) }
        verify(exactly = 0) { mockEditor.putString("host", any()) }
    }

    @Test
    fun testSetPort_updatesProfileWhenSelected() {
        val profileJson = """[{"id":"prof-1","name":"Home","host":"10.0.0.1","port":9119}]"""
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-1"
        every { mockPrefs.getString("connection_profiles", null) } returns profileJson

        AuthManager.setPort(9999)

        verify { mockEditor.putString(eq("connection_profiles"), any()) }
        verify(exactly = 0) { mockEditor.putInt("port", any()) }
    }

    @Test
    fun testProfileTokenRoundTrip() {
        every { mockPrefs.getString("selected_profile_id", null) } returns null
        AuthManager.setProfileToken("prof-a", "token-a")

        verify { mockEditor.putString("token_prof-a", "token-a") }
        verify { mockEditor.apply() }

        // Mock read-back
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        assertEquals("token-a", AuthManager.getProfileToken("prof-a"))
    }

    @Test
    fun testConnectionProfiles_roundTrip() {
        val profiles = listOf(
            com.m57.hermescontrol.data.model.ConnectionProfile("a", "A", "10.0.0.1", 9119),
            com.m57.hermescontrol.data.model.ConnectionProfile("b", "B", "10.0.0.2", 9220),
        )
        AuthManager.saveConnectionProfiles(profiles)

        verify { mockEditor.putString(eq("connection_profiles"), any()) }

        // Mock read-back
        val json = """[{"id":"a","name":"A","host":"10.0.0.1","port":9119},{"id":"b","name":"B","host":"10.0.0.2","port":9220}]"""
        every { mockPrefs.getString("connection_profiles", null) } returns json

        val loaded = AuthManager.getConnectionProfiles()
        assertEquals(2, loaded.size)
        assertEquals("A", loaded[0].name)
        assertEquals("10.0.0.2", loaded[1].host)
    }

    @Test
    fun testGetHost_fallsBackToGlobalWhenProfileNotFoundInList() {
        val profileJson = """[{"id":"prof-1","name":"Work","host":"10.0.0.1","port":9119}]"""
        every { mockPrefs.getString("selected_profile_id", null) } returns "other-id"
        every { mockPrefs.getString("connection_profiles", null) } returns profileJson
        every { mockPrefs.getString("host", "127.0.0.1") } returns "global-host"

        assertEquals("global-host", AuthManager.getHost())
    }

    // ── TEST-11: Multi-profile token scenarios ─────────────────────────

    @Test
    fun testGetToken_switchesTokenWhenProfileChanges() {
        // Profile A selected → returns token_a
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"
        every { mockPrefs.getString("token_prof-a", null) } returns "token-for-a"
        assertEquals("token-for-a", AuthManager.getToken())

        // Switch to Profile B → returns token_b
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-b"
        every { mockPrefs.getString("token_prof-b", null) } returns "token-for-b"
        assertEquals("token-for-b", AuthManager.getToken())

        // Switch back to Profile A → still returns token_a
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"
        assertEquals("token-for-a", AuthManager.getToken())
    }

    @Test
    fun testGetToken_fallsBackToGlobalWhenSelectedProfileLacksToken() {
        // Profile A is selected but has no token → falls back to global
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"
        every { mockPrefs.getString("token_prof-a", null) } returns null
        every { mockPrefs.getString("auth_token", null) } returns "shared-global-token"

        assertEquals("shared-global-token", AuthManager.getToken())

        // Switch to Profile B, which also has no token → same global fallback
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-b"
        every { mockPrefs.getString("token_prof-b", null) } returns null
        assertEquals("shared-global-token", AuthManager.getToken())
    }

    @Test
    fun testGetToken_selectiveProfileTokens_someNull() {
        // Profile A has a token
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"
        every { mockPrefs.getString("token_prof-a", null) } returns "token-a"
        assertEquals("token-a", AuthManager.getToken())

        // Profile B has no token, but global exists
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-b"
        every { mockPrefs.getString("token_prof-b", null) } returns null
        every { mockPrefs.getString("auth_token", null) } returns "fallback"
        assertEquals("fallback", AuthManager.getToken())

        // Profile C has its own token too
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-c"
        every { mockPrefs.getString("token_prof-c", null) } returns "token-c"
        assertEquals("token-c", AuthManager.getToken())
    }

    @Test
    fun testSetToken_switchingProfiles_maintainsSeparateTokens() {
        // Set a token while Profile A is selected
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"
        AuthManager.setToken("set-while-on-profile-a")
        verify { mockEditor.putString("token_prof-a", "set-while-on-profile-a") }
        verify { mockEditor.apply() }

        // Switch to Profile B
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-b"

        // Set a different token for Profile B
        AuthManager.setToken("set-while-on-profile-b")
        verify { mockEditor.putString("token_prof-b", "set-while-on-profile-b") }
        verify(exactly = 0) { mockEditor.putString("auth_token", any()) }
    }

    @Test
    fun testSetToken_toNull_clearsProfileToken() {
        every { mockPrefs.getString("selected_profile_id", null) } returns "prof-a"

        AuthManager.setToken(null)

        verify { mockEditor.putString("token_prof-a", null) }
        verify { mockEditor.apply() }
    }

    @Test
    fun testGetSelectedProfileId_returnsNullForEmptyString() {
        every { mockPrefs.getString("selected_profile_id", null) } returns ""
        assertNull(AuthManager.getSelectedProfileId())

        every { mockPrefs.getString("selected_profile_id", null) } returns "  "
        assertNull(AuthManager.getSelectedProfileId())
    }
}
