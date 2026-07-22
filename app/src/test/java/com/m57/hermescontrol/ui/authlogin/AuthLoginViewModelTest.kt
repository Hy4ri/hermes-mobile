package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.AuthSessionState
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AuthLoginViewModelTest {
    private lateinit var viewModel: AuthLoginViewModel
    private val app = mockk<Application>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.getConnectionProfiles() } returns emptyList()

        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns mockk<OkHttpClient>(relaxed = true)

        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        every { app.getString(R.string.auth_login_error_unreachable) } returns "Dashboard unreachable"

        viewModel = AuthLoginViewModel(app)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `onBaseUrlChange updates baseUrl and clears error and auth mode`() {
        viewModel.onBaseUrlChange(" https://192.168.1.100:9119/ ")

        val state = viewModel.uiState.value
        assertEquals("https://192.168.1.100:9119/", state.baseUrl)
        assertNull(state.errorMessage)
        assertNull(state.authMode)
    }

    @Test
    fun `onBaseUrlChange with http sets transport warning`() {
        viewModel.onBaseUrlChange("http://127.0.0.1:9119/")

        val state = viewModel.uiState.value
        assertEquals("http://127.0.0.1:9119/", state.baseUrl)
        assertEquals(ServerEndpoint.CLEARTEXT_WARNING, state.transportWarning)
        assertNull(state.errorMessage)
        assertNull(state.authMode)
    }

    @Test
    fun `clearConnectionState resets connectionSuccess errorMessage and isLoading`() {
        viewModel.clearConnectionState()

        val state = viewModel.uiState.value
        assertFalse(state.connectionSuccess)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `probe when status probe throws IOException updates error state`() {
        val mockCall = mockk<okhttp3.Call>()
        every { OkHttpProvider.probe.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Connection refused")

        viewModel.probe()

        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertFalse(state.probing)
        assertNull(state.authMode)
        assertEquals("Dashboard unreachable", state.errorMessage)
    }

    @Test
    fun `probe when status probe returns unsuccessful updates error state`() {
        val mockCall = mockk<okhttp3.Call>()
        val mockResponse = mockk<Response>()
        every { OkHttpProvider.probe.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse
        every { mockResponse.isSuccessful } returns false

        viewModel.probe()

        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertFalse(state.probing)
        assertNull(state.authMode)
        assertEquals("Dashboard unreachable", state.errorMessage)
    }

    // ── deriveAuthMode (authoritative /api/status mapping) ──

    @Test
    fun `deriveAuthMode gate down returns ALL sentinel`() {
        assertEquals(
            DashboardAuthMode.ALL,
            viewModel.deriveAuthMode(authRequired = false, providers = emptyList()),
        )
    }

    @Test
    fun `deriveAuthMode oauth provider returns OAUTH`() {
        assertEquals(
            DashboardAuthMode.OAUTH,
            viewModel.deriveAuthMode(authRequired = true, providers = listOf("oauth")),
        )
    }

    @Test
    fun `deriveAuthMode basic provider returns BASIC_AUTH`() {
        assertEquals(
            DashboardAuthMode.BASIC_AUTH,
            viewModel.deriveAuthMode(authRequired = true, providers = listOf("basic")),
        )
    }

    @Test
    fun `deriveAuthMode oauth takes precedence over basic`() {
        assertEquals(
            DashboardAuthMode.OAUTH,
            viewModel.deriveAuthMode(authRequired = true, providers = listOf("basic", "oauth")),
        )
    }

    @Test
    fun `deriveAuthMode gate up with unknown provider falls back to BASIC_AUTH`() {
        assertEquals(
            DashboardAuthMode.BASIC_AUTH,
            viewModel.deriveAuthMode(authRequired = true, providers = listOf("weird")),
        )
    }

    @Test
    fun `loadLoggedInProfiles when signInRequired is true clears profile shortcuts`() {
        AuthSessionState.requireSignIn()
        every { AuthManager.getConnectionProfiles() } returns
            listOf(
                ConnectionProfile(id = "p1", name = "P1", baseUrl = "http://127.0.0.1"),
            )
        every { AuthManager.getProfileToken("p1") } returns "token1"

        viewModel.loadLoggedInProfiles()

        assertTrue(viewModel.uiState.value.loggedInProfiles.isEmpty())
        AuthSessionState.resetForTest()
    }

    @Test
    fun `useExistingProfile marks session authenticated`() {
        AuthSessionState.requireSignIn()
        mockkObject(HermesWsClient)
        every { AuthManager.setSelectedProfileId("p1") } returns Unit
        every { HermesWsClient.connect() } returns Unit

        viewModel.useExistingProfile("p1")

        assertFalse(AuthSessionState.signInRequired.value)
        assertTrue(viewModel.uiState.value.connectionSuccess)
        AuthSessionState.resetForTest()
    }
}
