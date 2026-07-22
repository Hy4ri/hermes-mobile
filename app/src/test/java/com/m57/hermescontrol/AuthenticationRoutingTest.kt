package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.local.AuthSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthenticationRoutingTest {
    @Before
    fun setup() {
        AuthSessionState.resetForTest()
    }

    @After
    fun teardown() {
        AuthSessionState.resetForTest()
        NavigationController.backStack = null
    }

    @Test
    fun testExpiryPropagationState() {
        assertFalse(AuthSessionState.signInRequired.value)

        AuthSessionState.requireSignIn()
        assertTrue(AuthSessionState.signInRequired.value)

        AuthSessionState.markAuthenticated()
        assertFalse(AuthSessionState.signInRequired.value)
    }

    @Test
    fun testGoBackFallbackGuardsChatWhenSignInRequired() {
        val stack = NavBackStack<NavKey>(AuthLoginScreen)
        NavigationController.backStack = stack

        AuthSessionState.requireSignIn()
        assertTrue(AuthSessionState.signInRequired.value)

        // Attempting to go back from AuthLoginScreen when single element stack
        NavigationController.goBack(fallback = ChatScreen)

        // Must fall back to LandingScreen, not ChatScreen
        assertEquals(LandingScreen, stack.lastOrNull())
    }

    @Test
    fun testGoBackClearsToLandingWhenSignInRequiredAndExposingProtectedScreen() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        stack.add(AuthLoginScreen)
        NavigationController.backStack = stack

        AuthSessionState.requireSignIn()
        assertTrue(AuthSessionState.signInRequired.value)

        // Attempting to pop AuthLoginScreen when ChatScreen is underneath
        NavigationController.goBack()

        // Must not expose ChatScreen
        assertEquals(LandingScreen, stack.lastOrNull())
    }
}
