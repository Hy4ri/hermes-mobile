package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectColorTest {
    private fun assertColor(
        expected: Color,
        actual: Color?,
    ) {
        requireNotNull(actual)
        assertEquals(expected.red, actual.red, 0.01f)
        assertEquals(expected.green, actual.green, 0.01f)
        assertEquals(expected.blue, actual.blue, 0.01f)
    }

    @Test
    fun `space separated hsl parses`() {
        assertColor(Color.hsl(210f, 0.68f, 0.58f), parseProjectColor("hsl(210 68% 58%)"))
    }

    @Test
    fun `comma separated hsl parses`() {
        assertColor(Color.hsl(0f, 0.68f, 0.58f), parseProjectColor(" hsl(0, 68%, 58%) "))
    }

    @Test
    fun `hex parses`() {
        assertColor(parseHexColor("#4DA8FF", Color.Unspecified), parseProjectColor("#4DA8FF"))
    }

    @Test
    fun `invalid or missing colors are null`() {
        assertNull(parseProjectColor(null))
        assertNull(parseProjectColor(""))
        assertNull(parseProjectColor("tomato"))
        assertNull(parseProjectColor("hsl(400 68% 58%)"))
        assertNull(parseProjectColor("hsl(210 168% 58%)"))
        assertNull(parseProjectColor("#12"))
    }
}
