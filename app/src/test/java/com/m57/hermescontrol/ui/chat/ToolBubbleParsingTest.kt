package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBubbleParsingTest {
    // ── Terminal ──────────────────────────────────────────────

    @Test
    fun testParseTerminal_withOutput() {
        val json =
            """{
            "tool_id": "call_001",
            "name": "terminal",
            "args": {"command": "echo hellp"},
            "result": {"output": "hellp", "exit_code": 0, "error": null},
            "duration_s": 0.06
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertEquals("terminal", parsed!!.toolName)
        assertTrue(parsed.isTerminal)
        assertEquals("hellp", parsed.stdout)
        assertEquals("hellp", parsed.mainOutput)
        assertEquals(0, parsed.exitCode)
        assertEquals(0.06, parsed.durationSec!!, 0.01)
        assertEquals("$ echo hellp", parsed.summaryText)
    }

    @Test
    fun testParseTerminal_legacyStdout() {
        val json =
            """{
            "tool_id": "call_001b",
            "name": "terminal",
            "args": {"command": "npm run build"},
            "result": {"stdout": "Build succeeded!", "exit_code": 0},
            "duration_s": 5.2
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertTrue(parsed!!.isTerminal)
        assertEquals("Build succeeded!", parsed.stdout)
        assertEquals("Build succeeded!", parsed.mainOutput)
        assertEquals(0, parsed.exitCode)
        assertEquals(5.2, parsed.durationSec!!, 0.01)
        assertEquals("$ npm run build", parsed.summaryText)
    }

    @Test
    fun testParseTerminal_floatExitCode_isNotDropped() {
        // Backend emits exit_code as a FLOAT (0.0), not an int. asInt on a
        // "0.0" primitive throws NumberFormatException, which previously
        // nulled the whole parse -> raw JSON. Reproduces the live bug.
        val json =
            """{
            "tool_id": "call_001d",
            "name": "terminal",
            "args": {"command": "echo hi"},
            "result": {"output": "hi", "exit_code": 0.0, "error": null}
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull("float exit_code must not null the parse", parsed)
        assertTrue(parsed!!.isTerminal)
        assertEquals("hi", parsed.stdout)
        assertEquals(0, parsed.exitCode)
    }

    @Test
    fun testParseTerminal_withStderr() {
        val json =
            """{
            "tool_id": "call_002",
            "name": "terminal",
            "args": {"command": "rm -rf /"},
            "result": {"stderr": "Permission denied", "exit_code": 1}
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertTrue(parsed!!.isTerminal)
        assertEquals("Permission denied", parsed.stdout)
        assertEquals(1, parsed.exitCode)
        assertEquals("$ rm -rf /", parsed.summaryText)
    }

    // ── File tools ────────────────────────────────────────────

    @Test
    fun testParseReadFile() {
        val json =
            """{
            "tool_id": "call_003",
            "name": "read_file",
            "args": {"path": "/opt/hermes/config.yaml"},
            "result": {"content": "port: 8080\nhost: 0.0.0.0"}
        }"""
        val parsed = parseToolOutput(json, "read_file", false)
        assertNotNull(parsed)
        assertFalse(parsed!!.isTerminal)
        assertEquals("📄 /opt/hermes/config.yaml", parsed.summaryText)
        assertEquals("port: 8080\nhost: 0.0.0.0", parsed.mainOutput)
    }

    @Test
    fun testParseWriteFile() {
        val json =
            """{
            "tool_id": "call_004",
            "name": "write_file",
            "args": {"path": "/tmp/output.txt"},
            "result": {"content": "File written successfully"}
        }"""
        val parsed = parseToolOutput(json, "write_file", false)
        assertNotNull(parsed)
        assertEquals("✏️ /tmp/output.txt", parsed!!.summaryText)
    }

    @Test
    fun testParsePatch() {
        val json =
            """{
            "tool_id": "call_005",
            "name": "patch",
            "args": {"path": "src/main.kt"},
            "result": {"success": true}
        }"""
        val parsed = parseToolOutput(json, "patch", false)
        assertNotNull(parsed)
        assertEquals("🔧 src/main.kt", parsed!!.summaryText)
    }

    @Test
    fun testParseSearchFiles() {
        val json =
            """{
            "tool_id": "call_006",
            "name": "search_files",
            "args": {"pattern": "*.kt", "path": "/opt/hermes-mobile"},
            "result": {"matches_data": ["file1.kt", "file2.kt"]}
        }"""
        val parsed = parseToolOutput(json, "search_files", false)
        assertNotNull(parsed)
        assertEquals("🔍 *.kt", parsed!!.summaryText)
    }

    // ── Web / Browser ─────────────────────────────────────────

    @Test
    fun testParseWebSearch() {
        val json =
            """{
            "tool_id": "call_007",
            "name": "web_search",
            "args": {"query": "Kotlin Coroutines guide"},
            "result": {"results_data": ["..."]}
        }"""
        val parsed = parseToolOutput(json, "web_search", false)
        assertNotNull(parsed)
        assertEquals("🌐 Kotlin Coroutines guide", parsed!!.summaryText)
    }

    @Test
    fun testParseBrowserNavigate() {
        val json =
            """{
            "tool_id": "call_008",
            "name": "browser_navigate",
            "args": {"url": "https://example.com"},
            "result": {"title": "Example Domain"}
        }"""
        val parsed = parseToolOutput(json, "browser_navigate", false)
        assertNotNull(parsed)
        assertEquals("🌍 https://example.com", parsed!!.summaryText)
    }

    @Test
    fun testParseClarify() {
        val json =
            """{
            "tool_id": "call_009",
            "name": "clarify",
            "args": {"question": "Which environment?"},
            "result": {"user_response": "staging"}
        }"""
        val parsed = parseToolOutput(json, "clarify", false)
        assertNotNull(parsed)
        assertEquals("💬 Which environment?", parsed!!.summaryText)
    }

    // ── Running state ─────────────────────────────────────────

    @Test
    fun testParseRunningState() {
        val json =
            """{"tool_id": "call_010", "name": "terminal", "args": {"command": "sleep 10"}}"""
        val parsed = parseToolOutput(json, "terminal", true)
        assertNotNull(parsed)
        assertTrue(parsed!!.isRunning)
    }

    // ── Unknown tool fallback ─────────────────────────────────

    @Test
    fun testParseUnknownTool_noSummary() {
        val json =
            """{
            "tool_id": "call_011",
            "name": "weird_tool",
            "args": {"input": "data"},
            "result": {"output": "processed"}
        }"""
        val parsed = parseToolOutput(json, "weird_tool", false)
        assertNotNull(parsed)
        assertNull(parsed!!.summaryText) // no known config → no summary
        assertEquals("processed", parsed.mainOutput)
    }

    // ── Non-JSON content fallback ─────────────────────────────

    @Test
    fun testParseNonJson_returnsNull() {
        val parsed = parseToolOutput("just plain text", "terminal", false)
        assertNull(parsed)
    }

    // ── Empty args ────────────────────────────────────────────

    @Test
    fun testParseTerminal_withoutArgs() {
        val json =
            """{
            "tool_id": "call_012",
            "name": "terminal",
            "result": {"stdout": "done", "exit_code": 0}
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertTrue(parsed!!.isTerminal)
        assertEquals("done", parsed.stdout)
        assertEquals(0, parsed.exitCode)
        assertNull(parsed.summaryText) // no args → no summary
    }
}
