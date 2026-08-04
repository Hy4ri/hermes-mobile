package com.m57.hermescontrol.data.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveSessionHolderTest {
    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
    }

    @Test
    fun `runtime and stored ids resolve in both directions`() {
        ActiveSessionHolder.set("runtime-session", "stored-session")

        assertEquals("stored-session", ActiveSessionHolder.resolveStoredSessionId("runtime-session"))
        assertEquals("runtime-session", ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))
    }

    @Test
    fun `session switch discards the previous mapping`() {
        ActiveSessionHolder.set("runtime-previous", "stored-previous")
        ActiveSessionHolder.clear()
        ActiveSessionHolder.set("runtime-current", "stored-current")

        assertEquals("runtime-previous", ActiveSessionHolder.resolveStoredSessionId("runtime-previous"))
        assertNull(ActiveSessionHolder.resolveRuntimeSessionId("stored-previous"))
    }

    @Test
    fun `mapping can be rebuilt after reconnect`() {
        ActiveSessionHolder.set("runtime-before", "stored-session")
        ActiveSessionHolder.clear()

        assertNull(ActiveSessionHolder.activeSessionId.value)
        assertNull(ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))

        ActiveSessionHolder.set("runtime-after", "stored-session")
        assertEquals("runtime-after", ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))
    }
}
