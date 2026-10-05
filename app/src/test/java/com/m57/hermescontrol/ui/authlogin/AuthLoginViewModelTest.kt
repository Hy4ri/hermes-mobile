package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ClientCertificates
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.await
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthLoginViewModelTest {
    private lateinit var viewModel: AuthLoginViewModel
    private val app = mockk<Application>(relaxed = true)
    private val mockProbeClient = mockk<OkHttpClient>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.getConnectionProfiles() } returns emptyList()

        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns mockProbeClient

        mockkStatic(Log::class)
        mockkStatic("com.m57.hermescontrol.data.remote.CallExtKt")
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0

        every { app.getString(R.string.auth_login_error_unreachable) } returns "Dashboard unreachable"

        viewModel = AuthLoginViewModel(app)
    }

    @After
    fun teardown() {
        viewModel.clearConnectionState()
        Dispatchers.resetMain()
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
        every { mockProbeClient.newCall(any()) } returns mockCall
        coEvery { mockCall.await() } throws IOException("Connection refused")

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
        every { mockProbeClient.newCall(any()) } returns mockCall
        coEvery { mockCall.await() } returns mockResponse
        every { mockResponse.isSuccessful } returns false

        viewModel.probe()

        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertFalse(state.probing)
        assertNull(state.authMode)
        assertEquals("Dashboard unreachable", state.errorMessage)
    }

    @Test
    fun `first status alert offers certificate selection while preserving original error`() {
        val call = mockk<okhttp3.Call>()
        every { mockProbeClient.newCall(any()) } returns call
        coEvery { call.await() } throws
            IOException(
                "OkHttp wrapper",
                javax.net.ssl.SSLProtocolException("TLSV1_ALERT_CERTIFICATE_REQUIRED"),
            )
        viewModel.probe()
        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertEquals("Dashboard unreachable", state.errorMessage)
        assertEquals(
            "https://127.0.0.1:9119/",
            viewModel.certificatePrompt.state.value.origin
                .toString(),
        )
        viewModel.clearConnectionState()
        assertNull(viewModel.certificatePrompt.state.value.origin)
    }

    @Test
    fun `ordinary TLS error retains original unreachable path without certificate dialog`() {
        val call = mockk<okhttp3.Call>()
        every { mockProbeClient.newCall(any()) } returns call
        coEvery { call.await() } throws javax.net.ssl.SSLHandshakeException("CERTIFICATE_VERIFY_FAILED")
        viewModel.probe()
        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertEquals("Dashboard unreachable", state.errorMessage)
        assertNull(viewModel.certificatePrompt.state.value.origin)
    }

    @Test
    fun `the same alert during later homepage token discovery does not offer the dialog`() {
        val statusCall = mockk<okhttp3.Call>()
        val homepageCall = mockk<okhttp3.Call>()
        every { mockProbeClient.newCall(match { it.url.encodedPath == "/api/status" }) } returns statusCall
        every { mockProbeClient.newCall(match { it.url.encodedPath == "/" }) } returns homepageCall
        val response = mockk<Response>()
        every { response.isSuccessful } returns true
        every { response.body.string() } returns """{"auth_required":false}"""
        coEvery { statusCall.await() } returns response
        coEvery { homepageCall.await() } throws javax.net.ssl.SSLProtocolException("TLSV1_ALERT_CERTIFICATE_REQUIRED")
        viewModel.probe()
        val state = runBlocking { viewModel.uiState.first { !it.probing } }
        assertEquals(DashboardAuthMode.ALL, state.authMode)
        assertNull(viewModel.certificatePrompt.state.value.origin)
    }

    @Test
    fun `address edit and navigation invalidate a pending prompt`() =
        runBlocking {
            for (leave in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val call = mockk<okhttp3.Call>()
                every { mockProbeClient.newCall(any()) } returns call
                coEvery { call.await() } coAnswers {
                    started.complete(Unit)
                    release.await()
                    throw javax.net.ssl.SSLProtocolException("TLSV1_ALERT_CERTIFICATE_REQUIRED")
                }
                viewModel.probe()
                withTimeout(5000) { started.await() }
                if (leave) viewModel.clearConnectionState() else viewModel.onBaseUrlChange("https://other.test:9443/")
                release.complete(Unit)
                withTimeout(5000) { viewModel.uiState.first { !it.probing } }
                assertNull(viewModel.certificatePrompt.state.value.origin)
            }
        }

    @Test
    fun `saving explicitly replaces only the matching binding without retrying probe`() =
        runBlocking {
            val origin = "https://example.test:8443/".toHttpUrl()
            val expected = mapOf(origin.toString() to "old", "https://example.test:9443/" to "other-port")
            mockkObject(ClientCertificates)
            every { ClientCertificates.state } returns MutableStateFlow(expected)
            coEvery { ClientCertificates.verifyCandidate(origin, "candidate") } returns Unit
            every { ClientCertificates.save(origin, origin, "candidate", expected) } returns Unit
            viewModel.certificatePrompt.offer(viewModel.certificatePrompt.reset(), origin)
            viewModel.certificatePrompt.selected(
                requireNotNull(viewModel.certificatePrompt.beginSelection()),
                "candidate",
                true,
            )
            viewModel.saveCertificate()
            withTimeout(5000) { viewModel.certificatePrompt.state.first { it.savedOrigin != null } }
            verify(exactly = 1) { ClientCertificates.save(origin, origin, "candidate", expected) }
            verify(exactly = 0) { mockProbeClient.newCall(any()) }
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
}
