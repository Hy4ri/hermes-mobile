package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class FileViewCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun shortFile_displaysPathLineCountAndFullContentWithoutExpandButton() {
        val content = "fun hello() = println(\"world\")\nval x = 42"
        var copiedText = ""

        composeTestRule.setContent {
            FileViewCard(
                content = content,
                filePath = "src/main/Test.kt",
                onCopy = { copiedText = it },
            )
        }

        composeTestRule.onNodeWithTag("file_view_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("src/main/Test.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 lines").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show full file (2 lines)").assertDoesNotExist()

        composeTestRule.onNodeWithTag("file_view_card").assertIsDisplayed()
    }

    @Test
    fun longFile_showsExpandButtonAndTogglesFullFile() {
        val lines = (1..25).map { "line $it: println($it)" }
        val content = lines.joinToString("\n")

        composeTestRule.setContent {
            FileViewCard(
                content = content,
                filePath = "src/Big.kt",
            )
        }

        composeTestRule.onNodeWithText("Show full file (25 lines)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show full file (25 lines)").performClick()
        composeTestRule.onNodeWithText("Collapse file").assertIsDisplayed()
        composeTestRule.onNodeWithText("Collapse file").performClick()
        composeTestRule.onNodeWithText("Show full file (25 lines)").assertIsDisplayed()
    }

    @Test
    fun copyButton_invokesCallbackWithFullContent() {
        val content = "1|fun main() = Unit"
        var copied = ""

        composeTestRule.setContent {
            FileViewCard(
                content = content,
                filePath = "src/App.kt",
                onCopy = { copied = it },
            )
        }
        composeTestRule.onNodeWithText("src/App.kt").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Copy file content").performClick()
        assertEquals(content, copied)
    }
}
