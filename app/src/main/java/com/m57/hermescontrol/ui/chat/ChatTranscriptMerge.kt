package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val ATTACHED_CONTEXT_MARKER_RE =
    Regex("""(?:^|\n)--- Attached Context ---\s*\n""")

private val CONTEXT_REF_RE =
    Regex(
        """@(file|folder|url|image|tool|terminal):(?:"[^"\n]+"|'[^'\n]+'|`[^`\n]+`|\S+)""",
    )

private val OUT_OF_BAND_START_RE =
    Regex("""\[OUT-OF-BAND USER MESSAGE[^\n\]]*\]\s*""", RegexOption.IGNORE_CASE)
private val OUT_OF_BAND_END_RE =
    Regex("""\s*\[/OUT-OF-BAND USER MESSAGE\]""", RegexOption.IGNORE_CASE)

/**
 * Remove a gateway-generated `[OUT-OF-BAND USER MESSAGE ...]` wrapper from
 * mid-turn steering messages while preserving the user-authored prompt.
 */
internal fun stripGatewaySteerWrapper(content: String): String {
    val trimmed = content.trimStart()
    val startMatch = OUT_OF_BAND_START_RE.find(trimmed) ?: return content
    if (startMatch.range.first != 0) return content
    val afterStart = trimmed.substring(startMatch.range.last + 1)
    val endMatch = OUT_OF_BAND_END_RE.find(afterStart)
    val unwrapped =
        if (endMatch != null) {
            afterStart.substring(0, endMatch.range.first)
        } else {
            afterStart
        }
    return unwrapped.trim()
}

/**
 * Remove a gateway-generated `--- Attached Context ---` suffix while
 * preserving the user-authored portion of the message.
 *
 * The marker itself is plain text a user can legitimately type, so do not
 * treat it as a producer boundary unless the suffix also contains a context
 * reference of the shape emitted by the gateway.
 *
 * Unlike [stripAttachmentRefLines], this intentionally keeps `@file:` /
 * `@image:` references in the visible text. REST-only hydration has no local
 * optimistic bubble proving those references were injected by Mobile, and
 * users may have authored context references themselves.
 */
internal fun stripGatewayAttachedContext(content: String): String {
    val marker = ATTACHED_CONTEXT_MARKER_RE.find(content) ?: return content
    val attachedContext = content.substring(marker.range.last + 1)

    if (!CONTEXT_REF_RE.containsMatchIn(attachedContext)) {
        return content
    }

    return content.substring(0, marker.range.first).trimEnd()
}

/**
 * Canonical comparison key for a tool message's result payload.
 *
 * WS tool messages store the full tool.complete payload
 * (`{"tool_id":..., "name":..., "args":..., "result": {...}}`) while REST
 * transcript rows store just the result object (`{"output":..., "exit_code":...}`).
 * This key normalizes both sides — preferring the `result` field when present,
 * and treating int/float JSON numbers as equal — so the two representations of
 * the SAME tool call can be matched regardless of position or pagination.
 * Returns null for unparseable content (no match possible).
 */
internal fun canonicalToolResultKey(content: String): String? {
    val element =
        try {
            OkHttpProvider.json.parseToJsonElement(content)
        } catch (_: Exception) {
            return null
        }

    fun canon(e: JsonElement): String =
        when (e) {
            is JsonObject -> {
                e.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${canon(it.value)}" }
            }

            is JsonArray -> {
                e.joinToString(",") { canon(it) }
            }

            is JsonPrimitive -> {
                // Canonicalize ALL numbers through double, collapsing int/float
                // spellings of the same value (0 vs 0.0 → "i0", 0.5 → "d0.5").
                val s = e.content
                val d = s.toDoubleOrNull()
                if (d != null) {
                    if (d == d.toLong().toDouble()) "i${d.toLong()}" else "d$d"
                } else {
                    "s$s"
                }
            }
        }
    return when (element) {
        is JsonObject -> {
            element["result"]?.let { canon(it) } ?: canon(element)
        }

        else -> {
            canon(element)
        }
    }
}

