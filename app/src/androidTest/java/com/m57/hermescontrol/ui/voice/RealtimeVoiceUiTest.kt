package com.m57.hermescontrol.ui.voice

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.R
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
        compose.onNodeWithText(compose.activity.getString(R.string.realtime_global_short)).assertIsDisplayed()
        compose.onNodeWithTag("voice_end").assertIsDisplayed().performClick()
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
                state = TalkCallState(phase = "speaking", muted = muted, jobs = listOf(job, completed)),
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
        compose.onNodeWithTag("voice_reconnect").assertDoesNotExist()
        compose.onNodeWithTag("voice_mute").performClick().assertIsOn()
        compose.onNodeWithTag("voice_interrupt").performClick()
        compose.onNodeWithTag("voice_activity").performClick()
        compose.onNodeWithTag("voice_activity_content").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("voice_activity_content").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, interrupted)
            assertEquals(0, ended)
            assertEquals(false, stopped)
        }
        compose.onNodeWithTag("voice_activity").performClick()
        val resultLabel = compose.activity.getString(R.string.realtime_result)
        compose.onNodeWithTag("voice_activity_content").performScrollToNode(hasText(resultLabel))
        compose.onNodeWithText(resultLabel).performClick()
        compose.runOnIdle {
            assertEquals("41", resultRun)
            assertEquals(false, stopped)
        }
        val stopLabel = compose.activity.getString(R.string.realtime_stop_work)
        compose.onNodeWithTag("voice_activity_content").performScrollToNode(hasText(stopLabel))
        compose.onNodeWithText(stopLabel).performClick()
        compose.runOnIdle { assertEquals(true, stopped) }
        compose.onNodeWithTag("voice_end").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ended) }
    }

    @Test
    fun recoveryAndEndRemainReachableAtNarrowWidthWithLargeText() {
        var reconnects = 0
        var ended = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp).height(568.dp)) {
                    RealtimeTalkContent(
                        state =
                            TalkCallState(
                                phase = "error",
                                error = "Voice access denied (403); check Access and subscription. ".repeat(3),
                            ),
                        permissionDenied = false,
                        backgroundEnded = false,
                        onClose = { ended = true },
                        onPermission = {},
                        onReconnect = { reconnects++ },
                        onMute = {},
                        onInterrupt = {},
                        onStop = {},
                        onResult = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("voice_mute").assertIsNotEnabled()
        compose.onNodeWithTag("voice_end").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("voice_reconnect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
        compose.onNodeWithTag("voice_end").performClick()
        compose.runOnIdle { assertEquals(true, ended) }
    }
}
