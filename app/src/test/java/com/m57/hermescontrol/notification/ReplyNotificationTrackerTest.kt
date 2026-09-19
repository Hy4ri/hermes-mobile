package com.m57.hermescontrol.notification

import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplyNotificationTrackerTest {
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

    @Test
    fun `onReplyNotificationPosted sets target correctly`() {
        val gen = ReplyNotificationTracker.nextGeneration()
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = "prof-1",
            sessionId = "sess-123",
            completionId = "comp-456",
            textSnippet = "Hello world reply",
            generation = gen,
        )

        val target = ReplyNotificationTracker.getActiveTarget()
        assertEquals("prof-1", target?.scopeId)
        assertEquals("sess-123", target?.sessionId)
        assertEquals("comp-456", target?.completionId)
        assertEquals("Hello world reply", target?.textSnippet)
        assertEquals(gen, target?.generation)
    }

    @Test
    fun `matches verifies scope, session and completionId`() {
        val target =
            ReplyNotificationTarget(
                scopeId = "prof-1",
                sessionId = "sess-123",
                completionId = "comp-456",
                generation = 1L,
                textSnippet = "Answer text",
            )

        assertTrue(target.matches("prof-1", "sess-123", "comp-456"))
        // Scoped target rejects blank or null scope
        assertFalse(target.matches("", "sess-123", "comp-456"))
        assertFalse(target.matches(null, "sess-123", "comp-456"))

        // Target with blank scope matches unspecified scope
        val unscopedTarget = target.copy(scopeId = "")
        assertTrue(unscopedTarget.matches("", "sess-123", "comp-456"))
        assertTrue(unscopedTarget.matches(null, "sess-123", "comp-456"))

        // Wrong session fails
        assertFalse(target.matches("prof-1", "other-sess", "comp-456"))
        // Wrong scope fails
        assertFalse(target.matches("prof-2", "sess-123", "comp-456"))
        // Wrong completionId fails
        assertFalse(target.matches("prof-1", "sess-123", "other-comp"))
    }

    @Test
    fun `matches requires exact completionId match and rejects null or blank`() {
        val target =
            ReplyNotificationTarget(
                scopeId = "prof-1",
                sessionId = "sess-123",
                completionId = "comp-456",
                generation = 1L,
                textSnippet = "Here is the full summary of the changes",
            )

        assertTrue(
            target.matches(
                candidateScopeId = "prof-1",
                candidateSessionId = "sess-123",
                candidateCompletionId = "comp-456",
            ),
        )

        assertFalse(
            "Missing candidate completionId must not match target",
            target.matches(
                candidateScopeId = "prof-1",
                candidateSessionId = "sess-123",
                candidateCompletionId = null,
            ),
        )

        assertFalse(
            "Different candidate completionId must not match target",
            target.matches(
                candidateScopeId = "prof-1",
                candidateSessionId = "sess-123",
                candidateCompletionId = "other-comp",
            ),
        )
    }

    @Test
    fun `onNonReplyNotificationPosted clears target`() {
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = "prof-1",
            sessionId = "sess-123",
            completionId = "comp-456",
            textSnippet = "Reply",
            generation = 1L,
        )
        assertTrue(ReplyNotificationTracker.getActiveTarget() != null)

        ReplyNotificationTracker.onNonReplyNotificationPosted()
        assertNull(ReplyNotificationTracker.getActiveTarget())
    }

    @Test
    fun `onMessageVisible cancels notification on match and clears target`() {
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = "prof-1",
            sessionId = "sess-123",
            completionId = "comp-456",
            textSnippet = "Reply text",
            generation = 1L,
        )

        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context = mockContext,
                scopeId = "prof-1",
                sessionId = "sess-123",
                completionId = "comp-456",
            )

        assertTrue(cancelled)
        verify { mockNotificationManager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
        assertNull(ReplyNotificationTracker.getActiveTarget())
    }

    @Test
    fun `onMessageVisible does not cancel if session does not match`() {
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = "prof-1",
            sessionId = "sess-123",
            completionId = "comp-456",
            textSnippet = "Reply text",
            generation = 1L,
        )

        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context = mockContext,
                scopeId = "prof-1",
                sessionId = "different-session",
                completionId = "comp-456",
            )

        assertFalse(cancelled)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }
        assertTrue(ReplyNotificationTracker.getActiveTarget() != null)
    }

    @Test
    fun `cancelReplyNotification refuses to cancel if newer generation was posted`() {
        ReplyNotificationTracker.onReplyNotificationPosted(
            scopeId = "prof-1",
            sessionId = "sess-123",
            completionId = "comp-newer",
            textSnippet = "Newer reply",
            generation = 5L,
        )

        val cancelled =
            ReplyNotificationTracker.cancelReplyNotification(
                context = mockContext,
                expectedGeneration = 4L, // older generation
            )

        assertFalse(cancelled)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }
    }

    @Test
    fun `cancelReplyNotification refuses to cancel if active notification is not a reply`() {
        ReplyNotificationTracker.setTargetForTest(null)
        ReplyNotificationTracker.activeNotificationProvider = {
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_ACTION,
                scopeId = "prof-1",
                sessionId = "sess-123",
                completionId = "comp-1",
                textSnippet = "Approval needed",
                generation = 1L,
                timestamp = 1000L,
            )
        }

        val cancelled =
            ReplyNotificationTracker.cancelReplyNotification(
                context = mockContext,
                expectedGeneration = 1L,
            )

        assertFalse(cancelled)
        verify(inverse = true) { mockNotificationManager.cancel(any()) }
    }

    @Test
    fun `cold start recovery restores target from active notification extras`() {
        ReplyNotificationTracker.setTargetForTest(null)
        ReplyNotificationTracker.activeNotificationProvider = {
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "prof-recovered",
                sessionId = "sess-recovered",
                completionId = "comp-recovered",
                textSnippet = "recovered snippet",
                generation = 3L,
                timestamp = 1000L,
            )
        }

        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context = mockContext,
                scopeId = "prof-recovered",
                sessionId = "sess-recovered",
                completionId = "comp-recovered",
            )

        assertTrue(cancelled)
        verify { mockNotificationManager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }
}
