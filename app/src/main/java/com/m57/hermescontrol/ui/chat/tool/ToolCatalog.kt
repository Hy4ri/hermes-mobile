package com.m57.hermescontrol.ui.chat.tool

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
import com.m57.hermescontrol.ui.chat.tool.render.BrowserActionRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserClickRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserConsoleRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserImagesRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserNavigateRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserSnapshotRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserTypeRenderer
import com.m57.hermescontrol.ui.chat.tool.render.BrowserVisionRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ComputerUseRenderer
import com.m57.hermescontrol.ui.chat.tool.render.CronjobRenderer
import com.m57.hermescontrol.ui.chat.tool.render.DelegateTaskRenderer
import com.m57.hermescontrol.ui.chat.tool.render.FactStoreRenderer
import com.m57.hermescontrol.ui.chat.tool.render.FileEditRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ImageGenerateRenderer
import com.m57.hermescontrol.ui.chat.tool.render.MemoryRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ProcessRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ProjectListRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ProjectMutateRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ReadFileRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ReadTerminalRenderer
import com.m57.hermescontrol.ui.chat.tool.render.SearchFilesRenderer
import com.m57.hermescontrol.ui.chat.tool.render.SessionSearchRenderer
import com.m57.hermescontrol.ui.chat.tool.render.SkillManageRenderer
import com.m57.hermescontrol.ui.chat.tool.render.SkillViewRenderer
import com.m57.hermescontrol.ui.chat.tool.render.SkillsListRenderer
import com.m57.hermescontrol.ui.chat.tool.render.TerminalRenderer
import com.m57.hermescontrol.ui.chat.tool.render.TextToSpeechRenderer
import com.m57.hermescontrol.ui.chat.tool.render.TodoRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ToolDescribeRenderer
import com.m57.hermescontrol.ui.chat.tool.render.ToolSearchRenderer
import com.m57.hermescontrol.ui.chat.tool.render.VisionAnalyzeRenderer
import com.m57.hermescontrol.ui.chat.tool.render.WebExtractRenderer
import com.m57.hermescontrol.ui.chat.tool.render.WebSearchRenderer
import com.m57.hermescontrol.ui.chat.tool.render.XSearchRenderer

/** A tool's visual identity (header/status icon) and its [ToolRenderer]. */
internal data class ToolDefinition(
    val icon: ImageVector,
    val renderer: ToolRenderer,
)

/** All-default renderer: defers everything to the generic fallbacks. */
internal object GenericToolRenderer : ToolRenderer

/**
 * The single tool catalog (issue #1326): every known tool declares its icon
 * and renderer together, so the two can no longer drift apart.
 *
 * Unknown tools get the Build icon and [GenericToolRenderer]: every hook
 * returns null, so [ToolViewBuilder] falls back to the humanized tool name
 * and the [ToolResultSummary] heuristics — never a raw JSON dump.
 */
internal object ToolCatalog {
    val FALLBACK = ToolDefinition(Icons.Filled.Build, GenericToolRenderer)

