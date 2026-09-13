package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import kotlinx.serialization.json.JsonArray

/**
 * `computer_use`: desktop-automation results — app rosters, interactable
 * element inventories, multimodal text blocks, and plain action acks.
 */
internal object ComputerUseRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val args = call.args
        val result = call.result

        val error = ToolJson.firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val apps = result?.get("apps") as? JsonArray
        if (apps != null) {
            return "${apps.size} apps"
        }

        val elements = result?.get("elements") as? JsonArray
        if (elements != null) {
            val mode = ToolJson.firstString(result, listOf("mode"))
            val width = ToolJson.intValue(result["width"])
            val height = ToolJson.intValue(result["height"])
            val modePart = mode.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            val sizePart = if (width != null && height != null) " ${width}x$height" else ""
            return "${elements.size} elements$modePart$sizePart"
        }

        val action = ToolJson.firstString(args, listOf("action"))
        if (action.isNotEmpty()) {
            val ok = ToolJson.boolTrue(result?.get("ok"))
            val fail = ToolJson.boolFalse(result?.get("ok"))
            return "$action${when {
                ok -> " ✓"
                fail -> " ✗"
                else -> ""
            }}"
        }

        return "Computer use"
    }

    override fun detail(call: ToolCall): String {
        val args = call.args
        val result = call.result

        val error = ToolJson.firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val isMultimodal = ToolJson.boolTrue(result?.get("_multimodal"))
        val content = result?.get("content") as? JsonArray
        val multimodalText =
            if (isMultimodal && content != null) {
                content
                    .mapNotNull { el ->
                        val e = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                        if (ToolJson.firstString(e, listOf("type")) == "text") {
                            ToolJson.firstString(e, listOf("text"))
                        } else {
                            null
                        }
                    }.joinToString("\n")
            } else {
                ""
            }

        val textSummary = ToolJson.firstString(result, listOf("summary"))
        val visionAnalysis = ToolJson.firstString(result, listOf("vision_analysis"))
        val apps = result?.get("apps") as? JsonArray
        val elements = result?.get("elements") as? JsonArray
        val mode = ToolJson.firstString(result, listOf("mode"))
        val width = ToolJson.intValue(result?.get("width"))
        val height = ToolJson.intValue(result?.get("height"))
        val action = ToolJson.firstString(args, listOf("action"))
        val ok = ToolJson.boolTrue(result?.get("ok"))
        val fail = ToolJson.boolFalse(result?.get("ok"))
        val message = ToolJson.firstString(result, listOf("message"))

        val body =
            buildString {
                val textPart =
                    multimodalText
                        .ifEmpty {
                            ToolJson.firstString(
                                result,
                                listOf("text_summary"),
                            )
                        }.ifEmpty { textSummary }
                if (textPart.isNotEmpty()) {
                    append(textPart)
                    if (textPart == multimodalText && textSummary.isNotEmpty() && multimodalText != textSummary) {
                        append("\n\n$textSummary")
                    }
                }

                if (apps != null) {
                    if (isNotEmpty()) append("\n\n")
                    apps.forEachIndexed { idx, el ->
                        val obj = ToolJson.parseMaybeObject(el)
                        val name = ToolJson.firstString(obj, listOf("name")).ifEmpty { "?" }
                        val pid = ToolJson.intValue(obj?.get("pid"))
                        append("${idx + 1}. $name")
                        if (pid != null) append(" (PID: $pid)")
                        append("\n")
                    }
                } else if (elements != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("🖥️ ${elements.size} interactable elements")
                    if (mode.isNotEmpty()) append(" (mode: $mode)")
                    if (width != null && height != null) append(" • ${width}x$height")
                    if (textSummary.isNotEmpty()) append("\n\n$textSummary")
                    val previewCount = minOf(elements.size, 5)
                    if (previewCount > 0) {
                        append("\n\n")
                        for (i in 0 until previewCount) {
                            val obj = ToolJson.parseMaybeObject(elements[i])
                            val idx = ToolJson.intValue(obj?.get("index"))
                            val role = ToolJson.firstString(obj, listOf("role"))
                            val label = ToolJson.firstString(obj, listOf("label"))
                            append("  #$idx ${role}${label.takeIf { it.isNotEmpty() }?.let { " '$it'" } ?: ""}\n")
                        }
                        if (elements.size > previewCount) {
                            append("  ... and ${elements.size - previewCount} more")
                        }
                    }
                } else if (action.isNotEmpty() && multimodalText.isEmpty() &&
                    ToolJson
                        .firstString(
                            result,
                            listOf("text_summary"),
                        ).isEmpty() && textSummary.isEmpty()
                ) {
                    append("$action:")
                    when {
                        ok -> append(" ✅ ok")
                        fail -> append(" ❌ failed")
                    }
                    if (message.isNotEmpty()) append("\n$message")
                } else if (isEmpty()) {
                    append("Computer use action")
                }
            }

        return if (visionAnalysis.isNotEmpty()) "$body\n\n━━━ Vision Analysis ━━━\n$visionAnalysis" else body
    }
}
