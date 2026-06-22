package com.m57.hermescontrol.ui.connect

import android.app.Application
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApiService: HermesApiService
    private val mockApp = mockk<Application>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        mockkObject(ApiClient)
        mockkStatic(android.util.Base64::class)
        mockkStatic(android.net.Uri::class)

        // Default Uri mock — returns null for all query params (pairing string parsing fails by default)
        val mockUri = mockk<android.net.Uri>(relaxed = true)
        every { android.net.Uri.parse(any()) } returns mockUri

        mockApiService = mockk()
        every { ApiClient.hermesApi } returns mockApiService
        every { ApiClient.rebuild() } returns Unit

        // Default AuthManager stubs
        every { AuthManager.getToken() } returns ""
        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setHost(any()) } returns Unit
        every { AuthManager.setPort(any()) } returns Unit
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.setSelectedProfileId(any()) } returns Unit

        // Mock Application string resources
        every { mockApp.getString(R.string.connect_error_token_required) } returns "Token is required"
        every { mockApp.getString(R.string.connect_error_host_required) } returns "Host is required"
        every { mockApp.getString(R.string.connect_error_port_invalid) } returns "Port must be between 1 and 65535"
        every { mockApp.getString(R.string.connect_error_401) } returns "Invalid token (401 Unauthorized)"
        every { mockApp.getString(R.string.connect_error_403) } returns "Access denied (403 Forbidden)"
        every {
            mockApp.getString(R.string.connect_error_http_code, any())
        } answers { "Server returned HTTP ${secondArg<Any>()}" }
        every { mockApp.getString(R.string.connect_error_refused) } returns "Connection refused – is Hermes running?"
        every { mockApp.getString(R.string.connect_error_timeout) } returns "Connection timed out"
        every {
            mockApp.getString(R.string.connect_error_connection_failed, any())
        } answers { "Connection failed: ${secondArg<Any>()}" }
        every {
            mockApp.getString(R.string.connect_error_malformed)
        } returns "Malformed pairing string — expected URL or Base64-encoded JSON"
        every {
            mockApp.getString(R.string.connect_error_missing_fields)
        } returns "Malformed pairing string — missing host, port, or token"
        every {
            mockApp.getString(R.string.connect_error_parse_failed, any())
        } answers { "Failed to parse pairing string: ${secondArg<Any>()}" }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun testInitialLoadingFromAuthManager() {
        every { AuthManager.getToken() } returns "saved-token"
        every { AuthManager.getHost() } returns "hermes.local"
        every { AuthManager.getPort() } returns 8888

        val viewModel = ConnectViewModel(mockApp)
        val state = viewModel.uiState.value

        assertEquals("saved-token", state.token)
        assertEquals("hermes.local", state.host)
        assertEquals("8888", state.port)
        assertFalse(state.isConnecting)
        assertFalse(state.connectionSuccess)
        assertNull(state.errorMessage)
    }

    @Test
    fun testOnTokenChange() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onTokenChange("  new-token  ")
        assertEquals("new-token", viewModel.uiState.value.token)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testOnHostChange() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onHostChange("  192.168.1.50  ")
        assertEquals("192.168.1.50", viewModel.uiState.value.host)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testOnPortChange() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onPortChange("90abc90")
        assertEquals("9090", viewModel.uiState.value.port)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testConnect_blankToken_showsError() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onTokenChange("")
        viewModel.connect()
        assertEquals("Token is required", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testConnect_blankHost_showsError() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onTokenChange("valid-token")
        viewModel.onHostChange("")
        viewModel.connect()
        assertEquals("Host is required", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testConnect_invalidPortBounds_showsError() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onTokenChange("valid-token")
        viewModel.onHostChange("127.0.0.1")

        viewModel.onPortChange("0")
        viewModel.connect()
        assertEquals("Port must be between 1 and 65535", viewModel.uiState.value.errorMessage)

        viewModel.onPortChange("65536")
        viewModel.connect()
        assertEquals("Port must be between 1 and 65535", viewModel.uiState.value.errorMessage)

        viewModel.onPortChange("")
        viewModel.connect()
        assertEquals("Port must be between 1 and 65535", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testConnect_success() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("valid-token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body() } returns
                StatusResponse(
                    version = "1.0",
                    gateway_running = true,
                    active_sessions = 1,
                    auth_required = false,
                    gateway_platforms = emptyMap(),
                )
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()

            assertTrue(viewModel.uiState.value.isConnecting)

            advanceUntilIdle()

            verify { AuthManager.setToken("valid-token") }
            verify { AuthManager.setHost("127.0.0.1") }
            verify { AuthManager.setPort(9119) }
            verify { ApiClient.rebuild() }

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertTrue(state.connectionSuccess)
            assertNull(state.errorMessage)
        }

    @Test
    fun testConnect_failure_unauthorized() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("invalid-token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns false
            every { mockResponse.code() } returns 401
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Invalid token (401 Unauthorized)", state.errorMessage)
        }

    @Test
    fun testConnect_failure_forbidden() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("invalid-token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns false
            every { mockResponse.code() } returns 403
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Access denied (403 Forbidden)", state.errorMessage)
        }

    @Test
    fun testConnect_failure_otherHttpCode() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns false
            every { mockResponse.code() } returns 500
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Server returned HTTP 500", state.errorMessage)
        }

    @Test
    fun testConnect_exception_connectionRefused() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            coEvery { mockApiService.getStatus() } throws Exception("Connection refused")

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Connection refused – is Hermes running?", state.errorMessage)
        }

    @Test
    fun testConnect_exception_timeout() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            coEvery { mockApiService.getStatus() } throws Exception("timeout exception")

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Connection timed out", state.errorMessage)
        }

    @Test
    fun testConnect_exception_unknown() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")

            coEvery { mockApiService.getStatus() } throws Exception("Something went wrong")

            viewModel.connect()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isConnecting)
            assertFalse(state.connectionSuccess)
            assertEquals("Connection failed: Something went wrong", state.errorMessage)
        }

    @Test
    fun testOnPairingString_malformedBase64_showsError() {
        val viewModel = ConnectViewModel(mockApp)

        // Mock Base64.decode to throw IllegalArgumentException for an invalid short string
        every { android.util.Base64.decode(any<String>(), any()) } throws IllegalArgumentException("bad base64")

        viewModel.onPairingString("short-invalid-string")

        val state = viewModel.uiState.value
        assertEquals("Malformed pairing string — expected URL or Base64-encoded JSON", state.errorMessage)
    }

    @Test
    fun testOnPairingString_malformedBase64_validRawToken() {
        val viewModel = ConnectViewModel(mockApp)

        // Mock Base64.decode to throw IllegalArgumentException
        every { android.util.Base64.decode(any<String>(), any()) } throws IllegalArgumentException("bad base64")

        // A string that is >= 32 chars and alphanumeric matching the raw token regex
        val validToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        viewModel.onPairingString(validToken)

        val state = viewModel.uiState.value
        assertEquals(validToken, state.token)
        assertNull(state.errorMessage)
    }

    @Test
    fun testSelectProfile_updatesState() {
        val profile =
            com.m57.hermescontrol.data.model.ConnectionProfile(
                id = "test-id",
                name = "Test Profile",
                host = "192.168.1.100",
                port = 8080,
            )
        every { AuthManager.getProfileToken("test-id") } returns "profile-token"

        val viewModel = ConnectViewModel(mockApp)
        viewModel.selectProfile(profile)

        val state = viewModel.uiState.value
        assertEquals("Test Profile", state.profileName)
        assertEquals("192.168.1.100", state.host)
        assertEquals("8080", state.port)
        assertEquals("profile-token", state.token)
        assertEquals(profile, state.selectedProfile)

        verify { AuthManager.setSelectedProfileId("test-id") }
    }

    @Test
    fun testSelectProfile_null_clearsState() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.selectProfile(null)

        val state = viewModel.uiState.value
        assertEquals("", state.profileName)
        assertEquals("127.0.0.1", state.host)
        assertEquals("9119", state.port)
        assertEquals("", state.token)
        assertNull(state.selectedProfile)

        verify { AuthManager.setSelectedProfileId(null) }
    }

    @Test
    fun testConnect_saveProfile_savesAndSelects() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("valid-token")
            viewModel.onHostChange("127.0.0.1")
            viewModel.onPortChange("9119")
            viewModel.onProfileNameChange("New Profile")
            viewModel.onSaveProfileChange(true)

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body() } returns
                StatusResponse(
                    version = "1.0",
                    gateway_running = true,
                    active_sessions = 1,
                    auth_required = false,
                    gateway_platforms = emptyMap(),
                )
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()
            advanceUntilIdle()

            verify { AuthManager.saveConnectionProfiles(any()) }
            verify { AuthManager.setSelectedProfileId(any()) }
            verify { AuthManager.setProfileToken(any(), "valid-token") }
            verify { ApiClient.rebuild() }

            val state = viewModel.uiState.value
            assertTrue(state.connectionSuccess)
        }

    // ── TEST-06: Profile edge cases ─────────────────────────────────────

    @Test
    fun testLoadSavedValues_withProfiles_loadsSelected() {
        val profile =
            com.m57.hermescontrol.data.model.ConnectionProfile(
                id = "prof-1",
                name = "Work",
                host = "10.0.0.1",
                port = 9119,
            )
        every { AuthManager.getToken() } returns ""
        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.getConnectionProfiles() } returns listOf(profile)
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = ConnectViewModel(mockApp)
        val state = viewModel.uiState.value

        assertEquals("Work", state.profileName)
        assertEquals("10.0.0.1", state.host)
        assertEquals("9119", state.port)
        assertEquals(profile, state.selectedProfile)
        assertEquals(1, state.profiles.size)
    }

    @Test
    fun testLoadSavedValues_withStaleSelectedId_fallsBackToDefaults() {
        val profile =
            com.m57.hermescontrol.data.model.ConnectionProfile(
                id = "prof-1",
                name = "Work",
                host = "10.0.0.1",
                port = 9119,
            )
        every { AuthManager.getConnectionProfiles() } returns listOf(profile)
        every { AuthManager.getSelectedProfileId() } returns "nonexistent-id"

        val viewModel = ConnectViewModel(mockApp)
        val state = viewModel.uiState.value

        assertNull(state.selectedProfile)
        assertEquals("", state.profileName)
        assertEquals("127.0.0.1", state.host)
    }

    @Test
    fun testSelectProfile_withoutToken_usesEmptyString() {
        every { AuthManager.getProfileToken(any()) } returns null

        val profile =
            com.m57.hermescontrol.data.model.ConnectionProfile(
                id = "prof-2",
                name = "NoToken",
                host = "10.0.0.2",
                port = 9220,
            )
        val viewModel = ConnectViewModel(mockApp)
        viewModel.selectProfile(profile)

        assertEquals("", viewModel.uiState.value.token)
        assertEquals("NoToken", viewModel.uiState.value.profileName)
    }

    @Test
    fun testConnect_withoutSaveProfile_clearsSelectedProfile() =
        runTest {
            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("standalone-token")
            viewModel.onHostChange("10.0.0.1")
            viewModel.onPortChange("9119")
            viewModel.onSaveProfileChange(false)

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body() } returns
                StatusResponse(
                    version = "1.0",
                    gateway_running = true,
                    active_sessions = 0,
                    auth_required = false,
                    gateway_platforms = emptyMap(),
                )
            coEvery { mockApiService.getStatus() } returns mockResponse

            viewModel.connect()
            advanceUntilIdle()

            verify { AuthManager.setSelectedProfileId(null) }
            verify { AuthManager.setToken("standalone-token") }
            verify { AuthManager.setHost("10.0.0.1") }
            verify(exactly = 0) { AuthManager.saveConnectionProfiles(any()) }
        }

    @Test
    fun testOnProfileNameChange_updatesState() {
        val viewModel = ConnectViewModel(mockApp)
        viewModel.onProfileNameChange("My Profile")
        assertEquals("My Profile", viewModel.uiState.value.profileName)
    }

    @Test
    fun testOnSaveProfileChange_togglesFlag() {
        val viewModel = ConnectViewModel(mockApp)
        assertFalse(viewModel.uiState.value.saveProfile)
        viewModel.onSaveProfileChange(true)
        assertTrue(viewModel.uiState.value.saveProfile)
        viewModel.onSaveProfileChange(false)
        assertFalse(viewModel.uiState.value.saveProfile)
    }

    @Test
    fun testMultipleProfiles_loadedOnInit() {
        val profiles =
            listOf(
                com.m57.hermescontrol.data.model.ConnectionProfile("a", "Alpha", "10.0.0.1", 9119),
                com.m57.hermescontrol.data.model.ConnectionProfile("b", "Beta", "10.0.0.2", 9220),
            )
        every { AuthManager.getConnectionProfiles() } returns profiles
        every { AuthManager.getSelectedProfileId() } returns null

        val viewModel = ConnectViewModel(mockApp)
        assertEquals(2, viewModel.uiState.value.profiles.size)
        assertEquals("Alpha", viewModel.uiState.value.profiles[0].name)
        assertEquals("Beta", viewModel.uiState.value.profiles[1].name)
    }

    @Test
    fun testOnPairingString_hermesUrl_parsesAndConnects() =
        runTest {
            // Stub the Uri mock for this test
            every { android.net.Uri.parse(any()) } answers {
                val uri = mockk<android.net.Uri>(relaxed = true)
                every { uri.getQueryParameter("host") } returns "192.168.1.1"
                every { uri.getQueryParameter("port") } returns "8888"
                every { uri.getQueryParameter("token") } returns "abc123"
                uri
            }

            val mockResponse = mockk<Response<StatusResponse>>()
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body() } returns
                StatusResponse(
                    version = "1.0",
                    gateway_running = true,
                    active_sessions = 0,
                    auth_required = false,
                    gateway_platforms = emptyMap(),
                )
            coEvery { mockApiService.getStatus() } returns mockResponse

            val viewModel = ConnectViewModel(mockApp)
            viewModel.onPairingString("hermes://connect?host=192.168.1.1&port=8888&token=abc123")

            val state = viewModel.uiState.value
            assertEquals("192.168.1.1", state.host)
            assertEquals("8888", state.port)
            assertEquals("abc123", state.token)
            // Should have triggered connect
            advanceUntilIdle()
            assertTrue("connection should succeed after pairing", viewModel.uiState.value.connectionSuccess)
        }
}
