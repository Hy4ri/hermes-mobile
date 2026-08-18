package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Owns all chat scroll behavior (issue #682):
 *
 * 1. **Bottom-follow intent** ([isFollowingBottom]) is tracked continuously from
 *    [LazyListState] via [snapshotFlow], not decided only when new data arrives.
 * 2. **Precise bottom detection** ([isAtBottom]) checks the last item's bottom
 *    edge against the viewport bottom within a small pixel tolerance, instead of
 *    an item-count threshold.
 * 3. **Streaming follow** — [onTailChanged] keeps following streamed output only
 *    while the reader is pinned at the bottom. An upward scroll pauses follow
 *    immediately (via [isFollowingBottom]) and never forces the reader back down.
 * 4. **Tail updates** — callers funnel every tail change (messages, streaming,
 *    thinking, subagent cards, clarify prompts) through [onTailChanged] with a
 *    stable tail key, so follow + unread tracking react to real content changes.
 * 5. **Unread indicator** — when follow is paused, [pendingCount] accumulates the
 *    number of tail updates; shows on the FAB. Tapping the FAB resumes following
 *    and clears the count.
 * 6. **Paging anchor preservation** — capture the first visible item + offset
 *    with [captureAnchor] before prepending older history, then
 *    [restoreAnchor] after insertion so the same content stays under the eye.
 * 7. **Serialized scroll commands** — every scroll (send, FAB, session switch,
 *    search navigation, auto-follow) is launched from [scope] so animations
 *    don't compete.
 *
 * Construct with [rememberChatScrollController].
 */
class ChatScrollController(
    private val listState: LazyListState,
    internal val scope: CoroutineScope,
) {
    /** True while the reader is pinned at (or within tolerance of) the bottom. */
    var isFollowingBottom by mutableStateOf(true)
        private set

    /**
     * Number of new messages that arrived while follow was paused. Shown on the
     * scroll-to-bottom FAB; cleared when the reader resumes following.
     */
    var pendingCount by mutableStateOf(0)
        private set

    private var lastTailKey: Any? = null
    private var lastMessageCount: Int = 0

    /**
     * Guard flag: true while a programmatic scroll-to-bottom is in flight.
     * Prevents [observeUserScrollPosition] from flipping [isFollowingBottom]
     * to false during the layout race — the scroll targets the old last item,
     * Compose lays out the new item below, and [isAtBottom] momentarily
     * returns false before the second scroll pass catches it.
     */
    private var isProgrammaticScroll by mutableStateOf(false)

    /**
     * Pixel tolerance for "at bottom" detection. Covers the LazyColumn's
     * bottom contentPadding (8dp ≈ 24px on hdpi) plus a small margin so
     * that soft-keyboard transitions and fractional rendering don't falsely
     * break follow mode.
     */
    var bottomPixelTolerance by mutableStateOf(48)

    /** Serialized scroll scope — all scroll jobs funnel through here. */
    fun launchScroll(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    /**
     * Read the live bottom-follow state from [LazyListState] and react to
     * user-driven scroll position changes. Should be called inside a
     * `LaunchedEffect(Unit)` so it observes the list for the screen's lifetime.
     */
    fun observeUserScrollPosition() {
        scope.launch {
            snapshotFlow { listState.isAtBottom(bottomPixelTolerance) }
                .distinctUntilChanged()
                .collect { atBottom ->
                    if (atBottom) {
                        isFollowingBottom = true
                        // Reaching the bottom means the reader caught up.
                        pendingCount = 0
                    } else if (!isProgrammaticScroll) {
                        // Only break follow on USER-initiated scrolls.
                        // During programmatic scrolls the viewport is
                        // transiently not-at-bottom while new items lay out.
                        isFollowingBottom = false
                    }
                }
        }
    }

    /**
     * Call on every tail-content change (new message, streaming token, thinking
     * toggle, subagent card, clarify prompt). [tailKey] must be a stable value
     * that changes only when the tail content actually changes — pass
     * [tailContentKey] for a ready-made key. [messageCount] is the current total
     * message count, used to derive the unseen-message badge while paused.
     *
     * Follows the tail only when [isFollowingBottom] was true before this
     * change. While paused, increments [pendingCount] by the number of new
     * messages instead.
     */
    fun onTailChanged(
        tailKey: Any?,
        messageCount: Int = 0,
    ) {
        if (tailKey == lastTailKey) {
            lastMessageCount = messageCount
            return
        }
        val newMessages = (messageCount - lastMessageCount).coerceAtLeast(0)
        lastMessageCount = messageCount
        lastTailKey = tailKey
        if (isFollowingBottom) {
            // Pin instantly so rapid streamed tokens don't queue competing
            // animations; the reader stays glued to the growing tail.
            // Use layout-aware scrolling to survive the Compose layout race.
            scope.launch {
                isProgrammaticScroll = true
                try {
                    scrollToBottomAwaitingLayout()
                } finally {
                    isProgrammaticScroll = false
                }
            }
        } else {
            pendingCount += newMessages
        }
    }

    /**
     * Scroll to the true bottom of the list, waiting for Compose to lay out
     * any newly added items first.
     *
     * The race: state.messages changes → LaunchedEffect fires onTailChanged →
     * scrollToBottom uses layoutInfo.totalItemsCount which still reflects the
     * OLD item count → scrolls to the old last item → the new item lays out
     * below the viewport → isAtBottom returns false → follow mode breaks.
     *
     * Fix: yield to let the composition/layout pass run, then scroll. If the
     * item count still hasn't caught up (rare, heavy layout), wait briefly
     * via snapshotFlow on the layout info.
     */
    private suspend fun scrollToBottomAwaitingLayout() {
        // Yield twice: first for recomposition, second for layout.
        yield()
        yield()
        listState.scrollToBottom(animated = false)
        // If we're still not at the bottom after the first scroll (e.g. a
        // new item was laid out between the scroll and now), do one more pass.
        if (!listState.isAtBottom(bottomPixelTolerance)) {
            // Wait up to 150ms for the layout to settle with the new items.
            withTimeoutOrNull(150L) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
            }
            listState.scrollToBottom(animated = false)
        }
    }

    /** Force-follow to the bottom (session switch / explicit send). Clears unread. */
    fun jumpToBottom(animated: Boolean = false) {
        pendingCount = 0
        isFollowingBottom = true
        scope.launch {
            isProgrammaticScroll = true
            try {
                listState.scrollToBottom(animated = animated)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    /** FAB tap: resume following + clear unread. */
    fun resumeFollowing() = jumpToBottom(animated = true)

    /** True when the FAB should be visible (not following bottom + content exists). */
    fun showFab(contentPresent: Boolean): Boolean = !isFollowingBottom && contentPresent

    /**
     * Current pixel scroll offset of the first visible item — captured before
     * prepending older history so it can be restored after insertion.
     */
    fun captureAnchorOffset(): Int = listState.firstVisibleItemScrollOffset

    /**
     * Restore the viewport to a specific item (resolved by id in the caller, so
     * the restore is robust even if streaming tokens arrive during the page
     * insert). Keeps the reader's eye on the same content after prepend.
     */
    fun scrollToItem(
        index: Int,
        offset: Int,
    ) {
        scope.launch { listState.scrollToItem(index, offset) }
    }

    /**
     * Navigate to a search match (serialized through [scope]).
     *
     * [index] is the LAZY-COLUMN item index (see [messageIdToLazyIndex] —
     * message indices ≠ lazy indices once reasoning/tool items exist).
     * Top-aligns the item, then repositions it so the matched WORD lands
     * comfortably in view (~1/3 down the viewport): the word's position is
     * estimated as textOffset / contentLength × measured item height. Mirrors
     * the verified scrollToBottom pattern (top-align → measure → adjust).
     *
     * @param contentOffset character offset of the word in the message text.
     * @param contentLength total length of the message text.
     */
    fun scrollToSearchMatch(
        index: Int,
        contentOffset: Int,
        contentLength: Int,
    ) {
        scope.launch {
            listState.animateScrollToItem(index)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launch
            val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
            val fraction = if (contentLength > 0) contentOffset.toFloat() / contentLength else 0f
            val targetTop = (viewportHeight / 3) - (item.size * fraction).toInt()
            val delta = item.offset - targetTop
            if (delta != 0) {
                listState.animateScrollBy(delta.toFloat())
            }
        }
    }
}

/**
 * Stable tail-key covering messages, streaming state, typing, subagent cards,
 * and clarification prompts — anything that can change the list tail.
 */
fun tailContentKey(
    messages: List<*>,
    streamingMessage: Any?,
    isThinking: Boolean,
    subagentIndicators: List<*>,
    clarifyRequest: Any?,
): Any =
    listOf(
        messages.size,
        streamingMessage?.hashCode() ?: 0,
        isThinking,
        subagentIndicators.size,
        clarifyRequest?.hashCode() ?: 0,
    )

/**
 * Continuous bottom-follow tracker derived from [LazyListState]: true when the
 * last item's bottom edge is at (or within [tolerance] px of) the viewport
 * bottom. Replaces the old item-count `isAtBottom(threshold)` heuristic.
 */
fun LazyListState.isAtBottom(tolerance: Int = 8): Boolean {
    val layoutInfo = this.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val lastItem = visibleItems.last()
    if (lastItem.index < layoutInfo.totalItemsCount - 1) return false
    val lastBottom = lastItem.offset + lastItem.size
    val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return lastBottom <= viewportBottom + tolerance
}

/**
 * Scroll so the bottom edge of the last item is aligned to the viewport bottom.
 * Top-aligns first (instant or animated), then scrolls the exact remaining gap
 * so a taller-than-viewport last item's bottom stays visible. Uses the remaining
 * delta (not `Int.MAX_VALUE`) to avoid integer-overflow wrap in the internal
 * scroll-position clamp.
 */
suspend fun LazyListState.scrollToBottom(animated: Boolean) {
    val layoutInfo = this.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (animated) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
    val info = this.layoutInfo
    val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val remaining =
        (lastItem.offset + lastItem.size + info.afterContentPadding) - info.viewportEndOffset
    if (remaining > 0) {
        if (animated) {
            animateScrollBy(remaining.toFloat())
        } else {
            scroll { scrollBy(remaining.toFloat()) }
        }
    }
}

@Composable
fun rememberChatScrollController(
    listState: LazyListState,
    scope: CoroutineScope,
): ChatScrollController = remember(listState, scope) { ChatScrollController(listState, scope) }
