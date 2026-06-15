package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