/**
 * True when [a] and [b] are the same logical message (the WS-persisted and
 * REST-persisted copies of one row — they carry different ids, see #771).
 * Tool messages match on their normalized result payload; other roles on
 * exact content, IGNORING leading/trailing whitespace.
 *
 * Issue #842: the app seals the RAW streamed text (which can carry leading
 * blank lines the model emits before its narration), while the backend
 * persists a CLEANED copy (leading whitespace stripped). An exact-content
 * compare made the reload merge treat them as different messages and add
 * the REST copy on top — the commentary duplicated ~10s after the stream
 * ended. Trim closes the drift: the sealed live bubble is covered by the
 * REST row and the duplicate never renders.
 */
internal fun sameLogicalMessage(
    a: ChatMessage,
    b: ChatMessage,
): Boolean = TranscriptComparison().same(a, b)

/** One operation owns this cache; even invalid JSON is parsed at most once per content. */
internal class TranscriptComparison(
    private val canonicalize: (String) -> String? = ::canonicalToolResultKey,
) {
    private val toolKeys = HashMap<String, String?>()
    private val trimmed = HashMap<String, String>()
    private val captions = HashMap<String, String>()

    fun toolKey(content: String): String? {
        if (!toolKeys.containsKey(content)) toolKeys[content] = canonicalize(content)
        return toolKeys[content]
    }

    fun same(
        a: ChatMessage,
        b: ChatMessage,
    ): Boolean {
        if (a.role != b.role) return false
        val aRestId = a.canonicalRestId
        val bRestId = b.canonicalRestId
        if (aRestId != null && bRestId != null) return aRestId == bRestId
        // #129: completion-bearing replies need confirmed identity, not repeated prose.
        // Older pages deliberately do not acquire a live reply's completion in the mapper.
        if (a.role == MessageRole.ASSISTANT && (a.completionId != null || b.completionId != null)) {
            return a.completionId != null && a.completionId == b.completionId
        }
        if (a.role == MessageRole.ASSISTANT && a.content.isBlank() && b.content.isBlank() &&
            (a.reasoningText.isNotBlank() || b.reasoningText.isNotBlank())
        ) {
            return a.reasoningText.isNotBlank() && a.reasoningText == b.reasoningText
        }
        if (a.role == MessageRole.TOOL) {
            if (a.toolCallId.isNotBlank() && b.toolCallId.isNotBlank()) return a.toolCallId == b.toolCallId
            val key = toolKey(a.content)
            return key != null && key == toolKey(b.content)
        }
        // Rows generated only by Mobile never have a canonical REST counterpart.
        // Do not let coincidentally identical server text claim their identity.
        if (a.role == MessageRole.ASSISTANT &&
            (a.displayKind == "local_feedback" || b.displayKind == "local_feedback")
        ) {
            return false
        }
        if (a.role == MessageRole.USER &&
            (a.displayKind == "clarify_response" || b.displayKind == "clarify_response")
        ) {
            return false
        }
        val ta = trimmed.getOrPut(a.content) { a.content.trim() }
        val tb = trimmed.getOrPut(b.content) { b.content.trim() }
        if (a.role == MessageRole.USER && (ta.startsWith("/") || tb.startsWith("/"))) return false
        if (ta == tb) return true
        if (a.role == MessageRole.ASSISTANT &&
            (a.completionId != null || b.completionId != null) &&
            a.completionId == b.completionId &&
            ta.isNotBlank() && tb.isNotBlank() &&
            ChatVerifierFooter.matchesBase(ta, tb)
        ) {
            return true
        }
        if (a.role == MessageRole.USER &&
            captions.getOrPut(ta) { stripAttachmentRefLines(ta) } ==
            captions.getOrPut(tb) { stripAttachmentRefLines(tb) }
        ) {
            return true
        }
        return ta.length >= 40 && tb.length >= 40 && (tb.startsWith(ta) || ta.startsWith(tb))
    }
}

