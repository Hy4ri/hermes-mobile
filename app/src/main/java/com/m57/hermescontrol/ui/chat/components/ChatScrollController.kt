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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
 * 6. **Paging anchors** — the full-bleed renderer preserves the live lazy row
 *    key and offset when older history is inserted; this controller owns no
 *    fetch-time anchor or animated compensation.
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

    /** The sole active scroll command. Replaced by newer tail, search or explicit jumps. */
    private var scrollJob: Job? = null

    /**
     * Pixel tolerance for "at bottom" detection. Covers the LazyColumn's
     * bottom contentPadding (8dp ≈ 24px on hdpi) plus a small margin so
     * that soft-keyboard transitions and fractional rendering don't falsely
     * break follow mode.
     */
    var bottomPixelTolerance by mutableStateOf(48)

    /** Replace the previous scroll command; user gestures can cancel the same job. */
    private fun launchScroll(block: suspend CoroutineScope.() -> Unit) {
        scrollJob?.cancel()
        scrollJob = scope.launch(block = block)
    }

    /** Observe only arrival at the bottom; departures are owned by actual user input. */
    fun observeUserScrollPosition() {
        scope.launch {
            var previouslyAtBottom = listState.isAtBottom(bottomPixelTolerance)
            snapshotFlow { listState.isAtBottom(bottomPixelTolerance) }
                .distinctUntilChanged()
                .collect { atBottom ->
                    if (atBottom && !previouslyAtBottom) {
                        isFollowingBottom = true
                        pendingCount = 0
                    }
                    previouslyAtBottom = atBottom
                }
        }
    }

    /** An upward user gesture takes ownership from auto-follow immediately. */
    fun onUserScrollUp() = pauseFollowing()

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
            launchScroll { scrollToBottomAwaitingLayout() }
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
    private suspend fun scrollToBottomAwaitingLayout(animated: Boolean = false) {
        // Yield twice: first for recomposition, second for layout.
        yield()
        yield()
        listState.scrollToBottom(animated = animated)
        // Yield once more if layout changed during the first pass, then align
        // the current last row; newer tails replace this job instead of racing it.
        if (!listState.isAtBottom(bottomPixelTolerance)) {
            yield()
            listState.scrollToBottom(animated = animated)
        }
    }

    /** Force-follow to the bottom (session switch / explicit send). Clears unread. */
    fun jumpToBottom(animated: Boolean = false) {
        pendingCount = 0
        isFollowingBottom = true
        launchScroll { scrollToBottomAwaitingLayout(animated = animated) }
    }

    /** An explicit history gesture must not be undone by a short list's bottom-follow. */
    fun pauseFollowing() {
        isFollowingBottom = false
        scrollJob?.cancel()
    }

    /** Historical timeline navigation shares the scroll command with follow and search. */
    fun jumpToHistoryStart() {
        pauseFollowing()
        launchScroll { listState.scrollToItem(0) }
    }

    /** FAB tap: resume following + clear unread. */
    fun resumeFollowing() = jumpToBottom(animated = true)

    /** True when the FAB should be visible (not following bottom + content exists). */
    fun showFab(contentPresent: Boolean): Boolean = !isFollowingBottom && contentPresent

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
        pauseFollowing()
        launchScroll {
            listState.animateScrollToItem(index)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launchScroll
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
        messages.lastOrNull(),
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
