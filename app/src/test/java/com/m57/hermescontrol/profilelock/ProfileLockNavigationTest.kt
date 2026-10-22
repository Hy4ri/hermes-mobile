package com.m57.hermescontrol.profilelock

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.ClientCertificatesScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.KanbanScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.ProvidersScreen
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.SettingsVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1643: protected destinations stay unreachable through every navigation path while locked. */
class ProfileLockNavigationTest {
    private val prefs = FakeSharedPreferences()

    @Before
    fun setUp() {
        ProfileLock.setStoreForTest(ProfileLockStore(prefs))
        NavigationController.backStack = null
        NavigationController.consumePendingKanbanNavigation()
        NavigationController.consumeChatReturnScreen()
    }

    @After
    fun tearDown() {
        ProfileLock.setStoreForTest(null)
        NavigationController.backStack = null
        NavigationController.consumePendingKanbanNavigation()
        NavigationController.consumeChatReturnScreen()
    }

    private fun lock() = assertTrue(ProfileLock.enable(ProfileLockTarget("home", "kids"), "4826"))

    @Test
    fun `navigation to protected screens is a no-op while locked`() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        lock()

        NavigationController.navigateTo(ProfilesScreen)
        NavigationController.navigateTo(ProvidersScreen)
        NavigationController.navigateTo(SettingsVault)
        assertEquals(listOf<NavKey>(ChatScreen), stack.toList())

        NavigationController.navigateTo(SettingsScreen)
        NavigationController.navigateTo(SettingsAppearance)
        assertEquals(listOf(ChatScreen, SettingsScreen, SettingsAppearance), stack.toList())
    }

    @Test
    fun `protected screens open again once the lock is off`() {
        val stack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = stack
        lock()
        ProfileLock.disable("4826")

        NavigationController.navigateTo(ProfilesScreen)
        assertEquals(listOf(ChatScreen, ProfilesScreen), stack.toList())
    }

    @Test
    fun `locking drops protected back-stack entries and return targets`() {
        val stack = NavBackStack<NavKey>(ChatScreen, SettingsScreen, SettingsConnection, ClientCertificatesScreen)
        NavigationController.backStack = stack
        lock()

        assertTrue(NavigationController.dropLockedDestinations())
        assertEquals(listOf(ChatScreen, SettingsScreen), stack.toList())
        assertFalse(NavigationController.dropLockedDestinations())
    }

    @Test
    fun `a protected chat return target is cleared`() {
        val stack = NavBackStack<NavKey>(ChatScreen, KanbanScreen)
        NavigationController.backStack = stack
        NavigationController.openChatSession("s1")
        assertEquals(KanbanScreen, NavigationController.chatReturnScreen)
        lock()

        NavigationController.dropLockedDestinations()
        assertNull(NavigationController.chatReturnScreen)
    }

    @Test
    fun `an allowed chat return target survives`() {
        val stack = NavBackStack<NavKey>(ChatScreen, HistoryScreen)
        NavigationController.backStack = stack
        NavigationController.openChatSession("s1")
        lock()

        NavigationController.dropLockedDestinations()
        assertEquals(HistoryScreen, NavigationController.chatReturnScreen)
    }

    @Test
    fun `kanban notification deep links are refused while locked`() {
        lock()
        NavigationController.openKanbanTaskFromNotification("main", "t_abcd1234")
        assertNull(NavigationController.pendingKanbanNavigation)
    }

    @Test
    fun `a kanban deep link queued before locking is dropped`() {
        NavigationController.backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.openKanbanTaskFromNotification("main", "t_abcd1234")
        lock()

        NavigationController.dropLockedDestinations()
        assertNull(NavigationController.pendingKanbanNavigation)
    }

    @Test
    fun `resetTo a protected screen lands on chat`() {
        val stack = NavBackStack<NavKey>(SettingsScreen)
        NavigationController.backStack = stack
        lock()

        NavigationController.resetTo(ProfilesScreen)
        assertEquals(listOf<NavKey>(ChatScreen), stack.toList())
    }
}
