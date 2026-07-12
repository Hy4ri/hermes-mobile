package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        every { AuthManager.getHost() } returns "localhost"
        every { AuthManager.getPort() } returns 9119
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
    fun `onHostChange updates host and clears error and auth mode`() {
        viewModel.onHostChange(" 192.168.1.100 ")

        val state = viewModel.uiState.value
        assertEquals("192.168.1.100", state.host)
        assertNull(state.errorMessage)
        assertNull(state.authMode)
    }

    @Test
    fun `onPortChange updates port keeping only digits and clears error and auth mode`() {
        viewModel.onPortChange("9a1b1c9")

        val state = viewModel.uiState.value
        assertEquals("9119", state.port)
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

    @Test
    fun `probe with embedded token becomes ready for seamless connection`() {
        val server = MockWebServer()
        server.start()
        try {
            every { AuthManager.getHost() } returns server.hostName
            every { AuthManager.getPort() } returns server.port
            every { OkHttpProvider.probe } returns OkHttpClient()
            viewModel = AuthLoginViewModel(app)

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<script>window.__HERMES_SESSION_TOKEN__ = \"stable-token\";</script>"),
            )

            viewModel.probe()

            val state =
                runBlocking {
                    withTimeout(3_000) { viewModel.uiState.first { !it.probing && it.authMode != null } }
                }
            assertEquals(DashboardAuthMode.TOKEN_ONLY, state.authMode)
            assertEquals("stable-token", state.token)
            assertTrue(state.autoConnectReady)
        } finally {
            server.shutdown()
        }
    }
}
