package com.m57.hermescontrol.applock

import android.app.Application
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    private val application = mockk<Application>(relaxed = true)
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    private fun session(enabled: Boolean = true): AppLockSession {
        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getBoolean("enabled", false) } returns enabled
        every { prefs.getLong("background_timeout_ms", 30_000L) } returns 30_000L
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.commit() } returns true
        return AppLockSession(application).also { it.resume(0, true) }
    }

    private fun unlock(session: AppLockSession) {
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.UNLOCK, true))
        session.completeAuthentication(request, true, true, 0)
    }

    @Test
    fun `cold session only opens after explicit successful authentication`() {
        val session = session()
        assertTrue(session.policy.blocked)
        assertFalse(session.hasOpened)
        unlock(session)
        assertFalse(session.policy.blocked)
        assertTrue(session.hasOpened)
        assertNull(session.pendingAuth)
    }

    @Test
    fun `cancelled enable does not persist or enable protection`() {
        val session = session(false)
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.ENABLE, true))
        assertFalse(session.policy.enabled)
        session.completeAuthentication(request, false, true, 0)
        assertFalse(session.policy.enabled)
        verify(exactly = 0) { editor.commit() }
    }

    @Test
    fun `enabling authenticates and persists before acknowledging the setting`() {
        val session = session(false)
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.ENABLE, true))
        session.completeAuthentication(request, true, true, 0)
        assertTrue(session.policy.enabled)
        assertFalse(session.policy.blocked)
        verify(exactly = 1) { editor.putBoolean("enabled", true) }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `screen off invalidates an in-flight success even within grace`() {
        val session = session()
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.UNLOCK, true))
        session.screenOff()
        session.completeAuthentication(request, true, true, 0)
        session.resume(1, true)
        assertTrue(session.policy.blocked)
        assertNull(session.pendingAuth)
    }

    @Test
    fun `missing device security never grants a ticket or disables the lock`() {
        val session = session()
        assertNull(session.beginAuthentication(AppLockAuthAction.UNLOCK, false))
        assertTrue(session.policy.blocked)
        assertTrue(session.policy.enabled)
        assertNotNull(session.error)
    }

    @Test
    fun `credential activity duration does not expire its own successful authentication`() {
        val session = session()
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.UNLOCK, true))
        session.pause()
        session.stop(100, false)
        session.completeAuthentication(request, true, true, 100_000)
        assertTrue(session.policy.blocked)
        session.resume(100_001, true)
        assertFalse(session.policy.blocked)
    }

    @Test
    fun `cancelling credential activity after long absence expires the previous authorization`() {
        val session = session()
        unlock(session)
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.DISABLE, true))
        session.pause()
        session.stop(100, false)
        session.resume(100_000, true)
        assertTrue(session.policy.blocked)
        session.completeAuthentication(request, false, true, 100_001)
        assertTrue(session.policy.blocked)
        assertTrue(session.policy.enabled)
    }

    @Test
    fun `failed disabling write stays locked and enabled`() {
        val session = session()
        unlock(session)
        every { editor.commit() } returns false
        val request = requireNotNull(session.beginAuthentication(AppLockAuthAction.DISABLE, true))
        session.completeAuthentication(request, true, true, 0)
        assertTrue(session.policy.enabled)
        assertTrue(session.policy.blocked)
        assertNotNull(session.error)
    }
}
