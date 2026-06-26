package com.m57.hermescontrol.ui.chat

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.ws.ConnectionStatus
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
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        // Suppress the POST_NOTIFICATIONS runtime permission dialog that
        // the ChatScreen's LaunchedEffect(Unit) would otherwise trigger.
        // On the emulator this dialog pauses the activity and disposes the
        // compose hierarchy, making subsequent node lookups fail.
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED
    }

    @Test
    fun chatScreen_renders_and_acceptsInput() {
        // Default ChatUiState has connectionStatus=DISCONNECTED which
        // disables the input field and hides the send button.  Override
        // so the interactive controls are active.
        val uiState =
            ChatUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
            )
        val mockViewModel = mockk<ChatViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState).asStateFlow()
        every { mockViewModel.streamingState } returns MutableStateFlow(StreamingState()).asStateFlow()

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
