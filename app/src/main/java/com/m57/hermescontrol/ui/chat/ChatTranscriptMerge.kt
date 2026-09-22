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
): Boolean {
    if (a.role != b.role) return false
    if (a.role == MessageRole.TOOL) {
        // Issue #842: prefer the gateway's tool call id when both sides carry
        // it — the REST transcript stores `tool_call_id` and the live WS
        // bubble keeps it from `tool.start`. Content canonicalization cannot
        // cover MCP/web tools: the REST side stores the payload as raw
        // `<untrusted_tool_result>` text (not JSON), so it has no key at all.
        if (a.toolCallId.isNotBlank() && b.toolCallId.isNotBlank()) {
            return a.toolCallId == b.toolCallId
        }
        val ka = canonicalToolResultKey(a.content)
        val kb = canonicalToolResultKey(b.content)
        return ka != null && ka == kb
    }
    val ta = a.content.trim()
    val tb = b.content.trim()
    if (ta == tb) return true
    // Attachment dedupe: when the user sends an image/file, the backend
    // persists an ENRICHED copy of the prompt — the real caption plus
    // `@image:`/`@file:` reference lines and a `[screenshot]` marker
    // (tui_gateway _build_image_ref_message / run_agent flattening). The
    // optimistic bubble carries the raw caption only, so the exact compare
    // above fails and the REST sync renders the same message twice. Compare
    // USER rows on the caption with injection lines stripped (both sides —
    // stripping a clean string is a no-op). USER-only so assistant text that
    // legitimately mentions these tokens is never collapsed.
    if (a.role == MessageRole.USER) {
        val ca = stripAttachmentRefLines(ta)
        val cb = stripAttachmentRefLines(tb)
        if (ca == cb) return true
    }
    // Issue #842: a seal race can leave the live orphan a few trailing tokens
    // short of the streamed narration (the last delta was still in the
    // throttled buffer when tool.start sealed the message). The backend
    // persists the COMPLETE copy, so the orphan is a strict prefix of the
    // REST row. Accept prefix-covering only for substantial texts (>=40
    // chars) so a short reply can never be swallowed by a longer message
    // that merely starts with it.
    return ta.length >= 40 &&
        tb.length >= 40 &&
        (tb.startsWith(ta) || ta.startsWith(tb))
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
        // The gateway may persist a second, enriched representation of the
        // prompt with an `--- Attached Context ---` block. That block is
        // model-facing context, not user-authored text, so it must not prevent
        // the optimistic user bubble from matching the REST copy.
        .substringBefore("\n--- Attached Context ---")
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
internal fun dedupeCachedMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val rest = messages.filter { it.id.startsWith("rest-") }
    if (rest.isEmpty()) return messages
    val nonRest = messages.filterNot { it.id.startsWith("rest-") }
    if (nonRest.isEmpty()) return messages
    val keepRest = rest.filter { restMsg -> nonRest.none { sameLogicalMessage(it, restMsg) } }
    return (nonRest + keepRest).sortedBy { it.timestamp }
}

/**
 * A transcript reload must NOT yank live WS bubbles the server has not
 * persisted yet. The gateway stores a tool row only once the tool
 * COMPLETES server-side, so a reload that lands while a tool is running
 * (app background/foreground mid-turn, reconnect re-resume, pull-refresh)
 * returns a page without the tool row — replacing the list outright made
 * the in-flight tool bubble vanish and left tool.complete with no RUNNING
 * message to update (issue #771).
 *
 * Merge instead of replace: append any current message the REST page does
 * not already cover (checked by id AND logical content via
 * [sameLogicalMessage]) and keep chronological order.
 */
internal fun mergeTranscriptWithLive(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
): List<ChatMessage> {
    val dedupedRest = restMessages.dedupeById()
    val dedupedCurrent = currentMessages.dedupeById()
    // User rows can carry live/cache-only metadata (for example whether the
    // bubble continues the active turn). Keep that richer copy when REST
    // returns the same logical row.
    val currentUsers = dedupedCurrent.filter { it.role == MessageRole.USER }.toMutableList()
    val mergedRest =
        dedupedRest.map { rest ->
            if (rest.role != MessageRole.USER) return@map rest
            val matchIndex =
                currentUsers.indexOfFirst { it.id == rest.id }.takeIf { it >= 0 }
                    ?: currentUsers.indexOfFirst { sameLogicalMessage(rest, it) }
            if (matchIndex >= 0) currentUsers.removeAt(matchIndex) else rest
        }
    val restIds = dedupedRest.map { it.id }.toSet()
    val liveTail =
        dedupedCurrent.filter { old ->
            old.id !in restIds && mergedRest.none { sameLogicalMessage(it, old) }
        }
    if (liveTail.isEmpty()) return mergedRest.dedupeById()
    return (mergedRest + liveTail).dedupeById().sortedBy { it.timestamp }
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
            serverMessageIndex(message.id, sessionId)?.let { it < offset } == true
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
