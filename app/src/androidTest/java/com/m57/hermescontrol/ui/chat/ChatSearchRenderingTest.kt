package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatSearchRenderingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun reasoningMatchExpandsCollapsedCardAndShowsTheHit() {
        composeTestRule.setContent {
            MaterialTheme {
                ReasoningCard(
                    reasoningText = "a hidden needle in the trace",
                    searchQuery = "needle",
                    isCurrentMatch = true,
                )
            }
        }

        composeTestRule.onNodeWithText("a hidden needle in the trace").assertIsDisplayed()
    }

    @Test
    fun toolMatchRemainsVisibleInCollapsedHeader() {
        composeTestRule.setContent {
            MaterialTheme {
                ToolBubble(
                    message = ChatMessage(role = MessageRole.TOOL, content = "opaque", toolName = "terminal"),
                    searchQuery = "terminal",
                    isCurrentMatch = true,
                )
            }
        }

        composeTestRule.onNodeWithText("terminal").assertIsDisplayed()
    }
}
