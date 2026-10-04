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

data class PendingNewChatNavigation(
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
    var pendingNewChatNavigation: PendingNewChatNavigation? by mutableStateOf(null)
        private set

    /**
     * The list screen a chat was opened from (History, Bots). Back out of that
     * chat returns here instead of exiting the app (Telegram chat-list parity).
     * Consumed by MainNavigation's root-chat back handler; cleared by any other
     * navigation so a stale target can never hijack a later back press.
     */
    var chatReturnScreen: NavKey? by mutableStateOf(null)
        private set

    val pendingSessionId: String? get() = pendingChatNavigation?.sessionId

    private var nextChatNavigationRequestId = 0L
    private var nextNewChatNavigationRequestId = 0L

    /**
     * Top-level primary screens (all drawer-accessible screens).
     * Navigating to any of these clears the stack to `[ChatScreen, key]` (or `[ChatScreen]` for Chat),
     * ensuring swiping back returns to ChatScreen.
     */
    fun isPrimaryScreen(key: NavKey): Boolean = key == ChatScreen || ScreenRegistry.ALL_SCREENS.any { it.key == key }

    /** Returns the armed return screen and disarms it. */
    fun consumeChatReturnScreen(): NavKey? = chatReturnScreen.also { chatReturnScreen = null }

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return

        // Any explicit navigation invalidates the pending chat return — only a
        // session/chat opened from a list arms it again (see [queueChatNavigation]).
        // This must precede the dedup guard: re-selecting Chat in the drawer is
        // still an explicit navigation and must not keep a stale return armed.
        chatReturnScreen = null

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
            // Connection management must return to Landing before the first login.
            val root = if (stack.firstOrNull() == LandingScreen) LandingScreen else ChatScreen
            stack.clear()
            stack.add(root)
            stack.add(key)
            return
        }

        // Subscreen / drill-down navigation: append to current stack
        stack.add(key)
    }

    fun openChatSession(sessionId: String) {
        // Opened from a list (History cards, search results, Bots): arm the
        // return so back from the chat lands back on that list instead of
        // exiting the app (Telegram chat-list parity).
        queueChatNavigation(sessionId, scrollToBottom = false, returnToList = true)
    }

    fun openChatSessionFromNotification(sessionId: String) {
        // A notification open is not a list navigation: back keeps the classic
        // root-chat exit flow.
        queueChatNavigation(sessionId, scrollToBottom = true, returnToList = false)
    }

    fun openNewChat() {
        pendingNewChatNavigation =
            PendingNewChatNavigation(
                requestId = ++nextNewChatNavigationRequestId,
            )
        navigateTo(ChatScreen)
    }

    private fun queueChatNavigation(
        sessionId: String,
        scrollToBottom: Boolean,
        returnToList: Boolean,
    ) {
        if (sessionId.isBlank()) return
        // Snapshot the origin BEFORE navigateTo resets the stack to [ChatScreen].
        val returnScreen =
            if (returnToList) {
                backStack
                    ?.lastOrNull()
                    ?.takeIf { it != ChatScreen && isPrimaryScreen(it) }
            } else {
                null
            }
        pendingChatNavigation =
            PendingChatNavigation(
                sessionId = sessionId,
                scrollToBottom = scrollToBottom,
                requestId = ++nextChatNavigationRequestId,
            )
        navigateTo(ChatScreen)
        // Set after navigateTo so the reset itself does not disarm the return.
        chatReturnScreen = returnScreen
    }

    fun consumePendingChatNavigation(): PendingChatNavigation? =
        pendingChatNavigation.also { pendingChatNavigation = null }

    fun consumePendingNewChatNavigation(): PendingNewChatNavigation? =
        pendingNewChatNavigation.also { pendingNewChatNavigation = null }

    fun consumePendingSessionId(): String? = consumePendingChatNavigation()?.sessionId

    /** Clear the stack and navigate to the given screen atomically. */
    fun resetTo(screen: NavKey) {
        val stack = backStack ?: return
        chatReturnScreen = null
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
