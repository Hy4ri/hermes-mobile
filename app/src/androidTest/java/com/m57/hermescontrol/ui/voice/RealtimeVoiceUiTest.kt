package com.m57.hermescontrol.ui.voice

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
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeVoiceUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun globalEntryWithoutChatSessionPreservesDraftAndLeavesDictationSeparateAtNarrowWidth() {
        // UI-only: no saved credentials, network calls, permission grants, or microphone capture.
        var dictationTaps = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    val input = rememberTextFieldState()
                    var voice by remember { mutableStateOf(false) }
                    ChatInputBar(
                        inputState = input,
                        onSend = {},
                        onMicTap = { dictationTaps++ },
                        isListening = false,
                        isAgentTyping = false,
                        isConnected = true,
                        commandCatalog = CommandCatalog(),
                        isSessionReady = false,
                        realtimeEnabled = true,
                        onRealtimeTap = { voice = true },
                    )
                    if (voice) {
                        Dialog(onDismissRequest = { voice = false }) {
                            Box(Modifier.width(320.dp)) {
                                RealtimeTalkContent(
                                    state = TalkCallState(),
                                    permissionDenied = true,
                                    backgroundEnded = false,
                                    onClose = { voice = false },
                                    onPermission = {},
                                    onReconnect = {},
                                    onMute = {},
                                    onInterrupt = {},
                                    onStop = {},
                                    onResult = {},
                                )
                            }
                        }
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
        compose.onNodeWithText("Hermes Talk").assertIsDisplayed()
        compose
            .onNodeWithText(
                "Shared Hermes Talk is not bound to the current Bot or chat session. " +
                    "Voice transcripts stay in this call, not in chat.",
            ).assertIsDisplayed()
        compose.onNodeWithText("End voice").performScrollTo().performClick()
        compose.onNodeWithTag("chat_input").assertTextEquals("keep this draft")
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, dictationTaps) }
    }

    @Test
    fun microphoneAndVoiceCancellationDoNotStopGlobalWork() {
        val job =
            Json
                .parseToJsonElement(
                    """{"runId":42,"kind":"hermes","status":"running","output":""}""",
                ).jsonObject
        val completed =
            Json
                .parseToJsonElement(
                    """{"runId":41,"kind":"hermes","status":"completed","output":"Review complete."}""",
                ).jsonObject
        var interrupted = 0
        var ended = 0
        var resultRun = ""
        var stopped = false
        compose.setContent {
            var muted by remember { mutableStateOf(false) }
            RealtimeTalkContent(
                state = TalkCallState(phase = "listening", muted = muted, jobs = listOf(job, completed)),
                permissionDenied = false,
                backgroundEnded = false,
                onClose = { ended++ },
                onPermission = {},
                onReconnect = {},
                onMute = { muted = !muted },
                onInterrupt = { interrupted++ },
                onStop = {
                    assertEquals(job, it)
                    stopped = true
                },
                onResult = { resultRun = it["runId"].toString() },
            )
        }
        compose.onNodeWithText("Hermes Talk").assertIsDisplayed()
        compose.onNodeWithText("Reconnect voice").assertIsNotEnabled()
        compose.onNodeWithText("Mute").performClick()
        compose.onNodeWithText("Unmute").assertIsDisplayed()
        compose.onNodeWithText("Cancel voice response").performScrollTo().performClick()
        compose.onNodeWithText("End voice").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, interrupted)
            assertEquals(1, ended)
            assertEquals(false, stopped)
        }
        compose.onNodeWithText("Run #42 · hermes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View result").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("41", resultRun)
            assertEquals(false, stopped)
        }
        compose.onNodeWithText("Stop Work").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, stopped) }
    }
}
