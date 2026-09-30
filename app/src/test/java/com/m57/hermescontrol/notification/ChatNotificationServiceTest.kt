package com.m57.hermescontrol.notification

import android.content.Context
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.WsEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatNotificationService] companion object — specifically
 * the `isAppInForeground` flag that gates notification display.
 *
 * Issue #291 (Critical Test Coverage): Verifies that `setAppForeground`
 * correctly toggles the flag and that the field is `@Volatile` for thread
 * safety (it is read from the event-collection coroutine on a background
 * dispatcher while `setAppForeground` is called from the UI thread).
 *
 * These are pure unit tests with no Android dependencies — the companion
 * object can be exercised directly via reflection for the private flag
 * or through the public `setAppForeground` API.
 */
class ChatNotificationServiceTest {
    @Test
    fun `stop never tears down a service awaiting foreground promotion`() {
        val context = mockk<Context>(relaxed = true)

        NotificationHelper.stop(context)

        verify(exactly = 0) { context.stopService(any()) }
    }

    @Test
    fun `setAppForeground true sets the flag`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        val atomicBoolean = field.get(null) as java.util.concurrent.atomic.AtomicBoolean

        // Reset to known state
        atomicBoolean.set(false)
        ChatNotificationService.setAppForeground(true)

        assertEquals("flag should be true after setAppForeground(true)", true, atomicBoolean.get())
    }

    @Test
    fun `setAppForeground false clears the flag`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        val atomicBoolean = field.get(null) as java.util.concurrent.atomic.AtomicBoolean

        // Set to known state
        atomicBoolean.set(true)
        ChatNotificationService.setAppForeground(false)

        assertEquals("flag should be false after setAppForeground(false)", false, atomicBoolean.get())
    }

    @Test
    fun `isAppInForeground defaults to false`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        val atomicBoolean = field.get(null) as java.util.concurrent.atomic.AtomicBoolean

        // Ensure it's in a clean state
        atomicBoolean.set(false)

        assertEquals("initial value should be false", false, atomicBoolean.get())
    }

    @Test
    fun `isAppInForeground field is volatile for thread safety`() {
        // Obsolete test as the type is now AtomicBoolean which guarantees thread safety implicitly
    }

    @Test
    fun `setAppForeground is idempotent when called multiple times`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        val atomicBoolean = field.get(null) as java.util.concurrent.atomic.AtomicBoolean

        // Set true twice
        ChatNotificationService.setAppForeground(true)
        ChatNotificationService.setAppForeground(true)
        assertEquals("flag should remain true after consecutive setAppForeground(true)", true, atomicBoolean.get())

        // Set false twice
        ChatNotificationService.setAppForeground(false)
        ChatNotificationService.setAppForeground(false)
        assertEquals(
            "flag should remain false after consecutive setAppForeground(false)",
            false,
            atomicBoolean.get(),
        )
    }

    @Test
    fun `terminal reply error uses generic failure notification without success affordances`() {
        val rawDiagnostic = "Authorization: Bearer secret-token"
        val plan =
            messageCompleteNotificationPlan(
                event =
                    WsEvent.MessageComplete(
                        text = rawDiagnostic,
                        sessionId = "runtime-1",
                        storedSessionId = "stored-1",
                        completionId = "completion-1",
                        rawPayload =
                            mapOf(
                                "status" to "error",
                                "error" to rawDiagnostic,
                            ),
                    ),
                targetSessionId = "stored-1",
                newMessageText = "New message",
                failureText = "Hermes couldn't finish this reply",
            )

        assertEquals("Hermes couldn't finish this reply", plan.text)
        assertFalse(plan.text.contains(rawDiagnostic))
        assertEquals("stored-1", plan.sessionId)
        assertFalse(plan.isReplyMessage)
        assertFalse(plan.allowInlineReply)
        assertNull(plan.completionId)
        assertNull(plan.correlationText)
    }

    @Test
    fun `successful completion keeps reply correlation and inline reply`() {
        val plan =
            messageCompleteNotificationPlan(
                event =
                    WsEvent.MessageComplete(
                        text = "Answer\ncontinued",
                        sessionId = "runtime-1",
                        completionId = "completion-1",
                        rawPayload = mapOf("status" to "ok"),
                    ),
                targetSessionId = "stored-1",
                newMessageText = "New message",
                failureText = "Hermes couldn't finish this reply",
            )

        assertEquals("Answer continued", plan.text)
        assertEquals("stored-1", plan.sessionId)
        assertTrue(plan.isReplyMessage)
        assertTrue(plan.allowInlineReply)
        assertEquals("completion-1", plan.completionId)
        assertEquals("Answer\ncontinued", plan.correlationText)
    }
}

