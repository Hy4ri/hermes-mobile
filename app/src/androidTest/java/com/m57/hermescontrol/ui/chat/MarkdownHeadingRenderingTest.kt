package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
}
