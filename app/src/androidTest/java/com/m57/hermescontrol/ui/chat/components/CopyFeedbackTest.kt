package com.m57.hermescontrol.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class CopyFeedbackTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun CopyFeedbackHarness(
        tagPrefix: String,
        stateReceiver: (MutableState<Boolean>) -> Unit = {},
    ) {
        val feedbackState = rememberCopyFeedback()
        stateReceiver(feedbackState)

        Column {
            Text(
                text = if (feedbackState.value) "Copied" else "Idle",
                modifier = Modifier.testTag("${tagPrefix}_status"),
            )
            Text(
                text = "Trigger",
                modifier =
                    Modifier
                        .testTag("${tagPrefix}_trigger")
                        .clickable { feedbackState.value = true },
            )
        }
    }

    @Test
    fun initialStateIsFalse() {
        composeTestRule.setContent {
            CopyFeedbackHarness(tagPrefix = "feedback")
        }

        composeTestRule
            .onNodeWithTag("feedback_status")
            .assertIsDisplayed()
            .assertTextEquals("Idle")
    }

    @Test
    fun settingTrueShowsFeedbackAndResetsAfter1500Ms() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            CopyFeedbackHarness(tagPrefix = "feedback")
        }

        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Idle")

        composeTestRule.onNodeWithTag("feedback_trigger").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Copied")

        // Advance 1400ms - should still be true (100ms before 1500ms timeout)
        composeTestRule.mainClock.advanceTimeBy(1400L)
        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Copied")

        // Advance another 100ms to hit exactly 1500ms
        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Idle")
    }

    @Test
    fun settingTrueAgainWhileAlreadyTrueDoesNotRestartActiveTimer() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            CopyFeedbackHarness(tagPrefix = "feedback")
        }

        // Trigger at t = 0ms
        composeTestRule.onNodeWithTag("feedback_trigger").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Copied")

        // Advance to t = 1000ms
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Copied")

        // Re-trigger setting true at t = 1000ms while already true
        composeTestRule.onNodeWithTag("feedback_trigger").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Copied")

        // If the active timer is NOT restarted, it resets at t = 1500ms (500ms after the second click)
        composeTestRule.mainClock.advanceTimeBy(500L)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("feedback_status").assertTextEquals("Idle")
    }

    @Test
    fun separateComposedHookInstancesAreIndependent() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            Column {
                CopyFeedbackHarness(tagPrefix = "instance_a")
                CopyFeedbackHarness(tagPrefix = "instance_b")
            }
        }

        composeTestRule.onNodeWithTag("instance_a_status").assertTextEquals("Idle")
        composeTestRule.onNodeWithTag("instance_b_status").assertTextEquals("Idle")

        // Trigger only instance A
        composeTestRule.onNodeWithTag("instance_a_trigger").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        // Instance A should be active, instance B should remain idle
        composeTestRule.onNodeWithTag("instance_a_status").assertTextEquals("Copied")
        composeTestRule.onNodeWithTag("instance_b_status").assertTextEquals("Idle")

        // Advance 1000ms
        composeTestRule.mainClock.advanceTimeBy(1000L)

        // Trigger instance B at t = 1000ms
        composeTestRule.onNodeWithTag("instance_b_trigger").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        // Both should be Copied now
        composeTestRule.onNodeWithTag("instance_a_status").assertTextEquals("Copied")
        composeTestRule.onNodeWithTag("instance_b_status").assertTextEquals("Copied")

        // Advance 500ms to t = 1500ms: Instance A should expire, Instance B should still have 1000ms remaining
        composeTestRule.mainClock.advanceTimeBy(500L)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("instance_a_status").assertTextEquals("Idle")
        composeTestRule.onNodeWithTag("instance_b_status").assertTextEquals("Copied")

        // Advance remaining 1000ms for Instance B
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("instance_a_status").assertTextEquals("Idle")
        composeTestRule.onNodeWithTag("instance_b_status").assertTextEquals("Idle")
    }
}
