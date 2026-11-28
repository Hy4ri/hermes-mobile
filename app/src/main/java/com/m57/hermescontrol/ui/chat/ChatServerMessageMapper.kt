package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.notification.ReplyNotificationTarget
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps REST transcript rows ([SessionMessage]) into UI [ChatMessage]s.
 *
 * Pure: every input is plain data. Callers resolve the active reply-notification
 * target and the gateway media URL builder, so this never touches Android,
 * AuthManager, or the notification tracker (#1337).
 *
 * @param activeReplyTarget the reply notification currently posted, if any; its
 *   durable server row id reserves that row's completion identity.
 * @param mediaUrl builds an authenticated gateway URL for a `MEDIA:` path, or null
 *   when no gateway is configured.
 * @param nowMs fallback timestamp for rows without one.
 */
internal fun mapServerMessages(
    sessionId: String,
    messages: List<SessionMessage>,
    offset: Int,
    latestPaging: Boolean,
    liveMessages: List<ChatMessage>,
    isPagingOlder: Boolean = false,
    stableRowIds: Boolean = latestPaging,
    activeReplyTarget: ReplyNotificationTarget? = null,
    mediaUrl: (path: String) -> String? = { null },
    nowMs: Long = System.currentTimeMillis(),
): List<ChatMessage> {
    val existingById = liveMessages.associateBy { it.canonicalRestId ?: it.id }
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
        if (stableRowIds) {
            RestMessageId.of(sessionId, requireNotNull(messages[index].id) { "Latest transcript row has no stable id" })
        } else {
            RestMessageId.of(sessionId, offset + index)
        }

    val wsCompletionIdByRestIndex = mutableMapOf<Int, String>()
    val verifierContentByRestIndex = mutableMapOf<Int, String>()
    if (!isPagingOlder) {
        // #129: reserve durable identity before matching any repeated live prose.
        // REST-only recovery remains fail-closed when the target row is absent.
        val activeTarget = activeReplyTarget?.takeIf { it.sessionId == sessionId }
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
            val rawContent = m.displayContentText ?: m.contentText
            if (rawContent.isBlank()) continue
            val canonicalContent =
                if (rawContent.contains("MEDIA:")) HostMediaExtractor.strip(rawContent).trim() else rawContent.trim()
            val wsIdx = remainingWs.indexOfLast { it.content.trim() == canonicalContent }
            if (wsIdx >= 0) {
                remainingWs.removeAt(wsIdx).completionId?.let { wsCompletionIdByRestIndex[i] = it }
            }
        }
        // Fallback: match live assistant containing a file-mutation verifier footer against
        // its footer-free REST counterpart (issue #1241).
        for (i in messages.indices.reversed()) {
            if (remainingWs.isEmpty()) break
            if (i in wsCompletionIdByRestIndex || restIdAt(i) in reservedRestIds) continue
            val m = messages[i]
            if (m.role?.lowercase() in listOf("user", "system", "tool")) continue
            val rawContent = m.displayContentText ?: m.contentText
            if (rawContent.isBlank()) continue
            val canonicalContent =
                if (rawContent.contains("MEDIA:")) HostMediaExtractor.strip(rawContent).trim() else rawContent.trim()
            val wsIdx = remainingWs.indexOfLast { ChatVerifierFooter.matchesBase(it.content, canonicalContent) }
            if (wsIdx >= 0) {
                val matchedWs = remainingWs.removeAt(wsIdx)
                matchedWs.completionId?.let { wsCompletionIdByRestIndex[i] = it }
                // Propagate the richer live footer content to the REST row so canonical mapping keeps it
                if (ChatVerifierFooter.split(matchedWs.content) != null) {
                    verifierContentByRestIndex[i] = matchedWs.content
                }
            }
        }
    }

    // Reasoning follows the same reserved identities as completions, never a reusable text lookup.
    val assistants = liveMessages.filter { it.role == MessageRole.ASSISTANT }
    val assistantsByRestId = assistants.groupBy { it.canonicalRestId }
    val assistantsByCompletion = assistants.groupBy { it.completionId }
    val reasoningSources = arrayOfNulls<ChatMessage>(messages.size)
    messages.forEachIndexed { index, row ->
        if (row.role?.lowercase() in listOf("user", "system", "tool")) return@forEachIndexed
        val exact = assistantsByRestId[restIdAt(index)].orEmpty()
        val completion = liveByExactId[restIdAt(index)]?.completionId ?: wsCompletionIdByRestIndex[index]
        val candidates = exact + completion?.let { assistantsByCompletion[it] }.orEmpty()
        reasoningSources[index] = candidates.firstOrNull { it.reasoningText.isNotBlank() } ?: candidates.firstOrNull()
    }
    if (!isPagingOlder) {
        val remaining =
            assistants.filter { it.canonicalRestId == null && it.completionId == null }.toMutableList()
        for (index in messages.indices.reversed()) {
            val row = messages[index]
            val displayContent = row.displayContentText ?: row.contentText
            if (reasoningSources[index] != null || displayContent.isBlank() ||
                row.role?.lowercase() in listOf("user", "system", "tool") ||
                liveByExactId[restIdAt(index)]?.completionId != null || index in wsCompletionIdByRestIndex
            ) {
                continue
            }
            val content = HostMediaExtractor.strip(displayContent).trim()
            val match = remaining.indexOfLast { it.content.trim() == content }
            if (match >= 0) reasoningSources[index] = remaining.removeAt(match)
        }
        for (index in messages.indices.reversed()) {
            val row = messages[index]
            val displayContent = row.displayContentText ?: row.contentText
            if (reasoningSources[index] != null || displayContent.isBlank() ||
                row.role?.lowercase() in listOf("user", "system", "tool") ||
                liveByExactId[restIdAt(index)]?.completionId != null || index in wsCompletionIdByRestIndex
            ) {
                continue
            }
            val content = HostMediaExtractor.strip(displayContent).trim()
            val match = remaining.indexOfLast { ChatVerifierFooter.matchesBase(it.content, content) }
            if (match >= 0) reasoningSources[index] = remaining.removeAt(match)
        }
    }

    val mapped = mutableListOf<ChatMessage>()
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
                ?: nowMs

        val rawContent =
            if (role == MessageRole.TOOL && msg.display_metadata != null) {
                // Enrich tool content with display_metadata so persisted edit previews
                // (inline_diff) survive reload into the diff renderer
                val parsedContent = ToolJson.parseMaybeObject(msg.content)
                if (parsedContent != null && !parsedContent.containsKey("display_metadata")) {
                    JsonObject(parsedContent + ("display_metadata" to msg.display_metadata)).toString()
                } else {
                    msg.visibleText
                }
            } else {
                msg.visibleText
            }
        // #1284: projected reasoning already excludes public commentary; it is authoritative.
        val rowReasoning =
            if (msg.hasDisplayReasoning) {
                msg.displayReasoningText
            } else {
                msg.reasoningText.ifBlank {
                    if (role == MessageRole.ASSISTANT) {
                        reasoningSources[index]?.reasoningText.orEmpty()
                    } else {
                        existingById[restId]?.reasoningText.orEmpty()
                    }
                }
            }

        // Retain canonical reasoning rows across page boundaries; hide only textless placeholders.
        if (role == MessageRole.ASSISTANT && rawContent.isBlank() && rowReasoning.isBlank()) return@forEachIndexed

        // Context-reference preprocessing can persist model-facing attachment
        // contents after the user's text. A cold reload / second device has no
        // optimistic local bubble to hide that enriched REST representation,
        // so project the producer-owned suffix out at hydration time too.
        // Keep the actual @file:/@image: token here: unlike the dedupe path,
        // REST-only hydration cannot prove that reference was not user-authored.
        var finalContent =
            if (role == MessageRole.USER) {
                stripGatewayAttachedContext(stripGatewaySteerWrapper(rawContent))
            } else if (role == MessageRole.ASSISTANT && verifierContentByRestIndex.containsKey(index)) {
                verifierContentByRestIndex[index] ?: rawContent
            } else {
                rawContent
            }
        var attachments: List<Attachment>? = null
        var persistedContent: String? = null
        if (role == MessageRole.USER) {
            attachments = userImageAttachments(finalContent, mediaUrl).takeIf { it.isNotEmpty() }
        } else if (role == MessageRole.ASSISTANT && rawContent.contains("MEDIA:")) {
            val items = HostMediaExtractor.extract(rawContent)
            if (items.isNotEmpty()) {
                finalContent = HostMediaExtractor.strip(rawContent)
                // #1696: keep the raw directive for Room so cache loads re-derive.
                persistedContent = rawContent
                attachments = hostMediaAttachments(rawContent, mediaUrl).takeIf { it.isNotEmpty() }
            }
        }

        val completionId =
            if (role == MessageRole.ASSISTANT) {
                liveByExactId[restId]?.completionId ?: wsCompletionIdByRestIndex[index]
            } else {
                null
            }
        val pluginCard = msg.pluginCardMeta()
        val shownContent =
            if (pluginCard != null) {
                msg.pluginCardDisplayText()?.takeIf { it.isNotBlank() } ?: msg.contentText
            } else {
                finalContent
            }
        val tokenCount = msg.tokenCount ?: TokenEstimator.estimate(shownContent).takeIf { it > 0 }
        mapped.add(
            ChatMessage(
                id = if (pluginCard != null) pluginCardMessageId(pluginCard.pluginId, pluginCard.cardId) else restId,
                role = role,
                content = shownContent,
                reasoningText = rowReasoning,
                toolCallId = msg.toolCallId,
                attachments = attachments,
                timestamp = timestamp,
                isStreaming = false,
                // A plugin card must not carry displayKind: FullBleedChatList draws those as chips.
                displayKind = if (pluginCard != null) null else msg.display_kind,
                tokenCount = tokenCount,
                completionId = completionId,
                restId = pluginCard?.let { restId },
                serverRowId = msg.id?.toLong()?.takeIf { it > 0L },
                reactions = msg.reactions,
                persistedContent = persistedContent,
                pluginCard = pluginCard,
            ).normalizedGatewayNotice(),
        )
    }

    mapped.dropContinuedStopNotices()

    // REST echoes must not reserve a match before the richer WS copy of that tool.
    val liveTools = liveMessages.filter { it.role == MessageRole.TOOL && !RestMessageId.isRest(it.id) }
    val mappedTools = mapped.filter { it.role == MessageRole.TOOL }
    val matches = matchTranscriptMessages(mappedTools, liveTools)
    val toolsById = mappedTools.indices.associate { index -> mappedTools[index].id to matches[index] }
    return mapped.map { message ->
        val local = toolsById[message.id]
        if (local?.isHistoricalCache == true && local.toolStatus == ToolStatus.RUNNING) {
            // A canonical tool-result row settles a cached tool.start, not the reverse.
            local.copy(
                restId = message.canonicalRestId,
                serverRowId = message.serverRowId,
                content = message.content,
                toolStatus = ToolStatus.COMPLETED,
                isHistoricalCache = false,
            )
        } else {
            local?.copy(restId = message.canonicalRestId, serverRowId = message.serverRowId) ?: message
        }
    }
}

