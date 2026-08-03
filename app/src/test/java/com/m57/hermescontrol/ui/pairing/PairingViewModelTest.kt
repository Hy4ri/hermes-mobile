package com.m57.hermescontrol.ui.pairing

import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingItem
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private fun createViewModel(): PairingViewModel {
        val vm = PairingViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    /**
     * Pump the test scheduler while letting the real Dispatchers.IO hops
     * (safeLaunchLoad / withContext(IO)) land their resumptions.
     */
    private fun settle() {
        repeat(20) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubPairing(
        pending: List<PairingItem> = emptyList(),
        approved: List<PairingItem> = emptyList(),
    ) {
        coEvery { mockApi.getPairing() } returns
            Response.success(PairingResponse(pending = pending, approved = approved))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadPairing populates pending and approved lists`() {
        stubPairing(
            pending =
                listOf(
                    PairingItem(
                        platform = "telegram",
                        requestId = "req-1",
                        userId = "u1",
                        userName = "bob",
                        ageMinutes = 5,
                    ),
                ),
            approved = listOf(PairingItem(platform = "discord", userId = "u2", userName = "alice")),
        )
        val vm = createViewModel()

        vm.loadPairing()
        settle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(1, state.pairing?.pending?.size)
        assertEquals("bob", state.pairing?.pending?.first()?.userName)
        assertEquals(1, state.pairing?.approved?.size)
        assertEquals("alice", state.pairing?.approved?.first()?.userName)
    }

    @Test
    fun `loadPairing failure surfaces error message`() {
        // 404: backend error detail surfaces; no 5xx/429 retry so the test stays fast.
        coEvery { mockApi.getPairing() } returns
            Response.error(404, "{\"detail\":\"boom\"}".toResponseBody(null))
        val vm = createViewModel()

        vm.loadPairing()
        settle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.pairing)
        assertTrue(state.errorMessage?.contains("Failed to load pairing") == true)
    }

    @Test
    fun `approvePairing sends request id and reloads the list`() {
        val requestSlot = slot<PairingApproveRequest>()
        coEvery { mockApi.approvePairing(capture(requestSlot)) } returns Response.success(Unit)
        stubPairing()
        val vm = createViewModel()

        vm.approvePairing("telegram", "req-1")
        settle()

        assertEquals("telegram", requestSlot.captured.platform)
        assertEquals("req-1", requestSlot.captured.requestId)
        assertNull(requestSlot.captured.code)
        coVerify(exactly = 1) { mockApi.getPairing() }
        assertEquals("Pairing request approved", vm.uiState.value.toastMessage)
    }

    @Test
    fun `revokePairing sends platform and user id then reloads`() {
        val requestSlot = slot<PairingRevokeRequest>()
        coEvery { mockApi.revokePairing(capture(requestSlot)) } returns Response.success(Unit)
        stubPairing()
        val vm = createViewModel()

        vm.revokePairing("discord", "u2")
        settle()

        assertEquals("discord", requestSlot.captured.platform)
        assertEquals("u2", requestSlot.captured.userId)
        coVerify(exactly = 1) { mockApi.getPairing() }
        assertTrue(vm.uiState.value.toastMessage?.contains("u2") == true)
    }

    @Test
    fun `clearPending calls clear endpoint and reloads`() {
        coEvery { mockApi.clearPendingPairing() } returns Response.success(Unit)
        stubPairing()
        val vm = createViewModel()

        vm.clearPending()
        settle()

        coVerify(exactly = 1) { mockApi.clearPendingPairing() }
        coVerify(exactly = 1) { mockApi.getPairing() }
        assertEquals("Pending requests cleared", vm.uiState.value.toastMessage)
    }

    @Test
    fun `action failure shows toast and clears action key`() {
        // 404: backend error detail surfaces; no 5xx/429 retry so the test stays fast.
        coEvery { mockApi.approvePairing(any()) } returns
            Response.error(
                404,
                "{\"detail\":\"Pairing request or code not found or expired for platform 'telegram'.\"}".toResponseBody(
                    null,
                ),
            )
        stubPairing()
        val vm = createViewModel()

        vm.approvePairing("telegram", "req-1")
        settle()

        val state = vm.uiState.value
        assertNull(state.actionKey)
        assertTrue(state.toastMessage?.contains("Action failed") == true)
        assertTrue(state.toastMessage?.contains("not found or expired") == true)
    }

    @Test
    fun `clearToast resets toast message`() {
        coEvery { mockApi.approvePairing(any()) } returns Response.success(Unit)
        stubPairing()
        val vm = createViewModel()
        vm.approvePairing("telegram", "req-1")
        settle()
        assertTrue(vm.uiState.value.toastMessage != null)

        vm.clearToast()

        assertNull(vm.uiState.value.toastMessage)
    }
}
