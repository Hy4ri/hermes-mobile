package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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
}
