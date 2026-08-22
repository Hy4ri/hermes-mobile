package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.GatewayFileClient

/**
 * Maps REST transcript rows ([SessionMessage]) into UI [ChatMessage]s.
 *
 * Verbatim extraction of ChatViewModel.mapServerMessages (corral 2 of the
 * 2026-08 delegate refactor). Pure with respect to the VM: it reads the live
 * message list supplied by the caller and one paging-mode flag — no state
 * mutation, no I/O beyond URL construction.
 */
internal fun mapServerMessages(
    sessionId: String,
    messages: List<SessionMessage>,
    offset: Int,
    latestPaging: Boolean,
    liveMessages: List<ChatMessage>,
): List<ChatMessage> {
    val existingReasoningMap =
        liveMessages
            .filter { it.reasoningText.isNotBlank() }
            .associateBy { it.content }

    // Tool rows in the REST transcript carry NO tool name — the live WS
    // stream was the only source of `toolName`. Match each REST tool row
    // to its WS counterpart by RESULT CONTENT (not position — pagination
    // and mixed cache state make positional mapping misalign, leaving
    // the newest call with a null name → generic "tool" bubble). When a
    // match is found the live message is reused wholesale (same id +
    // toolName + rich payload), so persistence upserts the same row
    // instead of accumulating a second `rest-` copy in Room. Issue #771.
    val liveToolByResult = linkedMapOf<String, ChatMessage>()
    liveMessages
        .filter { it.role == MessageRole.TOOL }
        .sortedBy { it.id.startsWith("rest-") } // prefer live WS copies
        .forEach { msg ->
            canonicalToolResultKey(msg.content)?.let { key ->
                liveToolByResult.putIfAbsent(key, msg)
            }
        }

    // Issue #842: REST transcript rows carry the gateway's `tool_call_id`
    // — prefer matching live bubbles by that 1:1 identity. It works for
    // EVERY tool shape, including MCP/web rows whose REST copy is raw
    // `<untrusted_tool_result>` text with no JSON key to canonicalize.
    val liveToolByCallId = linkedMapOf<String, ChatMessage>()
    liveMessages
        .filter { it.role == MessageRole.TOOL && it.toolCallId.isNotBlank() }
        .sortedBy { it.id.startsWith("rest-") } // prefer live WS copies
        .forEach { msg ->
            liveToolByCallId.putIfAbsent(msg.toolCallId, msg)
        }

    val mapped = mutableListOf<ChatMessage>()
    // The gateway stores a reasoning-model's thinking as its OWN assistant
    // row (content = "", reasoning = trace) directly before the answer row.
    // Rendering that as a standalone empty assistant bubble is the
    // "reasoning box in a separate bubble" artifact — fold it into the
    // next assistant message with content instead. Issue #771.
    var pendingReasoning: String? = null

    messages.forEachIndexed { index, msg ->
        val role =
            when (msg.role?.lowercase()) {
                "user" -> MessageRole.USER
                "system" -> MessageRole.SYSTEM
                "tool" -> MessageRole.TOOL
                else -> MessageRole.ASSISTANT
            }
        val globalIndex = offset + index
        // Issue #859: under newest-anchored paging use the server's
        // AUTOINCREMENT row id as the stable key — from-end positions shift
        // as the transcript grows and would collide across hydrations
        // (distinctBy would silently drop the newest copy). Legacy paging
        // keeps the absolute-position key its count-based sync math needs.
        val restId =
            if (latestPaging) {
                msg.id?.let { "rest-$sessionId-$it" } ?: "rest-$sessionId-$globalIndex"
            } else {
                "rest-$sessionId-$globalIndex"
            }
        val timestamp =
            msg.timestampText
                ?.toDoubleOrNull()
                ?.times(1000)
                ?.toLong()
                ?: System.currentTimeMillis()

        val rawContent = msg.contentText
        val rowReasoning =
            if (msg.reasoningText.isNotBlank()) {
                msg.reasoningText
            } else {
                existingReasoningMap[rawContent]?.reasoningText.orEmpty()
            }

        // Empty assistant row — two cases stored by the gateway:
        //  1. Reasoning-only: thinking-model split storage (content = "",
        //     reasoning = trace). Stash the trace and fold it into the
        //     next assistant message that has content (issue #771).
        //  2. Tool-call placeholder: non-reasoning models emit content = ""
        //     with tool_calls metadata and no reasoning. These carry no
        //     user-visible text and must not render as empty bubbles
        //     (issue #956).
        if (role == MessageRole.ASSISTANT && rawContent.isBlank()) {
            if (rowReasoning.isNotBlank()) {
                pendingReasoning = rowReasoning
            }
            return@forEachIndexed
        }

        var finalContent = rawContent
        var attachments: List<Attachment>? = null
        if (role == MessageRole.ASSISTANT && rawContent.contains("MEDIA:")) {
            val items = HostMediaExtractor.extract(rawContent)
            if (items.isNotEmpty()) {
                val baseUrl = AuthManager.getBaseUrl()
                val token = AuthManager.getToken().orEmpty()
                finalContent = HostMediaExtractor.strip(rawContent)
                attachments =
                    items
                        .mapNotNull { item ->
                            val url =
                                GatewayFileClient.buildMediaUrl(
                                    baseUrl,
                                    token,
                                    item.path,
                                ) ?: return@mapNotNull null
                            Attachment(
                                uri = url,
                                name = mediaNameFromPath(item.path),
                                mimeType = mediaMimeForPath(item.path),
                                size = 0,
                                gatewayUrl = url,
                                source = AttachmentSource.GATEWAY,
                            )
                        }.takeIf { it.isNotEmpty() }
            }
        }

        val finalReasoning =
            if (rowReasoning.isNotBlank()) {
                rowReasoning
            } else if (role == MessageRole.ASSISTANT && pendingReasoning != null) {
                pendingReasoning.also { pendingReasoning = null }
            } else {
                ""
            }

        // Tool rows in the REST transcript carry no tool name. When the
        // result payload matches a live WS tool message, reuse it whole —
        // keeps the real name, the rich WS payload, AND the same id so
        // Room upserts instead of accumulating a duplicate `rest-` row.
        if (role == MessageRole.TOOL) {
            // Prefer the gateway call id (1:1, works for every tool
            // shape), then fall back to result-content matching.
            liveToolByCallId[msg.toolCallId]?.let { live ->
                mapped.add(live)
                return@forEachIndexed
            }
            canonicalToolResultKey(rawContent)?.let { key ->
                liveToolByResult[key]?.let { live ->
                    mapped.add(live)
                    return@forEachIndexed
                }
            }
        }

        mapped.add(
            ChatMessage(
                id = restId,
                role = role,
                content = finalContent,
                reasoningText = finalReasoning,
                toolCallId = msg.toolCallId,
                attachments = attachments,
                timestamp = timestamp,
                isStreaming = false,
                displayKind = msg.display_kind,
            ),
        )
    }

    // A reasoning-only row with no following answer (interrupted turn):
    // don't drop the trace — attach it to the last assistant message.
    if (pendingReasoning != null) {
        val lastAssistantIdx = mapped.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx >= 0) {
            val target = mapped[lastAssistantIdx]
            if (target.reasoningText.isBlank()) {
                mapped[lastAssistantIdx] = target.copy(reasoningText = pendingReasoning)
            }
        }
    }

    return mapped
}
