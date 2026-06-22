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
 * prevents duplicate screen entries on the back stack.
 *
 * Issue #291 (Critical Test Coverage): Verifies the deduplication logic at
 * line 45 (`if (stack.lastOrNull() == key) return`) and the primary-screen
 * stack-clearing behavior at line 47-49.
 *
 * All tests are pure Kotlin with no Android dependencies — they only exercise
 * [NavigationController]'s companion object methods against a real
 * [NavBackStack] instance.
 */
class NavigationControllerTest {
    @Before
    fun setUp() {
        // Start fresh — no pinned back stack from a previous test
        NavigationController.backStack = null
    }

    @After
    fun tearDown() {
        NavigationController.backStack = null
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
        val backStack = NavBackStack<NavKey>(SkillsScreen)
        NavigationController.backStack = backStack
        val sizeBefore = backStack.size

        NavigationController.navigateTo(SkillsScreen)

        assertEquals("stack size should not change when navigating to the same screen", sizeBefore, backStack.size)
        assertEquals("top of stack should still be SkillsScreen", SkillsScreen, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo on same non-primary screen is a no-op`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreen)
        NavigationController.backStack = backStack

        // ProfilesScreen is NOT in the default primaryScreens so it stays on the stack
        NavigationController.navigateTo(ProfilesScreen)

        assertEquals(1, backStack.size)
        assertEquals(ProfilesScreen, backStack.lastOrNull())
    }

    // ── Primary-screen behaviour: stack clearing ──────────────────────────

    @Test
    fun `navigateTo on a different primary screen clears the stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        // Navigate to a non-primary screen first (should add to stack)
        NavigationController.navigateTo(LogsScreen)
        assertEquals(2, backStack.size)

        // Now navigate to a different primary screen — must clear
        NavigationController.navigateTo(SkillsScreen)

        assertEquals("primary screen navigation should clear the stack", 1, backStack.size)
        assertEquals(SkillsScreen, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo on the current primary screen does not clear`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        // Add a non-primary screen
        NavigationController.navigateTo(ProfilesScreen)
        assertEquals(2, backStack.size)

        // Try navigating to ChatScreen again — it's primary AND the last item
        NavigationController.navigateTo(ChatScreen)

        // The dedup guard should prevent navigation since ChatScreen is already the last item
        // ProfilesScreen should still be on the stack
        assertEquals(2, backStack.size)
    }

    // ── Non-primary screen behaviour: stack appending ─────────────────────

    @Test
    fun `navigateTo on a non-primary screen appends to the stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(ProfilesScreen)
        assertEquals(2, backStack.size)
        assertEquals(ProfilesScreen, backStack.lastOrNull())

        NavigationController.navigateTo(KeysScreen)
        assertEquals(3, backStack.size)
        assertEquals(KeysScreen, backStack.lastOrNull())
    }

    // ── resetTo: atomic clear + navigate ──────────────────────────────────

    @Test
    fun `resetTo clears the stack and sets the target screen`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(ProfilesScreen)
        NavigationController.navigateTo(KeysScreen)
        assertEquals(3, backStack.size)

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

    // ── goBack: never leave the stack empty ───────────────────────────────

    @Test
    fun `goBack removes the top screen when stack has more than one`() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack
        NavigationController.navigateTo(ProfilesScreen)
        assertEquals(2, backStack.size)

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals(ChatScreen, backStack.lastOrNull())
    }

    @Test
    fun `goBack falls back to default screen when stack has one item`() {
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

    // ── primaryScreens and updatePrimaryScreens ───────────────────────────

    @Test
    fun `isPrimaryScreen returns true for default screens`() {
        assertTrue("ChatScreen should be primary by default", NavigationController.isPrimaryScreen(ChatScreen))
        assertTrue("SkillsScreen should be primary by default", NavigationController.isPrimaryScreen(SkillsScreen))
        assertTrue("CronJobsScreen should be primary by default", NavigationController.isPrimaryScreen(CronJobsScreen))
        assertTrue("SystemScreen should be primary by default", NavigationController.isPrimaryScreen(SystemScreen))
        assertTrue("SettingsScreen should be primary by default", NavigationController.isPrimaryScreen(SettingsScreen))
    }

    @Test
    fun `isPrimaryScreen returns false for non-default screens`() {
        assertFalse("ProfilesScreen should NOT be primary", NavigationController.isPrimaryScreen(ProfilesScreen))
        assertFalse("LogsScreen should NOT be primary", NavigationController.isPrimaryScreen(LogsScreen))
        assertFalse("ConfigScreen should NOT be primary", NavigationController.isPrimaryScreen(ConfigScreen))
    }

    @Test
    fun `updatePrimaryScreens replaces the full screen set`() {
        NavigationController.updatePrimaryScreens(setOf(ProfilesScreen, SystemScreen))

        assertTrue("ProfilesScreen should now be primary", NavigationController.isPrimaryScreen(ProfilesScreen))
        assertTrue("SystemScreen should still be primary", NavigationController.isPrimaryScreen(SystemScreen))

        // Old defaults should no longer be primary
        assertFalse("ChatScreen should no longer be primary", NavigationController.isPrimaryScreen(ChatScreen))
        assertFalse("SkillsScreen should no longer be primary", NavigationController.isPrimaryScreen(SkillsScreen))
        assertFalse("SettingsScreen should no longer be primary", NavigationController.isPrimaryScreen(SettingsScreen))

        // Reset to defaults to avoid test pollution
        NavigationController.updatePrimaryScreens(
            setOf(ChatScreen, SkillsScreen, CronJobsScreen, SystemScreen, SettingsScreen),
        )
    }
}
