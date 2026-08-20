package com.m57.hermescontrol.ui.connect

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.requiresLocalNetworkPermission
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Tests the Android 17 (API 37) ACCESS_LOCAL_NETWORK gate.
 *
 * The gate decision itself is a pure function [requiresLocalNetworkPermission]
 * (covered across the SDK/host matrix below, no mocking needed). The ViewModel
 * integration tests inject the device API level via [ConnectViewModel.sdkVersion]
 * instead of mocking the final static [android.os.Build.VERSION.SDK_INT] field,
 * which MockK cannot intercept in plain JVM unit tests.
 *
 * The runtime permission check ([ContextCompat.checkSelfPermission]) is mocked
 * statically, which is safe for a static *method* (unlike the SDK_INT field).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectPermissionGateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var mockApiService: HermesApiService

    private var permissionGranted = false

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockKAnnotations.init(this)
        mockkObject(AuthManager)
        mockkObject(ApiClient)
        mockkStatic(ContextCompat::class)

        mockApiService = mockk()
        every { ApiClient.hermesApi } returns mockApiService
        every { ApiClient.createTempService(any(), any()) } returns mockApiService
        every { ApiClient.rebuild() } returns Unit

        every { AuthManager.getToken() } returns "tok"
        every { AuthManager.getBaseUrl() } returns "http://192.168.1.50:9119/"
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setBaseUrl(any()) } returns Unit
        every { AuthManager.endpoint() } answers {
            ServerEndpoint.parse("http://192.168.1.50:9119/", CleartextPolicy.ALLOW_WITH_WARNING)
        }
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.setSelectedProfileId(any()) } returns Unit
        every { AuthManager.ensureDefaultSelected() } returns Unit

        every { mockApp.getString(any<Int>()) } returns ""

        every { mockApp.getString(R.string.connect_error_token_required) } returns "Token is required"
        every { mockApp.getString(R.string.connect_error_lan_permission_denied) } returns "LAN denied"

        every {
            ContextCompat.checkSelfPermission(
                mockContext,
                android.Manifest.permission.ACCESS_LOCAL_NETWORK,
            )
        } answers { if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }

        // Successful status probe → connect() proceeds to persist.
        coEvery { mockApiService.getStatus() } returns
            Response.success(
                com.m57.hermescontrol.data.model.StatusResponse(
                    version = "test",
                    gateway_running = true,
                    active_sessions = 0,
                    auth_required = false,
                ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // --- pure gate decision matrix (no mocking) ---

    @Test
    fun `requiresLocalNetworkPermission API 37 LAN host needs permission`() {
        assertTrue(requiresLocalNetworkPermission(37, "http://192.168.1.50:9119"))
    }

    @Test
    fun `requiresLocalNetworkPermission below API 37 LAN host does not need permission`() {
        assertFalse(requiresLocalNetworkPermission(36, "http://192.168.1.50:9119"))
    }

    @Test
    fun `requiresLocalNetworkPermission API 37 loopback host does not need permission`() {
        assertFalse(requiresLocalNetworkPermission(37, "http://127.0.0.1:9119"))
    }

    @Test
    fun `requiresLocalNetworkPermission API 37 public host does not need permission`() {
        assertFalse(requiresLocalNetworkPermission(37, "https://gateway.example.com"))
    }

    // --- ViewModel integration (API level injected via sdkVersion) ---

    @Test
    fun `API 37 + LAN host + no permission surfaces lanPermissionNeeded`() =
        runTest {
            permissionGranted = false
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 37
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.lanPermissionNeeded)
        }

    @Test
    fun `API 37 + LAN host + granted permission connects directly`() =
        runTest {
            permissionGranted = true
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 37
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.lanPermissionNeeded)
            assertTrue(vm.uiState.value.connectionSuccess)
        }

    @Test
    fun `API 37 + loopback host connects without permission prompt`() =
        runTest {
            permissionGranted = false
            every { AuthManager.getBaseUrl() } returns "http://127.0.0.1:9119/"
            every { AuthManager.endpoint() } answers {
                ServerEndpoint.parse("http://127.0.0.1:9119/", CleartextPolicy.ALLOW_WITH_WARNING)
            }
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 37
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.lanPermissionNeeded)
            assertTrue(vm.uiState.value.connectionSuccess)
        }

    @Test
    fun `below API 37 LAN host connects without permission prompt`() =
        runTest {
            permissionGranted = false
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 36
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.lanPermissionNeeded)
            assertTrue(vm.uiState.value.connectionSuccess)
        }

    @Test
    fun `denied permission sets lanPermissionNeeded false and error message`() =
        runTest {
            permissionGranted = false
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 37
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.lanPermissionNeeded)
            // User denies → launcher reports false
            vm.onLanPermissionResult(false)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.lanPermissionNeeded)
            assertEquals("LAN denied", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.connectionSuccess)
        }

    @Test
    fun `granted after prompt connects`() =
        runTest {
            permissionGranted = false
            val vm = ConnectViewModel(mockApp)
            vm.sdkVersion = 37
            vm.requestConnect(mockContext)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.lanPermissionNeeded)
            // User grants → launcher reports true → connect proceeds
            permissionGranted = true
            vm.onLanPermissionResult(true)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.connectionSuccess)
        }
}
