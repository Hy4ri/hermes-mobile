package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NavigationController] — the central navigation guard that
 * prevents duplicate screen entries on the back stack and enforces
 * swipe-back-to-chat hierarchy.
 */
class NavigationControllerTest {
    @Before
    fun setUp() {
        // Start fresh — no pinned back stack from a previous test
        NavigationController.backStack = null
        NavigationController.consumePendingSessionId()
    }

    @After
    fun tearDown() {
        NavigationController.backStack = null
        NavigationController.consumePendingSessionId()
    }

    // ── Dedup guard: navigateTo with same key ──────────────────────────────

    @Test
    fun `navigateTo with null backStack does nothing`() {
        NavigationController.backStack = null
        NavigationController.navigateTo(ChatScreen)
        assertNull("backStack should remain null when not initialised", NavigationController.backStack)
    }

    @Test
    fun `navigateTo on same primary screen is a no-op`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, SkillsScreen)
        NavigationController.backStack = backStack
        val sizeBefore = backStack.size

        NavigationController.navigateTo(SkillsScreen)

        assertEquals("stack size should not change when navigating to the same screen", sizeBefore, backStack.size)
        assertEquals("top of stack should still be SkillsScreen", SkillsScreen, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo on same subscreen is a no-op`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, SettingsScreen, SettingsAppearance)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(SettingsAppearance)

        assertEquals(3, backStack.size)
        assertEquals(SettingsAppearance, backStack.lastOrNull())
    }

    // ── Primary-screen behaviour: resets stack to [ChatScreen, Target] ───────

    @Test
    fun `navigateTo on a different primary screen resets stack with ChatScreen as root`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(SkillsScreen)
        assertEquals(2, backStack.size)
        assertEquals(ChatScreen, backStack.firstOrNull())
        assertEquals(SkillsScreen, backStack.lastOrNull())

        // Now navigate to another primary screen (e.g. ProfilesScreen)
        NavigationController.navigateTo(ProfilesScreen)
        assertEquals("primary screen navigation should reset stack to [ChatScreen, target]", 2, backStack.size)
        assertEquals(ChatScreen, backStack.firstOrNull())
        assertEquals(ProfilesScreen, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo ChatScreen clears stack to single ChatScreen root`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, ProfilesScreen)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(ChatScreen)

        assertEquals("chat navigation should result in single root entry", 1, backStack.size)
        assertEquals(ChatScreen, backStack.lastOrNull())
    }

    // ── Subscreen behaviour: appends to stack ───────────────────────────────

    @Test
    fun `navigateTo on a subscreen appends to the current stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, SettingsScreen)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(SettingsAppearance)
        assertEquals(3, backStack.size)
        assertEquals(SettingsAppearance, backStack.lastOrNull())

        NavigationController.navigateTo(SettingsAbout)
        assertEquals(4, backStack.size)
        assertEquals(SettingsAbout, backStack.lastOrNull())
    }

    @Test
    fun `openChatSession navigates once and exposes a consumable session id`() {
        val backStack = NavBackStack<NavKey>(HistoryScreen)
        NavigationController.backStack = backStack

        NavigationController.openChatSession("stored-session")

        assertEquals(ChatScreen, backStack.lastOrNull())
        assertEquals("stored-session", NavigationController.consumePendingSessionId())
        assertNull(NavigationController.consumePendingSessionId())
    }

    @Test
    fun `openChatSession ignores blank ids`() {
        val backStack = NavBackStack<NavKey>(HistoryScreen)
        NavigationController.backStack = backStack

        NavigationController.openChatSession("   ")

        assertEquals(HistoryScreen, backStack.lastOrNull())
        assertNull(NavigationController.consumePendingSessionId())
    }

    @Test
    fun `notification chat request asks for bottom even when chat is already active`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        NavigationController.openChatSessionFromNotification("stored-session")
        val first = NavigationController.consumePendingChatNavigation()
        NavigationController.openChatSessionFromNotification("stored-session")
        val second = NavigationController.consumePendingChatNavigation()

        assertEquals(1, backStack.size)
        assertEquals("stored-session", first?.sessionId)
        assertTrue(first?.scrollToBottom == true)
        assertTrue(second?.scrollToBottom == true)
        assertTrue("repeated taps must be distinct events", first?.requestId != second?.requestId)
    }

    @Test
    fun `history chat request does not force bottom`() {
        NavigationController.backStack = NavBackStack<NavKey>(HistoryScreen)

        NavigationController.openChatSession("stored-session")

        assertFalse(NavigationController.consumePendingChatNavigation()?.scrollToBottom == true)
    }

    // ── resetTo: atomic clear + navigate ──────────────────────────────────

    @Test
    fun `resetTo clears the stack and sets the target screen`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, ProfilesScreen, KeysScreen)
        NavigationController.backStack = backStack

        NavigationController.resetTo(SettingsScreen)

        assertEquals(1, backStack.size)
        assertEquals(SettingsScreen, backStack.lastOrNull())
    }

    @Test
    fun `resetTo with null backStack does nothing`() {
        NavigationController.backStack = null
        NavigationController.resetTo(ChatScreen)

        assertNull(NavigationController.backStack)
    }

    // ── goBack: pops stack, preserves chat root ───────────────────────────

    @Test
    fun `goBack removes the top screen when stack has more than one`() {
        val backStack = NavBackStack<NavKey>(ChatScreen, SettingsScreen, SettingsAppearance)
        NavigationController.backStack = backStack

        NavigationController.goBack()

        assertEquals(2, backStack.size)
        assertEquals(SettingsScreen, backStack.lastOrNull())

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals(ChatScreen, backStack.lastOrNull())
    }

    @Test
    fun `goBack on root ChatScreen keeps ChatScreen`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals(ChatScreen, backStack.lastOrNull())
    }

    @Test
    fun `goBack falls back to default screen when non-root stack has one item`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreen)
        NavigationController.backStack = backStack

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals("default fallback should be ChatScreen", ChatScreen, backStack.lastOrNull())
    }

    @Test
    fun `goBack with custom fallback uses the given screen`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreen)
        NavigationController.backStack = backStack

        NavigationController.goBack(fallback = SkillsScreen)

        assertEquals(1, backStack.size)
        assertEquals(SkillsScreen, backStack.lastOrNull())
    }

    @Test
    fun `goBack with null backStack does nothing`() {
        NavigationController.backStack = null
        NavigationController.goBack()

        assertNull(NavigationController.backStack)
    }

    // ── primaryScreens ────────────────────────────────────────────────────

    @Test
    fun `isPrimaryScreen returns true for all drawer screens`() {
        assertTrue("ChatScreen should be primary", NavigationController.isPrimaryScreen(ChatScreen))
        assertTrue("SkillsScreen should be primary", NavigationController.isPrimaryScreen(SkillsScreen))
        assertTrue("CronJobsScreen should be primary", NavigationController.isPrimaryScreen(CronJobsScreen))
        assertTrue("SystemScreen should be primary", NavigationController.isPrimaryScreen(SystemScreen))
        assertTrue("SettingsScreen should be primary", NavigationController.isPrimaryScreen(SettingsScreen))
        assertTrue("ProfilesScreen should be primary", NavigationController.isPrimaryScreen(ProfilesScreen))
        assertTrue("LogsScreen should be primary", NavigationController.isPrimaryScreen(LogsScreen))
        assertTrue("ConfigScreen should be primary", NavigationController.isPrimaryScreen(ConfigScreen))
        assertTrue("BotsScreen should be primary", NavigationController.isPrimaryScreen(BotsScreen))
        assertTrue("HistoryScreen should be primary", NavigationController.isPrimaryScreen(HistoryScreen))
    }

    @Test
    fun `isPrimaryScreen returns false for subscreen detail keys`() {
        assertFalse(
            "SettingsAppearance should NOT be primary",
            NavigationController.isPrimaryScreen(SettingsAppearance),
        )
        assertFalse(
            "SettingsConnection should NOT be primary",
            NavigationController.isPrimaryScreen(SettingsConnection),
        )
        assertFalse("SettingsLanguage should NOT be primary", NavigationController.isPrimaryScreen(SettingsLanguage))
        assertFalse("SettingsChat should NOT be primary", NavigationController.isPrimaryScreen(SettingsChat))
        assertFalse("SettingsBehavior should NOT be primary", NavigationController.isPrimaryScreen(SettingsBehavior))
        assertFalse("SettingsAbout should NOT be primary", NavigationController.isPrimaryScreen(SettingsAbout))
        assertFalse(
            "ToolsetDetailKey should NOT be primary",
            NavigationController.isPrimaryScreen(ToolsetDetailKey("tool")),
        )
        assertFalse(
            "MemoryProviderDetailKey should NOT be primary",
            NavigationController.isPrimaryScreen(MemoryProviderDetailKey("mem")),
        )
        assertFalse("GroupChatKey should NOT be primary", NavigationController.isPrimaryScreen(GroupChatKey("room")))
        assertFalse("AuthLoginScreen should NOT be primary", NavigationController.isPrimaryScreen(AuthLoginScreen))
    }
}
