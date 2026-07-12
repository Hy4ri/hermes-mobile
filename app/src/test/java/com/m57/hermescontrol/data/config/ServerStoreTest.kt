package com.m57.hermescontrol.data.config

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerStoreTest {
    @Test
    fun testDefaultValues() {
        val state = ServerStoreState()
        assertEquals("100.101.12.16", state.host)
        assertEquals(9119, state.port)
        assertTrue(state.autoReconnect)
        assertFalse(state.screenCaptureProtectionEnabled)
        assertEquals("token", state.wsAuthParam)
        assertTrue(state.connectionProfiles.isEmpty())
        assertNull(state.selectedProfileId)
    }

    @Test
    fun testPureOps_addOrReplaceServer() {
        val state = ServerStoreState()
        val profile = ConnectionProfile(id = "1", name = "Test", host = "10.0.0.1", port = 8080)

        val newState = state.addOrReplaceServer(profile)
        assertEquals(1, newState.connectionProfiles.size)
        assertEquals(profile, newState.connectionProfiles[0])

        // Replace
        val updatedProfile = profile.copy(host = "10.0.0.2")
        val finalState = newState.addOrReplaceServer(updatedProfile)
        assertEquals(1, finalState.connectionProfiles.size)
        assertEquals("10.0.0.2", finalState.connectionProfiles[0].host)
    }

    @Test
    fun testPureOps_removeServer() {
        val profile1 = ConnectionProfile(id = "1", name = "Test1", host = "10.0.0.1", port = 8080)
        val profile2 = ConnectionProfile(id = "2", name = "Test2", host = "10.0.0.2", port = 8081)
        val state =
            ServerStoreState(
                connectionProfiles = listOf(profile1, profile2),
                selectedProfileId = "2",
            )

        val newState = state.removeServer("2")
        assertEquals(1, newState.connectionProfiles.size)
        assertEquals("1", newState.connectionProfiles[0].id)
        assertNull(newState.selectedProfileId)
    }

    @Test
    fun testPureOps_switchToServer() {
        val state = ServerStoreState(selectedProfileId = null)
        val newState = state.switchToServer("prof-1")
        assertEquals("prof-1", newState.selectedProfileId)
    }

    @Test
    fun testPureOps_selfHealed() {
        // Test orphaned selected profile ID
        val state =
            ServerStoreState(
                connectionProfiles = emptyList(),
                selectedProfileId = "orphaned-id",
            )
        val healed = state.selfHealed()
        assertNull(healed.selectedProfileId)

        // Test empty bottomNavItems
        val state2 =
            ServerStoreState(
                bottomNavItems = emptyList(),
            )
        val healed2 = state2.selfHealed()
        assertEquals(5, healed2.bottomNavItems.size)
        assertEquals("ChatScreen", healed2.bottomNavItems[0])
    }

    @Test
    fun testResolvedHostAndPort() {
        val profile = ConnectionProfile(id = "prof-1", name = "Custom", host = "10.0.0.5", port = 8000)
        val state =
            ServerStoreState(
                host = "127.0.0.1",
                port = 9119,
                connectionProfiles = listOf(profile),
                selectedProfileId = "prof-1",
            )

        assertEquals("10.0.0.5", state.resolvedHost)
        assertEquals(8000, state.resolvedPort)

        // Unselected
        val unselectedState = state.copy(selectedProfileId = null)
        assertEquals("127.0.0.1", unselectedState.resolvedHost)
        assertEquals(9119, unselectedState.resolvedPort)
    }
}
