package com.m57.hermescontrol.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppLockPolicyTest {
    @Test
    fun `cold start always locks`() {
        assertTrue(AppLockPolicy.shouldLock(lastBackgroundAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun `brief biometric overlay does not immediately relock`() {
        assertFalse(AppLockPolicy.shouldLock(lastBackgroundAtMs = 1_000L, nowMs = 30_000L))
    }

    @Test
    fun `return after timeout locks`() {
        assertTrue(AppLockPolicy.shouldLock(lastBackgroundAtMs = 1_000L, nowMs = 62_000L))
    }

    @Test
    fun `clock rollback locks safely`() {
        assertTrue(AppLockPolicy.shouldLock(lastBackgroundAtMs = 2_000L, nowMs = 1_000L))
    }
}
