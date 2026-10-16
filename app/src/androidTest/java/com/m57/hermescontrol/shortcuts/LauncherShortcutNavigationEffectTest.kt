package com.m57.hermescontrol.shortcuts

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherShortcutNavigationEffectTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun cleanup() {
        compose.runOnIdle {
            LauncherShortcuts.pendingRequest?.let(LauncherShortcuts::consume)
            NavigationController.consumePendingChatNavigation()
            NavigationController.consumePendingNewChatNavigation()
            NavigationController.backStack = null
        }
    }

    @Test
    fun coldHistoryRequestWaitsForLoginThenNavigatesExactlyOnce() {
        val authenticated = mutableStateOf(false)
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        LauncherShortcuts.accept(Intent().setAction(LauncherShortcuts.ACTION_HISTORY))
        compose.setContent { LauncherShortcutNavigationEffect(authenticated.value) }
        compose.runOnIdle {
            assertEquals(listOf(ChatScreen), stack.toList())
            assertNotNull(LauncherShortcuts.pendingRequest)
            authenticated.value = true
        }
        compose.runOnIdle {
            assertEquals(listOf(ChatScreen, HistoryScreen), stack.toList())
            assertNull(LauncherShortcuts.pendingRequest)
            NavigationController.navigateTo(ChatScreen)
        }
        compose.runOnIdle { assertEquals(listOf(ChatScreen), stack.toList()) }
    }

    @Test
    fun warmNewChatAndRepeatedHistoryActionsUseExistingNavigationWithoutDuplicates() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        compose.setContent { LauncherShortcutNavigationEffect(hasToken = true) }
        compose.runOnIdle {
            LauncherShortcuts.accept(Intent().setAction(LauncherShortcuts.ACTION_NEW_CHAT))
        }
        compose.runOnIdle {
            assertNotNull(NavigationController.pendingNewChatNavigation)
            assertEquals(listOf(ChatScreen), stack.toList())
            LauncherShortcuts.accept(Intent().setAction(LauncherShortcuts.ACTION_HISTORY))
        }
        compose.runOnIdle {
            assertNull(NavigationController.pendingNewChatNavigation)
            assertEquals(listOf(ChatScreen, HistoryScreen), stack.toList())
            LauncherShortcuts.accept(Intent().setAction(LauncherShortcuts.ACTION_HISTORY))
        }
        compose.runOnIdle {
            assertEquals(listOf(ChatScreen, HistoryScreen), stack.toList())
            assertNull(LauncherShortcuts.pendingRequest)
        }
    }
}
