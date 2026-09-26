package com.m57.hermescontrol.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppFontFamilyTest {
    @Test
    fun testEntries() {
        assertEquals(5, AppFontFamily.entries.size)
    }

    @Test
    fun testSystemDefault() {
        val entry = AppFontFamily.SYSTEM
        assertEquals("system", entry.key)
        assertEquals("System Default", entry.displayName)
        assertNotNull(entry.toFontFamily)
    }

    @Test
    fun testSansSerif() {
        val entry = AppFontFamily.SANS_SERIF
        assertEquals("sans_serif", entry.key)
        assertEquals("Sans Serif", entry.displayName)
        assertNotNull(entry.toFontFamily)
    }

    @Test
    fun testSerif() {
        val entry = AppFontFamily.SERIF
        assertEquals("serif", entry.key)
        assertEquals("Serif", entry.displayName)
        assertNotNull(entry.toFontFamily)
    }

    @Test
    fun testMonospace() {
        val entry = AppFontFamily.MONOSPACE
        assertEquals("monospace", entry.key)
        assertEquals("Monospace", entry.displayName)
        assertNotNull(entry.toFontFamily)
    }

    @Test
    fun testCursive() {
        val entry = AppFontFamily.CURSIVE
        assertEquals("cursive", entry.key)
        assertEquals("Cursive", entry.displayName)
        assertNotNull(entry.toFontFamily)
    }

    @Test
    fun testFromKey_knownKeys() {
        assertEquals(AppFontFamily.SYSTEM, AppFontFamily.fromKey("system"))
        assertEquals(AppFontFamily.SANS_SERIF, AppFontFamily.fromKey("sans_serif"))
        assertEquals(AppFontFamily.SERIF, AppFontFamily.fromKey("serif"))
        assertEquals(AppFontFamily.MONOSPACE, AppFontFamily.fromKey("monospace"))
        assertEquals(AppFontFamily.CURSIVE, AppFontFamily.fromKey("cursive"))
    }

    @Test
    fun testFromKey_unknownKeyDefaultsToSystem() {
        assertEquals(AppFontFamily.SYSTEM, AppFontFamily.fromKey("unknown_font"))
        assertEquals(AppFontFamily.SYSTEM, AppFontFamily.fromKey(""))
        assertEquals(AppFontFamily.SYSTEM, AppFontFamily.fromKey("arial"))
    }

    @Test
    fun testDisplayNames() {
        val names = AppFontFamily.displayNames
        assertEquals(5, names.size)
        assertEquals("System Default", names[0])
        assertEquals("Sans Serif", names[1])
        assertEquals("Serif", names[2])
        assertEquals("Monospace", names[3])
        assertEquals("Cursive", names[4])
    }

    @Test
    fun testKeys() {
        val keys = AppFontFamily.keys
        assertEquals(5, keys.size)
        assertEquals("system", keys[0])
        assertEquals("sans_serif", keys[1])
        assertEquals("serif", keys[2])
        assertEquals("monospace", keys[3])
        assertEquals("cursive", keys[4])
    }
}
