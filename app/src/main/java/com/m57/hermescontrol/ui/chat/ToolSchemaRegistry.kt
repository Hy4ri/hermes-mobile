package com.m57.hermescontrol.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

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
    /** Prefix for the summary line (e.g. "$ " for terminal). Emoji-free. */
    val summaryPrefix: String = "",
    /** Material icon for this tool type. */
    val icon: ImageVector = Icons.Filled.Build,
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
                    icon = Icons.Filled.Terminal,
                ),
            "read_file" to
                ToolDisplayConfig(
                    name = "read_file",
                    summaryArgKey = "path",
                    icon = Icons.Filled.Description,
                ),
            "write_file" to
                ToolDisplayConfig(
                    name = "write_file",
                    summaryArgKey = "path",
                    icon = Icons.Filled.Edit,
                ),
            "patch" to
                ToolDisplayConfig(
                    name = "patch",
                    summaryArgKey = "path",
                    icon = Icons.Filled.Build,
                ),
            "search_files" to
                ToolDisplayConfig(
                    name = "search_files",
                    summaryArgKey = "pattern",
                    icon = Icons.Filled.Search,
                ),
            "web_search" to
                ToolDisplayConfig(
                    name = "web_search",
                    summaryArgKey = "query",
                    icon = Icons.Filled.Public,
                ),
            "browser_navigate" to
                ToolDisplayConfig(
                    name = "browser_navigate",
                    summaryArgKey = "url",
                    icon = Icons.Filled.Language,
                ),
            "browser_click" to
                ToolDisplayConfig(
                    name = "browser_click",
                    summaryArgKey = "ref",
                    icon = Icons.Filled.TouchApp,
                ),
            "browser_snapshot" to
                ToolDisplayConfig(
                    name = "browser_snapshot",
                    summaryArgKey = null,
                    icon = Icons.Filled.Photo,
                ),
            "clarify" to
                ToolDisplayConfig(
                    name = "clarify",
                    summaryArgKey = "question",
                    icon = Icons.Filled.ChatBubble,
                ),
            "delegate_task" to
                ToolDisplayConfig(
                    name = "delegate_task",
                    summaryArgKey = "goal",
                    icon = Icons.Filled.AccountTree,
                ),
            "execute_code" to
                ToolDisplayConfig(
                    name = "execute_code",
                    summaryArgKey = "code",
                    icon = Icons.Filled.PlayArrow,
                ),
            "todo" to
                ToolDisplayConfig(
                    name = "todo",
                    summaryArgKey = null,
                    icon = Icons.Filled.Checklist,
                ),
            "fact_store" to
                ToolDisplayConfig(
                    name = "fact_store",
                    summaryArgKey = null,
                    icon = Icons.Filled.Psychology,
                ),
            "session_search" to
                ToolDisplayConfig(
                    name = "session_search",
                    summaryArgKey = null,
                    icon = Icons.Filled.Search,
                ),
            // ── Action-based tools ──
            "cronjob" to
                ToolDisplayConfig(
                    name = "cronjob",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Schedule,
                ),
            "memory" to
                ToolDisplayConfig(
                    name = "memory",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Memory,
                ),
            "fact_feedback" to
                ToolDisplayConfig(
                    name = "fact_feedback",
                    summaryArgKey = "action",
                    icon = Icons.Filled.ThumbUp,
                ),
            "process" to
                ToolDisplayConfig(
                    name = "process",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Settings,
                ),
            "skill_manage" to
                ToolDisplayConfig(
                    name = "skill_manage",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Build,
                ),
            // ── Web / Browser tools ──
            "web_extract" to
                ToolDisplayConfig(
                    name = "web_extract",
                    summaryArgKey = "urls",
                    icon = Icons.Filled.Language,
                ),
            "browser_type" to
                ToolDisplayConfig(
                    name = "browser_type",
                    summaryArgKey = null,
                    icon = Icons.Filled.Keyboard,
                ),
            "browser_cdp" to
                ToolDisplayConfig(
                    name = "browser_cdp",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Build,
                ),
            "browser_dialog" to
                ToolDisplayConfig(
                    name = "browser_dialog",
                    summaryArgKey = "action",
                    icon = Icons.Filled.ChatBubble,
                ),
            // ── Media / Vision tools ──
            "vision_analyze" to
                ToolDisplayConfig(
                    name = "vision_analyze",
                    summaryArgKey = "image_url",
                    icon = Icons.Filled.Visibility,
                ),
            "text_to_speech" to
                ToolDisplayConfig(
                    name = "text_to_speech",
                    summaryArgKey = null,
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                ),
            "video_generate" to
                ToolDisplayConfig(
                    name = "video_generate",
                    summaryArgKey = null,
                    icon = Icons.Filled.Movie,
                ),
            "image_generate" to
                ToolDisplayConfig(
                    name = "image_generate",
                    summaryArgKey = null,
                    icon = Icons.Filled.Image,
                ),
            // ── Social / Messaging tools ──
            "x_search" to
                ToolDisplayConfig(
                    name = "x_search",
                    summaryArgKey = "query",
                    icon = Icons.Filled.Forum,
                ),
            "send_message" to
                ToolDisplayConfig(
                    name = "send_message",
                    summaryArgKey = null,
                    icon = Icons.AutoMirrored.Filled.Send,
                ),
            // ── Skills tools ──
            "skills_list" to
                ToolDisplayConfig(
                    name = "skills_list",
                    summaryArgKey = null,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                ),
            "skill_view" to
                ToolDisplayConfig(
                    name = "skill_view",
                    summaryArgKey = "name",
                    icon = Icons.Filled.AutoStories,
                ),
            // ── On-demand tools ──
            "tool_search" to
                ToolDisplayConfig(
                    name = "tool_search",
                    summaryArgKey = "query",
                    icon = Icons.Filled.Search,
                ),
            "tool_describe" to
                ToolDisplayConfig(
                    name = "tool_describe",
                    summaryArgKey = "name",
                    icon = Icons.Filled.AutoStories,
                ),
            "tool_call" to
                ToolDisplayConfig(
                    name = "tool_call",
                    summaryArgKey = "name",
                    icon = Icons.Filled.Build,
                ),
            // ── Misc ──
            "read_terminal" to
                ToolDisplayConfig(
                    name = "read_terminal",
                    summaryArgKey = "session_id",
                    icon = Icons.Filled.Computer,
                ),
            "computer_use" to
                ToolDisplayConfig(
                    name = "computer_use",
                    summaryArgKey = "action",
                    icon = Icons.Filled.Computer,
                ),
        )

    fun getDisplayConfig(toolName: String?): ToolDisplayConfig =
        toolName?.let { knownTools[it] } ?: ToolDisplayConfig(name = toolName ?: "tool")
}
