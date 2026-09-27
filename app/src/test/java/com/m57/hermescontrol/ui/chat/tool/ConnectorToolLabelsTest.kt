package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorToolLabelsTest {
    @Test fun `gateway envelope labels reach the visible header without replacing real tool arguments`() {
        val content = """{
            "name":"tool_call",
            "args":{"labels":["bug"]},
            "labels":[{"app":"GitHub","text":"Create issue"}],
            "result":{}
        }"""
        val view =
            com.m57.hermescontrol.ui.chat
                .parseToolOutput(content, "tool_call", false)
        assertEquals("GitHub · Create issue", view?.serverDisplayName)
        assertEquals("GitHub · Create issue", view?.title)
    }

    @Test fun `server connector labels title both live and restored tool calls`() {
        val args =
            Json.parseToJsonElement(
                """{"hermes_tool_labels":[{"app":"Google Drive","text":"List files","kind":"connector"}]}""",
            )
        val running = ToolViewBuilder.build("tool_call", args, null, running = true)
        val restored = ToolViewBuilder.build("tool_call", args, Json.parseToJsonElement("{}"))
        assertEquals("Google Drive · List files", running.title)
        assertEquals(running.title, restored.title)
    }

    @Test fun `malformed metadata and actual labels argument keep fallback title`() {
        val args = Json.parseToJsonElement("""{"labels":["bug"],"hermes_tool_labels":[null,42,{"app":"GitHub"}]}""")
        val view = ToolViewBuilder.build("tool_call", args, null)
        assertTrue(view.title.isNotBlank())
        assertEquals(ToolViewBuilder.build("tool_call", null, null).title, view.title)
    }
}
