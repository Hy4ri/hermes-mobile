package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class DesktopMediaRenderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mediaDirectiveRendersImageInsteadOfPathText() {
        composeTestRule.setContent {
            MaterialTheme {
                ChatBubble(
                    message =
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = "MEDIA:C:\\Users\\example\\Downloads\\photo.png",
                        ),
                    isDarkTheme = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("desktop_media_image", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("MEDIA:", substring = true).assertDoesNotExist()
    }
}
