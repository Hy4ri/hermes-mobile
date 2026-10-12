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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionAuthIsolationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        val base = mockk<Context>(relaxed = true)
        context = TestContext(tempFolder.root, base)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { prefs.getString("server_custom_headers", null) } returns null
        every { prefs.getBoolean("migrated_to_datastore", any()) } returns true
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<String>(),
                any<String>(),
                any<Context>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns prefs
        mockkStatic(MasterKeys::class)
        every { MasterKeys.getOrCreate(any()) } returns "mockMasterKey"
        runBlocking { AuthManager.resetAndAwaitForTest() }
        AuthManager.init(context)
        runBlocking { AuthManager.awaitInitialization() }
        AuthManager.serverStore.getLatestState()
    }

    @After
    fun tearDown() {
        runBlocking { AuthManager.resetAndAwaitForTest() }
        unmockkAll()
    }

    @Test
    fun switchingConnectionsRestoresIndependentWsAuthModes() {
        AuthManager.saveConnectionProfiles(
            listOf(
                ConnectionProfile(id = "a", name = "A", baseUrl = "http://a/"),
                ConnectionProfile(id = "b", name = "B", baseUrl = "http://b/"),
            ),
        )

        AuthManager.setSelectedProfileId("a")
        AuthManager.setWsAuthParam("ticket")
        assertEquals("ticket", AuthManager.serverStore.getLatestState().wsAuthParam)

        AuthManager.setSelectedProfileId("b")
        AuthManager.setWsAuthParam("token")
        assertEquals("token", AuthManager.serverStore.getLatestState().wsAuthParam)

        AuthManager.setSelectedProfileId("a")
        assertEquals("ticket", AuthManager.serverStore.getLatestState().wsAuthParam)
        assertEquals(true, AuthManager.isGatedMode())
    }

    @Test
    fun switchingConnectionsDoesNotReuseAnotherConnectionToken() {
        AuthManager.saveConnectionProfiles(
            listOf(ConnectionProfile(id = "a", name = "A", baseUrl = "http://a/")),
        )
        every { prefs.getString("token_default", null) } returns "default-token"
        every { prefs.getString("token_a", null) } returns null

        AuthManager.setSelectedProfileId("a")

        assertNull(AuthManager.getToken())
    }

    @Test
    fun switchingConnectionsClearsActiveProfileId() {
        AuthManager.saveConnectionProfiles(
            listOf(
                ConnectionProfile(id = "a", name = "A", baseUrl = "http://a/"),
                ConnectionProfile(id = "b", name = "B", baseUrl = "http://b/"),
            ),
        )
        AuthManager.setSelectedProfileId("a")
        AuthManager.setActiveProfileId("server-profile")

        AuthManager.setSelectedProfileId("b")

        assertNull(AuthManager.activeProfileId.value)
    }

    @Test
    fun selectingSameConnectionPreservesActiveProfileId() {
        AuthManager.saveConnectionProfiles(
            listOf(ConnectionProfile(id = "a", name = "A", baseUrl = "http://a/")),
        )
        AuthManager.setSelectedProfileId("a")
        AuthManager.setActiveProfileId("server-profile")

        AuthManager.setSelectedProfileId("a")

        assertEquals("server-profile", AuthManager.activeProfileId.value)
    }
}
