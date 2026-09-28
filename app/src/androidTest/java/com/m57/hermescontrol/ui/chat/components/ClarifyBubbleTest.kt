package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ClarifyQuestionUi
import com.m57.hermescontrol.ui.chat.ClarifyUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClarifyBubbleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clickingProvidedChoice_invokesOnRespondSingleWithExactChoice() {
        var respondedSingle: String? = null
        var respondedBatch: Map<String, String>? = null
        var dismissed = false

        val clarifyRequest =
            ClarifyUi(
                text = "Which environment should be targeted?",
                options = listOf("Staging", "Production"),
                clarifyId = "req-1",
            )

        compose.setContent {
            HermesControlTheme {
                ClarifyBubble(
                    clarifyRequest = clarifyRequest,
                    onRespondSingle = { respondedSingle = it },
                    onRespondBatch = { respondedBatch = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Staging").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals("Staging", respondedSingle)
            assertNull(respondedBatch)
            assertEquals(false, dismissed)
        }
    }

    @Test
    fun clickingDismiss_invokesOnDismissWithoutResponse() {
        var respondedSingle: String? = null
        var respondedBatch: Map<String, String>? = null
        var dismissed = false

        val dismissLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_dismiss)

        val clarifyRequest =
            ClarifyUi(
                text = "Do you want to run database migrations?",
                options = listOf("Yes", "No"),
                clarifyId = "req-2",
            )

        compose.setContent {
            HermesControlTheme {
                ClarifyBubble(
                    clarifyRequest = clarifyRequest,
                    onRespondSingle = { respondedSingle = it },
                    onRespondBatch = { respondedBatch = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText(dismissLabel).assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(true, dismissed)
            assertNull(respondedSingle)
            assertNull(respondedBatch)
        }
    }

    @Test
    fun replacingRequestIdentity_resetsEnteredTextAndSelection() {
        val sharedQuestion =
            ClarifyQuestionUi(
                qid = "q-common",
                question = "Which build targets and extras should be enabled?",
                choices = listOf("APK", "AAB", "Native Symbols"),
                multiSelect = true,
            )

        var currentRequest by mutableStateOf(
            ClarifyUi(
                text = sharedQuestion.question,
                clarifyId = "req-1",
                questions = listOf(sharedQuestion),
            ),
        )

        compose.setContent {
            HermesControlTheme {
                ClarifyBubble(
                    clarifyRequest = currentRequest,
                    onRespondSingle = {},
                    onRespondBatch = {},
                    onDismiss = {},
                )
            }
        }

        compose
            .onNodeWithText("APK")
            .assertIsDisplayed()
            .assertIsNotSelected()
            .performClick()
        compose.onNodeWithText("APK").assertIsSelected()

        val textInputNode = compose.onNode(hasSetTextAction()).assertIsDisplayed()
        textInputNode.performTextInput("--dry-run")
        textInputNode.assertTextEquals("--dry-run")

        // Replace request identity while retaining identical qid, question, and options
        compose.runOnIdle {
            currentRequest =
                ClarifyUi(
                    text = sharedQuestion.question,
                    clarifyId = "req-2",
                    questions = listOf(sharedQuestion),
                )
        }

        // Selected choice and typed text must reset under new clarifyId
        compose.onNodeWithText("APK").assertIsDisplayed().assertIsNotSelected()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals("")
    }
}
