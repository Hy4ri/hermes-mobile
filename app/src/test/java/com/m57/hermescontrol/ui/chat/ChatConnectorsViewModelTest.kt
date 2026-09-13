package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectorsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockDelegate = mockk<ChatConnectorsDelegate>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ActiveSessionHolder.clear()
        HermesWsClient.setConnectionStatusForTest(ConnectionStatus.DISCONNECTED)
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
        HermesWsClient.setConnectionStatusForTest(ConnectionStatus.DISCONNECTED)
        AuthManager.resetAuthStateForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialSync_triggersOnActiveSessionChangedWithInitialSession() {
        ActiveSessionHolder.set("sess-init")

        ChatConnectorsViewModel(delegate = mockDelegate)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { mockDelegate.onActiveSessionChanged("sess-init") }
    }

    @Test
    fun testSessionChange_triggersOnActiveSessionChanged() {
        ActiveSessionHolder.set("sess-1")

        ChatConnectorsViewModel(delegate = mockDelegate)
        testDispatcher.scheduler.advanceUntilIdle()

        ActiveSessionHolder.set("sess-2")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { mockDelegate.onActiveSessionChanged("sess-2") }
    }

    @Test
    fun testProfileChange_triggersOnActiveSessionChanged() {
        ActiveSessionHolder.set("sess-1")

        ChatConnectorsViewModel(delegate = mockDelegate)
        testDispatcher.scheduler.advanceUntilIdle()

        AuthManager.setActiveProfileId("profile-new")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(atLeast = 1) { mockDelegate.onActiveSessionChanged("sess-1") }
    }

    @Test
    fun testServerChange_triggersOnActiveSessionChanged() {
        ActiveSessionHolder.set("sess-1")

        ChatConnectorsViewModel(delegate = mockDelegate)
        testDispatcher.scheduler.advanceUntilIdle()

        AuthManager.setBaseUrlForTest("http://192.168.1.199:8080")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(atLeast = 1) { mockDelegate.onActiveSessionChanged("sess-1") }
    }

    @Test
    fun testReconnect_triggersOnTransportReconnected() {
        ChatConnectorsViewModel(delegate = mockDelegate)
        testDispatcher.scheduler.advanceUntilIdle()

        HermesWsClient.setConnectionStatusForTest(ConnectionStatus.CONNECTED)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { mockDelegate.onTransportReconnected() }
    }

    @Test
    fun testForwardsUiMethodsToDelegate() {
        val uiStateFlow = MutableStateFlow(ChatConnectorsUiState(isVisible = true))
        val delegate =
            mockk<ChatConnectorsDelegate>(relaxed = true) {
                io.mockk.every { uiState } returns uiStateFlow
                io.mockk.every { takeBrowserEvent(any()) } returns
                    ConnectBrowserEvent(
                        slug = "slug",
                        url = "https://auth.example.com",
                    )
            }

        val viewModel = ChatConnectorsViewModel(delegate = delegate)

        assertEquals(true, viewModel.uiState.value.isVisible)

        viewModel.show()
        verify { delegate.show() }

        viewModel.hide()
        verify { delegate.hide() }

        viewModel.onResume()
        verify { delegate.onResume() }

        viewModel.onPause()
        verify { delegate.onPause() }

        viewModel.refresh(force = true)
        verify { delegate.refresh(force = true) }

        viewModel.connect("github", reconnect = true)
        verify { delegate.connect("github", reconnect = true) }

        val event = viewModel.takeBrowserEvent(123L)
        assertEquals("slug", event?.slug)
        verify { delegate.takeBrowserEvent(123L) }

        viewModel.launchError("test error")
        verify { delegate.launchError("test error") }

        viewModel.clearError()
        verify { delegate.clearError() }
    }
}
