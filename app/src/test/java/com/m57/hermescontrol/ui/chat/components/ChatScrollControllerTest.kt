package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mockLazyListStateAtBottom(): LazyListState {
        val listState = mockk<LazyListState>(relaxed = true)
        val layoutInfo = mockk<LazyListLayoutInfo>(relaxed = true)
        val itemInfo = mockk<LazyListItemInfo>(relaxed = true)

        every { layoutInfo.totalItemsCount } returns 5
        every { layoutInfo.viewportEndOffset } returns 1000
        every { layoutInfo.afterContentPadding } returns 0
        every { itemInfo.index } returns 4
        every { itemInfo.offset } returns 800
        every { itemInfo.size } returns 100
        every { layoutInfo.visibleItemsInfo } returns listOf(itemInfo)
        every { listState.layoutInfo } returns layoutInfo

        return listState
    }

    @Test
    fun `initial state has isFollowingBottom true and pendingCount 0`() {
        val listState = mockk<LazyListState>(relaxed = true)
        val scope = TestScope(testDispatcher)
        val controller = ChatScrollController(listState, scope)

        assertTrue(controller.isFollowingBottom)
        assertEquals(0, controller.pendingCount)
    }

    @Test
    fun `new tail while following keeps pendingCount 0 and requests scroll`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            val controller = ChatScrollController(listState, this)

            controller.onTailChanged(tailKey = "msg-1", messageCount = 1)
            advanceUntilIdle()

            assertTrue(controller.isFollowingBottom)
            assertEquals(0, controller.pendingCount)
            coVerify(atLeast = 1) { listState.scrollToItem(4, any()) }
        }

    @Test
    fun `pauseFollowing followed by new messages increments pendingCount`() {
        val listState = mockk<LazyListState>(relaxed = true)
        val scope = TestScope(testDispatcher)
        val controller = ChatScrollController(listState, scope)

        controller.pauseFollowing()
        assertFalse(controller.isFollowingBottom)

        controller.onTailChanged(tailKey = "msg-1", messageCount = 1)
        assertEquals(1, controller.pendingCount)

        controller.onTailChanged(tailKey = "msg-2", messageCount = 3)
        assertEquals(3, controller.pendingCount)
        assertFalse(controller.isFollowingBottom)
    }

    @Test
    fun `resumeFollowing clears pendingCount and restores isFollowingBottom`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            val controller = ChatScrollController(listState, this)

            controller.pauseFollowing()
            controller.onTailChanged(tailKey = "msg-1", messageCount = 2)
            assertEquals(2, controller.pendingCount)
            assertFalse(controller.isFollowingBottom)

            controller.resumeFollowing()
            advanceUntilIdle()

            assertTrue(controller.isFollowingBottom)
            assertEquals(0, controller.pendingCount)
            coVerify(atLeast = 1) { listState.animateScrollToItem(4, any()) }
        }

    @Test
    fun `jumpToBottom clears pendingCount and restores isFollowingBottom`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            val controller = ChatScrollController(listState, this)

            controller.pauseFollowing()
            controller.onTailChanged(tailKey = "msg-1", messageCount = 4)
            assertEquals(4, controller.pendingCount)
            assertFalse(controller.isFollowingBottom)

            controller.jumpToBottom(animated = false)
            advanceUntilIdle()

            assertTrue(controller.isFollowingBottom)
            assertEquals(0, controller.pendingCount)
            coVerify(atLeast = 1) { listState.scrollToItem(4, any()) }
        }

    @Test
    fun `tail key unchanged causes no change to pendingCount or scroll`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            val controller = ChatScrollController(listState, this)

            controller.pauseFollowing()
            controller.onTailChanged(tailKey = "same-key", messageCount = 1)
            assertEquals(1, controller.pendingCount)

            // Repeating the same tailKey should be a no-op
            controller.onTailChanged(tailKey = "same-key", messageCount = 5)
            assertEquals(1, controller.pendingCount)

            advanceUntilIdle()
            coVerify(exactly = 0) { listState.scrollToItem(any(), any()) }
            coVerify(exactly = 0) { listState.animateScrollToItem(any(), any()) }
        }

    @Test
    fun `showFab returns true only when paused and content is present`() {
        val listState = mockk<LazyListState>(relaxed = true)
        val scope = TestScope(testDispatcher)
        val controller = ChatScrollController(listState, scope)

        // Following bottom -> false regardless of contentPresent
        assertFalse(controller.showFab(contentPresent = true))
        assertFalse(controller.showFab(contentPresent = false))

        controller.pauseFollowing()
        // Paused -> true only if contentPresent
        assertTrue(controller.showFab(contentPresent = true))
        assertFalse(controller.showFab(contentPresent = false))
    }
}
