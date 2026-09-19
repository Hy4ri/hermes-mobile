package com.m57.hermescontrol.ui.chat

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.notification.ActiveReplyInfo
import com.m57.hermescontrol.notification.ChatNotificationService
import com.m57.hermescontrol.notification.ReplyNotificationTarget
import com.m57.hermescontrol.notification.ReplyNotificationTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReadNotificationReviewRegressionTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns manager
        ReplyNotificationTracker.resetForTest()
    }

    @After
    fun tearDown() {
        ReplyNotificationTracker.resetForTest()
    }

    private fun postTarget(text: String = "Done") {
        ReplyNotificationTracker.onReplyNotificationPosted("default", "session", "new-completion", text, 1L)
    }

    @Test
    fun roomRoundTripMustRetainCompletionIdentity() {
        val live = ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "new-completion")
        val restored = live.toEntity("session").toUiModel()
        assertEquals("Room reload lost notification identity", "new-completion", restored.completionId)
    }

    @Test
    fun repeatedTextInHistoryMustNotCopyLatestIdentityOntoOlderMessage() {
        val live =
            listOf(
                ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "old-completion"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "new-completion"),
            )
        val history =
            listOf(
                SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")),
                SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", history, 0, true, live)
        assertEquals(listOf("old-completion", "new-completion"), mapped.map { it.completionId })
    }

    @Test
    fun viewingOldCachedTextMustNotClearNewReplyNotification() {
        postTarget("Done")
        val old =
            ChatMessage(role = MessageRole.ASSISTANT, content = "Done yesterday; unrelated old response")
                .toEntity("session")
                .toUiModel()
        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                old.completionId,
                old.content,
            )
        assertFalse("Old text incorrectly acknowledged the new reply", cancelled)
        verify(exactly = 0) { manager.cancel(any()) }
    }

    @Test
    fun strippedCommentaryCompletionMustBindTerminalVisibleMessage() {
        val orphan = ChatMessage(id = "orphan", role = MessageRole.ASSISTANT, content = "already shown")
        val state = ChatUiState(currentSessionId = "session", messages = listOf(orphan))
        val streaming = StreamingState(streamingMessage = orphan, sealedOrphanIds = listOf("orphan"))
        val result =
            ChatWsEventReducer.reduce(
                state,
                streaming,
                WsEvent.MessageComplete("already shown", "session", completionId = "new-completion"),
                "session",
            )
        assertEquals(
            "Completion lost when no new final bubble is needed",
            "new-completion",
            result.state.messages
                .single()
                .completionId,
        )
        val persistEffect =
            result.effects.filterIsInstance<ReducerEffect.PersistMessage>().singleOrNull()
        assertEquals(
            "PersistMessage effect must be emitted for the sealed orphan bubble so Room persists it",
            "new-completion",
            persistEffect?.message?.completionId,
        )
    }

    @Test
    fun loadOlderMessagesMustNotStealCompletionIdFromLiveMessage() {
        val live =
            listOf(
                ChatMessage(
                    id = "ws-1",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = "new-completion",
                ),
            )
        val olderHistory =
            listOf(
                SessionMessage(id = 50, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", olderHistory, 50, true, live, isPagingOlder = true)
        assertEquals("Older paged message must not steal live completionId", null, mapped.single().completionId)
    }

    @Test
    fun duplicateTextOutsideFetchedPageMustNotShiftCompletionId() {
        val live =
            listOf(
                ChatMessage(
                    id = "rest-session-10",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = null,
                ),
                ChatMessage(
                    id = "rest-session-20",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = "new-completion",
                ),
            )
        // Page only includes message 20 (message 10 is outside this page)
        val history =
            listOf(
                SessionMessage(id = 20, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", history, 0, true, live, isPagingOlder = false)
        assertEquals(
            "Exact REST id match must preserve identity regardless of older duplicates",
            "new-completion",
            mapped.single().completionId,
        )
    }

    @Test
    fun metadataFreeReplacementMustNotBeCancelled() {
        postTarget()
        ReplyNotificationTracker.activeNotificationProvider = {
            ActiveReplyInfo(2, null, null, null, null, null, 0L, 1L)
        }
        assertFalse(ReplyNotificationTracker.onMessageVisible(context, "default", "session", "new-completion", "Done"))
        verify(exactly = 0) { manager.cancel(any()) }
    }

    @Test
    fun actionPostingMustNotInterleaveBetweenInspectionAndCancellation() {
        val reply =
            ActiveReplyInfo(
                2,
                ReplyNotificationTracker.KIND_REPLY,
                "default",
                "session",
                "new-completion",
                "Done",
                1L,
                1L,
            )
        val actualSlot = AtomicReference("reply")
        val ready = CountDownLatch(1)
        val allowNotify = CountDownLatch(1)
        val posted = CountDownLatch(1)
        val action = mockk<Notification>()
        every { manager.notify(2, action) } answers { actualSlot.set("action") }
        every { manager.cancel(2) } answers { actualSlot.set("empty") }
        postTarget()
        val writer =
            Thread {
                // Same split ownership/write sequence as showReplyNotification and NotificationReplyReceiver.
                ReplyNotificationTracker.onNonReplyNotificationPosted()
                ready.countDown()
                check(allowNotify.await(5, TimeUnit.SECONDS))
                manager.notify(2, action)
                posted.countDown()
            }
        writer.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        var reads = 0
        ReplyNotificationTracker.activeNotificationProvider = {
            val snapshot = reply
            reads++
            if (reads == 2) {
                // Pause after querying activeNotifications but before cancel(), then complete the action write.
                allowNotify.countDown()
                check(posted.await(5, TimeUnit.SECONDS))
            }
            snapshot
        }
        try {
            ReplyNotificationTracker.onMessageVisible(context, "default", "session", "new-completion", "Done")
        } finally {
            allowNotify.countDown()
            writer.join(6000)
        }
        assertEquals("New action notification was cancelled by the old read", "action", actualSlot.get())
    }

    @Test
    fun unknownScopeMustNotAcknowledgeScopedReply() {
        val target = ReplyNotificationTarget("other-profile", "session", "completion", 1L, "Done")
        assertFalse(target.matches(null, "session", "completion", "Done"))
    }
}
