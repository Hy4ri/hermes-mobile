package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole

/**
 * Turn model for the full-bleed chat renderer (issue #866).
 *
 * A turn is a unit of conversation for spacing + header purposes:
 * - [ChatTurn.User]: one user message — always its own turn (bubble anchor).
 * - [ChatTurn.Agent]: everything between user messages — assistant prose,
 *   tool rows, and system events, in original order.
 */
sealed interface ChatTurn {
    /** User message — always its own turn (bubble anchor). */
    data class User(val message: ChatMessage) : ChatTurn

    /** One agent turn: prose, tool rows, and system events in order. */
    data class Agent(val entries: List<AgentEntry>) : ChatTurn
}

sealed interface AgentEntry {
    data class Prose(val message: ChatMessage) : AgentEntry

    data class ToolRow(val message: ChatMessage) : AgentEntry

    data class SystemEvent(val message: ChatMessage) : AgentEntry
}

/**
 * Stable content prefix the backend uses for its max-iterations runtime nudge
 * (`handle_max_iterations` in run_agent.py → chat_completion_helpers.py, text
 * sourced from `agent.context_compressor.MAX_ITERATIONS_SUMMARY_REQUEST`). The
 * backend persists it as a plain `role="user"` row with NO `display_kind`
 * (SessionDB projection strips underscore metadata flags), so on the mobile
 * side it would otherwise render as a fake user bubble. Treat it as a system
 * event so it gets the distinct system design instead.
 */
private const val MAX_ITERATIONS_SYSTEM_MARKER =
    "You've reached the maximum number of tool-calling iterations allowed."

private fun ChatMessage.isSyntheticSystemRow(): Boolean =
    role == MessageRole.USER &&
        displayKind == null &&
        content.startsWith(MAX_ITERATIONS_SYSTEM_MARKER)

/**
 * Split a flat message list into turns for the full-bleed renderer.
 *
 * Each USER message closes the current agent turn (if any) and opens a User
 * turn; all non-user messages belong to the surrounding agent turn. A new
 * agent turn starts after each user turn or at the start of the list.
 */
fun groupIntoTurns(messages: List<ChatMessage>): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    val agentEntries = mutableListOf<AgentEntry>()

    fun flushAgent() {
        if (agentEntries.isNotEmpty()) {
            turns += ChatTurn.Agent(agentEntries.toList())
            agentEntries.clear()
        }
    }

    messages.forEach { message ->
        when {
            // Timeline markers (display_kind) ride as role=user rows but are
            // NOT user turns — group them as system-style timeline entries so
            // they render as centered chips, not fake user bubbles (issue #904).
            // The backend's max-iterations nudge is a role=user row with NO
            // display_kind (it's stripped on persistence); detect it by its
            // stable content prefix and route it as a system event too, tagging
            // it so the timeline chip can name it.
            message.displayKind != null ->
                agentEntries += AgentEntry.SystemEvent(message)

            message.isSyntheticSystemRow() ->
                agentEntries +=
                    AgentEntry.SystemEvent(message.copy(displayKind = "max_iterations_reached"))

            message.role == MessageRole.USER -> {
                flushAgent()
                turns += ChatTurn.User(message)
            }

            MessageRole.ASSISTANT == message.role -> agentEntries += AgentEntry.Prose(message)
            MessageRole.TOOL == message.role -> agentEntries += AgentEntry.ToolRow(message)
            MessageRole.SYSTEM == message.role -> agentEntries += AgentEntry.SystemEvent(message)
        }
    }
    flushAgent()
    return turns
}

/**
 * Like [groupIntoTurns] but folds the in-flight streaming assistant message
 * into the current agent turn, so it renders as part of the turn (reasoning
 * hoist, turn headers, spacing) instead of as a detached tail item.
 *
 * Defensive: if the streaming message's id is already present in [messages]
 * (commit race — the message landed while the UI still held the streaming
 * copy), it is not appended again; a duplicate prose entry would produce a
 * LazyColumn duplicate-key crash.
 */
fun groupIntoTurnsWithStreaming(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
): List<ChatTurn> {
    if (streamingMessage == null || messages.any { it.id == streamingMessage.id }) {
        return groupIntoTurns(messages)
    }
    val turns = groupIntoTurns(messages).toMutableList()
    val prose = AgentEntry.Prose(streamingMessage)
    val last = turns.lastOrNull()
    if (last is ChatTurn.Agent) {
        turns[turns.lastIndex] = ChatTurn.Agent(last.entries + prose)
    } else {
        turns += ChatTurn.Agent(listOf(prose))
    }
    return turns
}

/**
 * Map every visible chat message id to its LazyColumn item index in the
 * full-bleed renderer.
 *
 * The lazy list does NOT have one item per message: each agent turn with a
 * reasoning block emits an extra `reasoning-<id>` item BEFORE its prose, and
 * tool rows / system events are items too. Scrolling a search match by raw
 * message index therefore lands on the WRONG item whenever reasoning or tool
 * rows precede the target — the classic "match is above/below the view" bug.
 *
 * Mirrors the item emission order in [FullBleedChatList] exactly:
 * user turn → 1 item; agent turn → optional reasoning item, then one item per
 * entry. [leadingItems] accounts for fixed items emitted before the turns
 * (e.g. the `loading-older` spinner when paging).
 *
 * @return messageId → LazyColumn item index of the message's content item
 *   (prose items for agent messages; user items for user messages).
 */
fun messageIdToLazyIndex(
    turns: List<ChatTurn>,
    leadingItems: Int = 0,
): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    var itemIndex = leadingItems
    turns.forEach { turn ->
        when (turn) {
            is ChatTurn.User -> {
                map[turn.message.id] = itemIndex
                itemIndex++
            }

            is ChatTurn.Agent -> {
                val hasReasoning =
                    turn.entries
                        .filterIsInstance<AgentEntry.Prose>()
                        .any { it.message.reasoningText.isNotBlank() }
                if (hasReasoning) {
                    // The reasoning-<id> hoist item occupies one slot.
                    itemIndex++
                }
                turn.entries.forEach { entry ->
                    when (entry) {
                        is AgentEntry.Prose -> {
                            map[entry.message.id] = itemIndex
                            itemIndex++
                        }

                        is AgentEntry.ToolRow -> itemIndex++
                        is AgentEntry.SystemEvent -> itemIndex++
                    }
                }
            }
        }
    }
    return map
}

/**
 * Resolve the message id of the CURRENT search match once, so per-item
 * highlight lookups are O(1) id comparisons instead of O(n) linear scans
 * (`messages.indexOfFirst` per rendered bubble was O(n²) per search update).
 */
fun currentMatchMessageId(
    messages: List<ChatMessage>,
    searchMatchIndices: List<Int>,
    currentSearchMatchIndex: Int,
): String? {
    if (currentSearchMatchIndex < 0 || currentSearchMatchIndex >= searchMatchIndices.size) return null
    val messageIndex = searchMatchIndices[currentSearchMatchIndex]
    if (messageIndex < 0 || messageIndex >= messages.size) return null
    return messages[messageIndex].id
}

/**
 * Message ids that contain at least one search match. Bubbles outside this
 * set skip their highlight scan entirely (they used to re-run it on every
 * search-state change even with zero hits).
 */
fun matchedMessageIds(
    messages: List<ChatMessage>,
    searchMatchIndices: List<Int>,
): Set<String> =
    searchMatchIndices
        .mapNotNull { messages.getOrNull(it)?.id }
        .toSet()
