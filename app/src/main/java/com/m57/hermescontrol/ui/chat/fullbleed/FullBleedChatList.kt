package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.ToolCallDivider
import com.m57.hermescontrol.ui.chat.VaultCodePromptUi
import com.m57.hermescontrol.ui.chat.VaultSaveLoginPromptUi
import com.m57.hermescontrol.ui.chat.VaultUnlockPromptUi
import com.m57.hermescontrol.ui.chat.components.ChatHistoryPrefetch
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import com.m57.hermescontrol.ui.chat.components.VaultCodeCard
import com.m57.hermescontrol.ui.chat.components.VaultSaveLoginCard
import com.m57.hermescontrol.ui.chat.components.VaultUnlockCard
import com.m57.hermescontrol.ui.chat.toolCallMilestones
import com.m57.hermescontrol.ui.common.EmptyState

/**
 * The chat message list for FULL-BLEED style (issue #866) — the single chat
 * surface since the bubble renderer was removed. User messages keep their
 * bubble (the universal anchor), agent turns render full-bleed, and tool rows
 * / system events render as distinct compact cards.
 * Spacing contract:
 * - intra-turn: entries separated by 6.dp (Column padding on agent turn items)
 * - inter-turn: 12.dp bottom padding after each turn's last item
 *
 * COMPOSE GOTCHA (verified): LazyColumn `item {}` content lambdas execute
 * LAZILY at item-composition time, not during this DSL-building loop. Loop
 * locals that are read inside item lambdas must be captured as immutable
 * vals FIRST (eagerly), or every item sees the loop's final value and
 * per-entry state can be rendered against the wrong message.
 */
