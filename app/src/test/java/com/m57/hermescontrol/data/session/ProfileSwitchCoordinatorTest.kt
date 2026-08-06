package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The switch coordinator is the single atomic profile-switch flow — every
 * surface (Profiles screen, future quick-switch) routes through it. These
 * tests pin the ORDER of operations, because chat's fresh-session behavior
 * depends on the switch broadcast landing BEFORE the socket re-dial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchCoordinatorTest {
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        // NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock
        // bleeds into later test classes in the same JVM (it hijacks
        // Dispatchers.IO for HermesWsClient's reconnect coroutines), which
        // deterministically broke HermesWsClientTest.testAutoReconnect in
        // full-suite runs. Real Dispatchers.IO is fine: the network layer is
        // mocked, so withContext(Dispatchers.IO) hops are instant.

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.setSelectedProfileId(any()) } returns Unit

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `success flips server then persists selection then re-dials socket`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.setActiveProfile(SetActiveProfileRequest("meow")) }
            verify(ordering = Ordering.SEQUENCE) {
                AuthManager.setSelectedProfileId("meow")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `success broadcasts the switch so chat wipes before the re-dial`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.switched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchProfile("meow")
            runCurrent()

            // The broadcast is buffered with capacity 1, so chat observes the
            // wipe BEFORE gateway.ready arrives on the re-dialed socket and
            // auto-creates the fresh session (desktop requestFreshSession).
            assertEquals("meow", received.tryReceive().getOrNull())
        }

    @Test
    fun `failure touches nothing`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Failure)
            verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
            verify(exactly = 0) { HermesWsClient.disconnect() }
            verify(exactly = 0) { HermesWsClient.connect() }
        }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))
}