/** Consume each occurrence once, reserving exact IDs before considering content echoes. */
internal fun matchTranscriptMessages(
    incoming: List<ChatMessage>,
    existing: List<ChatMessage>,
    comparison: TranscriptComparison = TranscriptComparison(),
    allowAssistantContentMatches: Boolean = true,
): List<ChatMessage?> {
    val byId = existing.withIndex().associate { it.value.id to it.index }
    val byRestId =
        existing.withIndex().mapNotNull { (index, message) -> message.canonicalRestId?.let { it to index } }.toMap()
    val used = BooleanArray(existing.size)
    val matches = arrayOfNulls<ChatMessage>(incoming.size)
    incoming.forEachIndexed { index, message ->
        (byId[message.id] ?: message.canonicalRestId?.let { byRestId[it] })?.let { match ->
            if (!used[match]) {
                used[match] = true
                matches[index] = existing[match]
            }
        }
    }
    val byRole = existing.indices.groupBy { existing[it].role }
    val byCall =
        existing.indices
            .filter { existing[it].toolCallId.isNotBlank() }
            .groupBy { existing[it].toolCallId }
    val byResult =
        existing.indices
            .filter { existing[it].role == MessageRole.TOOL }
            .groupBy { comparison.toolKey(existing[it].content) }
    val matchOrder =
        incoming.indices.filter { incoming[it].role != MessageRole.ASSISTANT } +
            incoming.indices.reversed().filter { incoming[it].role == MessageRole.ASSISTANT }
    matchOrder.forEach { index ->
        val message = incoming[index]
        if (matches[index] != null) return@forEach
        val candidates =
            if (message.role == MessageRole.TOOL) {
                val callMatches = byCall[message.toolCallId].orEmpty()
                callMatches + byResult[comparison.toolKey(message.content)].orEmpty()
            } else {
                // Prefix matching scans only candidates with the same role.
                byRole[message.role].orEmpty().let {
                    if (message.role == MessageRole.ASSISTANT) it.asReversed() else it
                }
            }
        val match =
            candidates.firstOrNull { candidate ->
                val other = existing[candidate]
                !used[candidate] &&
                    (
                        allowAssistantContentMatches || message.role != MessageRole.ASSISTANT ||
                            message.completionId != null || other.completionId != null
                    ) &&
                    // Distinct IDs within the same source are separate occurrences, not echoes.
                    ((message.canonicalRestId == null) != (other.canonicalRestId == null)) &&
                    (
                        comparison.same(message, other) ||
                            (
                                allowAssistantContentMatches &&
                                    message.role == MessageRole.ASSISTANT &&
                                    message.content.isNotBlank() && other.content.isNotBlank() &&
                                    message.completionId != null &&
                                    message.completionId == other.completionId &&
                                    ChatVerifierFooter.matchesBase(message.content, other.content)
                            )
                    )
            }
        if (match != null) {
            used[match] = true
            matches[index] = existing[match]
        }
    }
    return matches.toList()
}

/**
 * Remove backend attachment-injection lines from a user message so the
 * optimistic bubble and the server-enriched REST copy compare equal.
 * Lines the gateway adds on top of the user's caption: `@image:<path>`,
 * `@file:<ref>`, and `[screenshot]` (multipart image placeholder written by
 * run_agent). Blank lines left behind are dropped too.
 */
internal fun stripAttachmentRefLines(content: String): String =
    stripGatewayAttachedContext(stripGatewaySteerWrapper(content))
        .lines()
        .map { it.trim() }
        .filterNot { line ->
            line.startsWith("@image:") ||
                line.startsWith("@file:") ||
                line == "[screenshot]"
        }.joinToString("\n")
        .trim()

/**
 * Room accumulates BOTH the WS-persisted copy (UUID id, rich tool payload,
 * tool name) and the REST-persisted copy (`rest-` id, result-only payload,
 * no tool name) of every message. Painting the cache verbatim renders the
 * same call twice. Drop the `rest-` copy whenever a WS copy of the same
 * logical message exists (issue #771).
 */
