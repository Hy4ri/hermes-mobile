package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.tool.ToolView
import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for [composeToolSummaryLines] — the collapsed-summary
 * deduplication: the header row already shows the tool name, so a generic
 * engine title ("Fact Store") must not appear a second time in the summary.
 */
class ToolSummaryLinesTest {
    private fun view(
        title: String,
        subtitle: String = "",
        countLabel: String? = null,
    ) = ToolView(
        status = ToolViewStatus.SUCCESS,
        title = title,
        subtitle = subtitle,
        countLabel = countLabel,
    )

    @Test
    fun `generic title merges into the emoji line`() {
        val lines = composeToolSummaryLines(view("Fact Store", "Fact added (ID: 5)"), "fact_store", "🧠")

        assertEquals("🧠 Fact added (ID: 5)", lines!!.first)
        assertNull(lines.second)
    }

    @Test
    fun `todo shows counts on one line`() {
        val lines = composeToolSummaryLines(view("Todo", "2 items (1 pending, 1 completed)"), "todo", "✅")

        assertEquals("✅ 2 items (1 pending, 1 completed)", lines!!.first)
        assertNull(lines.second)
    }

    @Test
    fun `descriptive title stays with subtitle on line two`() {
        val lines = composeToolSummaryLines(view("Searched \"cats\"", "Query: cats", "3 results"), "web_search", "🌐")

        assertEquals("🌐 Searched \"cats\" (3 results)", lines!!.first)
        assertEquals("Query: cats", lines.second)
    }

    @Test
    fun `terminal title stays with output preview on line two`() {
        val lines = composeToolSummaryLines(view("Ran ls", "wiring.tsx"), "terminal", "💻")

        assertEquals("💻 Ran ls", lines!!.first)
        assertEquals("wiring.tsx", lines.second)
    }

    @Test
    fun `read file title stays with path on line two`() {
        val lines = composeToolSummaryLines(view("Read a.kt L10-14", "/repo/src/a.kt"), "read_file", "📄")

        assertEquals("📄 Read a.kt L10-14", lines!!.first)
        assertEquals("/repo/src/a.kt", lines.second)
    }

    @Test
    fun `generic title with no subtitle and no count is hidden`() {
        assertNull(composeToolSummaryLines(view("Fact Store"), "fact_store", "🧠"))
    }

    @Test
    fun `unknown tool generic title merges with engine first line`() {
        val lines = composeToolSummaryLines(view("Weird Tool", "- Title: Build report"), "weird_tool", "🔧")

        assertEquals("🔧 - Title: Build report", lines!!.first)
        assertNull(lines.second)
    }

    @Test
    fun `null tool name resolves to the generic tool title`() {
        val lines = composeToolSummaryLines(view("Tool", "raw payload text"), null, "🔧")

        assertEquals("🔧 raw payload text", lines!!.first)
        assertNull(lines.second)
    }

    @Test
    fun `subtitle identical to title is not duplicated`() {
        val lines = composeToolSummaryLines(view("Fact Store", "Fact Store"), "fact_store", "🧠")

        assertNull(lines)
    }
}