    val entries: Map<String, ToolDefinition> =
        mapOf(
            "browser_back" to ToolDefinition(Icons.Filled.Language, BrowserActionRenderer),
            "browser_cdp" to ToolDefinition(Icons.Filled.Build, GenericToolRenderer),
            "browser_click" to ToolDefinition(Icons.Filled.TouchApp, BrowserClickRenderer),
            "browser_console" to ToolDefinition(Icons.Filled.Terminal, BrowserConsoleRenderer),
            "browser_dialog" to ToolDefinition(Icons.Filled.ChatBubble, GenericToolRenderer),
            "browser_fill" to ToolDefinition(Icons.Filled.Keyboard, BrowserTypeRenderer),
            "browser_get_images" to ToolDefinition(Icons.Filled.Image, BrowserImagesRenderer),
            "browser_navigate" to ToolDefinition(Icons.Filled.Language, BrowserNavigateRenderer),
            "browser_press" to ToolDefinition(Icons.Filled.Keyboard, BrowserActionRenderer),
            "browser_scroll" to ToolDefinition(Icons.Filled.TouchApp, BrowserActionRenderer),
            "browser_snapshot" to ToolDefinition(Icons.Filled.Photo, BrowserSnapshotRenderer),
            "browser_type" to ToolDefinition(Icons.Filled.Keyboard, BrowserTypeRenderer),
            "browser_vision" to ToolDefinition(Icons.Filled.Visibility, BrowserVisionRenderer),
            "clarify" to ToolDefinition(Icons.Filled.ChatBubble, GenericToolRenderer),
            "computer_use" to ToolDefinition(Icons.Filled.Computer, ComputerUseRenderer),
            "cronjob" to ToolDefinition(Icons.Filled.Schedule, CronjobRenderer),
            "cronjob_manage" to ToolDefinition(Icons.Filled.Schedule, CronjobRenderer),
            "delegate_task" to ToolDefinition(Icons.Filled.AccountTree, DelegateTaskRenderer),
            "edit_file" to ToolDefinition(Icons.Filled.Edit, FileEditRenderer),
            "execute_code" to ToolDefinition(Icons.Filled.PlayArrow, TerminalRenderer),
            "fact_feedback" to ToolDefinition(Icons.Filled.ThumbUp, GenericToolRenderer),
            "fact_store" to ToolDefinition(Icons.Filled.Psychology, FactStoreRenderer),
            "image_generate" to ToolDefinition(Icons.Filled.Image, ImageGenerateRenderer),
            "memory" to ToolDefinition(Icons.Filled.Memory, MemoryRenderer),
            "patch" to ToolDefinition(Icons.Filled.Build, FileEditRenderer),
            "process" to ToolDefinition(Icons.Filled.Settings, ProcessRenderer),
            "process_manage" to ToolDefinition(Icons.Filled.Settings, ProcessRenderer),
            "project_create" to ToolDefinition(Icons.Filled.AccountTree, ProjectMutateRenderer),
            "project_list" to ToolDefinition(Icons.Filled.AccountTree, ProjectListRenderer),
            "project_switch" to ToolDefinition(Icons.Filled.AccountTree, ProjectMutateRenderer),
            "read_file" to ToolDefinition(Icons.Filled.Description, ReadFileRenderer),
            "read_terminal" to ToolDefinition(Icons.Filled.Computer, ReadTerminalRenderer),
            "search_files" to ToolDefinition(Icons.Filled.Search, SearchFilesRenderer),
            "send_message" to ToolDefinition(Icons.AutoMirrored.Filled.Send, GenericToolRenderer),
            "session_search" to ToolDefinition(Icons.Filled.Search, SessionSearchRenderer),
            "skill_manage" to ToolDefinition(Icons.Filled.Build, SkillManageRenderer),
            "skill_view" to ToolDefinition(Icons.Filled.AutoStories, SkillViewRenderer),
            "skills_list" to ToolDefinition(Icons.AutoMirrored.Filled.MenuBook, SkillsListRenderer),
            "terminal" to ToolDefinition(Icons.Filled.Terminal, TerminalRenderer),
            "text_to_speech" to ToolDefinition(Icons.AutoMirrored.Filled.VolumeUp, TextToSpeechRenderer),
            "todo" to ToolDefinition(Icons.Filled.Checklist, TodoRenderer),
            "todo_list" to ToolDefinition(Icons.Filled.Checklist, TodoRenderer),
            "tool_call" to ToolDefinition(Icons.Filled.Build, GenericToolRenderer),
            "tool_describe" to ToolDefinition(Icons.Filled.AutoStories, ToolDescribeRenderer),
            "tool_search" to ToolDefinition(Icons.Filled.Search, ToolSearchRenderer),
            "video_generate" to ToolDefinition(Icons.Filled.Movie, GenericToolRenderer),
            "vision_analyze" to ToolDefinition(Icons.Filled.Visibility, VisionAnalyzeRenderer),
            "web_extract" to ToolDefinition(Icons.Filled.Language, WebExtractRenderer),
            "web_search" to ToolDefinition(Icons.Filled.Public, WebSearchRenderer),
            "write_file" to ToolDefinition(Icons.Filled.Edit, FileEditRenderer),
            "x_search" to ToolDefinition(Icons.Filled.Forum, XSearchRenderer),
        )

    fun definitionFor(toolName: String?): ToolDefinition = toolName?.let { entries[it] } ?: FALLBACK
}
