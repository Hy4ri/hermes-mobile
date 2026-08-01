package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported contract tests from the desktop app's
 * `lib/tool-result-summary.test.ts` (vitest) — the engine must satisfy the
 * same cases here.
 */
class ToolResultSummaryTest {
    private fun parse(json: String) = Json.parseToJsonElement(json)

    // ── formatToolResultSummary ─────────────────────────────────────────

    @Test
    fun `unwraps wrapper payloads into structured key-value lines`() {
        val summary =
            ToolResultSummary.formatToolResultSummary(
                parse(
                    """{"success":true,"result":{"data":{"path":"/tmp/demo.txt","status":"ok",""" +
                        """"lines_written":12,"checksum":"abc123"}}}""",
                ),
            )

        assertTrue(summary.contains("- Path: /tmp/demo.txt"))
        assertTrue(summary.contains("- Status: ok"))
        assertTrue(summary.contains("- Lines Written: 12"))
        assertFalse(summary.contains("\"path\""))
    }

    @Test
    fun `summarizes object arrays as readable list items`() {
        val json =
            """[
                {"title":"First result","snippet":"alpha preview text"},
                {"title":"Second result","status":"cached"},
                {"title":"Third result","summary":"more details"},
                {"title":"Fourth result","summary":"line 4"},
                {"title":"Fifth result","summary":"line 5"},
                {"title":"Sixth result","summary":"line 6"},
                {"title":"Seventh result","summary":"line 7"}
            ]"""
        val summary = ToolResultSummary.formatToolResultSummary(parse(json))

        assertTrue("got:\n$summary", summary.contains("- First result - alpha preview text"))
        assertTrue(summary.contains("- Second result (cached)"))
        assertTrue("got:\n$summary", summary.contains("- … 1 more item"))
    }

    @Test
    fun `truncates long field values for compact display`() {
        val summary =
            ToolResultSummary.formatToolResultSummary(
                parse("""{"message":"ok","details":"prefix ${"x".repeat(500)}"}"""),
            )
        val detailsLine = summary.split("\n").firstOrNull { it.startsWith("- Details:") }

        assertTrue(detailsLine != null)
        assertTrue(detailsLine!!.length < 230)
        assertTrue(detailsLine.contains("…"))
    }

    @Test
    fun `formats stringified json payloads without raw dumps`() {
        val summary =
            ToolResultSummary.formatToolResultSummary(
                JsonPrimitive("""{"data":{"title":"Build report","completed":true}}"""),
            )

        assertTrue(summary.contains("- Title: Build report"))
        assertTrue(summary.contains("- Completed: true"))
    }

    // ── extractToolErrorMessage ─────────────────────────────────────────

    @Test
    fun `finds nested error messages through wrappers`() {
        val error =
            ToolResultSummary.extractToolErrorMessage(
                parse(
                    """{"success":false,"result":{"output":{"error":{"message":""" +
                        """"Permission denied writing /tmp/demo.txt"}}}}""",
                ),
            )

        assertEquals("Permission denied writing /tmp/demo.txt", error)
    }

    @Test
    fun `does not treat successful payload messages as errors`() {
        val error =
            ToolResultSummary.extractToolErrorMessage(
                parse("""{"success":true,"message":"Completed successfully","data":{"count":3}}"""),
            )

        assertEquals("", error)
    }

    @Test
    fun `ignores placeholder error fields in successful payloads`() {
        val error =
            ToolResultSummary.extractToolErrorMessage(
                parse("""{"success":true,"data":{"error":"none","status":"ok"}}"""),
            )

        assertEquals("", error)
    }

    // ── extra edge cases beyond the desktop suite ───────────────────────

    @Test
    fun `empty array renders nothing instead of noise`() {
        val summary = ToolResultSummary.formatToolResultSummary(parse("""{"results":[]}"""))

        assertEquals("", summary)
    }

    @Test
    fun `unwraps up to four wrapper levels`() {
        val summary =
            ToolResultSummary.formatToolResultSummary(
                parse(
                    """{"data":{"result":{"output":{"response":{"title":"deep","count":2}}}}}""",
                ),
            )

        assertTrue(summary.contains("- Title: deep"))
        assertTrue(summary.contains("- Count: 2"))
    }

    @Test
    fun `scalar results pass through`() {
        assertEquals("hello", ToolResultSummary.formatToolResultSummary(JsonPrimitive("hello")))
        assertEquals("42", ToolResultSummary.formatToolResultSummary(JsonPrimitive(42)))
    }
}
