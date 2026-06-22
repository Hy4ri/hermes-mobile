package com.m57.hermescontrol.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for ChatScreen composable (TEST-09, issue #292).
 *
 * Tests validate that the chat screen renders correctly and handles
 * user input. Requires an instrumented Android environment.
 *
 * Uses createAndroidComposeRule<ComponentActivity> for explicit Activity lifecycle
 * management across test methods — avoids the "No compose hierarchies found"
 * issue that createComposeRule() has on the emulator with multiple test methods.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatScreen_rendersWithoutCrashing() {
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(ChatUiState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }
    }

    @Test
    fun chatScreen_inputField_acceptsText() {
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(ChatUiState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
    }

    @Test
    fun chatScreen_sendButton_isDisplayed() {
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(ChatUiState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()
    }
}
