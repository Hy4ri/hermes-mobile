package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Returns true when the list is scrolled near its bottom
 * (within [threshold] items of the last index).
 */
fun LazyListState.isAtBottom(threshold: Int = 3): Boolean {
    val layoutInfo = this.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val lastVisibleItem = visibleItems.last()
    return lastVisibleItem.index >= layoutInfo.totalItemsCount - threshold
}

/**
 * Scroll so the bottom edge of the last item is aligned to the bottom of the
 * viewport (issue #583).
 *
 * `animateScrollToItem(lastIndex)` only top-aligns the last item, so when the
 * last item is taller than the viewport its bottom is clipped below the fold.
 * We first top-align (instant), then scroll the exact remaining gap so the
 * bottom is visible. Using the remaining delta (not `scrollOffset = Int.MAX_VALUE`)
 * avoids integer overflow in the internal scroll-position clamp, which would
 * otherwise wrap the offset to 0 and scroll back to the top.
 */
suspend fun LazyListState.scrollToBottom(animated: Boolean) {
    val layoutInfo = this.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return
    val lastIndex = layoutInfo.totalItemsCount - 1
    // Top-align the last item first so layoutInfo reflects the last item's offset.
    // When animated, use the animated variant so the FAB / send clicks keep a
    // smooth scroll instead of an instant jump followed by a tiny animation.
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

/**
 * Floating action button shown when the message list is not at the bottom;
 * tapping scrolls the list back to the bottom.
 */
@Composable
fun BoxScope.ChatScrollToBottomFab(
    showScrollToBottom: Boolean,
    scrollScope: CoroutineScope,
    listState: LazyListState,
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    isThinking: Boolean,
) {
    AnimatedVisibility(
        visible = showScrollToBottom,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
    ) {
        FloatingActionButton(
            onClick = {
                scrollScope.launch {
                    val totalItems =
                        messages.size +
                            (if (streamingMessage != null) 1 else 0) +
                            (if (isThinking) 1 else 0)
                    if (totalItems > 0) {
                        listState.scrollToBottom(animated = true)
                    }
                }
            },
            modifier = Modifier.size(40.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.content_desc_scroll_to_bottom),
            )
        }
    }
}
