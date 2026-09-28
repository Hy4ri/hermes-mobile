package com.m57.hermescontrol.ui.chat.tool

import com.m57.hermescontrol.ui.chat.parseToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ToolViewCacheTest {
    private val payload = """{"name":"terminal","args":{"command":"ls"},"result":{"output":"a","exit_code":0}}"""

    @Before
    fun reset() = ToolViewCache.clear()

    @Test
    fun `cached view equals a fresh parse`() {
        val cached = ToolViewCache.getOrParse(payload, "terminal", false)
        assertEquals(parseToolOutput(payload, "terminal", false), cached)
    }

    @Test
    fun `prewarm makes composition read a hit`() {
        ToolViewCache.prewarm(payload, "terminal", false)
        val first = ToolViewCache.getOrParse(payload, "terminal", false)
        assertSame(first, ToolViewCache.getOrParse(payload, "terminal", false))
        assertEquals(1, ToolViewCache.size())
    }

    @Test
    fun `running state and content changes are separate entries`() {
        val running = ToolViewCache.getOrParse(payload, "terminal", true)
        val done = ToolViewCache.getOrParse(payload, "terminal", false)
        assertNotSame(running, done)
        assertEquals(2, ToolViewCache.size())
    }

    @Test
    fun `non json content is cached as null`() {
        assertNull(ToolViewCache.getOrParse("plain text", "terminal", false))
        assertNull(ToolViewCache.getOrParse("plain text", "terminal", false))
        assertEquals(1, ToolViewCache.size())
    }

    @Test
    fun `cache is bounded`() {
        repeat(600) { i -> ToolViewCache.getOrParse("""{"i":$i}""", "terminal", false) }
        assertEquals(512, ToolViewCache.size())
    }
}
