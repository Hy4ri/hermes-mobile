package com.m57.hermescontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSessionLanGateTest {
    @Test
    fun savedLanTokenOnApi37RequiresGate() {
        assertTrue(needsSavedSessionLanGate(37, "saved", "http://192.168.1.9:9119/"))
    }

    @Test
    fun noSavedTokenNeverRequiresStartupGate() {
        assertFalse(needsSavedSessionLanGate(37, null, "http://192.168.1.9:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "", "http://192.168.1.9:9119/"))
    }

    @Test
    fun olderOsAndLoopbackAndPublicEndpointsBypassGate() {
        assertFalse(needsSavedSessionLanGate(36, "saved", "http://192.168.1.9:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "saved", "http://127.0.0.1:9119/"))
        assertFalse(needsSavedSessionLanGate(37, "saved", "https://8.8.8.8/"))
    }
}
