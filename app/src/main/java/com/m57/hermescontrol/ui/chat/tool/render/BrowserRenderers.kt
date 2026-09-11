package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import kotlinx.serialization.json.JsonArray

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

/** browser_scroll / browser_back / browser_press: one-line acks. */
internal object BrowserActionRenderer : ToolRenderer {
    override fun doneTitle(call: ToolCall): String? =
        when (call.name) {
            "browser_scroll" ->
                "Scrolled ${ToolJson.firstString(call.result, listOf("scrolled"))
                    .ifEmpty { ToolJson.firstString(call.args, listOf("direction")) }.ifEmpty { "page" }}"
            "browser_back" -> {
                val url = ToolJson.firstString(call.result, listOf("url"))
                if (url.isNotEmpty()) "Went back to ${ToolJson.hostnameOf(url)}" else "Went back"
            }
            "browser_press" ->
                "Pressed ${ToolJson.firstString(call.result, listOf("pressed"))
                    .ifEmpty { ToolJson.firstString(call.args, listOf("key")) }}"
            else -> null
        }

    override fun subtitle(call: ToolCall): String = ""

    override fun detail(call: ToolCall): String = ""
}

/** browser_get_images: image inventory of the current page. */
internal object BrowserImagesRenderer : ToolRenderer {
    override fun doneTitle(call: ToolCall): String {
        val n = ToolJson.intValue(call.result?.get("count")) ?: 0
        return "Found $n image${if (n == 1) "" else "s"}"
    }

    override fun detail(call: ToolCall): String =
        (call.result?.get("images") as? JsonArray)?.take(12)?.mapNotNull { el ->
            val img = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
            val src = ToolJson.firstString(img, listOf("src"))
            val alt = ToolJson.firstString(img, listOf("alt"))
            listOf(alt, src).filter { it.isNotEmpty() }
                .joinToString(" — ").takeIf { it.isNotEmpty() }?.let { "- $it" }
        }?.joinToString("\n") ?: ""
}

/** browser_vision: screenshot Q&A — the analysis text is the payload. */
internal object BrowserVisionRenderer : ToolRenderer {
    override fun pendingTitle(call: ToolCall): String = "Looking at page"

    override fun doneTitle(call: ToolCall): String = "Analyzed page"

    override fun subtitle(call: ToolCall): String =
        ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("question")), 120)

    override fun detail(call: ToolCall): String =
        ToolJson.firstString(call.result, listOf("analysis"))
            .ifEmpty { ToolJson.firstString(call.result, listOf("note")) }
}

/** browser_console: message/error counts, or a JS eval result. */
internal object BrowserConsoleRenderer : ToolRenderer {
    override fun doneTitle(call: ToolCall): String? {
        val result = call.result ?: return null
        if (result.containsKey("result")) return "Evaluated JS"
        val msgs = ToolJson.intValue(result["total_messages"]) ?: 0
        val errs = ToolJson.intValue(result["total_errors"]) ?: 0
        return "Console: $msgs message${if (msgs == 1) "" else "s"}" +
            if (errs > 0) ", $errs error${if (errs == 1) "" else "s"}" else ""
    }

    override fun detail(call: ToolCall): String {
        val result = call.result ?: return ""
        result["result"]?.let { return it.toString() }
        val lines =
            (result["console_messages"] as? JsonArray)?.take(30)?.mapNotNull { el ->
                val m = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                "[${ToolJson.firstString(m, listOf("type")).ifEmpty { "log" }}] " +
                    ToolJson.compactPreview(ToolJson.firstString(m, listOf("text")), 200)
            } ?: emptyList()
        val errors =
            (result["js_errors"] as? JsonArray)?.take(10)?.mapNotNull { el ->
                val e = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                "[error] ${ToolJson.compactPreview(ToolJson.firstString(e, listOf("message")), 200)}"
            } ?: emptyList()
        return (errors + lines).joinToString("\n")
    }
}
