package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.ws.CommandCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Gesture-level coverage for the Telegram-style voice-note mic: a held press
 * arms the recorder, the release submits exactly once, sliding left cancels,
 * sliding up locks hands-free, and the shared click handlers must never
 * re-dispatch the dictation (mic tap) action for a hold.
 *
 * Regression: the action button's click handler fires on ANY stationary
 * release — including one that ended a hold — so letting go of a voice note
 * also launched the system speech-recognizer intent. The gesture loop now
 * consumes the hold phase, and these tests pin that behavior down.
 *
 * The hold threshold runs on the compose test clock, so the tests advance
 * [createAndroidComposeRule]'s mainClock instead of sleeping.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class VoiceNoteGestureTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var micTaps = 0
    private var holdStarts = 0
    private var holdEnds = 0
    private var holdCancels = 0
    private var sendCount = 0
    private var locks = 0

    /**
     * Renders the real input bar with the launcher's contract: hold start/end
     * flip the recording state, the lock callback flips the locked flag, and a
     * cancel releases both — exactly like ChatMediaLaunchers does.
     */
    private fun setComposer(
        initiallyLocked: Boolean = false,
        draft: String = "",
    ) {
        // The lock-hint tooltip hides after the first successful lock and
        // persists that; start every run from a clean slate so the rendered
        // UI is deterministic.
        composeTestRule.activity
            .getSharedPreferences("chat_voice_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        composeTestRule.setContent {
            var locked by remember { mutableStateOf(initiallyLocked) }
            var recording by remember { mutableStateOf(initiallyLocked) }
            // Bottom-anchor the bar like the real chat screen, so floating
            // overlays above the card render inside the captured frame.
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.weight(1f))
                ChatInputBar(
                    inputFieldValue = TextFieldValue(draft),
                    onInputChange = {},
                    onSend = { sendCount++ },
                    onMicTap = { micTaps++ },
                    isListening = false,
                    isAgentTyping = false,
                    isConnected = true,
                    commandCatalog = CommandCatalog(),
                    isSessionReady = true,
                    onMicHoldStart = {
                        holdStarts++
                        recording = true
                    },
                    onMicHoldEnd = {
                        holdEnds++
                        recording = false
                    },
                    onMicHoldCancel = {
                        holdCancels++
                        locked = false
                        recording = false
                    },
                    onMicLock = {
                        locks++
                        locked = true
                    },
                    isVoiceNoteLocked = locked,
                    isRecordingVoice = recording,
                )
            }
        }
    }

    @Test
    fun stationaryHoldRelease_sendsWithoutTriggeringMicTap() {
        setComposer()

        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        // Mid-hold capture: the recording strip must be visible even though
        // the mic button sits under the finger (device follow-up, #1247).
        val shot = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        File(composeTestRule.activity.getExternalFilesDir(null), "voice_note_recording.png")
            .outputStream()
            .use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The lock hint must exist while holding (it sits above the card).
        composeTestRule.onNodeWithText("Slide up to lock recording").assertExists()
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { up() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals("release must submit the note", 1, holdEnds)
            assertEquals(
                "a held release must not re-dispatch the tap action",
                0,
                micTaps,
            )
            assertEquals("a submitted hold must not cancel", 0, holdCancels)
        }
    }

    @Test
    fun quickTap_stillDispatchesMicTap() {
        setComposer()

        composeTestRule.onNodeWithTag("mic_button").performTouchInput {
            down(center)
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals("a quick tap must start dictation", 1, micTaps)
            assertEquals(0, holdStarts)
            assertEquals(0, holdEnds)
        }
    }

    @Test
    fun holdSlideAway_cancelsWithoutSending() {
        setComposer()

        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { moveBy(Offset(-240f, 0f)) }
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { up() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals("sliding left must cancel the recording", 1, holdCancels)
            assertEquals("a cancelled hold must not submit", 0, holdEnds)
            assertEquals("a cancelled hold must not start dictation", 0, micTaps)
        }
    }

    @Test
    fun holdSlideUp_locksWithoutSending() {
        setComposer()

        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { moveBy(Offset(0f, -240f)) }
        // Lifting the finger after the slide-up lock: target the root, because
        // the action slot has already morphed into the send button.
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals("sliding up must lock the recording", 1, locks)
            assertEquals("a locked hold must not submit on release", 0, holdEnds)
            assertEquals("a locked hold must not cancel", 0, holdCancels)
            assertEquals("a locked hold must not start dictation", 0, micTaps)
        }

        composeTestRule.onNodeWithTag("voice_note_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_note_send_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals("the locked send button must dispatch the mic action", 1, micTaps)
            assertEquals("sending must not re-lock", 1, locks)
        }
    }

    @Test
    fun holdRelease_returnsFocusToTheInput() {
        setComposer()

        composeTestRule.onNodeWithTag("chat_input").performClick()
        composeTestRule.onNodeWithTag("chat_input").assertIsFocused()

        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { up() }
        composeTestRule.waitForIdle()

        // The strip replaced the input field while recording; the send must
        // hand focus back so the keyboard re-opens without an extra tap
        // (device follow-up, #1247).
        composeTestRule.onNodeWithTag("chat_input").assertIsFocused()
    }

    @Test
    fun lockedRecording_showsCancelActionAndRoutesCancel() {
        setComposer(initiallyLocked = true)

        composeTestRule.onNodeWithTag("voice_note_recording_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_note_cancel_button").assertIsDisplayed()

        val shot = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val file =
            File(composeTestRule.activity.getExternalFilesDir(null), "voice_note_locked.png")
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }

        composeTestRule.onNodeWithTag("voice_note_cancel_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals("cancel must cancel the recording", 1, holdCancels)
        }
    }

    @Test
    fun holdReleaseWithDraft_doesNotRouteTapToDictation() {
        // With a draft present the secondary mic ("mic_button") appears next to
        // the input. Review, PR #1280: its gesture modifier is chained after
        // the button's own clickable (the reverse of the action button), so a
        // stationary hold-release must not leak a finger-up into onMicTap.
        setComposer(draft = "draft")
        val tapsBefore = micTaps

        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { up() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals("release must submit the note", 1, holdEnds)
            assertEquals("a stationary hold-release must not fire a tap", tapsBefore, micTaps)
        }
    }

    @Test
    fun lockedWithDraft_actionButtonFinishesTheVoiceNote() {
        // Blocker, review, PR #1280: with a draft present the action button is
        // a send arrow; once the recording is locked it must finish the voice
        // note instead of sending the draft text.
        setComposer(draft = "draft")
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { moveBy(Offset(0f, -240f)) }
        composeTestRule.onNodeWithTag("mic_button").performTouchInput { up() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("voice_note_send_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals("the tap must finish the voice note", 1, micTaps)
            assertEquals("the draft text must not be sent", 0, sendCount)
        }
    }
}
