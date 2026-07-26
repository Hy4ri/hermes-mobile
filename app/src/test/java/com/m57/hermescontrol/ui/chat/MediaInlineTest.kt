package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaInlineTest {
    @Test
    fun `inlines readable image into data url`() {
        val file = File.createTempFile("media-inline-test", ".png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()))
        val text = "Here is the result: MEDIA:${file.absolutePath}"
        val out = MediaInline.inlineLocalMediaText(text)
        assertTrue(out.contains("data:image/png;base64,"))
        assertTrue(out.startsWith("Here is the result: ![image](data:image/png;base64,"))
        assertFalse(out.contains("MEDIA:"))
        file.delete()
    }

    @Test
    fun `drops unreachable media directive`() {
        val text = "see MEDIA:/nonexistent/path/that/does/not/exist.png for details"
        val out = MediaInline.inlineLocalMediaText(text)
        assertEquals("see  for details", out)
        assertFalse(out.contains("MEDIA:"))
    }

    @Test
    fun `returns unchanged when no media tag`() {
        val text = "just some normal text with no image"
        assertEquals(text, MediaInline.inlineLocalMediaText(text))
    }

    @Test
    fun `handles quoted path`() {
        val file = File.createTempFile("media-quote", ".jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        val text = "MEDIA:\"${file.absolutePath}\""
        val out = MediaInline.inlineLocalMediaText(text)
        assertTrue(out.startsWith("![image](data:image/jpg;base64,"))
        assertFalse(out.contains("MEDIA:"))
        file.delete()
    }

    @Test
    fun `rejects relative path`() {
        val text = "MEDIA:relative/path.png"
        assertEquals(text, MediaInline.inlineLocalMediaText(text))
    }
}
