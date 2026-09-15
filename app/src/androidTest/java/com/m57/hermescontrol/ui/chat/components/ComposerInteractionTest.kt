package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    private fun setComposer(
        reasoningLevel: String? = "medium",
        model: String = "openai/gpt-5.5",
        composerWidth: Dp? = null,
        modelAfterTap: String? = null,
    ) {
        composeTestRule.setContent {
            var input by remember { mutableStateOf(TextFieldValue("")) }
            var listening by remember { mutableStateOf(false) }
            var currentModel by remember { mutableStateOf(model) }
            val composer: @Composable () -> Unit = {
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
                    currentSessionModel = currentModel,
                    reasoningLevel = reasoningLevel,
                    onModelTap = {
                        modelTaps++
                        modelAfterTap?.let { currentModel = it }
                    },
                    onReasoningTap = { selectedLevel = it },
                )
            }
            if (composerWidth == null) composer() else Box(Modifier.width(composerWidth)) { composer() }
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

    @Test
    fun longModelName_narrowComposer_scrollsWithoutOpeningPicker_orHidingControls() {
        val longModel = "openrouter/some-extremely-long-model-name-preview-2026-with-extra-characters"
        setComposer(model = longModel, composerWidth = 280.dp)

        composeTestRule.onNodeWithTag("reasoning_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
        val modelText = composeTestRule.onNodeWithText(longModel)
        val leftBefore = modelText.getUnclippedBoundsInRoot().left

        composeTestRule.onNodeWithTag("model_chip").performTouchInput { swipeLeft() }
        val leftAfter = modelText.getUnclippedBoundsInRoot().left

        composeTestRule.runOnIdle {
            assertEquals("swiping the model must not open the picker", 0, modelTaps)
        }
        check(leftAfter < leftBefore) { "model text did not move after horizontal swipe" }
        composeTestRule.onNodeWithTag("reasoning_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
    }

    @Test
    fun shortModelName_wideComposerKeepsPillCompact() {
        setComposer(composerWidth = 420.dp)

        val availableWidth = 420.dp
        val modelChip = composeTestRule.onNodeWithTag("model_chip").getUnclippedBoundsInRoot()
        val reasoningChip = composeTestRule.onNodeWithTag("reasoning_chip").getUnclippedBoundsInRoot()
        val pillWidth = maxOf(modelChip.right, reasoningChip.right) - minOf(modelChip.left, reasoningChip.left)

        check(pillWidth < availableWidth * 0.8f) {
            "short model pill should remain content-sized, width=$pillWidth available=$availableWidth"
        }
    }

    @Test
    fun changingModel_resetsScrolledLabelToTheStart() {
        val firstModel = "openrouter/some-extremely-long-model-name-preview-2026-with-extra-characters"
        val secondModel = "anthropic/another-extremely-long-model-name-preview-2026-with-extra-characters"
        setComposer(model = firstModel, modelAfterTap = secondModel, composerWidth = 280.dp)

        val firstText = composeTestRule.onNodeWithText(firstModel)
        val leftBefore = firstText.getUnclippedBoundsInRoot().left
        composeTestRule.onNodeWithTag("model_chip").performTouchInput { swipeLeft() }
        check(firstText.getUnclippedBoundsInRoot().left < leftBefore) {
            "first model text did not move after horizontal swipe"
        }

        composeTestRule.onNodeWithTag("model_chip").performClick()
        val secondText = composeTestRule.onNodeWithText(secondModel)
        val leftAfterModelChange = secondText.getUnclippedBoundsInRoot().left

        check(leftAfterModelChange >= leftBefore - 1.dp) {
            "new model label should reset to its initial left position"
        }
        composeTestRule.onNodeWithTag("reasoning_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_button").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals("model selection should still invoke the picker", 1, modelTaps)
        }
    }
}
