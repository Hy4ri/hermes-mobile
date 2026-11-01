package com.m57.hermescontrol.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AgentDisplayNameTest {
    @Before
    fun setUp() = AgentDisplayName.resetForTest()

    @After
    fun tearDown() = AgentDisplayName.resetForTest()

    @Test
    fun defaultProfileWithoutCustomName_isHermes() {
        assertEquals("Hermes", AgentDisplayName.resolve("conn", "default"))
        assertEquals("Hermes", AgentDisplayName.resolve(null, null))
    }

    @Test
    fun namedProfileWithoutCustomName_isProfileName() {
        assertEquals("lexi", AgentDisplayName.resolve("conn", "lexi"))
    }

    @Test
    fun customName_appliesOnlyToItsOwnProfile() {
        AgentDisplayName.set("conn", "x", "Jarvis")

        assertEquals("Jarvis", AgentDisplayName.resolve("conn", "x"))
        assertEquals("y", AgentDisplayName.resolve("conn", "y"))
        assertEquals("Hermes", AgentDisplayName.resolve("conn", "default"))
    }

    @Test
    fun customName_appliesOnlyToItsOwnConnection() {
        AgentDisplayName.set("home", "lexi", "Jarvis")

        assertEquals("Jarvis", AgentDisplayName.resolve("home", "lexi"))
        assertEquals("lexi", AgentDisplayName.resolve("work", "lexi"))
    }

    @Test
    fun blankName_removesCustomName() {
        AgentDisplayName.set("conn", "x", "Jarvis")
        AgentDisplayName.set("conn", "x", "   ")

        assertNull(AgentDisplayName.custom("conn", "x"))
        assertEquals("x", AgentDisplayName.resolve("conn", "x"))
    }

    @Test
    fun name_isTrimmedAndCapped() {
        AgentDisplayName.set("conn", "x", "  " + "a".repeat(100) + "  ")

        assertEquals("a".repeat(AgentDisplayName.MAX_LENGTH), AgentDisplayName.custom("conn", "x"))
    }

    @Test
    fun rename_movesCustomNameToNewProfileName() {
        AgentDisplayName.set("conn", "old", "Jarvis")
        AgentDisplayName.rename("conn", "old", "new")

        assertNull(AgentDisplayName.custom("conn", "old"))
        assertEquals("Jarvis", AgentDisplayName.resolve("conn", "new"))
    }

    @Test
    fun keys_doNotCollideAcrossSeparator() {
        AgentDisplayName.set("a|b", "c", "One")

        assertNull(AgentDisplayName.custom("a", "b|c"))
    }
}
