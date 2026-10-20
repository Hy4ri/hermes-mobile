package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.authlogin.AuthProviderOption
import com.m57.hermescontrol.ui.authlogin.BrowserLoginResult
import com.m57.hermescontrol.ui.authlogin.GatewayAuthInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReloginControllerTest {
    private val oidc = AuthProviderOption("self-hosted", "Zitadel", supportsPassword = false)
    private val basic = AuthProviderOption("basic", "Password", supportsPassword = true)

    private class FakeBackend(
        var info: GatewayAuthInfo?,
        var last: String? = null,
    ) : ReloginBackend {
        val passwordCalls = mutableListOf<Triple<String, String, String>>()
        val browserCalls = mutableListOf<String>()
        var passwordResult: String? = null
        var browserResult = CompletableDeferred<BrowserLoginResult>()

        override suspend fun probe() = info

        override suspend fun lastProvider() = last

        override suspend fun passwordLogin(
            username: String,
            password: String,
            provider: String,
        ): String? {
            passwordCalls += Triple(username, password, provider)
            return passwordResult
        }

        override suspend fun browserLogin(
            provider: String,
            openBrowser: (String) -> Unit,
        ): BrowserLoginResult {
            browserCalls += provider
            openBrowser("https://gw/auth/native/authorize")
            return browserResult.await()
        }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private var authenticated = 0

    private fun controller(backend: FakeBackend) =
        ChatReloginController(
            scope = scope,
            backend = backend,
            browserErrorText = { if (it is BrowserLoginResult.TimedOut) "timed out" else null },
            onAuthenticated = { authenticated++ },
        )

    @Test
    fun oidcOnlyGatewayOffersBrowserSignInNotPasswordForm() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(oidc), browserSupported = true))
        val c = controller(backend)

        c.open()
        scope.advanceUntilIdle()

        val state = c.state.value
        assertTrue(state.visible)
        assertFalse(state.usesPassword)
        assertEquals("self-hosted", state.selectedProvider)

        val opened = mutableListOf<String>()
        c.startBrowser { opened += it }
        scope.advanceUntilIdle()
        assertTrue(c.state.value.browserWaiting)
        assertEquals(listOf("self-hosted"), backend.browserCalls)
        assertEquals(1, opened.size)

        backend.browserResult.complete(BrowserLoginResult.Success("ticket"))
        scope.advanceUntilIdle()
        assertFalse(c.state.value.visible)
        assertEquals(1, authenticated)
    }

    @Test
    fun passwordFormIsDisabledForBrowserProvider() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(oidc), browserSupported = true))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        c.submitPassword("u", "p")
        scope.advanceUntilIdle()

        assertTrue(backend.passwordCalls.isEmpty())
    }

    @Test
    fun preselectsTheProviderTheExpiredSessionUsed() {
        val backend =
            FakeBackend(GatewayAuthInfo(true, listOf(basic, oidc), browserSupported = true), last = "self-hosted")
        val c = controller(backend)

        c.open()
        scope.advanceUntilIdle()

        assertEquals("self-hosted", c.state.value.selectedProvider)
        assertFalse(c.state.value.usesPassword)
    }

    @Test
    fun passwordLoginPostsTheSelectedProviderName() {
        val custom = AuthProviderOption("ldap", "LDAP", supportsPassword = true)
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(custom, oidc), browserSupported = true))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        c.submitPassword("alice", "pw")
        scope.advanceUntilIdle()

        assertEquals(listOf(Triple("alice", "pw", "ldap")), backend.passwordCalls)
        assertFalse(c.state.value.visible)
        assertEquals(1, authenticated)
    }

    @Test
    fun gatewayWithoutProviderListKeepsLegacyBasicPasswordForm() {
        val backend = FakeBackend(GatewayAuthInfo(false, emptyList(), browserSupported = false))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        assertTrue(c.state.value.usesPassword)
        c.submitPassword("alice", "pw")
        scope.advanceUntilIdle()
        assertEquals("basic", backend.passwordCalls.single().third)
    }

    @Test
    fun passwordFailureKeepsDialogOpenWithError() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(basic), browserSupported = true))
        backend.passwordResult = "Invalid username or password (401)"
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        c.submitPassword("alice", "bad")
        scope.advanceUntilIdle()

        assertTrue(c.state.value.visible)
        assertFalse(c.state.value.submitting)
        assertEquals("Invalid username or password (401)", c.state.value.error)
        assertEquals(0, authenticated)
    }

    @Test
    fun browserFailureShowsErrorAndAllowsRetry() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(oidc), browserSupported = true))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        c.startBrowser {}
        backend.browserResult.complete(BrowserLoginResult.TimedOut)
        scope.advanceUntilIdle()

        assertFalse(c.state.value.browserWaiting)
        assertEquals("timed out", c.state.value.error)
        assertEquals(0, authenticated)
    }

    @Test
    fun oldGatewayWithoutNativeFlowDoesNotStartBrowser() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(oidc), browserSupported = false))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()

        c.startBrowser {}
        scope.advanceUntilIdle()

        assertTrue(backend.browserCalls.isEmpty())
    }

    @Test
    fun unreachableGatewayCanBeRetried() {
        val backend = FakeBackend(info = null)
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()
        assertTrue(c.state.value.unreachable)

        backend.info = GatewayAuthInfo(true, listOf(oidc), browserSupported = true)
        c.retry()
        scope.advanceUntilIdle()

        assertFalse(c.state.value.unreachable)
        assertEquals("self-hosted", c.state.value.selectedProvider)
    }

    @Test
    fun dismissCancelsWaitingBrowserSignIn() {
        val backend = FakeBackend(GatewayAuthInfo(true, listOf(oidc), browserSupported = true))
        val c = controller(backend)
        c.open()
        scope.advanceUntilIdle()
        c.startBrowser {}
        scope.advanceUntilIdle()

        c.dismiss()
        backend.browserResult.complete(BrowserLoginResult.Success("late"))
        scope.advanceUntilIdle()

        assertFalse(c.state.value.visible)
        assertNull(c.state.value.error)
        assertEquals(0, authenticated)
    }
}
