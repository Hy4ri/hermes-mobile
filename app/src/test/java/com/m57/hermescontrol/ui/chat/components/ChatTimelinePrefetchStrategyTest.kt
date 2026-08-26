package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class ChatTimelinePrefetchStrategyTest {
    @Test
    fun strategy_neverSchedulesOffscreenChatItems() {
        val scope = mockk<LazyListPrefetchScope>(relaxed = true)
        val layoutInfo = mockk<LazyListLayoutInfo>(relaxed = true)
        val nestedScope = mockk<NestedPrefetchScope>(relaxed = true)

        with(ChatTimelineNoPrefetchStrategy) {
            with(scope) {
                onScroll(delta = 120f, layoutInfo)
                onVisibleItemsUpdated(layoutInfo)
            }
            with(nestedScope) {
                onNestedPrefetch(firstVisibleItemIndex = 10)
            }
        }

        verify(exactly = 0) { scope.schedulePrefetch(any(), any()) }
        verify(exactly = 0) { nestedScope.schedulePrecomposition(any()) }
        verify(exactly = 0) { nestedScope.schedulePrecompositionAndPremeasure(any(), any()) }
    }
}
