package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAvatarTest {
    @Test
    fun testShapeResolution() {
        val size = 40.dp
        assertEquals(CircleShape, resolveAvatarShape("circle", size))
        assertEquals(CircleShape, resolveAvatarShape("round", size))
        assertEquals(CircleShape, resolveAvatarShape(null, size))
        assertEquals(CircleShape, resolveAvatarShape("unknown_shape", size))

        assertTrue(resolveAvatarShape("square", size) is RoundedCornerShape)
        assertTrue(resolveAvatarShape("boxy", size) is RoundedCornerShape)
        assertTrue(resolveAvatarShape("rounded", size) is RoundedCornerShape)
        assertTrue(resolveAvatarShape("hexagon", size) is CutCornerShape)
        assertTrue(resolveAvatarShape("diamond", size) is CutCornerShape)
    }

    @Test
    fun testIconResolution() {
        assertEquals(Icons.Filled.Code, resolveAvatarIcon("code"))
        assertEquals(Icons.Filled.Code, resolveAvatarIcon("terminal"))
        assertEquals(Icons.Filled.Psychology, resolveAvatarIcon("brain"))
        assertEquals(Icons.Filled.Psychology, resolveAvatarIcon("research"))
        assertEquals(Icons.Filled.SmartToy, resolveAvatarIcon("robot"))
        assertEquals(Icons.Filled.SmartToy, resolveAvatarIcon("bot"))
        assertNull(resolveAvatarIcon(null))
        assertNull(resolveAvatarIcon("unknown_icon"))
    }

    @Test
    fun testInitialsExtraction() {
        assertEquals("RE", extractInitials("researcher"))
        assertEquals("DS", extractInitials("daily-sync"))
        assertEquals("BT", extractInitials("bot_tester"))
        assertEquals("AB", extractInitials("Alpha Beta"))
        assertEquals("A", extractInitials("a"))
        assertEquals("?", extractInitials(""))
    }

    @Test
    fun testHexColorParsing() {
        val fallback = Color.Gray
        val parsed = parseHexColor("#FF0000", fallback)
        assertEquals(Color(0xFFFF0000.toInt()), parsed)

        val alphaParsed = parseHexColor("#8000FF00", fallback)
        assertEquals(Color(0x8000FF00.toInt()), alphaParsed)

        val invalid = parseHexColor("not-a-color", fallback)
        assertEquals(fallback, invalid)

        val nullColor = parseHexColor(null, fallback)
        assertEquals(fallback, nullColor)
    }
}
