package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.PendingNewChatNavigation
import com.m57.hermescontrol.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TileNavigationGuardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cancellationPreservesWorkAndDoesNotAuthorizeNavigation() {
        var allowed = true
        var cancelled = false
        compose.setContent {
            allowed =
                TileNavigationGuard(PendingNewChatNavigation(1, fromTile = true), true, false, {}) {
                    cancelled = true
                }
        }
        compose.runOnIdle { assertFalse(allowed) }
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle {
            assertTrue(cancelled)
            assertFalse(allowed)
        }
    }

    @Test
    fun rotationPreservesConfirmationAndConsentOnlyAppliesToTheCurrentRequest() {
        val restoration = StateRestorationTester(compose)
        val request = mutableStateOf<PendingNewChatNavigation?>(PendingNewChatNavigation(1, fromTile = true))
        var confirmed = false
        var allowed = true
        restoration.setContent {
            allowed =
                TileNavigationGuard(request.value, true, false, {
                    confirmed = true
                    request.value = null
                }) {}
        }
        compose.runOnIdle { assertFalse(allowed) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertFalse(allowed)
            assertFalse(confirmed)
        }
        compose.onNodeWithText(context.getString(R.string.action_discard)).performClick()
        compose.runOnIdle {
            assertTrue(confirmed)
            assertTrue(allowed)
            request.value = PendingNewChatNavigation(2, fromTile = true)
        }
        compose.runOnIdle { assertFalse(allowed) }
    }

    @Test
    fun recordingOrSessionPreparationDefersEvenAnEmptyComposer() {
        val deferred = mutableStateOf(true)
        var allowed = true
        compose.setContent {
            allowed = TileNavigationGuard(PendingNewChatNavigation(1, fromTile = true), false, deferred.value, {}) {}
        }
        compose.runOnIdle {
            assertFalse(allowed)
            deferred.value = false
        }
        compose.runOnIdle { assertTrue(allowed) }
    }
}
