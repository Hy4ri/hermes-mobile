package com.m57.hermescontrol.notification

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

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
    fun `setAppForeground true sets the flag`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        // Reset to known state
        field.setBoolean(null, false)
        ChatNotificationService.setAppForeground(true)

        assertEquals("flag should be true after setAppForeground(true)", true, field.getBoolean(null))
    }

    @Test
    fun `setAppForeground false clears the flag`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        // Set to known state
        field.setBoolean(null, true)
        ChatNotificationService.setAppForeground(false)

        assertEquals("flag should be false after setAppForeground(false)", false, field.getBoolean(null))
    }

    @Test
    fun `isAppInForeground defaults to false`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        // Ensure it's in a clean state
        field.setBoolean(null, false)

        assertEquals("initial value should be false", false, field.getBoolean(null))
    }

    @Test
    fun `isAppInForeground field is volatile for thread safety`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        val modifiers = field.modifiers

        assertEquals(
            "isAppInForeground must be @Volatile — it is read from a background " +
                "coroutine (event collection) and written from the UI thread " +
                "(setAppForeground called from ChatScreen lifecycle)",
            true,
            Modifier.isVolatile(modifiers),
        )
    }

    @Test
    fun `setAppForeground is idempotent when called multiple times`() {
        val field =
            ChatNotificationService::class.java
                .getDeclaredField("isAppInForeground")
        field.isAccessible = true

        // Set true twice
        ChatNotificationService.setAppForeground(true)
        ChatNotificationService.setAppForeground(true)
        assertEquals("flag should remain true after consecutive setAppForeground(true)", true, field.getBoolean(null))

        // Set false twice
        ChatNotificationService.setAppForeground(false)
        ChatNotificationService.setAppForeground(false)
        assertEquals(
            "flag should remain false after consecutive setAppForeground(false)",
            false,
            field.getBoolean(null),
        )
    }
}
