package com.m57.hermescontrol.ui.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
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
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.notification.ChatNotificationService
import com.m57.hermescontrol.notification.ReplyNotificationTracker
import com.m57.hermescontrol.ui.chat.components.ChatLifecycleEffects
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
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
    private lateinit var notificationManager: NotificationManager
    private val testChannelId = "test_chat_channel"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    context.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
        }
        val channel =
            NotificationChannel(
                testChannelId,
                "Test Chat Notifications",
                NotificationManager.IMPORTANCE_HIGH,
            )
        notificationManager.createNotificationChannel(channel)
        notificationManager.cancelAll()
        ReplyNotificationTracker.resetForTest()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        ReplyNotificationTracker.resetForTest()
    }

    private fun awaitNotification(
        timeoutMs: Long = 3000L,
        predicate: (Array<StatusBarNotification>) -> Boolean,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate(notificationManager.activeNotifications)) return true
            Thread.sleep(50)
        }
        return predicate(notificationManager.activeNotifications)
    }

    private fun postRealNotification(
        kind: String,
        scopeId: String = "default",
        sessionId: String = "session-1",
        completionId: String = "comp-1",
        textSnippet: String = "Here is the reply",
    ): Long {
        val gen =
            if (kind == ReplyNotificationTracker.KIND_REPLY) {
                ReplyNotificationTracker.registerPendingReply(
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = completionId,
                    textSnippet = textSnippet,
                )
            } else {
                ReplyNotificationTracker.nextGeneration()
            }

        val notification =
            NotificationCompat
                .Builder(context, testChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Agent")
                .setContentText(textSnippet)
                .addExtras(
                    Bundle().apply {
                        putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, kind)
                        putString(ReplyNotificationTracker.EXTRA_SCOPE_ID, scopeId)
                        putString(ReplyNotificationTracker.EXTRA_SESSION_ID, sessionId)
                        putString(ReplyNotificationTracker.EXTRA_COMPLETION_ID, completionId)
                        putString(ReplyNotificationTracker.EXTRA_TEXT_SNIPPET, textSnippet)
                        putLong(ReplyNotificationTracker.EXTRA_GENERATION, gen)
                    },
                ).build()

        when (kind) {
            ReplyNotificationTracker.KIND_REPLY -> {
                ReplyNotificationTracker.postReplyNotification(context, notification, gen)
            }

            ReplyNotificationTracker.KIND_ACTION -> {
                ReplyNotificationTracker.postActionNotification(context, notification)
            }

            ReplyNotificationTracker.KIND_REPLIED -> {
                ReplyNotificationTracker.postRepliedNotification(context, notification)
            }
        }

        val active = awaitNotification { sbns -> sbns.any { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID } }
        assertTrue("Notification ID 2 must be active in system after posting", active)
        return gen
    }

    @Test
    fun chatLifecycleEffects_dismissesRealSystemNotification_whenAssistantMessageDisplayed() {
        val sessionId = "session-read-test"
        val completionId = "comp-12345"
        val replyText = "Here is the completed response."

        postRealNotification(
            kind = ReplyNotificationTracker.KIND_REPLY,
            scopeId = AuthManager.activeProfileId.value.orEmpty(),
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = replyText,
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

        val dismissed =
            awaitNotification { sbns -> sbns.none { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID } }
        assertTrue(
            "Notification ID 2 must be dismissed from real system notifications when message is viewed",
            dismissed,
        )
    }

    @Test
    fun chatLifecycleEffects_doesNotDismissRealSystemNotification_forActionRequest() {
        val sessionId = "session-action-test"
        val completionId = "comp-action"
        val promptText = "Approval required"

        postRealNotification(
            kind = ReplyNotificationTracker.KIND_ACTION,
            scopeId = AuthManager.activeProfileId.value.orEmpty(),
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = promptText,
        )

        val assistantMessage =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = promptText,
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
        Thread.sleep(200)

        assertTrue(
            "Action notifications must NOT be auto-dismissed when viewing chat",
            notificationManager.activeNotifications.any { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID },
        )
    }

    @Test
    fun chatLifecycleEffects_doesNotDismissRealSystemNotification_forRepliedNotification() {
        val sessionId = "session-replied-test"
        val completionId = "comp-replied"
        val replyStatusText = "Replied: done"

        postRealNotification(
            kind = ReplyNotificationTracker.KIND_REPLIED,
            scopeId = AuthManager.activeProfileId.value.orEmpty(),
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = replyStatusText,
        )

        val assistantMessage =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = replyStatusText,
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
        Thread.sleep(200)

        assertTrue(
            "Replied status notifications must NOT be auto-dismissed when viewing chat",
            notificationManager.activeNotifications.any { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID },
        )
    }

    @Test
    fun chatLifecycleEffects_doesNotDismissNotification_forDifferentSession() {
        val sessionIdWithNotif = "session-with-notif"
        val differentSessionId = "other-session"
        val completionId = "comp-12345"
        val replyText = "Here is the completed response."

        postRealNotification(
            kind = ReplyNotificationTracker.KIND_REPLY,
            scopeId = AuthManager.activeProfileId.value.orEmpty(),
            sessionId = sessionIdWithNotif,
            completionId = completionId,
            textSnippet = replyText,
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
        Thread.sleep(200)

        assertTrue(
            "Notification ID 2 must remain active for a different session",
            notificationManager.activeNotifications.any { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID },
        )
    }
}
