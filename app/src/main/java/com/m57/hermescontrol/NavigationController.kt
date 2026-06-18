package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Central navigation controller with deduplication guard.
 *
 * Primary screens (bottom nav tabs) clear the back stack and become the new
 * root — this matches Material's bottom-nav pattern where each tab has its own
 * back stack history (simplified: we just clear).
 *
 * B7 (Jun 18 2026): Never call `backStack.add()` directly from UI callbacks.
 * Always route through [navigateTo] to prevent stacking duplicate screen
 * entries that compete for touch events.
 */
object NavigationController {
    var backStack: NavBackStack<NavKey>? = null

    // Bottom-nav primary screens — tapping one clears the stack to root.
    private val primaryScreens: Set<NavKey> =
        setOf(
            ChatScreen,
            SkillsScreen,
            CronJobsScreen,
            SystemScreen,
            SettingsScreen,
        )

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (key in primaryScreens) {
            stack.clear()
        }
        stack.add(key)
    }

    fun add(key: NavKey) {
        backStack?.add(key)
    }

    fun goBack() {
        val stack = backStack ?: return
        if (stack.size > 1) stack.removeLastOrNull()
    }
}
