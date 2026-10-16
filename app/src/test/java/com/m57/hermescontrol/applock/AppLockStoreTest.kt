package com.m57.hermescontrol.applock

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStoreTest {
    private val prefs = mockk<SharedPreferences>()

    @Test
    fun `missing policy defaults off without treating an invalid timeout as authorization`() {
        every { prefs.getBoolean("enabled", false) } returns false
        every { prefs.getLong("background_timeout_ms", 30_000L) } returns -1L
        assertEquals(AppLockSettings(false, 30_000L), AppLockStore(prefs).read())
    }

    @Test
    fun `corrupt policy fails closed`() {
        every { prefs.getBoolean("enabled", false) } throws ClassCastException("corrupt setting")
        assertTrue(AppLockStore(prefs).read().enabled)
    }

    @Test
    fun `existing file decoded as empty preferences is not treated as a fresh install`() {
        every { prefs.contains("enabled") } returns false
        every { prefs.getLong("background_timeout_ms", 30_000L) } returns 30_000L
        assertTrue(AppLockStore(prefs, previouslyStored = true).read().enabled)
    }

    @Test
    fun `failed disable restores the original in-memory security setting`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getBoolean("enabled", false) } returns true
        every { prefs.getLong("background_timeout_ms", 30_000L) } returns 30_000L
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.commit() } returns false
        assertFalse(AppLockStore(prefs).save(AppLockSettings(false)))
        verifySequence {
            prefs.getBoolean("enabled", false)
            prefs.getLong("background_timeout_ms", 30_000L)
            prefs.edit()
            editor.putBoolean("enabled", false)
            editor.putLong("background_timeout_ms", 30_000L)
            editor.commit()
            prefs.edit()
            editor.putBoolean("enabled", true)
            editor.putLong("background_timeout_ms", 30_000L)
            editor.commit()
        }
    }

    @Test
    fun `disk write failure is not reported as success`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putBoolean("enabled", true) } returns editor
        every { editor.putLong("background_timeout_ms", 30_000L) } returns editor
        every { editor.commit() } returns false
        assertFalse(AppLockStore(prefs).save(AppLockSettings(true)))
    }
}
