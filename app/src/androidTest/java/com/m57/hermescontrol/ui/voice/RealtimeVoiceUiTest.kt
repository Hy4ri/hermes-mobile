package com.m57.hermescontrol.ui.voice

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeVoiceUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun realtimeEntryPreservesDraftAndLeavesDictationSeparateAtNarrowWidth() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    val input = rememberTextFieldState()
                    var voice by remember { mutableStateOf(false) }
                    ChatInputBar(
                        inputState = input,
                        onSend = {},
                        onMicTap = {},
                        isListening = false,
                        isAgentTyping = false,
                        isConnected = true,
                        commandCatalog = CommandCatalog(),
                        isSessionReady = true,
                        realtimeEnabled = true,
                        onRealtimeTap = { voice = true },
                    )
                    if (voice) {
                        RealtimeTalkDialog(
                            launch =
                                TalkLaunch(
                                    DataScope("disposable-test", "http://127.0.0.1:1", "alpha"),
                                    "session-a",
                                    "Bot A",
                                ),
                            onDismiss = { voice = false },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_input").performTextInput("keep this draft")
        compose.onNodeWithTag("mic_button").assertExists()
        compose
            .onNodeWithTag("realtime_button")
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("End voice").performClick()
        compose.onNodeWithTag("chat_input").assertTextEquals("keep this draft")
        compose.onNodeWithTag("send_button").assertIsEnabled()
    }

    @Test
    fun canonicalApprovalAndMuteAreDifferentFromStoppingWork() {
        val job =
            Json
                .parseToJsonElement(
                    """{
            "run_id":"run-a","action_id":"action-a","goal":"Bot A work","status":"waiting_for_approval",
            "approval":{"actionable":true,"approvals":[{"request_id":"approval-a","choices":["once","deny"]}]}
        }""",
                ).jsonObject
        var approved = ""
        var stopped = false
        compose.setContent {
            var muted by remember { mutableStateOf(false) }
            RealtimeTalkContent(
                botName = "Bot A",
                state = TalkCallState(phase = "listening", muted = muted, jobs = listOf(job)),
                permissionDenied = false,
                backgroundEnded = false,
                onClose = {},
                onPermission = {},
                onReconnect = {},
                onMute = { muted = !muted },
                onInterrupt = {},
                onApprove = { _, id, choice -> approved = "$id:$choice" },
                onStop = { stopped = true },
                onResult = {},
            )
        }
        compose.onNodeWithText("Bot A").assertIsDisplayed()
        compose.onNodeWithText("Reconnect voice").assertIsNotEnabled()
        compose.onNodeWithText("Mute").performClick()
        compose.onNodeWithText("Unmute").assertIsDisplayed()
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("approval-a:once", approved)
            assertEquals(false, stopped)
        }
        compose.onNodeWithText("Stop Work").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, stopped) }
    }
}
