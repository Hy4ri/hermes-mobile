package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        if (a.role == MessageRole.TOOL) {
            if (a.toolCallId.isNotBlank() && b.toolCallId.isNotBlank()) return a.toolCallId == b.toolCallId
            val key = toolKey(a.content)
            return key != null && key == toolKey(b.content)
        }
        val ta = trimmed.getOrPut(a.content) { a.content.trim() }
        val tb = trimmed.getOrPut(b.content) { b.content.trim() }
        if (ta == tb) return true
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
    incoming.forEachIndexed { index, message ->
        if (matches[index] != null) return@forEachIndexed
        val candidates =
            if (message.role == MessageRole.TOOL) {
                val callMatches = byCall[message.toolCallId].orEmpty()
                callMatches + byResult[comparison.toolKey(message.content)].orEmpty()
            } else {
                // Prefix matching scans only candidates with the same role.
                byRole[message.role].orEmpty()
            }
        val match =
            candidates.firstOrNull { candidate ->
                val other = existing[candidate]
                !used[candidate] &&
                    // Distinct IDs within the same source are separate occurrences, not echoes.
                    ((message.canonicalRestId == null) != (other.canonicalRestId == null)) &&
                    comparison.same(message, other)
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
    content
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
            message.copy(restId = it.canonicalRestId, completionId = message.completionId ?: it.completionId)
        } ?: message
    }
}

/** Cache pages enrich confirmed identities without changing keys already on screen (#859). */
internal fun mergeCachedTranscriptPage(
    page: List<ChatMessage>,
    current: List<ChatMessage>,
): List<ChatMessage> {
    val currentById = current.associateBy { it.id }
    // A cached UUID has no persisted alias. Restore a known alias before page-local matching
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
                val rich = if (match.id.startsWith("rest-") && !message.id.startsWith("rest-")) message else match
                match.id to
                    rich.copy(
                        id = match.id,
                        restId = message.canonicalRestId ?: match.canonicalRestId,
                        completionId = match.completionId ?: message.completionId,
                    )
            }.toMap()
    return (
        incoming.filterIndexed { index, _ -> matches[index] == null } +
            current.map { replacements[it.id] ?: it }
    ).dedupeById().sortedBy { it.timestamp }
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
    val matches = matchTranscriptMessages(incoming, current)
    val consumed = matches.mapNotNull { it?.id }.toSet()
    val merged =
        incoming.mapIndexed { index, message ->
            val match = matches[index]
            // Keep local user metadata and stable IDs already used by the renderer.
            when {
                match?.role == MessageRole.USER -> {
                    match.copy(
                        restId = (message.canonicalRestId ?: match.canonicalRestId).takeUnless { it == match.id },
                    )
                }

                preserveLiveIds && match != null && !match.id.startsWith("rest-") -> {
                    match.copy(
                        restId = message.canonicalRestId ?: match.canonicalRestId,
                        content = message.content,
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
                    )
                }

                else -> {
                    message.copy(completionId = message.completionId ?: match?.completionId)
                }
            }
        }
    val transcript =
        if (chronological) {
            (merged + current.filterNot { it.id in consumed }).dedupeById().sortedBy { it.timestamp }
        } else {
            mergeOlderRows(merged, current, matches)
        }
    // #129: a mapped completion can confirm a cached REST row while its rich UUID
    // copy is also present. Fold that now-confirmed echo without changing the live key.
    // The page has already consumed its content matches; consuming another would
    // collapse a separate repeated occurrence. Only confirmed identities can fold here.
    return dedupeCachedMessages(transcript, confirmedOnly = true)
}

/** Update overlaps in place, inserting new rows between known server/chronological boundaries. */
private fun mergeOlderRows(
    incoming: List<ChatMessage>,
    current: List<ChatMessage>,
    matches: List<ChatMessage?>,
): List<ChatMessage> {
    val positions = current.withIndex().associate { it.value.id to it.index }
    val replacements = HashMap<String, ChatMessage>()
    val insertions = Array(current.size + 1) { mutableListOf<ChatMessage>() }
    var lowerBound = 0
    incoming.forEachIndexed { index, message ->
        val match = matches[index]
        if (match != null) {
            replacements[match.id] = message
            lowerBound = maxOf(lowerBound, positions.getValue(match.id) + 1)
        } else {
            val nextAnchor =
                matches
                    .asSequence()
                    .drop(index + 1)
                    .filterNotNull()
                    .map { positions.getValue(it.id) }
                    .firstOrNull { it >= lowerBound } ?: current.size
            var position = lowerBound
            while (position < nextAnchor && olderRowPrecedes(current[position], message)) position++
            insertions[position] += message
            lowerBound = position
        }
    }
    return buildList {
        current.forEachIndexed { index, message ->
            addAll(insertions[index])
            add(replacements[message.id] ?: message)
        }
        addAll(insertions[current.size])
    }.dedupeById()
}

private fun olderRowPrecedes(
    existing: ChatMessage,
    incoming: ChatMessage,
): Boolean {
    // Legacy absolute row positions and latest stable server IDs both order rows even
    // when the server omits timestamps. Never use a displayed list index as identity.
    val existingId = existing.canonicalRestId
    val incomingId = incoming.canonicalRestId
    if (existingId != null && incomingId != null &&
        existingId.substringBeforeLast('-') == incomingId.substringBeforeLast('-')
    ) {
        val existingRow = existingId.substringAfterLast('-').toLongOrNull()
        val incomingRow = incomingId.substringAfterLast('-').toLongOrNull()
        if (existingRow != null && incomingRow != null) return existingRow < incomingRow
    }
    return existing.timestamp < incoming.timestamp
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
