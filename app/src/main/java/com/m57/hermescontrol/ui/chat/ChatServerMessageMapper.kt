package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.notification.ReplyNotificationTracker

/**
 * Maps REST transcript rows ([SessionMessage]) into UI [ChatMessage]s.
 *
 * Reads the supplied transcript snapshot and recovers notification identity through
 * ReplyNotificationTracker's synchronized lookup. Safe on the history dispatcher;
 * it never mutates ViewModel state or dismisses a notification.
 */
internal fun mapServerMessages(
    sessionId: String,
    messages: List<SessionMessage>,
    offset: Int,
    latestPaging: Boolean,
    liveMessages: List<ChatMessage>,
    isPagingOlder: Boolean = false,
    context: android.content.Context? = null,
): List<ChatMessage> {
    val existingById = liveMessages.associateBy { it.canonicalRestId ?: it.id }
    val existingReasoningMap =
        liveMessages
            .filter { it.reasoningText.isNotBlank() }
            .groupBy { it.content }

    val liveByExactId =
        liveMessages
            .filter { !it.completionId.isNullOrBlank() }
            .associateBy { it.canonicalRestId ?: it.id }

    val unmappedLiveWsAssistants =
        if (isPagingOlder) {
            emptyList()
        } else {
            liveMessages
                .filter {
                    it.role == MessageRole.ASSISTANT && it.canonicalRestId == null &&
                        !it.completionId.isNullOrBlank()
                }
        }

    fun restIdAt(index: Int): String =
        if (latestPaging) {
            "rest-$sessionId-${requireNotNull(messages[index].id) { "Latest transcript row has no stable id" }}"
        } else {
            "rest-$sessionId-${offset + index}"
        }

    val wsCompletionIdByRestIndex = mutableMapOf<Int, String>()
    if (!isPagingOlder) {
        // #129: reserve durable identity before matching any repeated live prose.
        // REST-only recovery remains fail-closed when the target row is absent.
        val activeTarget = ReplyNotificationTracker.getActiveTarget(context)?.takeIf { it.sessionId == sessionId }
        val durableTarget = activeTarget?.takeIf { it.serverMessageId != null }
        if (durableTarget != null) {
            val exactIndex =
                messages.indexOfFirst { message ->
                    message.id == durableTarget.serverMessageId &&
                        message.role.equals("assistant", ignoreCase = true)
                }
            if (exactIndex >= 0 && !liveByExactId.containsKey(restIdAt(exactIndex))) {
                wsCompletionIdByRestIndex[exactIndex] = durableTarget.completionId
            }
        }
        val confirmedCompletions =
            liveMessages.filter { it.canonicalRestId != null }.mapNotNull { it.completionId }.toSet()
        val remainingWs =
            unmappedLiveWsAssistants
                .filter {
                    it.completionId !in confirmedCompletions && it.completionId != durableTarget?.completionId
                }.toMutableList()
        // #129: reserve known completions and live aliases. A metadata-free cached REST
        // echo must remain eligible for its pending WS completion before older duplicates.
        val reservedRestIds = liveByExactId.keys + liveMessages.mapNotNull { it.restId }
        // Retain upstream's newest-to-newest, one-to-one WS echo matching.
        for (i in messages.indices.reversed()) {
            if (remainingWs.isEmpty()) break
            if (i in wsCompletionIdByRestIndex || restIdAt(i) in reservedRestIds) continue
            val m = messages[i]
            if (m.role?.lowercase() in listOf("user", "system", "tool")) continue
            val rawContent = m.contentText
            if (rawContent.isBlank()) continue
            val canonicalContent =
                if (rawContent.contains("MEDIA:")) HostMediaExtractor.strip(rawContent).trim() else rawContent.trim()
            val wsIdx = remainingWs.indexOfLast { it.content.trim() == canonicalContent }
            if (wsIdx >= 0) {
                remainingWs.removeAt(wsIdx).completionId?.let { wsCompletionIdByRestIndex[i] = it }
            }
        }
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
        // Issue #859: under newest-anchored paging use the server's
        // AUTOINCREMENT row id as the stable key — from-end positions shift
        // as the transcript grows and would collide across hydrations
        // (distinctBy would silently drop the newest copy). Legacy paging
        // keeps the absolute-position key its count-based sync math needs.
        val restId = restIdAt(index)
        val timestamp =
            msg.timestampText
                ?.toDoubleOrNull()
                ?.times(1000)
                ?.toLong()
                ?: existingById[restId]?.timestamp
                ?: System.currentTimeMillis()

        val rawContent = msg.contentText
        val rowReasoning =
            if (msg.reasoningText.isNotBlank()) {
                msg.reasoningText
            } else {
                existingById[restId]?.reasoningText
                    ?: existingReasoningMap[rawContent]
                        ?.firstOrNull { it.canonicalRestId == null }
                        ?.reasoningText
                        .orEmpty()
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

        val completionId =
            if (role == MessageRole.ASSISTANT) {
                liveByExactId[restId]?.completionId ?: wsCompletionIdByRestIndex[index]
            } else {
                null
            }
        val tokenCount = msg.tokenCount ?: TokenEstimator.estimate(finalContent).takeIf { it > 0 }
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
                tokenCount = tokenCount,
                completionId = completionId,
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

    // REST echoes must not reserve a match before the richer WS copy of that tool.
    val liveTools = liveMessages.filter { it.role == MessageRole.TOOL && !it.id.startsWith("rest-") }
    val mappedTools = mapped.filter { it.role == MessageRole.TOOL }
    val matches = matchTranscriptMessages(mappedTools, liveTools)
    val toolsById = mappedTools.indices.associate { index -> mappedTools[index].id to matches[index] }
    return mapped.map { message ->
        toolsById[message.id]?.copy(restId = message.canonicalRestId) ?: message
    }
}
