package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

/**
 * Chat messages can contain very large selectable Markdown blocks. Measuring a
 * neighboring item during LazyColumn prefetch can monopolize the main thread
 * long enough to trigger an input-dispatch ANR, even though that item is still
 * off screen. Measure chat items only when they enter the viewport instead.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object ChatTimelineNoPrefetchStrategy : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(
        delta: Float,
        layoutInfo: LazyListLayoutInfo,
    ) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}
