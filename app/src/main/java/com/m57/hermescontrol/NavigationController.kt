package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.local.AuthSessionState

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
    var pendingSessionId: String? = null

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
        val effectiveFallback =
            if (AuthSessionState.signInRequired.value && (fallback == ChatScreen || isPrimaryScreen(fallback))) {
                LandingScreen
            } else {
                fallback
            }
        if (stack.size > 1) {
            stack.removeLastOrNull()
            if (AuthSessionState.signInRequired.value &&
                stack.lastOrNull() != AuthLoginScreen &&
                stack.lastOrNull() != LandingScreen
            ) {
                stack.clear()
                stack.add(LandingScreen)
            }
        } else if (stack.size == 1) {
            stack.clear()
            stack.add(effectiveFallback)
        }
    }
}
