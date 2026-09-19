package com.m57.hermescontrol.ui.chat

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.m57.hermescontrol.notification.ChatNotificationService
import com.m57.hermescontrol.notification.ReplyNotificationTracker
import com.m57.hermescontrol.ui.chat.components.ChatReadObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatReadNotificationIntegrationTest {
    private lateinit var mockContext: Context
    private lateinit var mockNotificationManager: NotificationManager

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        ReplyNotificationTracker.resetForTest()
    }

    @After
    fun tearDown() {
        ReplyNotificationTracker.resetForTest()
    }

    private fun createMockItem(
        key: Any,
        offset: Int,
        size: Int,
    ): LazyListItemInfo {
        val item = mockk<LazyListItemInfo>()
        every { item.key } returns key
        every { item.offset } returns offset
        every { item.size } returns size
        return item
    }

    private fun createMockLayoutInfo(
        items: List<LazyListItemInfo>,
        startOffset: Int = 0,
        endOffset: Int = 1000,
    ): LazyListLayoutInfo {
        val layout = mockk<LazyListLayoutInfo>()
        every { layout.visibleItemsInfo } returns items
        every { layout.viewportStartOffset } returns startOffset
        every { layout.viewportEndOffset } returns endOffset
        return layout
    }

    @Test
    fun `end-to-end flow - visible target message cancels notification`() {
        val scopeId = "profile-main"
        val sessionId = "session-42"
        val completionId = "completion-abc"
        val textSnippet = "Here is the result of your task"

        val generation = ReplyNotificationTracker.nextGeneration()
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = textSnippet,
            generation = generation,
        )

        val targetMessage =
            ChatMessage(
                id = "msg-target",
                role = MessageRole.ASSISTANT,
                content = textSnippet,
                completionId = completionId,
            )

        val layout =
            createMockLayoutInfo(
                listOf(
                    createMockItem("prose-msg-target", offset = 200, size = 150),
                ),
            )

        val visibleMessages = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(targetMessage))
        var wasCancelled = false
        for (msg in visibleMessages) {
            if (ReplyNotificationTracker.onMessageVisible(
                    context = mockContext,
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = msg.completionId,
                    content = msg.content,
                )
            ) {
                wasCancelled = true
            }
        }

        assertTrue(wasCancelled)
        verify { mockNotificationManager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun `scrolled above target - notification stays until scrolled to target`() {
        val scopeId = "profile-main"
        val sessionId = "session-42"
        val completionId = "completion-abc"
        val textSnippet = "Newest assistant reply"

        val generation = ReplyNotificationTracker.nextGeneration()
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = textSnippet,
            generation = generation,
        )

        val olderMessage =
            ChatMessage(
                id = "msg-older",
                role = MessageRole.ASSISTANT,
                content = "Old assistant message",
                completionId = "comp-old",
            )
        val targetMessage =
            ChatMessage(
                id = "msg-target",
                role = MessageRole.ASSISTANT,
                content = textSnippet,
                completionId = completionId,
            )
        val allMessages = listOf(olderMessage, targetMessage)

        // Viewport 0..1000: user is at the top, only older message is visible; target is at offset 1500 (below viewport)
        val scrolledUpLayout =
            createMockLayoutInfo(
                listOf(
                    createMockItem("prose-msg-older", offset = 100, size = 200),
                    createMockItem("prose-msg-target", offset = 1500, size = 300),
                ),
                startOffset = 0,
                endOffset = 1000,
            )

        val visibleWhenScrolledUp = ChatReadObserver.findVisibleAssistantMessages(scrolledUpLayout, allMessages)
        var wasCancelledWhenScrolledUp = false
        for (msg in visibleWhenScrolledUp) {
            if (ReplyNotificationTracker.onMessageVisible(
                    context = mockContext,
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = msg.completionId,
                    content = msg.content,
                )
            ) {
                wasCancelledWhenScrolledUp = true
            }
        }

        assertFalse(wasCancelledWhenScrolledUp)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }

        // Now user scrolls down: target message enters viewport (offset 400, size 300)
        val scrolledDownLayout =
            createMockLayoutInfo(
                listOf(
                    createMockItem("prose-msg-target", offset = 400, size = 300),
                ),
                startOffset = 0,
                endOffset = 1000,
            )

        val visibleWhenScrolledDown = ChatReadObserver.findVisibleAssistantMessages(scrolledDownLayout, allMessages)
        var wasCancelledWhenScrolledDown = false
        for (msg in visibleWhenScrolledDown) {
            if (ReplyNotificationTracker.onMessageVisible(
                    context = mockContext,
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = msg.completionId,
                    content = msg.content,
                )
            ) {
                wasCancelledWhenScrolledDown = true
            }
        }

        assertTrue(wasCancelledWhenScrolledDown)
        verify { mockNotificationManager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun `viewing a different session does not dismiss notification`() {
        val scopeId = "profile-main"
        val sessionIdWithNotif = "session-notif"
        val completionId = "completion-abc"
        val textSnippet = "Important reply"

        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionIdWithNotif,
            completionId = completionId,
            textSnippet = textSnippet,
            generation = 1L,
        )

        val messageInOtherSession =
            ChatMessage(
                id = "msg-other",
                role = MessageRole.ASSISTANT,
                content = "Some other reply",
                completionId = "other-comp",
            )

        val layout =
            createMockLayoutInfo(
                listOf(
                    createMockItem("prose-msg-other", offset = 200, size = 150),
                ),
            )

        val visibleMessages = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(messageInOtherSession))
        var wasCancelled = false
        for (msg in visibleMessages) {
            if (ReplyNotificationTracker.onMessageVisible(
                    context = mockContext,
                    scopeId = scopeId,
                    sessionId = "different-session-id",
                    completionId = msg.completionId,
                    content = msg.content,
                )
            ) {
                wasCancelled = true
            }
        }

        assertFalse(wasCancelled)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }
    }

    @Test
    fun `action notification replacement prevents dismissal by message read`() {
        val scopeId = "profile-main"
        val sessionId = "session-42"
        val completionId = "completion-abc"
        val textSnippet = "Reply before action"

        // 1. Reply notification posted
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = scopeId,
            sessionId = sessionId,
            completionId = completionId,
            textSnippet = textSnippet,
            generation = 1L,
        )

        // 2. Later, an approval request arrives and replaces notification ID 2
        ReplyNotificationTracker.onNonReplyNotificationPosted()

        // 3. User opens chat and views the previous reply message
        val targetMessage =
            ChatMessage(
                id = "msg-target",
                role = MessageRole.ASSISTANT,
                content = textSnippet,
                completionId = completionId,
            )
        val layout =
            createMockLayoutInfo(
                listOf(
                    createMockItem("prose-msg-target", offset = 200, size = 150),
                ),
            )

        val visibleMessages = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(targetMessage))
        var wasCancelled = false
        for (msg in visibleMessages) {
            if (ReplyNotificationTracker.onMessageVisible(
                    context = mockContext,
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = msg.completionId,
                    content = msg.content,
                )
            ) {
                wasCancelled = true
            }
        }

        assertFalse("Action notification must NOT be dismissed by reading chat message", wasCancelled)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }
    }
}
