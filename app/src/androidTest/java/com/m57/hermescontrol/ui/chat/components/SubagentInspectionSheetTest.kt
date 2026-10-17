package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.ui.chat.SubagentControlState
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.SubagentTranscriptUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SubagentInspectionSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSubagentInspectionSheet_displaysSubagentAndTogglesTranscript() {
        var toggledSubagentId = ""
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
                model = "claude-sonnet",
                durationSeconds = 12.4,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                inspectingSubagentId = null,
                subagentTranscript = null,
                onToggleTranscript = { toggledSubagentId = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_inspection_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Analyze server logs").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_transcript_toggle_sub-1089").assertIsDisplayed()

        composeTestRule.onNodeWithTag("subagent_transcript_toggle_sub-1089").performClick()
        assertEquals("sub-1089", toggledSubagentId)
    }

    @Test
    fun testSubagentInspectionSheet_displaysTranscriptContentAndTruncation() {
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
            )
        val transcript =
            SubagentTranscriptUiState(
                subagentId = "sub-1089",
                text = "Fetched 100 log lines successfully.",
                isTruncated = true,
                isLoading = false,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                inspectingSubagentId = "sub-1089",
                subagentTranscript = transcript,
                onToggleTranscript = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_transcript_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_transcript_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fetched 100 log lines successfully.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rolling 16KB tail (older output truncated)").assertIsDisplayed()
    }

    @Test
    fun testSubagentInspectionSheet_displaysErrorAndTriggersRetry() {
        var retried = false
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
            )
        val transcript =
            SubagentTranscriptUiState(
                subagentId = "sub-1089",
                text = "",
                error = "Connection timeout",
                isLoading = false,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                inspectingSubagentId = "sub-1089",
                subagentTranscript = transcript,
                onToggleTranscript = {},
                onRetryTranscript = { retried = true },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Connection timeout").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_transcript_retry").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_transcript_retry").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun testSubagentInspectionSheet_pendingDisablesStopAndSteer() {
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
                controlState = SubagentControlState.STEERING,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                onSteerSubagent = { _, _ -> },
                onStopSubagent = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_control_sub-1089").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sending guidance…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Steer").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Stop").assertIsNotEnabled()
    }

    @Test
    fun testSubagentInspectionSheet_queuedNotSteered() {
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
                controlState = SubagentControlState.STEER_QUEUED,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_control_sub-1089").assertIsDisplayed()
        composeTestRule.onNodeWithText("Guidance queued. Delivery is not confirmed.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running").assertIsDisplayed()
        composeTestRule.onNodeWithText("Steered").assertDoesNotExist()
    }

    @Test
    fun testSubagentInspectionSheet_stopRequestedNotCancelled() {
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Analyze server logs",
                status = "running",
                controlState = SubagentControlState.STOP_REQUESTED,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_control_sub-1089").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stop requested. Waiting for the child to finish.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancelled").assertDoesNotExist()
    }

    @Test
    fun testSubagentInspectionSheet_unsupportedOrMissingIdDisablesControls() {
        val unsupportedIndicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1",
                goal = "Unsupported Task",
                status = "running",
                controlState = SubagentControlState.UNSUPPORTED,
            )
        val missingIdIndicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = null,
                goal = "No ID Task",
                status = "running",
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(unsupportedIndicator, missingIdIndicator),
                onSteerSubagent = { _, _ -> },
                onStopSubagent = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Unsupported Task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Child controls are not supported by this gateway.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No ID Task").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_steer_sub-1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("subagent_stop_sub-1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("subagent_steer_No ID Task").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("subagent_stop_No ID Task").assertIsNotEnabled()
    }

    @Test
    fun testSubagentInspectionSheet_rejectedKeepsControlsEnabled() {
        val indicator =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1089",
                goal = "Rejected Task",
                status = "running",
                controlState = SubagentControlState.STEER_REJECTED,
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator),
                onSteerSubagent = { _, _ -> },
                onStopSubagent = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_control_sub-1089").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Guidance rejected. The child may no longer accept messages.",
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Steer").assertIsEnabled()
        composeTestRule.onNodeWithText("Stop").assertIsEnabled()
    }

    @Test
    fun testSubagentInspectionSheet_twoChildrenOnlySelectedRowPending() {
        val indicator1 =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-1",
                goal = "Child One",
                status = "running",
                controlState = SubagentControlState.STEERING,
            )
        val indicator2 =
            SubagentIndicator(
                type = "subagent.progress",
                subagentId = "sub-2",
                goal = "Child Two",
                status = "running",
            )

        composeTestRule.setContent {
            SubagentInspectionSheet(
                indicators = listOf(indicator1, indicator2),
                onSteerSubagent = { _, _ -> },
                onStopSubagent = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag("subagent_control_sub-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sending guidance…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Child Two").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subagent_control_sub-2").assertDoesNotExist()
        composeTestRule.onNodeWithTag("subagent_steer_sub-1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("subagent_stop_sub-1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("subagent_steer_sub-2").assertIsEnabled()
        composeTestRule.onNodeWithTag("subagent_stop_sub-2").assertIsEnabled()
    }
}
