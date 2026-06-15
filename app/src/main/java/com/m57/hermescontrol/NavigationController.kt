package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

object NavigationController {
    var backStack: NavBackStack<NavKey>? = null

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (key == ChatScreen || key == SkillsScreen || key == CronJobsScreen) {
            stack.clear()
        }
        stack.add(key)
    }

    fun add(key: NavKey) {
        backStack?.add(key)
    }

    fun goBack() {
        backStack?.removeLastOrNull()
    }
}
