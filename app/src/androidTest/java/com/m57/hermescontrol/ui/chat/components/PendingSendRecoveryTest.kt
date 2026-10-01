package com.m57.hermescontrol.ui.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingSendRecoveryTest {
    @get:Rule
    val compose = createComposeRule()

    private val uncertain =
        PendingSend(
            id = "uncertain",
            scope = "test",
            sessionId = "session",
            text = "Possibly delivered",
            mode = BusySendMode.QUEUE,
            state = PendingSendState.UNKNOWN,
        )

    @Test
    fun uncertainRetryRequiresExplicitConfirmation() {
        var retried: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(listOf(uncertain), true, false, { retried = it }, {})
            }
        }
        compose.onNodeWithTag("pending_send_recovery").assertIsDisplayed().performClick()
        compose.onNodeWithText("Possibly delivered").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry_uncertain").performClick()
        compose.runOnIdle { assertNull(retried) }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(retried) }
        compose.onNodeWithTag("pending_retry_uncertain").performClick()
        compose.onNodeWithTag("pending_retry_confirm").performClick()
        compose.runOnIdle { assertEquals("uncertain", retried) }
    }

    @Test
    fun disconnectedSessionCanInspectAndDismissButCannotRetry() {
        var dismissed: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(listOf(uncertain), false, false, {}, { dismissed = it })
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithTag("pending_retry_uncertain").assertIsNotEnabled()
        compose.onNodeWithTag("pending_discard_uncertain").performClick()
        compose.runOnIdle { assertNull(dismissed) }
        compose
            .onNodeWithText(
                "Remove this message from the device queue? This does not cancel work or delete messages already received by the server.",
            ).assertIsDisplayed()
        compose.onNodeWithTag("pending_discard_confirm").performClick()
        compose.runOnIdle { assertEquals("uncertain", dismissed) }
    }

    @Test
    fun emptyOutboxDoesNotAddAComposerControl() {
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(emptyList(), true, false, {}, {})
            }
        }
        compose.onNodeWithTag("pending_send_recovery").assertDoesNotExist()
    }

    @Test
    fun longUncertainMessageKeepsConfirmationActionsVisible() {
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(
                    listOf(uncertain.copy(text = "Long diagnostic text ".repeat(200))),
                    true,
                    false,
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithTag("pending_retry_uncertain").performScrollTo().performClick()
        compose.onNodeWithTag("pending_retry_confirm").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
