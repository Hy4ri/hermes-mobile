package com.m57.hermescontrol.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class HermesDatabaseSafeFailTest {
    private fun tmp(bytes: ByteArray): File =
        File.createTempFile("hermesdb", ".db").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun plaintextHeaderDetected() {
        assertTrue(HermesDatabase.isPlaintextDatabase(tmp("SQLite format 3\u0000rest".toByteArray())))
    }

    @Test
    fun encryptedOrShortFileIsNotPlaintext() {
        assertFalse(HermesDatabase.isPlaintextDatabase(tmp(ByteArray(64) { 7 })))
        assertFalse(HermesDatabase.isPlaintextDatabase(tmp(ByteArray(0))))
    }

    @Test
    fun unreadableFileThrowsInsteadOfDeleting() {
        val dir =
            File.createTempFile("hermesdir", "").apply {
                delete()
                mkdir()
                deleteOnExit()
            }
        try {
            HermesDatabase.isPlaintextDatabase(dir)
            fail("expected exception")
        } catch (_: DatabaseUnavailableException) {
        }
    }
}
