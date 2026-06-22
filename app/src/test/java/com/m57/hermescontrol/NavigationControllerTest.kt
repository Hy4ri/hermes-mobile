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
        NavigationController.navigateTo(ChatScreenKey)
        assertNull("backStack should remain null when not initialised", NavigationController.backStack)
    }

    @Test
    fun `navigateTo on same primary screen is a no-op`() {
        val backStack = NavBackStack<NavKey>(SkillsScreenKey)
        NavigationController.backStack = backStack
        val sizeBefore = backStack.size

        NavigationController.navigateTo(SkillsScreenKey)

        assertEquals("stack size should not change when navigating to the same screen", sizeBefore, backStack.size)
        assertEquals("top of stack should still be SkillsScreenKey", SkillsScreenKey, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo on same non-primary screen is a no-op`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreenKey)
        NavigationController.backStack = backStack

        // ProfilesScreenKey is NOT in the default primaryScreens so it stays on the stack
        NavigationController.navigateTo(ProfilesScreenKey)

        assertEquals(1, backStack.size)
        assertEquals(ProfilesScreenKey, backStack.lastOrNull())
    }

    // ── Primary-screen behaviour: stack clearing ──────────────────────────

    @Test
    fun `navigateTo on a different primary screen clears the stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreenKey)
        NavigationController.backStack = backStack

        // Navigate to a non-primary screen first (should add to stack)
        NavigationController.navigateTo(LogsScreenKey)
        assertEquals(2, backStack.size)

        // Now navigate to a different primary screen — must clear
        NavigationController.navigateTo(SkillsScreenKey)

        assertEquals("primary screen navigation should clear the stack", 1, backStack.size)
        assertEquals(SkillsScreenKey, backStack.lastOrNull())
    }

    @Test
    fun `navigateTo on the current primary screen clears the stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreenKey)
        NavigationController.backStack = backStack

        // Add a non-primary screen
        NavigationController.navigateTo(ProfilesScreenKey)
        assertEquals(2, backStack.size)

        // Navigate to ChatScreenKey — it's a primary screen, so the stack gets
        // cleared before adding it. The dedup guard only fires when the key
        // is already the LAST item (ChatScreenKey is at index 0, ProfilesScreenKey
        // is last), not when it's merely present somewhere in the stack.
        NavigationController.navigateTo(ChatScreenKey)

        // Primary screen navigation clears the stack
        assertEquals("primary navigation clears stack", 1, backStack.size)
        assertEquals(ChatScreenKey, backStack.lastOrNull())
    }

    // ── Non-primary screen behaviour: stack appending ─────────────────────

    @Test
    fun `navigateTo on a non-primary screen appends to the stack`() {
        val backStack = NavBackStack<NavKey>(ChatScreenKey)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(ProfilesScreenKey)
        assertEquals(2, backStack.size)
        assertEquals(ProfilesScreenKey, backStack.lastOrNull())

        NavigationController.navigateTo(KeysScreenKey)
        assertEquals(3, backStack.size)
        assertEquals(KeysScreenKey, backStack.lastOrNull())
    }

    // ── resetTo: atomic clear + navigate ──────────────────────────────────

    @Test
    fun `resetTo clears the stack and sets the target screen`() {
        val backStack = NavBackStack<NavKey>(ChatScreenKey)
        NavigationController.backStack = backStack

        NavigationController.navigateTo(ProfilesScreenKey)
        NavigationController.navigateTo(KeysScreenKey)
        assertEquals(3, backStack.size)

        NavigationController.resetTo(SettingsScreenKey)

        assertEquals(1, backStack.size)
        assertEquals(SettingsScreenKey, backStack.lastOrNull())
    }

    @Test
    fun `resetTo with null backStack does nothing`() {
        NavigationController.backStack = null
        NavigationController.resetTo(ChatScreenKey)

        assertNull(NavigationController.backStack)
    }

    // ── goBack: never leave the stack empty ───────────────────────────────

    @Test
    fun `goBack removes the top screen when stack has more than one`() {
        val backStack = NavBackStack<NavKey>(ChatScreenKey)
        NavigationController.backStack = backStack
        NavigationController.navigateTo(ProfilesScreenKey)
        assertEquals(2, backStack.size)

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals(ChatScreenKey, backStack.lastOrNull())
    }

    @Test
    fun `goBack falls back to default screen when stack has one item`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreenKey)
        NavigationController.backStack = backStack

        NavigationController.goBack()

        assertEquals(1, backStack.size)
        assertEquals("default fallback should be ChatScreenKey", ChatScreenKey, backStack.lastOrNull())
    }

    @Test
    fun `goBack with custom fallback uses the given screen`() {
        val backStack = NavBackStack<NavKey>(ProfilesScreenKey)
        NavigationController.backStack = backStack

        NavigationController.goBack(fallback = SkillsScreenKey)

        assertEquals(1, backStack.size)
        assertEquals(SkillsScreenKey, backStack.lastOrNull())
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
        assertTrue("ChatScreenKey should be primary by default", NavigationController.isPrimaryScreen(ChatScreenKey))
        assertTrue("SkillsScreenKey should be primary by default", NavigationController.isPrimaryScreen(SkillsScreenKey))
        assertTrue("CronJobsScreenKey should be primary by default", NavigationController.isPrimaryScreen(CronJobsScreenKey))
        assertTrue("SystemScreenKey should be primary by default", NavigationController.isPrimaryScreen(SystemScreenKey))
        assertTrue("SettingsScreenKey should be primary by default", NavigationController.isPrimaryScreen(SettingsScreenKey))
    }

    @Test
    fun `isPrimaryScreen returns false for non-default screens`() {
        assertFalse("ProfilesScreenKey should NOT be primary", NavigationController.isPrimaryScreen(ProfilesScreenKey))
        assertFalse("LogsScreenKey should NOT be primary", NavigationController.isPrimaryScreen(LogsScreenKey))
        assertFalse("ConfigScreenKey should NOT be primary", NavigationController.isPrimaryScreen(ConfigScreenKey))
    }

    @Test
    fun `updatePrimaryScreens replaces the full screen set`() {
        NavigationController.updatePrimaryScreens(setOf(ProfilesScreenKey, SystemScreenKey))

        assertTrue("ProfilesScreenKey should now be primary", NavigationController.isPrimaryScreen(ProfilesScreenKey))
        assertTrue("SystemScreenKey should still be primary", NavigationController.isPrimaryScreen(SystemScreenKey))

        // Old defaults should no longer be primary
        assertFalse("ChatScreenKey should no longer be primary", NavigationController.isPrimaryScreen(ChatScreenKey))
        assertFalse("SkillsScreenKey should no longer be primary", NavigationController.isPrimaryScreen(SkillsScreenKey))
        assertFalse("SettingsScreenKey should no longer be primary", NavigationController.isPrimaryScreen(SettingsScreenKey))

        // Reset to defaults to avoid test pollution
        NavigationController.updatePrimaryScreens(
            setOf(ChatScreenKey, SkillsScreenKey, CronJobsScreenKey, SystemScreenKey, SettingsScreenKey),
        )
    }
}
