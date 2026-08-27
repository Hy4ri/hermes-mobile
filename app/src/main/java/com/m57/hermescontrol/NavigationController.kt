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

    // Top-level primary screens (Chat, Skills, Cron, System, Settings)
    private val primaryScreens: MutableSet<NavKey> =
        mutableSetOf(
            ChatScreen,
            SkillsScreen,
            CronJobsScreen,
            SystemScreen,
            SettingsScreen,
        )

    /** Returns whether the given key is a primary top-level screen. */
    fun isPrimaryScreen(key: NavKey): Boolean = key in primaryScreens

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (isPrimaryScreen(key)) {
            stack.clear()
        }
        // Drawer dismissal is handled by DrawerGestureController (issue #619):
        // when a non-gesture sub-page composes, its HermesScaffold reconciles
        // drawerGesturesEnabled=false and the controller closes the drawer
        // itself via SideEffect. No synchronous closeDrawer callback here.
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
        if (stack.lastOrNull() == ChatScreen) {
            resetTo(HistoryScreen)
            return
        }
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (stack.size == 1) {
            stack.clear()
            stack.add(fallback)
        }
    }
}
