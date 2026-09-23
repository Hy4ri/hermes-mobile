package com.m57.hermescontrol.ui.chat.tool

/**
 * Per-tool (or per-family) display strategy.
 *
 * Every hook is a *refinement*: return null to fall back to the generic
 * treatment ([ToolViewBuilder] humanizes the tool name for titles and uses
 * [ToolResultSummary]-based text for subtitle/detail). Returning an empty
 * string is a real answer — "this tool intentionally shows nothing here" —
 * and suppresses the generic fallback.
 */
internal interface ToolRenderer {
    /** Title while the call is still running ("Running ls", "Searching …"). */
    fun pendingTitle(call: ToolCall): String? = null

    /** Title after successful completion ("Ran ls", "Opened example.com"). */
    fun doneTitle(call: ToolCall): String? = null

    /** Title when the call errored. */
    fun errorTitle(call: ToolCall): String? = null

    /** Secondary line naming what was acted on. Not used when an error is shown. */
    fun subtitle(call: ToolCall): String? = null

    /** Expanded body text. */
    fun detail(call: ToolCall): String? = null

    /** Expanded body text while the call is still running. */
    fun pendingDetail(call: ToolCall): String? = null

    /** Structured extras (streams, diffs, search hits) for special row layouts. */
    fun extras(
        call: ToolCall,
        status: ToolViewStatus,
    ): ToolViewExtras = ToolViewExtras.NONE
}

/**
 * Structured, tool-specific fields a renderer can contribute to [ToolView]
 * beyond the title/subtitle/detail text.
 */
internal data class ToolViewExtras(
    val detailLabel: String? = null,
    /** Process output characters omitted by the backend. */
    val outputCut: Long? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val terminalCommand: String? = null,
    val inlineDiff: String? = null,
    val diffPath: String? = null,
    val diffStats: DiffStats? = null,
    val searchHits: List<SearchHit>? = null,
    val searchQuery: String? = null,
) {
    companion object {
        val NONE = ToolViewExtras()
    }
}
