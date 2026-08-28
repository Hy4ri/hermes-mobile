package com.m57.hermescontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

data class PendingChatNavigation(
    val sessionId: String,
    val scrollToBottom: Boolean,
    val requestId: Long,
)

/**
 * Central navigation controller with deduplication guard.
 *
 * Top-level primary screens (drawer items) clear the back stack and become the new
 * root — this matches navigation drawer patterns where switching top-level sections
 * resets the stack.
 *
 * B7 (Jun 18 2026): Never call `backStack.add()` directly from UI callbacks.
 * Always route through [navigateTo] to prevent stacking duplicate screen
 * entries that compete for touch events.
 */
object NavigationController {
    var backStack: NavBackStack<NavKey>? = null
    var pendingChatNavigation: PendingChatNavigation? by mutableStateOf(null)
        private set
    val pendingSessionId: String? get() = pendingChatNavigation?.sessionId

    private var nextChatNavigationRequestId = 0L

    /**
     * Top-level primary screens (all drawer-accessible screens).
     * Navigating to any of these clears the stack to `[ChatScreen, key]` (or `[ChatScreen]` for Chat),
     * ensuring swiping back returns to ChatScreen.
     */
    fun isPrimaryScreen(key: NavKey): Boolean = key == ChatScreen || ScreenRegistry.ALL_SCREENS.any { it.key == key }

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (key == ChatScreen) {
            stack.clear()
            stack.add(ChatScreen)
            return
        }

        if (key == LandingScreen) {
            stack.clear()
            stack.add(LandingScreen)
            return
        }

        if (isPrimaryScreen(key)) {
            stack.clear()
            stack.add(ChatScreen)
            stack.add(key)
            return
        }

        // Subscreen / drill-down navigation: append to current stack
        stack.add(key)
    }

    fun openChatSession(sessionId: String) {
        queueChatNavigation(sessionId, scrollToBottom = false)
    }

    fun openChatSessionFromNotification(sessionId: String) {
        queueChatNavigation(sessionId, scrollToBottom = true)
    }

    private fun queueChatNavigation(
        sessionId: String,
        scrollToBottom: Boolean,
    ) {
        if (sessionId.isBlank()) return
        pendingChatNavigation =
            PendingChatNavigation(
                sessionId = sessionId,
                scrollToBottom = scrollToBottom,
                requestId = ++nextChatNavigationRequestId,
            )
        navigateTo(ChatScreen)
    }

    fun consumePendingChatNavigation(): PendingChatNavigation? =
        pendingChatNavigation.also { pendingChatNavigation = null }

    fun consumePendingSessionId(): String? = consumePendingChatNavigation()?.sessionId

    /** Clear the stack and navigate to the given screen atomically. */
    fun resetTo(screen: NavKey) {
        val stack = backStack ?: return
        stack.clear()
        stack.add(screen)
    }

    /**
     * Navigate back one step, or fall back to [fallback] when the stack has only one item.
     * Never leaves the stack empty.
     */
    fun goBack(fallback: NavKey = ChatScreen) {
        val stack = backStack ?: return
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (stack.size == 1) {
            if (stack.lastOrNull() != fallback && stack.lastOrNull() != LandingScreen) {
                stack.clear()
                stack.add(fallback)
            }
        }
    }
}
