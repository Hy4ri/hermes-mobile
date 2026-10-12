package com.m57.hermescontrol.data.remote

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServerTrustPreferenceTest {
    private val memory = mutableMapOf<String, Boolean>()
    private val disk = mutableMapOf<String, Boolean>()
    private var fail = false
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val setting = ServerTrustPreference(preferences)

    init {
        every { preferences.getBoolean(any(), any()) } answers { memory[firstArg()] ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            memory[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } answers {
            if (fail) {
                false
            } else {
                disk.putAll(memory)
                true
            }
        }
    }

    private fun restart() {
        memory.clear()
        memory.putAll(disk)
    }

    @Test
    fun `absent setting defaults off and disabling survives restart`() {
        assertFalse(setting.read())
        setting.write(true)
        restart()
        assertTrue(setting.read())
        setting.write(false)
        restart()
        assertFalse(setting.read())
    }

    @Test
    fun `upgrade with unrelated saved preferences still defaults off`() {
        memory["existing_setting"] = true
        disk.putAll(memory)
        restart()
        assertFalse(setting.read())
    }

    @Test
    fun `failed enabling cannot persist opt in`() {
        fail = true
        assertThrows(IOException::class.java) { setting.write(true) }
        assertFalse(setting.read())
        restart()
        assertFalse(setting.read())
    }

    @Test
    fun `failed disabling restores memory and reports failure without claiming persistence`() {
        setting.write(true)
        fail = true
        assertThrows(IOException::class.java) { setting.write(false) }
        assertTrue(setting.read())
        restart()
        assertTrue(setting.read())
        fail = false
        setting.write(false)
        restart()
        assertFalse(setting.read())
    }
}
