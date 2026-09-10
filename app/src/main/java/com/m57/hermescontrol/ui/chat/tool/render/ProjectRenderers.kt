package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** `project_list`: project roster with the active project starred. */
internal object ProjectListRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val projects = (call.result?.get("projects") as? JsonArray)?.size
        val active = ToolJson.firstString(call.result, listOf("active_id"))

        return if (projects != null) {
            "$projects projects${active.takeIf { it.isNotEmpty() }?.let { " (1 active)" } ?: ""}"
        } else {
            "Projects"
        }
    }

    override fun detail(call: ToolCall): String =
        (call.result?.get("projects") as? JsonArray)
            ?.mapNotNull { el ->
                val p = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val name = ToolJson.firstString(p, listOf("name"))
                val slug = ToolJson.firstString(p, listOf("slug"))
                val path = ToolJson.firstString(p, listOf("primary_path"))
                val isActive =
                    (p["active"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
                val lines = mutableListOf("${if (isActive) "⭐ " else "📁 "}$name")
                if (slug.isNotEmpty()) lines += "     🏷️ $slug"
                if (path.isNotEmpty()) lines += "     📍 $path"
                if (isActive) lines += "     ✅ ACTIVE PROJECT"
                lines.joinToString("\n")
            }?.joinToString("\n\n")
            ?: ""
}

/** `project_create` / `project_switch`: mutation ack with name/slug/path. */
internal object ProjectMutateRenderer : ToolRenderer {
    private fun actionLabel(call: ToolCall): String = if (call.name == "project_create") "Created" else "Switched to"

    override fun subtitle(call: ToolCall): String {
        val name = ToolJson.firstString(call.result, listOf("name"))
        val label = actionLabel(call)

        return if (name.isNotEmpty()) "$label $name" else label
    }

    override fun detail(call: ToolCall): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val name = ToolJson.firstString(call.result, listOf("name"))
        val slug = ToolJson.firstString(call.result, listOf("slug"))
        val path = ToolJson.firstString(call.result, listOf("primary_path"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
            } else {
                append("✅ ${actionLabel(call)}")
                if (name.isNotEmpty()) append(": $name")
                if (slug.isNotEmpty()) append("\n   🏷️ $slug")
                if (path.isNotEmpty()) append("\n   📍 $path")
            }
        }
    }
}