internal fun dedupeCachedMessages(
    messages: List<ChatMessage>,
    confirmedOnly: Boolean = false,
): List<ChatMessage> {
    val unique = messages.dedupeById()
    val rest = unique.filter { it.id.startsWith("rest-") }
    val live = unique.filterNot { it.id.startsWith("rest-") }
    if (rest.isEmpty() || live.isEmpty()) return unique
    val matches =
        matchTranscriptMessages(rest, live).mapIndexed { index, match ->
            match?.takeIf {
                !confirmedOnly || rest[index].canonicalRestId == it.canonicalRestId ||
                    (rest[index].completionId != null && rest[index].completionId == it.completionId)
            }
        }
    val echoes =
        rest.indices
            .filter { matches[it] != null }
            .map { rest[it].id }
            .toSet()
    // #859: retain the REST identity when keeping a rich UUID row, including across cache pages.
    val aliases =
        rest.indices
            .mapNotNull { index ->
                matches[index]?.id?.let { it to rest[index] }
            }.toMap()
    return unique.filterNot { it.id in echoes }.map { message ->
        aliases[message.id]?.let {
            message.copy(
                restId = it.canonicalRestId,
                completionId = message.completionId ?: it.completionId,
                displayKind = message.displayKind ?: it.displayKind,
                isRestoredUnconfirmed = false,
            )
        } ?: message
    }
}

/** Cache pages enrich confirmed identities without changing keys already on screen (#859). */
internal fun mergeCachedTranscriptPage(
    page: List<ChatMessage>,
    current: List<ChatMessage>,
): List<ChatMessage> {
    val currentById = current.associateBy { it.id }
    // Legacy cached UUIDs may lack an alias. Restore a known alias before page-local matching
    // so an older identical REST row cannot claim that UUID again.
    val incoming =
        dedupeCachedMessages(
            page.map { message ->
                currentById[message.id]?.restId?.let { message.copy(restId = it) } ?: message
            },
        )
    val matches = matchTranscriptMessages(incoming, current)
    val replacements =
        incoming
            .mapIndexedNotNull { index, message ->
                val match = matches[index] ?: return@mapIndexedNotNull null
                val preservedContent =
                    if (match.role == MessageRole.ASSISTANT &&
                        ChatVerifierFooter.split(match.content) != null &&
                        ChatVerifierFooter.matchesBase(match.content, message.content)
                    ) {
                        match.content
                    } else if (message.role == MessageRole.ASSISTANT &&
                        ChatVerifierFooter.split(message.content) != null &&
                        ChatVerifierFooter.matchesBase(message.content, match.content)
                    ) {
                        message.content
                    } else {
                        null
                    }
                val rich =
                    when {
                        // A late historical snapshot may contribute safe metadata, but it must
                        // never replace a canonical server payload/status already on screen.
                        message.isHistoricalCache && match.canonicalRestId != null -> {
                            match.copy(
                                toolName = match.toolName ?: message.toolName,
                                toolCallId = match.toolCallId.ifBlank { message.toolCallId },
                                attachments = match.attachments ?: message.attachments,
                                finishTimestamp = match.finishTimestamp ?: message.finishTimestamp,
                                tokenCount = match.tokenCount ?: message.tokenCount,
                                tps = match.tps ?: message.tps,
                            )
                        }

                        match.id.startsWith("rest-") && !message.id.startsWith("rest-") -> {
                            message
                        }

                        else -> {
                            match
                        }
                    }
                match.id to
                    rich.copy(
                        id = match.id,
                        content = preservedContent ?: rich.content,
                        restId = match.canonicalRestId ?: message.canonicalRestId,
                        completionId = match.completionId ?: message.completionId,
                        displayKind = match.displayKind ?: message.displayKind,
                        isRestoredUnconfirmed =
                            match.isRestoredUnconfirmed && message.isRestoredUnconfirmed &&
                                match.canonicalRestId == null && message.canonicalRestId == null,
                    )
            }.toMap()
    val resolvedOrders =
        incoming.indices
            .mapNotNull { index ->
                matches[index]?.id?.let { id -> incoming[index].canonicalOrder?.let { id to it } }
            }.toMap()
    return (
        current.map { replacements[it.id] ?: it } +
            incoming.filterIndexed { index, _ -> matches[index] == null }
    ).dedupeById().inTranscriptOrder(incoming + current, resolvedOrders).reconcileReasoningRows()
}

