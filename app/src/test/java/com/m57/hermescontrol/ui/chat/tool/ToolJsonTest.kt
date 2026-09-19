package com.m57.hermescontrol.ui.chat.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolJsonTest {
    @Test
    fun `prettyPrintJson formats minified json object line-by-line with indentation`() {
        val raw = """{"name":"test","count":42,"active":true}"""
        val formatted = ToolJson.prettyPrintJson(raw)

        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("  \"name\": \"test\""))
        assertTrue(formatted.contains("  \"count\": 42"))
        assertTrue(formatted.contains("  \"active\": true"))
    }

    @Test
    fun `prettyPrintJson formats json array with indentation`() {
        val raw = """[{"id":1},{"id":2}]"""
        val formatted = ToolJson.prettyPrintJson(raw)

        assertTrue(formatted.contains("\n"))
        assertTrue(formatted.contains("  {\n    \"id\": 1\n  }"))
    }

    @Test
    fun `prettyPrintJson returns non-json content unchanged`() {
        val raw = "Execution failed with exit code 1"
        val formatted = ToolJson.prettyPrintJson(raw)

        assertEquals(raw, formatted)
    }

    @Test
    fun `prettyPrintJson returns empty and blank strings unchanged`() {
        assertEquals("", ToolJson.prettyPrintJson(""))
        assertEquals("   ", ToolJson.prettyPrintJson("   "))
    }
}
