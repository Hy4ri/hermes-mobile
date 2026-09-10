package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer

/** `browser_navigate`: hostname titles, including the failed-to-open form. */
internal object BrowserNavigateRenderer : ToolRenderer {
    override fun pendingTitle(call: ToolCall): String = "Opening ${targetHostname(call.args, null)}"

    override fun doneTitle(call: ToolCall): String = "Opened ${targetHostname(call.args, call.result)}"

    override fun errorTitle(call: ToolCall): String? {
        val url =
            ToolJson
                .findFirstUrl(call.result)
                .ifEmpty { call.result?.get("url")?.toString() ?: "" }

        return if (url.isNotEmpty()) "Failed to open ${ToolJson.hostnameOf(url)}" else null
    }

    override fun subtitle(call: ToolCall): String {
        val url =
            ToolJson
                .firstString(call.args, listOf("url", "target"))
                .ifEmpty { ToolJson.firstString(call.result, listOf("url")) }
                .ifEmpty { ToolJson.findFirstUrl(call.args, call.result) }

        return if (url.isNotEmpty()) ToolJson.hostnameOf(url) else "Navigated in browser"
    }
}

/** `browser_snapshot`: control-count summary of the accessibility snapshot. */
internal object BrowserSnapshotRenderer : ToolRenderer {
    private fun summarizeSnapshot(snapshot: String): String {
        fun count(re: Regex): Int = re.findAll(snapshot).count()

        val stats =
            listOf(
                "${count(Regex("""button\s+"[^"]+"""))} buttons",
                "${count(Regex("""link\s+"[^"]+"""))} links",
                "${count(Regex("""(?:textbox|combobox|searchbox)\s+"[^"]+"""))} inputs",
            ).joinToString(" · ")

        val labels =
            Regex("""(?:button|link|combobox|textbox)\s+"([^"]+)"""")
                .findAll(snapshot)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .take(4)
                .toList()

        return if (labels.isNotEmpty()) "$stats\nTop controls: ${labels.joinToString(", ")}" else stats
    }

    override fun subtitle(call: ToolCall): String {
        val snapshot = ToolJson.firstString(call.result, listOf("snapshot"))

        return if (snapshot.isNotEmpty()) {
            summarizeSnapshot(snapshot)
        } else {
            "Captured a browser accessibility snapshot"
        }
    }

    override fun detail(call: ToolCall): String {
        val snapshot = ToolJson.firstString(call.result, listOf("snapshot"))

        return if (snapshot.isNotEmpty()) {
            summarizeSnapshot(snapshot)
        } else {
            ToolJson.fallbackDetailText(call.rawArgs, call.rawResult)
        }
    }
}

/** `browser_click`: what got clicked, with internal refs called out. */
internal object BrowserClickRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val clicked =
            ToolJson
                .firstString(call.result, listOf("clicked"))
                .ifEmpty { ToolJson.firstString(call.args, listOf("ref", "target")) }

        return when {
            clicked.isEmpty() -> "Clicked on page"
            clicked.startsWith("@") -> "Clicked page element (internal ref $clicked)"
            else -> "Clicked $clicked"
        }
    }
}

/** `browser_fill` / `browser_type`: which field got which value. */
internal object BrowserTypeRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val field = ToolJson.firstString(call.args, listOf("label", "field", "ref", "target"))
        val value = ToolJson.firstString(call.args, listOf("value", "text"))

        return listOf(
            field.takeIf { it.isNotEmpty() }?.let { "Field: $it" },
            value.takeIf { it.isNotEmpty() }?.let { "Value: ${ToolJson.compactPreview(it, 42)}" },
        ).filterNotNull()
            .joinToString(" · ")
            .ifEmpty { "Filled page input" }
    }
}
