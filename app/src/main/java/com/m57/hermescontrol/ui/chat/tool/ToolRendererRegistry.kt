package com.m57.hermescontrol.ui.chat.tool

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
import com.m57.hermescontrol.ui.chat.tool.render.ToolSearchRenderer
import com.m57.hermescontrol.ui.chat.tool.render.VisionAnalyzeRenderer
import com.m57.hermescontrol.ui.chat.tool.render.WebExtractRenderer
import com.m57.hermescontrol.ui.chat.tool.render.WebSearchRenderer
import com.m57.hermescontrol.ui.chat.tool.render.XSearchRenderer

/**
 * Maps tool names to their display strategy.
 *
 * Unknown tools get [GenericToolRenderer]: every hook returns null, so
 * [ToolViewBuilder] falls back to the humanized tool name and the
 * [ToolResultSummary] heuristics — never a raw JSON dump.
 */
internal object ToolRendererRegistry {
    /** All-default renderer: defers everything to the generic fallbacks. */
    private object GenericToolRenderer : ToolRenderer

    private val renderers: Map<String, ToolRenderer> =
        mapOf(
            // Shell
            "terminal" to TerminalRenderer,
            "execute_code" to TerminalRenderer,
            "read_terminal" to ReadTerminalRenderer,
            // Delegation
            "delegate_task" to DelegateTaskRenderer,
            // Files
            "read_file" to ReadFileRenderer,
            "edit_file" to FileEditRenderer,
            "patch" to FileEditRenderer,
            "write_file" to FileEditRenderer,
            "search_files" to SearchFilesRenderer,
            // Web
            "web_search" to WebSearchRenderer,
            "web_extract" to WebExtractRenderer,
            "x_search" to XSearchRenderer,
            // Browser
            "browser_navigate" to BrowserNavigateRenderer,
            "browser_snapshot" to BrowserSnapshotRenderer,
            "browser_click" to BrowserClickRenderer,
            "browser_fill" to BrowserTypeRenderer,
            "browser_type" to BrowserTypeRenderer,
            "browser_scroll" to BrowserActionRenderer,
            "browser_back" to BrowserActionRenderer,
            "browser_press" to BrowserActionRenderer,
            "browser_get_images" to BrowserImagesRenderer,
            "browser_vision" to BrowserVisionRenderer,
            "browser_console" to BrowserConsoleRenderer,
            // Memory / facts
            "memory" to MemoryRenderer,
            "fact_store" to FactStoreRenderer,
            // Task management
            "cronjob" to CronjobRenderer,
            "cronjob_manage" to CronjobRenderer,
            "todo" to TodoRenderer,
            "todo_list" to TodoRenderer,
            "session_search" to SessionSearchRenderer,
            "process" to ProcessRenderer,
            "process_manage" to ProcessRenderer,
            // Skills / tool discovery
            "skills_list" to SkillsListRenderer,
            "skill_view" to SkillViewRenderer,
            "skill_manage" to SkillManageRenderer,
            "tool_search" to ToolSearchRenderer,
            // Media
            "image_generate" to ImageGenerateRenderer,
            "vision_analyze" to VisionAnalyzeRenderer,
            "text_to_speech" to TextToSpeechRenderer,
            // Projects
            "project_list" to ProjectListRenderer,
            "project_create" to ProjectMutateRenderer,
            "project_switch" to ProjectMutateRenderer,
            // Desktop automation
            "computer_use" to ComputerUseRenderer,
        )

    fun rendererFor(toolName: String): ToolRenderer = renderers[toolName] ?: GenericToolRenderer
}
