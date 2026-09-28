package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.searchTextFor

/** Resolve a search hit against emitted lazy rows, including hoisted reasoning and tool rows. */
@Composable
internal fun ChatSearchScrollEffect(
    searchState: ChatSearchState,
    messages: List<ChatMessage>,
    renderedFirstId: String?,
    turns: List<ChatTurn>,
    scrollController: ChatScrollController,
) {
    LaunchedEffect(
        searchState.isActive,
        searchState.currentIndex,
        searchState.matchIndices,
        searchState.matchOffsets,
        searchState.matchTargets,
        renderedFirstId,
    ) {
        val index = searchState.currentIndex
        if (!searchState.isActive || index !in searchState.matchIndices.indices) return@LaunchedEffect
        val messageIndex = searchState.matchIndices[index]
        val message = messages.getOrNull(messageIndex) ?: return@LaunchedEffect
        val target = searchState.matchTargets.getOrNull(index) ?: return@LaunchedEffect
        val match =
            com.m57.hermescontrol.ui.chat.SearchMatch(
                messageIndex = messageIndex,
                contentOffset = searchState.matchOffsets.getOrElse(index) { 0 },
                target = target,
            )
        // A hit in a staged history prefix is retried when the prefix enters layout.
        val lazyIndex = searchMatchToLazyIndex(turns, messages, match) ?: return@LaunchedEffect
        scrollController.scrollToSearchMatch(lazyIndex, match.contentOffset, searchTextFor(message, target).length)
    }
}
