package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `skills_list`: count-with-category subtitle and a name/description list. */
internal object SkillsListRenderer : ToolRenderer {
    private fun skills(call: ToolCall): JsonArray? =
        (call.result?.get("skills") as? JsonArray)
            ?: (call.result?.get("results") as? JsonArray)

    override fun subtitle(call: ToolCall): String {
        val category = ToolJson.firstString(call.args, listOf("category"))
        val count = skills(call)?.size ?: 0

        return if (count > 0) {
            "$count skills${category.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}"
        } else {
            "No skills found"
        }
    }

    override fun detail(call: ToolCall): String =
        skills(call)
            ?.mapNotNull { el ->
                val s = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val name = ToolJson.firstString(s, listOf("name"))
                val desc = ToolJson.firstString(s, listOf("description"))
                val cat = ToolJson.firstString(s, listOf("category"))
                val lines = mutableListOf("📌 $name")
                if (desc.isNotEmpty()) lines += "     $desc"
                if (cat.isNotEmpty()) lines += "     [$cat]"
                lines.joinToString("\n")
            }?.joinToString("\n\n")
            ?: "No skills found"
}

/** `skill_view`: content preview capped at 500 chars plus linked files. */
internal object SkillViewRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String = ToolJson.firstString(call.args, listOf("name"))

    override fun detail(call: ToolCall): String {
        val content = ToolJson.firstString(call.result, listOf("content"))
        val linkedFiles = call.result?.get("linked_files") as? JsonObject
        val contentPreview =
            if (content.length > 500) {
                "${content.take(
                    500,
                )}\n... [${content.length - 500} more chars]"
            } else {
                content
            }
        val filesInfo =
            linkedFiles
                ?.entries
                ?.joinToString("\n") { (k, v) ->
                    val paths =
                        when (v) {
                            is JsonArray -> {
                                v.joinToString(
                                    ", ",
                                ) { (it as? JsonPrimitive)?.content ?: it.toString() }
                            }

                            is JsonPrimitive -> {
                                v.content
                            }

                            else -> {
                                v.toString()
                            }
                        }
                    "     📎 $k: $paths"
                }

        return buildString {
            if (contentPreview.isNotEmpty()) append(contentPreview)
            if (filesInfo != null) {
                if (contentPreview.isNotEmpty()) append("\n\n")
                append("━━━ Linked Files ━━━\n$filesInfo")
            }
            if (content.isEmpty() && linkedFiles == null) append("No content available")
        }
    }
}

/** `skill_manage`: action ack with the skill name. */
internal object SkillManageRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val action = ToolJson.firstString(call.args, listOf("action"))
        val name = ToolJson.firstString(call.args, listOf("name"))

        return if (name.isNotEmpty()) "Skill $action: $name" else "Skill $action"
    }

    override fun detail(call: ToolCall): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val msg = ToolJson.firstString(call.result, listOf("message"))
        val failed = ToolJson.boolFalse(call.result?.get("success"))

        // Pinned quirk from the original engine: the branch order is inverted
        // (a non-false `success` renders the failure line). Preserved verbatim
        // by the characterization suite; fix upstream deliberately if desired.
        return when {
            !failed -> "❌ Skill operation failed"
            msg.isNotEmpty() -> "✅ $msg"
            else -> "✅ Skill operation done"
        }
    }
}

/** `tool_search`: on-demand tool discovery matches. */
internal object ToolSearchRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val query = ToolJson.firstString(call.args, listOf("query"))
        val matches = (call.result?.get("matches") as? JsonArray)?.size

        return if (matches != null) "$query ($matches matches)" else query
    }

    override fun detail(call: ToolCall): String =
        (call.result?.get("matches") as? JsonArray)
            ?.mapNotNull { el ->
                val m = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val name = ToolJson.firstString(m, listOf("name"))
                val desc = ToolJson.firstString(m, listOf("description"))
                val lines = mutableListOf("🔧 $name")
                if (desc.isNotEmpty()) lines += "     $desc"
                lines.joinToString("\n")
            }?.joinToString("\n\n")
            ?: "No matching tools"
}
