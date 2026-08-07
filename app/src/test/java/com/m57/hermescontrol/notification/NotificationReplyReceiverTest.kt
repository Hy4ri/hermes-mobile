package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NotificationReplyReceiver] — the BroadcastReceiver that
 * handles inline reply actions on chat notifications.
 *
 * Issue #291 (Critical Test Coverage): Verifies the full flow —
 *   (a) WS message is confirmed via [HermesWsClient.request]
 *   (b) Reply is persisted to Room via [ChatMessageDao.upsert]
 *   (c) A "Replied" confirmation notification is posted
 *   (d) Invalid/missing input is handled gracefully (no crash)
 *   (e) Room failures are logged and the pending result is still finished
 *
 * Uses [HermesDatabase.setForTest] for deterministic Room mocking and
 * [spyk] for [BroadcastReceiver.goAsync] interception.
 *
 * NOTE: This does NOT use coVerify for suspend functions (incompatible with
 * MockK 1.13.12). Instead, call counting via coEvery + answers is used.
 */
class NotificationReplyReceiverTest {
    private lateinit var mockContext: Context
    private lateinit var mockIntent: Intent
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockPendingResult: BroadcastReceiver.PendingResult
    private lateinit var mockDb: HermesDatabase
    private lateinit var mockDao: ChatMessageDao
    private lateinit var receiver: NotificationReplyReceiver
    private lateinit var mockNotification: Notification

    // Call counters for suspend function tracking
    private var upsertCallCount = 0

    @Before
    fun setUp() {
        upsertCallCount = 0
        ActiveSessionHolder.clear()

        // Mock Android framework statics (same pattern as HermesWsClientTest)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        // Mock RemoteInput static method
        mockkStatic(RemoteInput::class)

        // Mock Context
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager

        // Mock Intent
        mockIntent = mockk(relaxed = true)

        // Mock Room DB via its test injection point
        mockDao = mockk(relaxed = true)
        coEvery { mockDao.upsert(any<ChatMessageEntity>()) } answers {
            upsertCallCount++
            Unit
        }
        coEvery { mockDao.sessionExists(any<String>()) } returns true
        mockDb = mockk(relaxed = true)
        every { mockDb.chatMessageDao() } returns mockDao
        HermesDatabase.setForTest(mockDb)

        // Mock PendingResult
        mockPendingResult = mockk(relaxed = true)

        // Mock Notification
        mockNotification = mockk(relaxed = true)

        // Mock HermesWsClient singleton
        mockkObject(HermesWsClient)
        every { HermesWsClient.request(any(), any(), any()) } answers {
            val method = arg<String>(0)
            val params = arg<Map<String, Any>>(1)
            val result =
                if (method == WsMethods.SESSION_RESUME) {
                    mapOf("session_id" to "runtime-${params["session_id"]}")
                } else {
                    mapOf("accepted" to true)
                }
            CompletableDeferred<Any?>(result)
        }

        // Create receiver with overridden goAsyncCompat() and buildReplyNotification()
        // — BroadcastReceiver.goAsync() is final (Java) and cannot be mocked via spyk,
        // and NotificationCompat.Builder.build() calls framework methods that throw
        // "not mocked" in unit tests.
        receiver =
            object : NotificationReplyReceiver() {
                override fun goAsyncCompat(): BroadcastReceiver.PendingResult = mockPendingResult

                override fun buildReplyNotification(context: Context): Notification = mockNotification
            }
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
        HermesDatabase.setForTest(null)
        unmockkAll()
    }

    // ── Happy path: valid reply ────────────────────────────────────────────

    @Test
    fun `valid reply sends message via WebSocket`() {
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500) // Need to wait for coroutine

