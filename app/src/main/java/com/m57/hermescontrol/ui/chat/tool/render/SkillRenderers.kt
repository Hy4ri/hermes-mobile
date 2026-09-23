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
        val succeeded = ToolJson.boolTrue(call.result?.get("success"))

        return when {
            failed -> "❌ ${msg.ifEmpty { "Skill operation failed" }}"
            succeeded -> "✅ ${msg.ifEmpty { "Skill operation done" }}"
            else -> "❌ ${msg.ifEmpty { "Skill operation failed" }}"
        }
    }
}

/** `tool_search`: on-demand tool discovery matches. */
internal object ToolSearchRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val query =
            ToolJson
                .firstString(call.args, listOf("query"))
                .ifEmpty { (call.args?.get("queries") as? JsonArray)?.joinToString(", ") { text(it) } ?: "" }
        val matches =
            (call.result?.get("results") as? JsonArray)?.sumOf { row ->
                (ToolJson.parseMaybeObject(row)?.get("matches") as? JsonArray)?.size ?: 0
            } ?: (call.result?.get("matches") as? JsonArray)?.size

        return if (matches != null) "$query ($matches ${if (matches == 1) "match" else "matches"})" else query
    }

    override fun detail(call: ToolCall): String {
        val tools = call.result?.get("tools") as? JsonObject
        val matches =
            (call.result?.get("results") as? JsonArray)
                ?.flatMap { row -> (ToolJson.parseMaybeObject(row)?.get("matches") as? JsonArray).orEmpty() }
                ?: (call.result?.get("matches") as? JsonArray).orEmpty()
        return matches
            .mapNotNull { match ->
                val item = ToolJson.parseMaybeObject(match)
                val name = item?.let { ToolJson.firstString(it, listOf("name")) } ?: text(match)
                if (name.isEmpty()) return@mapNotNull null
                val metadata = tools?.get(name) as? JsonObject
                val desc =
                    ToolJson
                        .firstString(metadata, listOf("description"))
                        .ifEmpty { ToolJson.firstString(item, listOf("description")) }
                val source = ToolJson.firstString(metadata, listOf("source_name"))
                val required = (metadata?.get("required") as? JsonArray)?.joinToString(", ") { text(it) }.orEmpty()
                buildString {
                    append("🔧 $name")
                    if (source.isNotEmpty()) append(" · $source")
                    if (desc.isNotEmpty()) append("\n     $desc")
                    if (required.isNotEmpty()) append("\n     Required: $required")
                }
            }.distinct()
            .joinToString("\n\n")
            .ifEmpty { "No matching tools" }
    }
}

/** `tool_describe`: readable schema summaries instead of a collapsed JSON object. */
internal object ToolDescribeRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val names = (call.args?.get("names") as? JsonArray)?.joinToString(", ") { text(it) }.orEmpty()
        val count = (call.result?.get("tools") as? JsonObject)?.size
        return if (count != null) "$names ($count ${if (count == 1) "tool" else "tools"})" else names
    }

    override fun detail(call: ToolCall): String =
        (call.result?.get("tools") as? JsonObject)
            ?.entries
            ?.joinToString("\n\n") { (name, value) ->
                val tool = ToolJson.parseMaybeObject(value)
                val desc = ToolJson.firstString(tool, listOf("description"))
                val schema = tool?.get("parameters") as? JsonObject
                val properties = schema?.get("properties") as? JsonObject
                val required = (schema?.get("required") as? JsonArray)?.map(::text).orEmpty().toSet()
                buildString {
                    append("🔧 $name")
                    if (desc.isNotEmpty()) append("\n     $desc")
                    if (!properties.isNullOrEmpty()) {
                        append("\n     Parameters: ")
                        append(
                            properties.entries.joinToString("; ") { (paramName, value) ->
                                val param = ToolJson.parseMaybeObject(value)
                                val type = ToolJson.firstString(param, listOf("type"))
                                val help = ToolJson.firstString(param, listOf("description"))
                                val default = param?.get("default")
                                buildString {
                                    append(paramName)
                                    if (type.isNotEmpty()) append(" ($type)")
                                    if (paramName in required) append(" *")
                                    if (help.isNotEmpty()) append(" — $help")
                                    if (default != null) append(" [default: ${text(default)}]")
                                }
                            },
                        )
                    } else if (required.isNotEmpty()) {
                        append("\n     Required: ${required.joinToString(", ")}")
                    }
                }
            }?.ifEmpty { "No tool descriptions returned" } ?: "No tool descriptions returned"
}

private fun text(value: kotlinx.serialization.json.JsonElement): String =
    (value as? JsonPrimitive)?.content ?: value.toString()
