package com.m57.hermescontrol.ui.chat

/**
 * Display configuration for a Hermes tool — controls the summary line,
 * icon, and which arg field is shown when the bubble is collapsed.
 *
 * Based on the canonical tool schemas defined in the Hermes Agent source
 * at /opt/hermes-agent/tools/ (each tool has a `*_SCHEMA` dict).
 */
data class ToolDisplayConfig(
    val name: String,
    /** Key into the `args` dict for the one-line summary (e.g. "command" for terminal). */
    val summaryArgKey: String? = null,
    /** Prefix for the summary line (e.g. "$ " for terminal, "📄 " for read_file). */
    val summaryPrefix: String = "",
    /** Emoji icon for this tool type. */
    val iconEmoji: String = "🔧",
)

/** Maps tool names to their display config. Ordered by expected frequency of use. */
object ToolSchemaRegistry {
    private val knownTools: Map<String, ToolDisplayConfig> =
        mapOf(
            "terminal" to
                ToolDisplayConfig(
                    name = "terminal",
                    summaryArgKey = "command",
                    summaryPrefix = "$ ",
                    iconEmoji = "💻",
                ),
            "read_file" to
                ToolDisplayConfig(
                    name = "read_file",
                    summaryArgKey = "path",
                    summaryPrefix = "📄 ",
                    iconEmoji = "📄",
                ),
            "write_file" to
                ToolDisplayConfig(
                    name = "write_file",
                    summaryArgKey = "path",
                    summaryPrefix = "✏️ ",
                    iconEmoji = "✏️",
                ),
            "patch" to
                ToolDisplayConfig(
                    name = "patch",
                    summaryArgKey = "path",
                    summaryPrefix = "🔧 ",
                    iconEmoji = "🔧",
                ),
            "search_files" to
                ToolDisplayConfig(
                    name = "search_files",
                    summaryArgKey = "pattern",
                    summaryPrefix = "🔍 ",
                    iconEmoji = "🔍",
                ),
            "web_search" to
                ToolDisplayConfig(
                    name = "web_search",
                    summaryArgKey = "query",
                    summaryPrefix = "🌐 ",
                    iconEmoji = "🌐",
                ),
            "browser_navigate" to
                ToolDisplayConfig(
                    name = "browser_navigate",
                    summaryArgKey = "url",
                    summaryPrefix = "🌍 ",
                    iconEmoji = "🌍",
                ),
            "browser_click" to
                ToolDisplayConfig(
                    name = "browser_click",
                    summaryArgKey = "ref",
                    summaryPrefix = "🖱 ",
                    iconEmoji = "🖱",
                ),
            "browser_snapshot" to
                ToolDisplayConfig(
                    name = "browser_snapshot",
                    summaryArgKey = null,
                    iconEmoji = "📋",
                ),
            "clarify" to
                ToolDisplayConfig(
                    name = "clarify",
                    summaryArgKey = "question",
                    summaryPrefix = "💬 ",
                    iconEmoji = "💬",
                ),
            "delegate_task" to
                ToolDisplayConfig(
                    name = "delegate_task",
                    summaryArgKey = "goal",
                    summaryPrefix = "🔄 ",
                    iconEmoji = "🔄",
                ),
            "execute_code" to
                ToolDisplayConfig(
                    name = "execute_code",
                    summaryArgKey = "code",
                    summaryPrefix = "▶️ ",
                    iconEmoji = "▶️",
                ),
            "todo" to
                ToolDisplayConfig(
                    name = "todo",
                    summaryArgKey = null,
                    iconEmoji = "📋",
                ),
            "fact_store" to
                ToolDisplayConfig(
                    name = "fact_store",
                    summaryArgKey = null,
                    iconEmoji = "🧠",
                ),
            "session_search" to
                ToolDisplayConfig(
                    name = "session_search",
                    summaryArgKey = null,
                    iconEmoji = "🔍",
                ),
            // ── Action-based tools ──
            "cronjob" to
                ToolDisplayConfig(
                    name = "cronjob",
                    summaryArgKey = "action",
                    iconEmoji = "🔄",
                ),
            "memory" to
                ToolDisplayConfig(
                    name = "memory",
                    summaryArgKey = "action",
                    iconEmoji = "💾",
                ),
            "fact_feedback" to
                ToolDisplayConfig(
                    name = "fact_feedback",
                    summaryArgKey = "action",
                    iconEmoji = "👍",
                ),
            "process" to
                ToolDisplayConfig(
                    name = "process",
                    summaryArgKey = "action",
                    iconEmoji = "⚙️",
                ),
            "skill_manage" to
                ToolDisplayConfig(
                    name = "skill_manage",
                    summaryArgKey = "action",
                    iconEmoji = "🛠️",
                ),
            // ── Web / Browser tools ──
            "web_extract" to
                ToolDisplayConfig(
                    name = "web_extract",
                    summaryArgKey = "urls",
                    iconEmoji = "🕸️",
                ),
            "browser_type" to
                ToolDisplayConfig(
                    name = "browser_type",
                    summaryArgKey = null,
                    iconEmoji = "⌨️",
                ),
            "browser_cdp" to
                ToolDisplayConfig(
                    name = "browser_cdp",
                    summaryArgKey = "action",
                    iconEmoji = "🔧",
                ),
            "browser_dialog" to
                ToolDisplayConfig(
                    name = "browser_dialog",
                    summaryArgKey = "action",
                    iconEmoji = "💬",
                ),
            // ── Media / Vision tools ──
            "vision_analyze" to
                ToolDisplayConfig(
                    name = "vision_analyze",
                    summaryArgKey = "image_url",
                    iconEmoji = "👁️",
                ),
            "text_to_speech" to
                ToolDisplayConfig(
                    name = "text_to_speech",
                    summaryArgKey = null,
                    iconEmoji = "🔊",
                ),
            "video_generate" to
                ToolDisplayConfig(
                    name = "video_generate",
                    summaryArgKey = null,
                    iconEmoji = "🎬",
                ),
            "image_generate" to
                ToolDisplayConfig(
                    name = "image_generate",
                    summaryArgKey = null,
                    iconEmoji = "🎨",
                ),
            // ── Social / Messaging tools ──
            "x_search" to
                ToolDisplayConfig(
                    name = "x_search",
                    summaryArgKey = "query",
                    iconEmoji = "𝕏",
                ),
            "send_message" to
                ToolDisplayConfig(
                    name = "send_message",
                    summaryArgKey = null,
                    iconEmoji = "📨",
                ),
            // ── Skills tools ──
            "skills_list" to
                ToolDisplayConfig(
                    name = "skills_list",
                    summaryArgKey = null,
                    iconEmoji = "📚",
                ),
            "skill_view" to
                ToolDisplayConfig(
                    name = "skill_view",
                    summaryArgKey = "name",
                    iconEmoji = "📖",
                ),
            // ── On-demand tools ──
            "tool_search" to
                ToolDisplayConfig(
                    name = "tool_search",
                    summaryArgKey = "query",
                    iconEmoji = "🔍",
                ),
            "tool_describe" to
                ToolDisplayConfig(
                    name = "tool_describe",
                    summaryArgKey = "name",
                    iconEmoji = "📖",
                ),
            "tool_call" to
                ToolDisplayConfig(
                    name = "tool_call",
                    summaryArgKey = "name",
                    iconEmoji = "🔧",
                ),
            // ── Misc ──
            "read_terminal" to
                ToolDisplayConfig(
                    name = "read_terminal",
                    summaryArgKey = "session_id",
                    iconEmoji = "🖥️",
                ),
            "computer_use" to
                ToolDisplayConfig(
                    name = "computer_use",
                    summaryArgKey = "action",
                    iconEmoji = "🖥️",
                ),
        )

    fun getDisplayConfig(toolName: String?): ToolDisplayConfig =
        toolName?.let { knownTools[it] } ?: ToolDisplayConfig(name = toolName ?: "tool")
}
