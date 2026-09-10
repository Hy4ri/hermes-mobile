package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The tool-display engine's orchestrator — a port of the desktop app's
 * `buildToolView` (fallback-model), extended with mobile-specific tools.
 *
 * One pure function turns a tool call's args/result into a [ToolView] the
 * renderer can paint without touching raw JSON. The pipeline:
 *
 * 1. normalize the payload into a [ToolCall] (stringified JSON re-parsed)
 * 2. resolve status via [ToolStatusResolver] (running / success / error /
 *    warning, with the desktop's rule that a non-zero exit code alone is
 *    NOT an error)
 * 3. dispatch to the tool's [ToolRenderer] (via [ToolRendererRegistry]) for
 *    title/subtitle/detail and structured extras (streams, diffs, hits)
 * 4. apply the cross-cutting polish: error/detail merging, redundancy
 *    suppression, count labels ([ToolCounts]), duration, image URLs
 *
 * Unknown tools fall back to [ToolResultSummary] heuristics so they still
 * read as a human summary instead of a raw JSON dump.
 */
object ToolViewBuilder {
    /**
     * The generic humanized title for a tool name ("fact_store" -> "Fact
     * Store"). Exposed so the view can drop the title from the collapsed
     * summary when it adds nothing over the header row's own tool name.
     */
    internal fun genericTitleFor(toolName: String?): String = ToolJson.titleForTool(toolName ?: "tool")

    fun build(
        toolName: String,
        args: JsonElement?,
        result: JsonElement?,
        isError: Boolean = false,
        running: Boolean = false,
    ): ToolView {
        val call = ToolCall.of(toolName, args, result)
        val renderer = ToolRendererRegistry.rendererFor(toolName)

        val status = ToolStatusResolver.status(call, isError, running)
        val error =
            if (status == ToolViewStatus.SUCCESS) {
                ""
            } else {
                ToolStatusResolver.errorText(call, isError)
            }

        val title =
            when {
                running -> renderer.pendingTitle(call)
                error.isNotEmpty() -> renderer.errorTitle(call)
                else -> renderer.doneTitle(call)
            } ?: ToolJson.titleForTool(toolName)

        val subtitle =
            if (error.isNotEmpty()) {
                error
            } else {
                renderer.subtitle(call) ?: genericSubtitle(call)
            }

        val detailBody =
            ToolJson.stripDividerLines(
                renderer.detail(call) ?: ToolJson.fallbackDetailText(call.rawArgs, call.rawResult),
            )
        val detail =
            if (error.isNotEmpty()) {
                listOf(error, detailBody)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("\n\n")
            } else if (looksRedundant(title, detailBody) || looksRedundant(subtitle, detailBody)) {
                // Detail that repeats the header is noise — e.g. a read_file
                // whose content equals its own title. The header carries it.
                ""
            } else {
                detailBody
            }

        val extras = renderer.extras(call, status)

        val countLabel =
            if (status == ToolViewStatus.ERROR) {
                null
            } else {
                ToolCounts.countLabelFor(call)
            }

        return ToolView(
            status = status,
            title = title,
            subtitle = subtitle,
            detail = detail,
            detailLabel = extras.detailLabel,
            countLabel = countLabel?.let { (count, noun) -> "$count ${ToolCounts.pluralizeNoun(noun, count)}" },
            durationLabel = ToolJson.durationLabel(call.result),
            stdout = extras.stdout,
            stderr = extras.stderr,
            exitCode = extras.exitCode,
            terminalCommand = extras.terminalCommand,
            inlineDiff = extras.inlineDiff?.ifEmpty { null },
            diffPath = extras.diffPath,
            diffStats = extras.diffStats,
            imageUrl = imageUrlFor(call.args, call.result),
            searchHits = extras.searchHits,
            searchQuery = extras.searchQuery,
            error = error.ifEmpty { null },
        )
    }

    /** Subtitle fallback: first line of the heuristic summary, then contexts. */
    private fun genericSubtitle(call: ToolCall): String {
        val summary = ToolResultSummary.formatToolResultSummary(call.rawResult)
        val firstLine = summary.split("\n").firstOrNull()?.takeIf { it.isNotEmpty() } ?: ""

        return ToolJson
            .compactPreview(firstLine, 120)
            .ifEmpty { ToolJson.compactPreview(call.result, 120) }
            .ifEmpty { ToolJson.compactPreview(call.args, 120) }
            .ifEmpty { ToolJson.fallbackDetailText(call.args, call.rawResult) }
    }

    private fun looksRedundant(
        title: String,
        detail: String,
    ): Boolean {
        if (detail.isEmpty()) {
            return true
        }

        val norm: (String) -> String = { input -> input.lowercase().replace(Regex("\\s+"), " ").trim() }

        return norm(title) == norm(detail)
    }

    private fun imageUrlFor(
        args: JsonObject?,
        result: JsonObject?,
    ): String? {
        val candidate =
            ToolJson
                .firstString(result, listOf("image_url", "url", "path", "image_path"))
                .ifEmpty { ToolJson.firstString(args, listOf("image_url", "url", "path")) }

        if (candidate.isEmpty()) {
            return null
        }

        val isDataImage = candidate.lowercase().startsWith("data:image/")
        val isRemoteImage =
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(candidate) &&
                Regex("\\.(png|jpe?g|gif|webp|bmp|svg)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(candidate)

        return if (isDataImage || isRemoteImage) candidate else null
    }
}
