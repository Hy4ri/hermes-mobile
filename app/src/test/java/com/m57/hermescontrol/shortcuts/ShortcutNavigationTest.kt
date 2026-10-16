package com.m57.hermescontrol.shortcuts

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.DataScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutNavigationTest {
    @After
    fun cleanup() {
        NavigationController.backStack = null
        NavigationController.consumePendingChatNavigation()
        NavigationController.consumePendingNewChatNavigation()
    }

    @Test
    fun `history action repeated on a warm stack never adds duplicate entries`() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        repeat(3) { NavigationController.navigateTo(HistoryScreen) }
        assertEquals(listOf(ChatScreen, HistoryScreen), stack.toList())
    }

    @Test
    fun `new chat action queues a fresh session even when chat is already visible`() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        NavigationController.openNewChat()
        val first = NavigationController.consumePendingNewChatNavigation()
        NavigationController.openNewChat()
        val second = NavigationController.consumePendingNewChatNavigation()
        assertEquals(listOf(ChatScreen), stack.toList())
        assertEquals(requireNotNull(first).requestId + 1, requireNotNull(second).requestId)
        assertNull(NavigationController.consumePendingNewChatNavigation())
    }

    @Test
    fun `session shortcut carries validated ownership through deferred navigation`() {
        val scope = DataScope("connection", "http://localhost:8080", "default", 3)
        NavigationController.backStack = NavBackStack<NavKey>(ChatScreen, HistoryScreen)
        NavigationController.openChatSessionFromShortcut("chat/id", scope)
        val request = requireNotNull(NavigationController.consumePendingChatNavigation())
        assertEquals("chat/id", request.sessionId)
        assertEquals(scope, request.scope)
        assertEquals(true, request.scrollToBottom)
        assertEquals(listOf(ChatScreen), NavigationController.backStack?.toList())
    }

    @Test
    fun `latest new chat action supersedes a pending conversation target`() {
        val scope = DataScope("connection", "http://localhost:8080", "default")
        NavigationController.openChatSessionFromShortcut("chat", scope)
        NavigationController.openNewChat()
        assertNull(NavigationController.consumePendingChatNavigation())
    }

    @Test
    fun `latest conversation target supersedes a pending new chat action`() {
        val scope = DataScope("connection", "http://localhost:8080", "default")
        NavigationController.openNewChat()
        NavigationController.openChatSessionFromShortcut("chat", scope)
        assertNull(NavigationController.consumePendingNewChatNavigation())
    }
}
