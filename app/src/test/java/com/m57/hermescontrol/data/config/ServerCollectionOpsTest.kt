package com.m57.hermescontrol.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerCollectionOpsTest {

    @Test
    fun addOrUpdate_addsNewProfile() {
        val initialState = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119")
            )
        )
        val newProfile = ConnectionProfile(id = "2", name = "Profile 2", baseUrl = "http://192.168.1.2:9119")

        val result = initialState.addOrUpdate(newProfile)

        assertEquals(2, result.connectionProfiles.size)
        assertEquals(newProfile, result.connectionProfiles[1])
    }

    @Test
    fun addOrUpdate_replacesExistingProfile() {
        val initialState = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119"),
                ConnectionProfile(id = "2", name = "Profile 2", baseUrl = "http://192.168.1.2:9119")
            )
        )
        val updatedProfile = ConnectionProfile(id = "1", name = "Updated Profile 1", baseUrl = "http://192.168.1.100:9119")

        val result = initialState.addOrUpdate(updatedProfile)

        assertEquals(2, result.connectionProfiles.size)
        // Should retain the order implicitly, or just check it exists
        val replaced = result.connectionProfiles.find { it.id == "1" }
        assertEquals("Updated Profile 1", replaced?.name)
        assertEquals("http://192.168.1.100:9119", replaced?.baseUrl)
    }

    @Test
    fun selfHealed_keepsActiveProfile() {
        val initialState = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119"),
                ConnectionProfile(id = "2", name = "Profile 2", baseUrl = "http://192.168.1.2:9119")
            ),
            selectedProfileId = "2"
        )

        val result = initialState.selfHealed()

        assertEquals("2", result.selectedProfileId)
    }

    @Test
    fun selfHealed_nullifiesMissingProfile() {
        val initialState = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119")
            ),
            selectedProfileId = "3"
        )

        val result = initialState.selfHealed()

        assertNull(result.selectedProfileId)
    }

    @Test
    fun selfHealed_handlesAlreadyNull() {
        val initialState = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119")
            ),
            selectedProfileId = null
        )

        val result = initialState.selfHealed()

        assertNull(result.selectedProfileId)
    }

    @Test
    fun resolvedBaseUrl_usesSelectedProfileBaseUrl() {
        val state = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "http://192.168.1.1:9119")
            ),
            selectedProfileId = "1",
            baseUrl = "http://top.level.base.url:9119" // Should be ignored
        )

        val result = state.resolvedBaseUrl

        assertEquals("http://192.168.1.1:9119/", result)
    }

    @Test
    fun resolvedBaseUrl_fallsBackToTopLevelBaseUrl() {
        val state = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = null) // Selected but no baseUrl
            ),
            selectedProfileId = "1",
            baseUrl = "http://top.level.base.url:9119" // Should be used as fallback
        )

        val result = state.resolvedBaseUrl

        assertEquals("http://top.level.base.url:9119/", result)
    }

    @Test
    fun resolvedBaseUrl_fallsBackToLegacyLoopback() {
        val state = ServerStoreState(
            connectionProfiles = emptyList(), // No selected profile
            selectedProfileId = null,
            baseUrl = null, // No top-level baseUrl
            host = "127.0.0.1",
            port = 9119
        )

        val result = state.resolvedBaseUrl

        assertEquals("http://127.0.0.1:9119/", result)
    }

    @Test
    fun resolvedHost_extractsCorrectHost() {
        val state = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "https://my.server.local:443")
            ),
            selectedProfileId = "1"
        )

        val result = state.resolvedHost

        assertEquals("my.server.local", result)
    }

    @Test
    fun resolvedPort_extractsCorrectPort() {
        val state = ServerStoreState(
            connectionProfiles = listOf(
                ConnectionProfile(id = "1", name = "Profile 1", baseUrl = "https://my.server.local:8443")
            ),
            selectedProfileId = "1"
        )

        val result = state.resolvedPort

        assertEquals(8443, result)
    }
}
