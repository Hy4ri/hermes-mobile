package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer

/** `image_generate`: generated-image URL with the prompt in the detail. */
internal object ImageGenerateRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val imageUrl =
            ToolJson
                .firstString(call.result, listOf("image"))
                .ifEmpty { ToolJson.firstString(call.args, listOf("image_url")) }
        val modality = ToolJson.firstString(call.result, listOf("modality"))

        return listOf(imageUrl.take(60), modality.takeIf { it.isNotEmpty() }?.let { "($it)" })
            .filterNotNull()
            .joinToString(" ")
    }

    override fun detail(call: ToolCall): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val imageUrl = ToolJson.firstString(call.result, listOf("image"))
        val prompt = ToolJson.firstString(call.args, listOf("prompt"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
                if (imageUrl.isNotEmpty()) append("\n🔗 $imageUrl")
            } else if (imageUrl.isNotEmpty()) {
                append("🖼️ ")
                if (prompt.isNotEmpty()) append("$prompt\n")
                append("\n🔗 $imageUrl")
            } else {
                append("✅ Generated")
            }
        }
    }
}

/** `vision_analyze`: the analyzed image URL and the model's description. */
internal object VisionAnalyzeRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String = ToolJson.firstString(call.args, listOf("image_url")).take(60)

    override fun detail(call: ToolCall): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val description = ToolJson.firstString(call.result, listOf("description"))
        val content = ToolJson.firstString(call.result, listOf("content"))

        return when {
            error.isNotEmpty() -> "❌ $error"
            description.isNotEmpty() -> description
            content.isNotEmpty() -> content
            else -> "No description available"
        }
    }
}

/** `text_to_speech`: audio synthesis. Success payload carries file_path(s), provider, chunk_count. */
internal object TextToSpeechRenderer : ToolRenderer {
    override fun pendingTitle(call: ToolCall): String = "Generating speech"

    override fun doneTitle(call: ToolCall): String = "Generated speech"

    override fun subtitle(call: ToolCall): String {
        val provider = ToolJson.firstString(call.result, listOf("provider"))
        val chunks = ToolJson.intValue(call.result?.get("chunk_count"))
        val parts =
            listOfNotNull(
                provider.takeIf { it.isNotEmpty() },
                chunks?.takeIf { it > 1 }?.let { "$it chunks" },
            )
        return parts.joinToString(" · ")
            .ifEmpty { ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("text")), 120) }
    }

    override fun detail(call: ToolCall): String {
        val text = ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("text")), 400)
        val path = ToolJson.firstString(call.result, listOf("file_path", "output_path"))
        return listOfNotNull(
            text.takeIf { it.isNotEmpty() }?.let { "“$it”" },
            path.takeIf { it.isNotEmpty() }?.let { "Saved to $it" },
        ).joinToString("\n\n")
    }
}