@Composable
fun FullBleedChatList(
    messages: List<ChatMessage>,
    streamingState: StreamingState,
    isAgentTyping: Boolean,
    searchState: ChatSearchState,
    typingEffectEnabled: Boolean,
    typingEffectDelayMs: Int,
    messageStatsEnabled: Boolean = false,
    showUserMessageTokens: Boolean = true,
    showAssistantMessageTokens: Boolean = true,
    showTokensPerSecond: Boolean = true,
    maxToolCallsPerTurn: Int? = null,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollController: ChatScrollController,
    lastAnimatedMessageId: String?,
    onLastAnimatedMessageIdChange: (String?) -> Unit,
    viewModel: ChatViewModel,
    clarifyRequest: ClarifyUi? = null,
    onRespondClarify: ((String) -> Unit)? = null,
    onRespondClarifyBatch: ((Map<String, String>) -> Unit)? = null,
    onDismissClarify: (() -> Unit)? = null,
    vaultUnlockPrompt: VaultUnlockPromptUi? = null,
    onRespondVaultUnlock: ((String) -> Unit)? = null,
    onDismissVaultUnlock: (() -> Unit)? = null,
    vaultSaveLoginPrompt: VaultSaveLoginPromptUi? = null,
    onRespondVaultSaveLogin: ((String, String) -> Unit)? = null,
    onDismissVaultSaveLogin: (() -> Unit)? = null,
    vaultCodePrompt: VaultCodePromptUi? = null,
    onRespondVaultCode: ((String) -> Unit)? = null,
    onDismissVaultCode: (() -> Unit)? = null,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    onImageClick: (ImageViewerModel) -> Unit = {},
    hasOlderMessages: Boolean = false,
    pagingSessionId: String? = null,
    onLoadOlder: () -> Unit = viewModel::loadOlderMessages,
    replyErrorContent: (@Composable () -> Unit)? = null,
) {
    if (messages.isEmpty() && !isLoading && !isAgentTyping && replyErrorContent == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.chat_empty_title),
                subtitle = stringResource(R.string.chat_empty_subtitle),
            )
        }
    } else {
        // Keep only the incoming older prefix out of layout during a drag/fling.
        // The input remains authoritative: overlap updates, new messages and streaming
        // stay live, and a session change resets the boundary immediately.
        val previousFirstId = remember(listState, pagingSessionId) { mutableStateOf(messages.firstOrNull()?.id) }
        val firstRenderedId = previousFirstId.value
        val olderPrefixSize =
            remember(messages, firstRenderedId) {
                if (firstRenderedId != null && firstRenderedId != messages.firstOrNull()?.id) {
                    messages.indexOfFirst { it.id == firstRenderedId }.coerceAtLeast(0)
                } else {
                    0
                }
            }
        val hiddenPrefixSize = if (olderPrefixSize > 0 && listState.isScrollInProgress) olderPrefixSize else 0
        val renderedMessages =
            remember(messages, hiddenPrefixSize) {
                if (hiddenPrefixSize > 0) messages.subList(hiddenPrefixSize, messages.size) else messages
            }
        val toolMilestones = remember(renderedMessages) { toolCallMilestones(renderedMessages) }
        val settledTurns = remember(renderedMessages) { groupIntoTurns(renderedMessages) }
        val settledIds = remember(renderedMessages) { renderedMessages.mapTo(HashSet()) { it.id } }
        val turns =
            remember(settledTurns, streamingState.streamingMessage) {
                appendStreamingTurn(settledTurns, settledIds, streamingState.streamingMessage)
            }
        val agentStatus = deriveAgentStatus(isAgentTyping, streamingState, renderedMessages)
        // A single ordered definition supplies both emitted tail rows and anchor keys.
        val tailItems =
            buildMap<String, @Composable () -> Unit> {
                replyErrorContent?.let { put("reply_error", it) }
                agentStatus?.let { status ->
                    put("agent_status") {
                        AgentStatusIndicator(status = status)
                    }
                }

                // Clarify bubble — rendered at the very bottom
                if (clarifyRequest != null) {
                    put("clarify_bubble") {
                        ClarifyBubble(
                            clarifyRequest = clarifyRequest,
                            onRespondSingle = { option -> onRespondClarify?.invoke(option) },
                            onRespondBatch = { answers -> onRespondClarifyBatch?.invoke(answers) },
                            onDismiss = { onDismissClarify?.invoke() },
                        )
                    }
                }

                if (vaultUnlockPrompt != null) {
                    put("vault_unlock_card") {
                        VaultUnlockCard(
                            prompt = vaultUnlockPrompt,
                            onConfirm = { password -> onRespondVaultUnlock?.invoke(password) },
                            onDismiss = { onDismissVaultUnlock?.invoke() },
                        )
                    }
                }

                if (vaultSaveLoginPrompt != null) {
                    put("vault_save_login_card") {
                        VaultSaveLoginCard(
                            prompt = vaultSaveLoginPrompt,
                            onConfirm = { identifier, password ->
                                onRespondVaultSaveLogin?.invoke(identifier, password)
                            },
                            onDismiss = { onDismissVaultSaveLogin?.invoke() },
                        )
                    }
                }

                if (vaultCodePrompt != null) {
                    put("vault_code_card") {
                        VaultCodeCard(
                            prompt = vaultCodePrompt,
                            onConfirm = { code -> onRespondVaultCode?.invoke(code) },
                            onDismiss = { onDismissVaultCode?.invoke() },
                        )
                    }
                }
            }
        // custom23 prepend regression: a 150-row page can move the anchor beyond
        // Compose's nearest-key lookup window. Capture the LIVE layout at insertion,
        // not when fetching starts, and request its new index before the next measure.
        // A staged prefix only reaches here at idle, so this cannot stop its drag/fling.
        SideEffect {
            val oldFirstId = previousFirstId.value
            val firstId = renderedMessages.firstOrNull()?.id
            if (oldFirstId != null && oldFirstId != firstId && renderedMessages.any { it.id == oldFirstId }) {
                val firstVisibleIndex = listState.firstVisibleItemIndex
                val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisibleIndex }
                if (anchor != null) {
                    val newIndex = (fullBleedItemKeys(turns) + tailItems.keys).indexOf(anchor.key)
                    if (newIndex >= 0 && newIndex != anchor.index) {
                        listState.requestScrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
                    }
                }
            }
            previousFirstId.value = firstId
        }
        val prefetch = remember(pagingSessionId) { ChatHistoryPrefetch() }
        val canLoad = rememberUpdatedState(hasOlderMessages && !isLoadingOlder && !isLoading)
        val loadOlder = rememberUpdatedState(onLoadOlder)
        val prefetchConnection =
            remember(listState, prefetch, scrollController) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (prefetch.onScroll(
                                firstVisibleIndex = listState.firstVisibleItemIndex,
                                deltaY = available.y,
                                userInput = source == NestedScrollSource.UserInput,
                                canLoad = canLoad.value,
                            )
                        ) {
                            scrollController.pauseFollowing()
                            loadOlder.value()
                        }
                        return Offset.Zero
                    }
                }
            }
        LaunchedEffect(listState, prefetch) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) prefetch.endGesture()
            }
        }

        // Scroll the current search match into view, word-focused. Lives here
        // (not in ChatLifecycleEffects) because only this composable knows
        // the message-id → lazy-item-index mapping. Reads search fields in
        // the effect (not the body), so only this effect restarts on change.
        LaunchedEffect(
            searchState.isActive,
            searchState.currentIndex,
            searchState.matchIndices,
            searchState.matchOffsets,
            renderedMessages.firstOrNull()?.id,
        ) {
            if (searchState.isActive &&
                searchState.currentIndex >= 0 &&
                searchState.currentIndex < searchState.matchIndices.size
            ) {
                val messageIndex = searchState.matchIndices[searchState.currentIndex]
                if (messageIndex < 0 || messageIndex >= messages.size) return@LaunchedEffect
                // Search indices refer to incoming messages; resolve by id into the
                // rendered rows. A hit in a staged prefix is retried when it becomes visible.
                val lazyIndexById = messageIdToLazyIndex(turns)
                val lazyIndex = lazyIndexById[messages[messageIndex].id] ?: return@LaunchedEffect
                val contentOffset = searchState.matchOffsets.getOrElse(searchState.currentIndex) { 0 }
                val contentLength = messages[messageIndex].content.length
                scrollController.scrollToSearchMatch(lazyIndex, contentOffset, contentLength)
            }
        }

        val currentDensity = LocalDensity.current
        val chatFontScale = LocalChatFontScale.current
        val chatDensity =
            remember(currentDensity, chatFontScale) {
                Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale * chatFontScale,
                )
            }

        CompositionLocalProvider(LocalDensity provides chatDensity) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(prefetchConnection),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    turns.forEach { turn ->
                        when (turn) {
                            is ChatTurn.User -> {
                                // Eager captures: item lambda reads these at
                                // composition time (lazy), so capture now.
                                val userMessage = turn.message
                                item(key = "user-${userMessage.id}") {
                                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                        renderChatBubble(
                                            message = userMessage,
                                            searchQuery = if (searchState.isActive) searchState.query else "",
                                            isCurrentMatch =
                                                searchState.currentMatchId != null &&
                                                    searchState.currentMatchId == userMessage.id,
                                            onOpenAttachment = viewModel::openAttachment,
                                            onSaveAttachment = onSaveAttachment,
                                            savingAttachmentPath = savingAttachmentPath,
                                            openingAttachmentPath = openingAttachmentPath,
                                            onImageClick = onImageClick,
                                            messageStatsEnabled = messageStatsEnabled,
                                            showUserMessageTokens = showUserMessageTokens,
                                        )
                                    }
                                }
                            }

                            is ChatTurn.Agent -> {
                                // Reasoning hoist: the turn's reasoning renders at the
                                // TOP of the turn — above tool rows — so thinking
                                // leads, then the tool work, then the answer. The
                                // matching prose entry renders without its own card.
                                val turnReasoning =
                                    turn.entries
                                        .filterIsInstance<AgentEntry.Prose>()
                                        .firstOrNull { it.message.reasoningText.isNotBlank() }
                                if (turnReasoning != null) {
                                    val reasoning = turnReasoning.message
                                    item(key = "reasoning-${reasoning.id}") {
                                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                            ReasoningCard(
                                                reasoningText = reasoning.reasoningText,
                                                isStreaming = reasoning.isStreaming,
                                            )
                                        }
                                    }
                                }
                                turn.entries.forEach { entry ->
                                    when (entry) {
                                        is AgentEntry.Prose -> {
                                            val proseMessage = entry.message
                                            val hoistedReasoning =
                                                turnReasoning != null &&
                                                    proseMessage.id == turnReasoning.message.id
                                            if (!proseMessage.hasVisibleAgentContent() && !hoistedReasoning &&
                                                proseMessage.reasoningText.isNotBlank()
                                            ) {
                                                item(key = "reasoning-${proseMessage.id}") {
                                                    ReasoningCard(
                                                        reasoningText = proseMessage.reasoningText,
                                                        isStreaming = proseMessage.isStreaming,
                                                    )
                                                }
                                            }
                                            if (proseMessage.hasVisibleAgentContent()) {
                                                item(key = "prose-${proseMessage.id}") {
                                                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                                        if (proseMessage.isStreaming && typingEffectEnabled) {
                                                            StreamingFullBleedWithTypingEffect(
                                                                streaming = proseMessage,
                                                                typingDelayMs = typingEffectDelayMs,
                                                                showReasoning = !hoistedReasoning,
                                                            )
                                                        } else {
                                                            FullBleedAgentMessage(
                                                                message = proseMessage,
                                                                // Highlight only bubbles that actually contain a match —
                                                                // the rest skip the highlight scan entirely.
                                                                searchQuery =
                                                                    if (searchState.isActive &&
                                                                        proseMessage.id in searchState.matchedIds
                                                                    ) {
                                                                        searchState.query
                                                                    } else {
                                                                        ""
                                                                    },
                                                                isCurrentMatch =
                                                                    searchState.currentMatchId != null &&
                                                                        searchState.currentMatchId == proseMessage.id,
                                                                showReasoning = !hoistedReasoning,
                                                                onOpenAttachment = viewModel::openAttachment,
                                                                onSaveAttachment = onSaveAttachment,
                                                                savingAttachmentPath = savingAttachmentPath,
                                                                openingAttachmentPath = openingAttachmentPath,
                                                                canSaveAttachment = savingAttachmentPath == null,
                                                                onImageClick = onImageClick,
                                                                messageStatsEnabled = messageStatsEnabled,
                                                                showAssistantMessageTokens = showAssistantMessageTokens,
                                                                showTokensPerSecond = showTokensPerSecond,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        is AgentEntry.ToolRow -> {
                                            val toolMessage = entry.message
                                            val milestone = toolMilestones[toolMessage.id]
                                            item(key = "tool-${toolMessage.id}") {
                                                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                                    FullBleedToolRow(toolMessage)
                                                    milestone?.let { count ->
                                                        ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                                    }
                                                }
                                            }
                                        }

                                        is AgentEntry.SystemEvent -> {
                                            val sysMessage = entry.message
                                            item(key = "sys-${sysMessage.id}") {
                                                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                                    if (sysMessage.displayKind != null) {
                                                        // Timeline marker (issue #904):
                                                        // model/personality switches and
                                                        // auto-continues render as a chip.
                                                        TimelineMarkerChip(message = sysMessage)
                                                    } else {
                                                        FullBleedSystemEvent(
                                                            message = sysMessage,
                                                            onRespondApproval = viewModel::respondToApproval,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    tailItems.forEach { (key, content) ->
                        item(key = key) { content() }
                    }
                }
                // Loading history must never become the list's first visible anchor.
                if (isLoadingOlder) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun renderChatBubble(
    message: ChatMessage,
    searchQuery: String,
    isCurrentMatch: Boolean,
    onOpenAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    savingAttachmentPath: String?,
    openingAttachmentPath: String?,
    onImageClick: (ImageViewerModel) -> Unit,
    messageStatsEnabled: Boolean,
    showUserMessageTokens: Boolean,
) {
    ChatBubble(
        message = message,
        searchQuery = searchQuery,
        isCurrentMatch = isCurrentMatch,
        onOpenAttachment = onOpenAttachment,
        onSaveAttachment = onSaveAttachment,
        savingAttachmentPath = savingAttachmentPath,
        openingAttachmentPath = openingAttachmentPath,
        canSaveAttachment = savingAttachmentPath == null,
        onImageClick = onImageClick,
        messageStatsEnabled = messageStatsEnabled,
        showUserMessageTokens = showUserMessageTokens,
    )
}
