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
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for ChatScreen composable (TEST-09, issue #292).
 *
 * Tests validate that the chat screen renders correctly and handles
 * user input. Requires an instrumented Android environment.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createMockViewModel(): ChatViewModel {
        val mock = mockk<ChatViewModel>(relaxed = true)
        every { mock.uiState } returns MutableStateFlow(ChatUiState()).asStateFlow()
        return mock
    }

    @Test
    fun chatScreen_rendersWithoutCrashing() {
        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = createMockViewModel(),
            )
        }
    }

    @Test
    fun chatScreen_inputField_acceptsText() {
        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = createMockViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
    }

    @Test
    fun chatScreen_sendButton_isDisplayed() {
        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = createMockViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()
    }
}
