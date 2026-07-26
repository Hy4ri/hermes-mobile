package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMediaExtractorTest {
    @Test
    fun `extracts single image path`() {
        val items = HostMediaExtractor.extract("Here: MEDIA:/tmp/foo.png")
        assertEquals(1, items.size)
        assertEquals("/tmp/foo.png", items[0].path)
        assertEquals("MEDIA:/tmp/foo.png", items[0].match)
    }

    @Test
    fun `extracts multiple directives of any type`() {
        val text = "MEDIA:/a/report.pdf and MEDIA:/b/clip.mp3 and MEDIA:/c/x.png"
        val items = HostMediaExtractor.extract(text)
        assertEquals(3, items.size)
        assertTrue(items.any { it.path == "/a/report.pdf" })
        assertTrue(items.any { it.path == "/b/clip.mp3" })
        assertTrue(items.any { it.path == "/c/x.png" })
    }

    @Test
    fun `returns empty when no MEDIA directive`() {
        assertEquals(0, HostMediaExtractor.extract("just text").size)
    }

    @Test
    fun `ignores relative paths`() {
        assertEquals(0, HostMediaExtractor.extract("MEDIA:relative/path.png").size)
    }

    @Test
    fun `strips directives from text`() {
        val out = HostMediaExtractor.strip("See MEDIA:/tmp/a.png for details")
        assertEquals("See for details", out)
        assertFalse(out.contains("MEDIA:"))
    }

    @Test
    fun `strips multiple directives`() {
        val out = HostMediaExtractor.strip("MEDIA:/a/x.pdf MEDIA:/b/y.mp3 end")
        assertEquals("end", out)
    }

    @Test
    fun `handles quoted paths`() {
        val items = HostMediaExtractor.extract("MEDIA:\"/tmp/a b.png\"")
        assertEquals(1, items.size)
        assertEquals("/tmp/a b.png", items[0].path)
    }
}
