package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/** Reconstructs the rich chat transcript returned by the Hermes session database API. */
object SessionHistoryMapper {
    fun map(
        sessionId: String,
        messages: List<SessionMessage>,
    ): List<ChatMessage> {
        val toolCalls = buildToolCallIndex(messages)
        return messages.mapIndexedNotNull { index, message ->
            val role = message.role?.lowercase()
            val content = flattenContent(message.content)
            val reasoning =
                sequenceOf(message.reasoning, message.reasoningContent, message.reasoningDetails)
                    .map(::flattenContent)
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
            val id = "rest-$sessionId-${message.id ?: index.toLong()}"
            val timestamp = normalizeTimestamp(message.timestampText)

            when (role) {
                "user" ->
                    ChatMessage(
                        id = id,
                        role = MessageRole.USER,
                        content = content,
                        timestamp = timestamp,
                    )

                "tool" -> {
                    val metadata = message.toolCallId?.let(toolCalls::get)
                    val toolName = message.toolName?.takeIf(String::isNotBlank) ?: metadata?.name ?: "Tool"
                    ChatMessage(
                        id = id,
                        role = MessageRole.TOOL,
                        content = toolPayload(toolName, metadata?.arguments, content),
                        timestamp = timestamp,
                        toolName = toolName,
                        toolStatus = ToolStatus.COMPLETED,
                    )
                }

                "system" ->
                    ChatMessage(
                        id = id,
                        role = MessageRole.SYSTEM,
                        content = content,
                        timestamp = timestamp,
                    )

                else -> {
                    // Assistant rows carrying only tool_calls are protocol envelopes,
                    // not visible chat bubbles. Their metadata is joined onto the
                    // matching tool result above.
                    if (content.isBlank() && reasoning.isBlank()) return@mapIndexedNotNull null
                    ChatMessage(
                        id = id,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        reasoningText = reasoning,
                        timestamp = timestamp,
                        isStreaming = false,
                    )
                }
            }
        }
    }

    private data class ToolCallMetadata(
        val name: String,
        val arguments: JsonElement?,
    )

    private fun buildToolCallIndex(messages: List<SessionMessage>): Map<String, ToolCallMetadata> =
        buildMap {
            for (message in messages) {
                val calls = runCatching { message.toolCalls?.jsonArray }.getOrNull() ?: continue
                for (call in calls) {
                    val obj = runCatching { call.jsonObject }.getOrNull() ?: continue
                    val id = obj["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: continue
                    val function = runCatching { obj["function"]?.jsonObject }.getOrNull()
                    val name =
                        function?.get("name")?.jsonPrimitive?.content
                            ?: obj["name"]?.jsonPrimitive?.content
                            ?: "Tool"
                    val rawArguments = function?.get("arguments") ?: obj["arguments"]
                    put(id, ToolCallMetadata(name = name, arguments = decodeArguments(rawArguments)))
                }
            }
        }

    private fun decodeArguments(value: JsonElement?): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return value
        if (!primitive.isString) return primitive
        return runCatching { OkHttpProvider.json.parseToJsonElement(primitive.content) }.getOrElse { primitive }
    }

    /**
     * Hermes history contains both legacy string content and OpenAI-style
     * structured content parts. Flatten the human-readable values without
     * rejecting the entire transcript when one row uses the richer shape.
     */
    private fun flattenContent(value: JsonElement?): String =
        when (value) {
            null, JsonNull -> ""
            is JsonPrimitive -> value.content
            is JsonArray -> value.map(::flattenContent).filter(String::isNotBlank).joinToString("\n")
            is JsonObject -> {
                val preferred =
                    sequenceOf("text", "content", "output", "result", "value", "image_url", "url")
                        .mapNotNull(value::get)
                        .map(::flattenContent)
                        .firstOrNull(String::isNotBlank)
                preferred ?: value.toString()
            }
        }

    private fun toolPayload(
        name: String,
        arguments: JsonElement?,
        resultText: String,
    ): String {
        val result =
            runCatching { OkHttpProvider.json.parseToJsonElement(resultText) }
                .getOrElse { JsonPrimitive(resultText) }
        return buildJsonObject {
            put("name", name)
            put("args", arguments ?: JsonObject(emptyMap()))
            put("result", result)
        }.toString()
    }

    private fun normalizeTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        raw.toDoubleOrNull()?.let { numeric ->
            return if (numeric < 10_000_000_000L) (numeric * 1_000.0).toLong() else numeric.toLong()
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    }
}
