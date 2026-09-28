package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class MarkdownHeadingRenderingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun headingRendersCodeInsideBold() {
        composeTestRule.setContent {
            MaterialTheme {
                MarkdownText(
                    text = "## **Set `foo` now**",
                    textColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        composeTestRule.onNodeWithText("Set foo now").assertIsDisplayed()
        composeTestRule.onNodeWithText("**Set `foo` now**").assertDoesNotExist()
    }

    @Test
    fun quoteAndListContinuationRenderNestedCodeBlocks() {
        composeTestRule.setContent {
            MaterialTheme {
                MarkdownText(
                    text =
                        """
                        > # Quote heading
                        > ```kotlin
                        > val quoted = 1
                        > ```

                        - Item
                          ```kotlin
                          val listed = 2
                          ```
                        """.trimIndent(),
                    textColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        composeTestRule.onNodeWithText("Quote heading").assertIsDisplayed()
        composeTestRule.onNodeWithText("Item").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("code_block").assertCountEquals(2)
        composeTestRule.onNodeWithText("val quoted = 1", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("val listed = 2", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamingMarkdownRendersBlocksBeforeCompletion() {
        composeTestRule.setContent {
            MaterialTheme {
                MarkdownText(
                    text = "## Live heading\n\n**ready**\n\n```kotlin\nval x = 1\n```",
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isStreaming = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Live heading").assertIsDisplayed()
        composeTestRule.onNodeWithText("ready").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("code_block").assertCountEquals(1)
    }
}
