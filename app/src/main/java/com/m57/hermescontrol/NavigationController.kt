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

    // Bottom-nav primary screens — dynamic, updated by Navigation.kt via
    // updatePrimaryScreens() when the user customises the bottom nav bar.
    // Default matches the default 5 bottom-nav items.
    private val primaryScreens: MutableSet<NavKey> =
        mutableSetOf(
            ChatScreen,
            SkillsScreen,
            CronJobsScreen,
            SystemScreen,
            SettingsScreen,
        )

    /** Returns whether the given key is a primary (bottom-nav) screen. */
    fun isPrimaryScreen(key: NavKey): Boolean = key in primaryScreens

    /** Replace the primary screen set. Called by Navigation.kt when the
     *  bottom-nav item config changes. */
    fun updatePrimaryScreens(keys: Set<NavKey>) {
        primaryScreens.clear()
        primaryScreens.addAll(keys)
    }

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (isPrimaryScreen(key)) {
            stack.clear()
        }
        stack.add(key)
    }

    fun add(key: NavKey) {
        backStack?.add(key)
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
            stack.clear()
            stack.add(fallback)
        }
    }
}
