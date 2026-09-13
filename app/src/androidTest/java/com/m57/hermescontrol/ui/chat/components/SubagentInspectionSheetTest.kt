package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
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
}
