package com.m57.hermescontrol.ui.chat

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for ChatScreen composable (TEST-09, issue #292).
 *
 * Validates that the chat screen renders correctly, its send button is
 * displayed, and the input field accepts text.
 *
 * Mocks ContextCompat.checkSelfPermission to return GRANTED so the
 * POST_NOTIFICATIONS runtime permission dialog isn't launched during
 * composition (it would pause the activity and dispose the compose
 * hierarchy).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED
    }

    @Test
    fun chatScreen_renders_and_acceptsInput() {
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(ChatUiState()).asStateFlow()

        composeTestRule.setContent {
            ChatScreen(
                onOpenDrawer = {},
                sessionId = null,
                viewModel = mockViewModel,
            )
        }

        // Verify the send button is displayed
        composeTestRule.onNodeWithTag("send_button").assertIsDisplayed()

        // Verify the input field accepts text
        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hello Hermes")
    }
}
