package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter-contract tests for [parseToolOutput] — the thin wrapper over the
 * desktop-ported engine. Shape normalization lives here; per-tool content
 * behavior is covered by ToolViewBuilderTest in ui.chat.tool.
 */
class ToolBubbleParsingTest {
    @Test
    fun `parses new-format payload with args and result`() {
        val view =
            parseToolOutput(
                """{"args":{"command":"ls"},"result":{"output":"wiring.tsx","exit_code":0}}""",
                "terminal",
                false,
            )

        assertNotNull(view)
        assertEquals("wiring.tsx", view!!.stdout)
        assertEquals(0, view.exitCode)
        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("Ran ls", view.title)
    }

    @Test
    fun `float exit code does not null the parse`() {
        val view = parseToolOutput("""{"result":{"output":"ok","exit_code":0.0}}""", "terminal", false)

        assertNotNull(view)
        assertEquals(0, view!!.exitCode)
    }

    @Test
    fun `string exit code parses like the desktop engine`() {
        val view = parseToolOutput("""{"result":{"output":"ok","exit_code":"0.0"}}""", "terminal", false)

        assertNotNull(view)
        assertEquals(0, view!!.exitCode)
    }

    @Test
    fun `non-zero exit without output becomes an error`() {
        val view = parseToolOutput("""{"result":{"exit_code":1}}""", "terminal", false)

        assertNotNull(view)
        assertEquals(ToolViewStatus.ERROR, view!!.status)
        assertEquals("Command failed with exit code 1.", view.error)
    }

    @Test
    fun `terminal error field surfaces`() {
        val view = parseToolOutput("""{"result":{"error":"Invalid command: frobnicate"}}""", "terminal", false)

        assertNotNull(view)
        assertEquals(ToolViewStatus.ERROR, view!!.status)
        assertTrue(view.error!!.contains("Invalid command"))
    }

    @Test
    fun `old-format payload without result sub-object parses`() {
        val view = parseToolOutput("""{"name":"terminal","output":"hi","exit_code":0}""", null, false)

        assertNotNull(view)
        assertEquals("hi", view!!.stdout)
        assertEquals(0, view.exitCode)
    }

    @Test
    fun `tool name resolves from payload when message has none`() {
        val view =
            parseToolOutput(
                """{"tool_id":"call_010","name":"terminal","args":{"command":"sleep 10"}}""",
                null,
                false,
            )

        assertNotNull(view)
        assertTrue(view!!.title, view.title.startsWith("Ran "))
        assertTrue(view.title.contains("sleep 10"))
    }

    @Test
    fun `running flag drives running status and pending title`() {
        val view = parseToolOutput("""{"args":{"command":"sleep 10"}}""", "terminal", true)

        assertNotNull(view)
        assertEquals(ToolViewStatus.RUNNING, view!!.status)
        assertTrue(view.title, view.title.startsWith("Running"))
    }

    @Test
    fun `web_search payload yields hits and count`() {
        val view =
            parseToolOutput(
                """{"args":{"search_term":"cats"},"result":{"data":{"web":[
                    {"title":"Cat Facts","url":"https://cats.example/1","description":"all about cats"},
                    {"title":"More Cats","url":"https://cats.example/2","description":"even more"}
                ]}}}""",
                "web_search",
                false,
            )

        assertNotNull(view)
        assertEquals(2, view!!.searchHits?.size)
        assertEquals("2 results", view.countLabel)
        assertEquals("Searched \"cats\"", view.title)
    }

    @Test
    fun `patch payload extracts diff`() {
        val view =
            parseToolOutput(
                """{"args":{"path":"/repo/src/wiring.tsx"},"result":{"inline_diff":""" +
                    """"--- a/wiring.tsx\n+++ b/wiring.tsx\n@@ -1,1 +1,1 @@\n-old\n+new"}}""",
                "patch",
                false,
            )

        assertNotNull(view)
        assertNotNull(view!!.inlineDiff)
        assertEquals("/repo/src/wiring.tsx", view.diffPath)
        assertEquals(1, view.diffStats?.added)
        assertEquals(1, view.diffStats?.removed)
    }

    @Test
    fun `unknown tool gets an engine summary not raw json`() {
        val view =
            parseToolOutput(
                """{"result":{"data":{"title":"Build report","completed":true}}}""",
                "weird_tool",
                false,
            )

        assertNotNull(view)
        assertEquals("Weird Tool", view!!.title)
        assertTrue(view.detail, view.detail.contains("Title: Build report"))
        assertTrue(view.detail.contains("Completed: true"))
    }

    @Test
    fun `non-json content returns null`() {
        assertNull(parseToolOutput("just plain text", "terminal", false))
    }

    @Test
    fun `non-object json returns null`() {
        assertNull(parseToolOutput("[1,2,3]", "terminal", false))
    }

    @Test
    fun `memory summary keeps its target`() {
        val view =
            parseToolOutput("""{"args":{"action":"add","target":"user"},"result":{"success":true}}""", "memory", false)

        assertNotNull(view)
        assertEquals("Memory Saved (user)", view!!.title)
    }
}
