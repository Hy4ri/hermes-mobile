package com.m57.hermescontrol.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingSendPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun row(state: PendingSendState) =
        PendingSend("receipt", "scope", "session", "My message", mode = BusySendMode.CORRECT, state = state)

    @Test
    fun ordinarySendNeverShowsDeliveryPanel() {
        val state = mutableStateOf(PendingSendState.SENDING)
        compose.setContent { PendingSendPanel(listOf(row(state.value)), onSendNow = {}) }
        compose.onNodeWithText("My message").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_sends)).assertDoesNotExist()
        compose.runOnIdle { state.value = PendingSendState.ACCEPTED }
        compose.onNodeWithText("My message").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_accepted)).assertDoesNotExist()
    }

    @Test
    fun queueDisappearsWhenDispatchedWithoutAwaitingLabel() {
        val state = mutableStateOf(PendingSendState.QUEUED)
        compose.setContent { PendingSendPanel(listOf(row(state.value)), onSendNow = {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_queued)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_send_now_warning)).assertDoesNotExist()
        compose.runOnIdle { state.value = PendingSendState.SENDING }
        compose.onNodeWithText("My message").assertDoesNotExist()
        compose.runOnIdle { state.value = PendingSendState.ACCEPTED }
        compose.onNodeWithText("My message").assertDoesNotExist()
    }

    @Test
    fun failedAndUncertainDeliveryRemainRecoverable() {
        val state = mutableStateOf(PendingSendState.REJECTED)
        var retried: String? = null
        compose.setContent { PendingSendPanel(listOf(row(state.value)), onSendNow = { retried = it }) }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_rejected)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_send_now)).performClick()
        compose.runOnIdle {
            assertEquals("receipt", retried)
            state.value = PendingSendState.UNKNOWN
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_unknown)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_pending_send_now_warning)).assertIsDisplayed()
    }
}