/** Merge one page with the current snapshot without consuming repeated results more than once. */
internal fun mergeTranscriptWithLive(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
    chronological: Boolean = true,
    preserveLiveIds: Boolean = false,
): List<ChatMessage> {
    val incoming = restMessages.dedupeById()
    val current = currentMessages.dedupeById()
    val matches = matchTranscriptMessages(incoming, current, allowAssistantContentMatches = chronological)
    val consumed = matches.mapNotNull { it?.id }.toSet()
    val merged =
        incoming.mapIndexed { index, message ->
            val match = matches[index]
            // Keep local user metadata and stable IDs already used by the renderer.
            when {
                match?.role == MessageRole.USER -> {
                    match.copy(
                        restId = (message.canonicalRestId ?: match.canonicalRestId).takeUnless { it == match.id },
                        displayKind = message.displayKind ?: match.displayKind,
                        isHistoricalCache = false,
                        isRestoredUnconfirmed = false,
                    )
                }

                preserveLiveIds && match != null && !match.id.startsWith("rest-") -> {
                    val mergedContent =
                        if (match.role == MessageRole.ASSISTANT &&
                            ChatVerifierFooter.split(match.content) != null &&
                            ChatVerifierFooter.matchesBase(match.content, message.content)
                        ) {
                            match.content
                        } else {
                            message.content
                        }
                    match.copy(
                        restId = message.canonicalRestId ?: match.canonicalRestId,
                        content = mergedContent,
                        timestamp = message.timestamp,
                        isStreaming = message.isStreaming,
                        reasoningText = message.reasoningText.ifBlank { match.reasoningText },
                        attachments = message.attachments ?: match.attachments,
                        toolName = message.toolName ?: match.toolName,
                        toolCallId = message.toolCallId.ifBlank { match.toolCallId },
                        toolStatus = message.toolStatus ?: match.toolStatus,
                        displayKind = message.displayKind ?: match.displayKind,
                        tokenCount = message.tokenCount ?: match.tokenCount,
                        completionId = message.completionId ?: match.completionId,
                        isHistoricalCache = false,
                        isRestoredUnconfirmed = false,
                    )
                }

                else -> {
                    val mergedContent =
                        if (message.role == MessageRole.ASSISTANT &&
                            match != null &&
                            ChatVerifierFooter.split(match.content) != null &&
                            ChatVerifierFooter.matchesBase(match.content, message.content)
                        ) {
                            match.content
                        } else {
                            message.content
                        }
                    message.copy(
                        content = mergedContent,
                        completionId = message.completionId ?: match?.completionId,
                        isHistoricalCache = false,
                        isRestoredUnconfirmed = false,
                    )
                }
            }
        }
    val transcript =
        if (chronological) {
            current.filterNot { it.id in consumed } + merged
        } else {
            merged + current.filterNot { it.id in consumed }
        }
    // #129: a mapped completion can confirm a cached REST row while its rich UUID
    // copy is also present. Fold that now-confirmed echo without changing the live key.
    // The page has already consumed its content matches; consuming another would
    // collapse a separate repeated occurrence. Only confirmed identities can fold here.
    val resolvedOrders =
        incoming.indices
            .mapNotNull { index ->
                matches[index]?.id?.let { id -> incoming[index].canonicalOrder?.let { id to it } }
            }.toMap()
    return dedupeCachedMessages(transcript.inTranscriptOrder(current, resolvedOrders), confirmedOnly = true)
        .reconcileReasoningRows()
}

