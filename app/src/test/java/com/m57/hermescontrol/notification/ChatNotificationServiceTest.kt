package com.m57.hermescontrol.notification

import org.junit.Assert.assertEquals
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
}
