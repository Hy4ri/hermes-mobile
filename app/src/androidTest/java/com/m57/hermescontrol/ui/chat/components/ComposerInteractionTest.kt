package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.ws.CommandCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Interaction coverage for the chat composer controls: the morphing action
 * button (dictate / send), stopping dictation from either mic position, and
 * both halves of the combined model/reasoning pill.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ComposerInteractionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var micTaps = 0
    private var sends = 0
    private var modelTaps = 0
    private var selectedLevel: String? = null

    /** Renders the real input bar with live text and a mic that toggles like ChatMediaLaunchers. */
    private fun setComposer(reasoningLevel: String? = "medium") {
        composeTestRule.setContent {
            var input by remember { mutableStateOf(TextFieldValue("")) }
            var listening by remember { mutableStateOf(false) }
            ChatInputBar(
                inputFieldValue = input,
                onInputChange = { input = it },
                onSend = {
                    sends++
                    input = TextFieldValue("")
                },
                onMicTap = {
                    micTaps++
                    listening = !listening
                },
                isListening = listening,
                isAgentTyping = false,
                isConnected = true,
                commandCatalog = CommandCatalog(),
                currentSessionModel = "openai/gpt-5.5",
                reasoningLevel = reasoningLevel,
                onModelTap = { modelTaps++ },
                onReasoningTap = { selectedLevel = it },
            )
        }
    }

    @Test
    fun emptyInput_actionButtonStartsDictation() {
        setComposer()

        composeTestRule.onNodeWithTag("send_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mic_button").performClick()

        composeTestRule.runOnIdle {
            assertEquals("action button must start dictation", 1, micTaps)
            assertEquals("action button must not send without input", 0, sends)
        }
    }

    @Test
    fun typedInput_actionButtonSends_andMicStillDictates() {
        setComposer()

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
        composeTestRule.onNodeWithTag("send_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals("send button must send", 1, sends)
            assertEquals("send must not trigger dictation", 0, micTaps)
        }

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Again")
        composeTestRule.onNodeWithTag("mic_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals("mic next to send must start dictation", 1, micTaps)
            assertEquals("mic must not send", 1, sends)
        }
    }

    @Test
    fun emptyInput_stopButtonStopsDictation() {
        setComposer()

        composeTestRule.onNodeWithTag("mic_button").performClick()
        composeTestRule.onNodeWithTag("mic_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mic_stop_button").performClick()

        composeTestRule.onNodeWithTag("mic_stop_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals("start + stop must both reach onMicTap", 2, micTaps) }
    }

    @Test
    fun typedInput_stopButtonStopsDictation_withoutSending() {
        setComposer()

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
        composeTestRule.onNodeWithTag("mic_button").performClick()
        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_stop_button").performClick()

        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals("start + stop must both reach onMicTap", 2, micTaps)
            assertEquals("stopping dictation must not send", 0, sends)
        }
    }

    @Test
    fun modelSideOfPill_opensModelPicker_only() {
        setComposer()

        composeTestRule.onNodeWithTag("model_chip").performClick()

        composeTestRule.onNodeWithText("REASONING").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals("model side must open the picker", 1, modelTaps)
            assertEquals("model side must not change reasoning", null, selectedLevel)
        }
    }

    @Test
    fun reasoningSideOfPill_opensMenu_andSelectsLevel() {
        setComposer(reasoningLevel = "medium")

        composeTestRule.onNodeWithTag("reasoning_chip").performClick()
        composeTestRule.onNodeWithText("REASONING").assertIsDisplayed()
        composeTestRule.onNodeWithText("High").performClick()

        composeTestRule.onNodeWithText("REASONING").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals("reasoning menu must report the picked level", "high", selectedLevel)
            assertEquals("reasoning side must not open the model picker", 0, modelTaps)
        }
    }
}
