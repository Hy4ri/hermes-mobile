package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.config.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionQuickSwitcherTest {
    private val flip3 =
        ConnectionProfile(
            id = "flip3",
            name = "Flip3 Home",
            baseUrl = "http://192.168.1.57:9119",
        )
    private val work =
        ConnectionProfile(
            id = "work",
            name = "Workstation",
            baseUrl = "https://hermes.tailnet.ts.net:9119",
        )

    @Test
    fun activeConnectionProfileMatchesSelectedId() {
        assertEquals(work, activeConnectionProfile(listOf(flip3, work), "work"))
    }

    @Test
    fun activeConnectionProfileFallsBackToFirstWhenSelectedIdMissingOrBlank() {
        assertEquals(flip3, activeConnectionProfile(listOf(flip3, work), "missing"))
        assertEquals(flip3, activeConnectionProfile(listOf(flip3, work), null))
        assertEquals(flip3, activeConnectionProfile(listOf(flip3, work), "   "))
    }

    @Test
    fun activeConnectionProfileReturnsNullWhenEmpty() {
        assertNull(activeConnectionProfile(emptyList(), "flip3"))
    }

    @Test
    fun connectionDisplayNamePrefersTrimmedProfileName() {
        val spaced = flip3.copy(name = "  Flip3 Home  ")
        assertEquals("Flip3 Home", connectionDisplayName(spaced, "http://10.0.0.1:9119"))
    }

    @Test
    fun connectionDisplayNameFallsBackToHostWhenProfileMissingOrBlank() {
        val blank = flip3.copy(name = "   ")
        assertEquals("192.168.1.57", connectionDisplayName(blank, "http://10.0.0.1:9119"))
        assertEquals("hermes.tailnet.ts.net", connectionDisplayName(null, "https://hermes.tailnet.ts.net:9119"))
    }

    @Test
    fun connectionDisplayNameFallsBackToRawUrlWhenUnparseable() {
        assertEquals("not a url", connectionDisplayName(null, "  not a url  "))
    }
}
