package com.m57.hermescontrol.ui.chat

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.notification.ChatNotificationService
import com.m57.hermescontrol.notification.ReplyNotificationTracker
import com.m57.hermescontrol.ui.chat.components.ChatLifecycleEffects
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatReadNotificationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var mockNotificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockNotificationManager = mockk(relaxed = true)
        ReplyNotificationTracker.resetForTest()
    }

    @After
    fun tearDown() {
        ReplyNotificationTracker.resetForTest()
    }

    @Test
    fun chatLifecycleEffects_dismissesNotification_whenAssistantMessageDisplayed() {
        val scopeId = AuthManager.activeProfileId.value.orEmpty()
        val sessionId = "session-read-test"
        val completionId = "comp-12345"
        val replyText = "Here is the completed response."

        val targetGen = ReplyNotificationTracker.nextGeneration()
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = replyText,
            generation = targetGen,
        )

        val assistantMessage =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = replyText,
                completionId = completionId,
            )
        val messages = listOf(assistantMessage)

        val mockVm = mockk<ChatViewModel>(relaxed = true)

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            val scrollScope = remember { CoroutineScope(Dispatchers.Main) }
            val scrollController = rememberChatScrollController(listState, scrollScope)
            val snackbarHostState = remember { SnackbarHostState() }

            LazyColumn(
                state = listState,
                modifier = Modifier.size(300.dp, 600.dp),
            ) {
                items(messages, key = { "prose-${it.id}" }) {
                    Text(it.content)
                }
            }

            ChatLifecycleEffects(
                sessionId = sessionId,
                connectionStatus = ConnectionStatus.CONNECTED,
                currentSessionId = sessionId,
                messages = messages,
                errorMessage = null,
                backgroundCompleteMessage = null,
                openError = null,
                clarifyRequest = null,
                sudoPrompt = null,
                secretPrompt = null,
                listState = listState,
                scrollController = scrollController,
                snackbarHostState = snackbarHostState,
                viewModel = mockVm,
            )
        }

        composeTestRule.waitForIdle()

        // Target should be dismissed and cleared because the assistant message was visible
        assertNull(
            "Notification target should be cleared after assistant message is rendered",
            ReplyNotificationTracker.getActiveTarget(),
        )
    }

    @Test
    fun chatLifecycleEffects_doesNotDismissNotification_forDifferentSession() {
        val scopeId = AuthManager.activeProfileId.value.orEmpty()
        val sessionIdWithNotif = "session-with-notif"
        val differentSessionId = "other-session"
        val completionId = "comp-12345"
        val replyText = "Here is the completed response."

        val targetGen = ReplyNotificationTracker.nextGeneration()
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionIdWithNotif,
            completionId = completionId,
            textSnippet = replyText,
            generation = targetGen,
        )

        val assistantMessage =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = replyText,
                completionId = completionId,
            )
        val messages = listOf(assistantMessage)

        val mockVm = mockk<ChatViewModel>(relaxed = true)

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            val scrollScope = remember { CoroutineScope(Dispatchers.Main) }
            val scrollController = rememberChatScrollController(listState, scrollScope)
            val snackbarHostState = remember { SnackbarHostState() }

            LazyColumn(
                state = listState,
                modifier = Modifier.size(300.dp, 600.dp),
            ) {
                items(messages, key = { "prose-${it.id}" }) {
                    Text(it.content)
                }
            }

            ChatLifecycleEffects(
                sessionId = differentSessionId,
                connectionStatus = ConnectionStatus.CONNECTED,
                currentSessionId = differentSessionId,
                messages = messages,
                errorMessage = null,
                backgroundCompleteMessage = null,
                openError = null,
                clarifyRequest = null,
                sudoPrompt = null,
                secretPrompt = null,
                listState = listState,
                scrollController = scrollController,
                snackbarHostState = snackbarHostState,
                viewModel = mockVm,
            )
        }

        composeTestRule.waitForIdle()

        // Target should still be active because currentSessionId was for another session
        assertTrue(
            "Notification target should remain active for different session",
            ReplyNotificationTracker.getActiveTarget() != null,
        )
    }
}
