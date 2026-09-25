package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import com.m57.hermescontrol.data.ws.CommandCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerReadinessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun draftRemainsEditableAndSendStaysDisabledUntilReady() {
        val ready = mutableStateOf(false)
        var sends = 0
        compose.setContent {
            var input by remember { mutableStateOf(TextFieldValue("")) }
            ChatInputBar(
                inputFieldValue = input,
                onInputChange = { input = it },
                onSend = { sends++ },
                onMicTap = {},
                isListening = false,
                isAgentTyping = true,
                canInterrupt = ready.value,
                isConnected = true,
                commandCatalog = CommandCatalog(),
                isSessionReady = ready.value,
            )
        }
        compose.onNodeWithTag("chat_input").performTextInput("held draft")
        compose.onNodeWithTag("chat_input").assertTextEquals("held draft")
        compose.onNodeWithTag("chat_session_preparing").assertExists()
        // Nothing to interrupt while the session prepares: the action button
        // must not offer Stop (review, PR #1250).
        compose.onNodeWithTag("stop_button").assertDoesNotExist()
        compose.onNodeWithTag("send_button").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, sends) }
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag("chat_session_preparing").assertDoesNotExist()
        // Ready + typing = an interruptible generation: Stop replaces the
        // action glyph while queue-send stays available in the flat slot.
        compose.onNodeWithTag("stop_button").assertExists()
        compose.onNodeWithTag("send_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.runOnIdle { ready.value = false }
        compose.onNodeWithTag("stop_button").assertDoesNotExist()
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        compose.onNodeWithTag("chat_input").assertTextEquals("held draft")
    }
}
