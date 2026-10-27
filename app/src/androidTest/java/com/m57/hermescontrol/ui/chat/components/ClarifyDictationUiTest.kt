package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ClarifyUi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClarifyDictationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @Test
    fun dictationLandsInTheClarifyField() {
        compose.setContent {
            HermesControlTheme {
                ClarifyBubble(
                    clarifyRequest =
                        ClarifyUi(
                            text = "Which database should the migration run against?",
                            options = listOf("Staging", "Production"),
                            clarifyId = "req-dictate",
                        ),
                    onRespondSingle = {},
                    onRespondBatch = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("clarify_dictate_button").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            // While the recognizer is on top there is no Compose hierarchy to query.
            runCatching {
                compose
                    .onAllNodes(hasSetTextAction() and hasText(FakeSpeechRecognizerActivity.TRANSCRIPT))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }
}