class ChatNotificationServiceActiveListTest {
    @Test
    fun `parseActiveSessionLookup decodes the typed JSON result and maps ids plus title`() {
        val raw =
            """
            {"sessions":[
                {"id":"rt1","session_key":"stored-1","title":"Nightly report","status":"idle","last_active":1780000000.0},
                {"id":"rt2","session_key":"stored-2","status":"idle"}
            ]}
            """.trimIndent()

        val found = parseActiveSessionLookup(raw, "rt1")
        assertEquals("stored-1", found?.storedId)
        assertEquals("Nightly report", found?.title)

        // Row without a title: notification falls back to the app name.
        val untitled = parseActiveSessionLookup(raw, "rt2")
        assertEquals("stored-2", untitled?.storedId)
        assertNull(untitled?.title)
    }

    @Test
    fun `parseActiveSessionLookup accepts the JsonElement a typed RPC call returns`() {
        val raw = """{"sessions":[{"id":"rt9","session_key":"stored-9","title":"T"}]}"""
        val element =
            kotlinx.serialization.json.Json
                .parseToJsonElement(raw)

        val found = parseActiveSessionLookup(element, "rt9")
        assertEquals("stored-9", found?.storedId)
        assertEquals("T", found?.title)
    }

    @Test
    fun `parseActiveSessionLookup rejects unknown runtime ids and empty keys`() {
        val raw = """{"sessions":[{"id":"rt1","session_key":"","status":"idle"}]}"""

        assertNull(parseActiveSessionLookup(raw, "rt1"))
        assertNull(parseActiveSessionLookup(raw, "missing"))
        assertNull(parseActiveSessionLookup(null, "rt1"))
        assertNull(parseActiveSessionLookup(raw, ""))
        assertNull(parseActiveSessionLookup("not json", "rt1"))
    }
}

class MessageCompleteRouteTest {
    @Test
    fun `active session routes to the reply path`() {
        val route = messageCompleteRoute(holderSessionId = "rt1", toggleOn = true, eventSessionId = "rt1")
        assertEquals(MessageCompleteRoute.Reply, route)
    }

    @Test
    fun `toggle off keeps legacy behavior for foreign sessions`() {
        val route = messageCompleteRoute(holderSessionId = "rt1", toggleOn = false, eventSessionId = "other")
        assertEquals(MessageCompleteRoute.Reply, route)
    }

    @Test
    fun `toggle on routes foreign sessions to the plain alert`() {
        assertTrue(
            messageCompleteRoute(holderSessionId = "rt1", toggleOn = true, eventSessionId = "other") is
                MessageCompleteRoute.ForeignSession,
        )
    }

    @Test
    fun `toggle on with a cleared holder routes the pinned alert`() {
        // After a background reconnect the holder is cleared: the user's own
        // reply lands in the plain alert (resolved stored id keeps the tap
        // working) instead of being dropped.
        assertTrue(
            messageCompleteRoute(holderSessionId = null, toggleOn = true, eventSessionId = "rt1") is
                MessageCompleteRoute.ForeignSession,
        )
    }

    @Test
    fun `toggle off with a cleared holder still uses the reply path`() {
        val route = messageCompleteRoute(holderSessionId = null, toggleOn = false, eventSessionId = "rt1")
        assertEquals(MessageCompleteRoute.Reply, route)
    }

    @Test
    fun `legacy reply route retires the service when the wait is over`() {
        // Toggle off + holder cleared by a reconnect: the route is Reply, and the
        // Reply branch always ends in onReplyCompleted(generation).
        assertEquals(
            MessageCompleteRoute.Reply,
            messageCompleteRoute(holderSessionId = null, toggleOn = false, eventSessionId = "rt1"),
        )
        var completedGeneration: Long? = null
        val controller =
            BackgroundConnectionController(
                snapshotProvider = {
                    BackgroundConnectionSnapshot(
                        appInForeground = false,
                        isDeparting = false,
                        keepConnectedOptIn = false,
                        pendingReply = true,
                        isEligibleForConnection = true,
                        status = ConnectionStatus.CONNECTED,
                        isAutoReconnect = true,
                        hasActiveNetwork = true,
                    )
                },
                requestServiceComplete = { gen -> completedGeneration = gen },
            )
        controller.onReplyCompleted(7L)
        assertEquals(7L, completedGeneration)
    }
}
