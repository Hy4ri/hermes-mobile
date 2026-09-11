package com.m57.hermescontrol.ui.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpImportSummaryTest {
    @Test
    fun `formatSummary returns success message when zero errors`() {
        val result = McpImportSummary.formatSummary(3, emptyList())
        assertEquals("Successfully imported 3 server(s)", result)
    }

    @Test
    fun `formatSummary returns partial success message when some failed`() {
        val result = McpImportSummary.formatSummary(2, listOf("serverA: timeout", "serverB: 404"))
        assertEquals("Imported 2 server(s), 2 failed: serverA: timeout; serverB: 404", result)
    }

    @Test
    fun `formatSummary returns failure message when all failed`() {
        val result = McpImportSummary.formatSummary(0, listOf("serverA: connection refused"))
        assertEquals("Failed to import: serverA: connection refused", result)
    }
}