/** Keep server order; place local notices and commands after their last preceding confirmed message, not at the transcript tail. */
private fun List<ChatMessage>.inTranscriptOrder(
    previous: List<ChatMessage>,
    resolvedOrders: Map<String, Long>,
): List<ChatMessage> {
    val latestCanonical = mapNotNull { it.canonicalOrder }.maxOrNull() ?: -1L
    // Session-start markers use -1 and must remain before unresolved cached history.
    val beforeCanonical = (mapNotNull { it.canonicalOrder }.filter { it >= 0L }.minOrNull() ?: 0L) - 1L
    var precedingCanonical: Long? = null
    var hasPendingPredecessor = false
    var pendingLocalOrder: Long? = null
    val localAnchors = mutableMapOf<String, Long>()
    val pendingOrderByLocal = mutableMapOf<String, Long>()
    previous.forEach { message ->
        val order = resolvedOrders[message.id] ?: message.canonicalOrder
        if (order != null) {
            precedingCanonical = order
            hasPendingPredecessor = false
            pendingLocalOrder = null
        } else if (message.isPermanentlyLocal()) {
            localAnchors[message.id] =
                if (hasPendingPredecessor) {
                    Long.MAX_VALUE
                } else {
                    precedingCanonical?.takeIf { it >= 0L }
                        ?: latestCanonical
                }
            if (hasPendingPredecessor) pendingOrderByLocal[message.id] = pendingLocalOrder ?: Long.MAX_VALUE
        } else if (message.isHistoricalCache || message.isRestoredUnconfirmed) {
            // Room groups UUID-only rows after all confirmed rows; that predecessor is
            // not a chronological anchor. Restored legacy rows stay before the server
            // window too, without promoting their uncertain delivery to confirmed history.
            localAnchors[message.id] = beforeCanonical
        } else {
            hasPendingPredecessor = true
            pendingLocalOrder = message.localOrder
        }
    }
    val previousIndices = previous.withIndex().associate { it.value.id to it.index }
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<ChatMessage>> {
                it.value.canonicalOrder ?: localAnchors[it.value.id] ?: Long.MAX_VALUE
            }.thenBy { if (localAnchors[it.value.id]?.let { anchor -> anchor != Long.MAX_VALUE } == true) 1 else 0 }
                .thenBy { it.value.localOrder ?: pendingOrderByLocal[it.value.id] ?: Long.MAX_VALUE }
                .thenBy { previousIndices[it.value.id] ?: it.index },
        ).map { it.value }
}

internal fun ChatMessage.isPermanentlyLocal(): Boolean =
    role == MessageRole.SYSTEM ||
        (role == MessageRole.USER && (content.startsWith("/") || displayKind == "clarify_response")) ||
        (role == MessageRole.ASSISTANT && displayKind == "local_feedback")

internal fun ChatMessage.isSessionStartMarker(): Boolean =
    role == MessageRole.SYSTEM && (content == "Session created" || content == "Session branched")

private val ChatMessage.canonicalOrder: Long?
    get() =
        when {
            isSessionStartMarker() -> -1L
            localOrder != null && restId == null -> null
            else -> canonicalRestId?.substringAfterLast('-')?.toLongOrNull()
        }

/** Retain the canonical trace once, without copying it across a user/system/tool boundary. */
private fun List<ChatMessage>.reconcileReasoningRows(): List<ChatMessage> =
    mapIndexed { index, message ->
        val previous = getOrNull(index - 1)
        if (previous?.role == MessageRole.ASSISTANT && previous.content.isBlank() &&
            previous.canonicalRestId != null && previous.reasoningText.isNotBlank() &&
            message.role == MessageRole.ASSISTANT && message.content.isNotBlank() &&
            message.canonicalRestId != null && message.reasoningText == previous.reasoningText
        ) {
            message.copy(reasoningText = "")
        } else {
            message
        }
    }

/** Merge a REST page without matching it against already-settled transcript rows. */
internal fun mergeIncrementalTranscriptPage(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
    sessionId: String,
    offset: Int,
): List<ChatMessage> {
    val settledEnd =
        currentMessages.indexOfLast { message ->
            serverMessageIndex(message.canonicalRestId ?: message.id, sessionId)?.let { it < offset } == true
        }
    return (
        currentMessages.take(settledEnd + 1) +
            mergeTranscriptWithLive(restMessages, currentMessages.drop(settledEnd + 1))
    ).dedupeById()
}

internal fun serverMessageIndex(
    id: String,
    sessionId: String,
): Int? = id.removePrefix("rest-$sessionId-").takeIf { it != id }?.toIntOrNull()

internal fun List<ChatMessage>.dedupeById(): List<ChatMessage> = associateBy { it.id }.values.toList()