/**
 * A stop notice says the reply ended there. When a hidden prompt follows and the assistant goes on (a host plugin
 * paused the turn to apply a sandbox grant and continued it), it did not: the notice comes off. A visible row
 * next (the user writing) keeps it, so a real Stop still reads as stopped after a reload.
 */
internal fun MutableList<ChatMessage>.dropContinuedStopNotices() {
    var i = 0
    while (i < size) {
        val row = this[i]
        val isStopNotice = row.role == MessageRole.SYSTEM && GatewayNotice.isInterruptSentinel(row.content)
        if (isStopNotice) {
            var next = i + 1
            var sawHidden = false
            while (next < size && this[next].role == MessageRole.USER && this[next].displayKind == "hidden") {
                sawHidden = true
                next++
            }
            val goesOn = sawHidden && next < size && this[next].role in setOf(MessageRole.ASSISTANT, MessageRole.TOOL)
            if (goesOn) {
                removeAt(i)
                continue
            }
        }
        i++
    }
}

/** `display_metadata.plugin_card` when the row is a durable plugin card. Malformed cards are ignored. */
internal fun SessionMessage.pluginCardMeta(): PluginCardMeta? {
    val meta = display_metadata as? JsonObject ?: return null
    val kind = (meta["display_kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (kind != DisplayKind.PLUGIN_CARD) return null
    val card = meta["plugin_card"] as? JsonObject ?: return null
    val pluginId =
        (card["plugin_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            .orEmpty()
    val cardId =
        (card["card_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            .orEmpty()
    if (pluginId.isEmpty() || cardId.isEmpty()) return null
    val directive = card["directive"] as? JsonObject
    val directiveName =
        (directive?.get("name") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val directiveAttrs =
        (directive?.get("attrs") as? JsonObject)
            ?.mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                key to text
            }?.toMap()
            .orEmpty()
    return PluginCardMeta(pluginId, cardId, directiveName, directiveAttrs)
}

/** Human-readable card body. Null when the gateway omitted `display_text`. */
internal fun SessionMessage.pluginCardDisplayText(): String? {
    val meta = display_metadata as? JsonObject ?: return null
    return (meta["display_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
