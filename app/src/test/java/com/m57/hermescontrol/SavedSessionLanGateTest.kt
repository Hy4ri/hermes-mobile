package com.m57.hermescontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSessionLanGateTest {
    @Test
    fun savedLanTokenOnApi37RequiresGate() {
        assertTrue(needsSavedSessionLanGate(37, "saved", false, "http://10.23.45.67:9119/"))
    }

    @Test
    fun cookieOnlyGatedSessionRequiresGate() {
        assertTrue(needsSavedSessionLanGate(37, null, true, "http://10.23.45.67:9119/"))
        assertTrue(needsSavedSessionLanGate(37, "", true, "http://10.23.45.67:9119/"))
    }

    @Test
    fun noSavedAuthenticationNeverRequiresStartupGate() {
        assertFalse(needsSavedSessionLanGate(37, null, false, "http://10.23.45.67:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "", false, "http://10.23.45.67:9119/"))
    }

    @Test
    fun olderOsAndLoopbackAndPublicEndpointsBypassGate() {
        assertFalse(needsSavedSessionLanGate(36, null, true, "http://10.23.45.67:9119/"))
        assertFalse(needsSavedSessionLanGate(37, null, true, "http://127.0.0.1:9119/"))
        assertFalse(needsSavedSessionLanGate(37, null, true, "https://8.8.8.8/"))
        assertFalse(needsSavedSessionLanGate(36, "saved", false, "http://10.23.45.67:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "saved", false, "http://127.0.0.1:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "saved", false, "https://8.8.8.8/"))
    }
}
