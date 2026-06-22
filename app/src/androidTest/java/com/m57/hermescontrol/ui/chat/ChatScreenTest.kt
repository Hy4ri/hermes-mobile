package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for ChatScreen composable (TEST-09, issue #292).
 *
 * These tests validate that the chat screen renders correctly and handles
 * user input. Requires an instrumented Android environment.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chatScreen_rendersWithoutCrashing() {
        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = null)
        }
    }

    @Test
    fun chatScreen_inputField_acceptsText() {
        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = null)
        }

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
    }

    @Test
    fun chatScreen_sendButton_isDisplayed() {
        composeTestRule.setContent {
            ChatScreen(onOpenDrawer = {}, sessionId = null)
        }

        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()
    }
}
