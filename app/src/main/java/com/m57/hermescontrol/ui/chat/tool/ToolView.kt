package com.m57.hermescontrol.ui.chat.tool

/** Render state of a tool call row. */
enum class ToolViewStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    WARNING,
}

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

data class DiffStats(
    val added: Int,
    val removed: Int,
)

/**
 * The full display model for one tool call, produced by [ToolViewBuilder].
 *
 * Ported shape from the desktop app's `ToolView` (fallback-model): the
 * renderer reads only these fields — no raw JSON spelunking in UI code.
 */
data class ToolView(
    val status: ToolViewStatus,
    /** One-line headline: "Read /tmp/x", "Ran sleep 10 + 2 commands". */
    val title: String,
    /** Secondary line naming what was acted on (path, hostname, query…). */
    val subtitle: String = "",
    /** Expanded body text. */
    val detail: String = "",
    /** Optional section label above [detail] (e.g. "Search results"). */
    val detailLabel: String? = null,
    /** Compact count, e.g. "3 results". */
    val countLabel: String? = null,
    /** Human duration, e.g. "1.2s". */
    val durationLabel: String? = null,
    /** Terminal tools: stdout stream when the backend split streams. */
    val stdout: String? = null,
    /** Terminal tools: stderr stream when the backend split streams. */
    val stderr: String? = null,
    /** Terminal tools: numeric exit code. */
    val exitCode: Int? = null,
    /** Terminal tools: the command that actually ran (for the `$` line). */
    val terminalCommand: String? = null,
    /** File-edit tools: the diff to render. */
    val inlineDiff: String? = null,
    /** File-edit tools: the file the diff applies to. */
    val diffPath: String? = null,
    val diffStats: DiffStats? = null,
    /** Image-producing tools: renderable URL (data: or http(s) image). */
    val imageUrl: String? = null,
    /** web_search: parsed result rows. */
    val searchHits: List<SearchHit>? = null,
    val searchQuery: String? = null,
    /** Detected error text (destructive styling in the view). */
    val error: String? = null,
)