        verify {
            HermesWsClient.request(
                WsMethods.PROMPT_SUBMIT,
                match { it["session_id"] == "runtime-session-abc" && it["text"] == "Hello" },
                any(),
            )
        }
    }

    @Test
    fun `valid reply persists message to Room`() {
        val entitySlot = slot<ChatMessageEntity>()
        coEvery { mockDao.upsert(capture(entitySlot)) } answers {
            upsertCallCount++
            Unit
        }

        givenValidReply("session-abc", "Hello")
        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        assertEquals("upsert should have been called", 1, upsertCallCount)
        assertEquals("session-abc", entitySlot.captured.sessionId)
        assertEquals("Hello", entitySlot.captured.content)
        assertEquals("USER", entitySlot.captured.role)
        assertNotNull("entity must have a UUID id", entitySlot.captured.id)
        assertTrue("id should be a non-empty UUID", entitySlot.captured.id.isNotBlank())
        assertTrue("timestamp should be positive", entitySlot.captured.timestamp > 0)
    }

    @Test
    fun `valid reply posts replied notification`() {
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500) // Need to wait for coroutine

        verify {
            mockNotificationManager.notify(
                ChatNotificationService.PENDING_NOTIFICATION_ID,
                any(),
            )
        }
    }

    @Test
    fun `valid reply finishes pending result after Room save`() {
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        verify { mockPendingResult.finish() }
    }

    // ── Boundary / invalid input ──────────────────────────────────────────

    @Test
    fun `reply with empty text does nothing`() {
        givenValidReply("session-abc", "   ")

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for empty reply", upsertCallCount > 0)
    }

    @Test
    fun `reply with null text does nothing`() {
        givenEmptyRemoteInput("session-abc")

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for null reply", upsertCallCount > 0)
    }

    @Test
    fun `reply with null sessionId does nothing`() {
        givenValidReply(null, "Hello")

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for null sessionId", upsertCallCount > 0)
    }

    @Test
    fun `reply with empty sessionId does nothing`() {
        givenValidReply("", "Hello")

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for empty sessionId", upsertCallCount > 0)
    }

    @Test
    fun `reply with blank sessionId does nothing`() {
        givenValidReply("   ", "Hello")

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for blank sessionId", upsertCallCount > 0)
    }

    @Test
    fun `null remote input does nothing`() {
        every { RemoteInput.getResultsFromIntent(mockIntent) } returns null
        every { mockIntent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID) } returns "session-abc"

        receiver.onReceive(mockContext, mockIntent)

        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        assertFalse("upsert should NOT be called for null remote input", upsertCallCount > 0)
    }

    @Test
    fun `reply with unknown sessionId does nothing`() {
        givenValidReply("unknown-session", "Hello")

        // Mock sessionExists to return false
        coEvery { mockDao.sessionExists("unknown-session") } returns false

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        // HermesWsClient should not be called
        verify(inverse = true) { HermesWsClient.sendMessage(any(), any()) }
        // DB upsert should not be called
        assertFalse("upsert should NOT be called for unknown sessionId", upsertCallCount > 0)
        // Pending result should still be finished
        verify { mockPendingResult.finish() }
        // Warning should be logged
        verify { android.util.Log.w("NotificationReply", "Ignoring reply for unknown session: unknown-session") }
    }

    @Test
    fun `Room failure logs error and finishes pending result`() {
        givenValidReply("session-abc", "Hello")

        // Make upsert throw
        coEvery { mockDao.upsert(any<ChatMessageEntity>()) } throws RuntimeException("DB full")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        // Error should be logged
        verify { android.util.Log.e("NotificationReply", any<String>(), any()) }

        // Pending result must still finish even on Room failure
        verify { mockPendingResult.finish() }
    }

    @Test
    fun `reply after reconnect resumes stored id and never sends stale runtime id`() {
        ActiveSessionHolder.set("stale-runtime", "session-abc")
        ActiveSessionHolder.clear()
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        verify {
            HermesWsClient.request(
                WsMethods.SESSION_RESUME,
                match { it["session_id"] == "session-abc" && it["omit_messages"] == true },
                any(),
            )
            HermesWsClient.request(
                WsMethods.PROMPT_SUBMIT,
                match { it["session_id"] == "runtime-session-abc" },
                any(),
            )
        }
        verify(inverse = true) {
            HermesWsClient.request(
                WsMethods.PROMPT_SUBMIT,
                match { it["session_id"] == "stale-runtime" },
                any(),
            )
        }
    }

    @Test
    fun `reply is persisted and confirmed only after send succeeds`() {
        val sendResult = CompletableDeferred<Any?>()
        every { HermesWsClient.request(WsMethods.PROMPT_SUBMIT, any(), any()) } returns sendResult
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(100)

        assertEquals(0, upsertCallCount)
        verify(inverse = true) { mockNotificationManager.notify(any(), any()) }

        sendResult.complete(mapOf("accepted" to true))
        Thread.sleep(500)

        assertEquals(1, upsertCallCount)
        verify { mockNotificationManager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, any()) }
    }

    @Test
    fun `failed send is neither persisted nor confirmed`() {
        every { HermesWsClient.request(WsMethods.PROMPT_SUBMIT, any(), any()) } returns
            CompletableDeferred<Any?>().apply { completeExceptionally(IllegalStateException("rejected")) }
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        assertEquals(0, upsertCallCount)
        verify(inverse = true) { mockNotificationManager.notify(any(), any()) }
        verify { mockPendingResult.finish() }
    }

    @Test
    fun `failed resume remains retryable on the next reply`() {
        var resumeAttempts = 0
        every { HermesWsClient.request(WsMethods.SESSION_RESUME, any(), any()) } answers {
            resumeAttempts++
            if (resumeAttempts == 1) {
                CompletableDeferred<Any?>().apply { completeExceptionally(IllegalStateException("rejected")) }
            } else {
                CompletableDeferred<Any?>(mapOf("session_id" to "runtime-session-abc"))
            }
        }
        givenValidReply("session-abc", "Hello")

        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)
        receiver.onReceive(mockContext, mockIntent)
        Thread.sleep(500)

        assertEquals(2, resumeAttempts)
        assertEquals(1, upsertCallCount)
        assertEquals("runtime-session-abc", ActiveSessionHolder.resolveRuntimeSessionId("session-abc"))
    }

    @Test
    fun `Room hang triggers timeout and finishes pending result`() {
        givenValidReply("session-abc", "Hello")

        // Make upsert hang indefinitely
        coEvery { mockDao.upsert(any<ChatMessageEntity>()) } coAnswers {
            kotlinx.coroutines.delay(10000)
        }

        receiver.onReceive(mockContext, mockIntent)

        // Wait longer than the 5-second timeout, but less than the 10-second delay
        Thread.sleep(6000)

        // Timeout should be caught in catch block and logged
        verify { android.util.Log.e("NotificationReply", any<String>(), any()) }

        // Pending result must still finish after timeout
        verify { mockPendingResult.finish() }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun givenValidReply(
        sessionId: String?,
        replyText: String,
    ) {
        val mockRemoteInputResult = mockk<android.os.Bundle>(relaxed = true)
        every {
            mockRemoteInputResult.getCharSequence(NotificationReplyReceiver.KEY_TEXT_REPLY)
        } returns replyText
        every { RemoteInput.getResultsFromIntent(mockIntent) } returns mockRemoteInputResult
        every { mockIntent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID) } returns sessionId
    }

    private fun givenEmptyRemoteInput(sessionId: String?) {
        val mockRemoteInputResult = mockk<android.os.Bundle>(relaxed = true)
        every {
            mockRemoteInputResult.getCharSequence(NotificationReplyReceiver.KEY_TEXT_REPLY)
        } returns null
        every { RemoteInput.getResultsFromIntent(mockIntent) } returns mockRemoteInputResult
        every { mockIntent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID) } returns sessionId
    }
}
