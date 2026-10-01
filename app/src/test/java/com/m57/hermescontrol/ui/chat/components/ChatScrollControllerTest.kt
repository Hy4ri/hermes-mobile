package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun `layout growth does not pause follow but returning to bottom after user gesture resumes`() =
        runTest(testDispatcher) {
            var atBottom by mutableStateOf(true)
            val listState = mockLazyListStateAtBottom()
            val layoutInfo = listState.layoutInfo
            val itemInfo = layoutInfo.visibleItemsInfo.single()
            every { itemInfo.index } answers { if (atBottom) 4 else 3 }
            val controller = ChatScrollController(listState, backgroundScope)

            controller.observeUserScrollPosition()
            runCurrent()
            atBottom = false // A new row laid out below the viewport, not a user scroll.
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(controller.isFollowingBottom)

            controller.onUserScrollUp()
            assertFalse(controller.isFollowingBottom)
            atBottom = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(controller.isFollowingBottom)
        }

    @Test
    fun `user upward gesture cancels in-flight follow and counts new messages`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            coEvery { listState.scrollToItem(4, any()) } coAnswers { delay(1_000) }
            val controller = ChatScrollController(listState, this)

            controller.onTailChanged(tailKey = "first", messageCount = 1)
            runCurrent()
            controller.onUserScrollUp()
            controller.onTailChanged(tailKey = "second", messageCount = 2)
            advanceUntilIdle()

            assertFalse(controller.isFollowingBottom)
            assertEquals(1, controller.pendingCount)
            coVerify(exactly = 1) { listState.scrollToItem(4, any()) }
        }

    @Test
    fun `historical jump replaces an in-flight follow and leaves follow paused`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            coEvery { listState.scrollToItem(4, any()) } coAnswers { delay(1_000) }
            val controller = ChatScrollController(listState, this)

            controller.onTailChanged(tailKey = "tail", messageCount = 1)
            runCurrent()
            controller.jumpToHistoryStart()
            advanceUntilIdle()

            assertFalse(controller.isFollowingBottom)
            coVerify(exactly = 1) { listState.scrollToItem(0, any()) }
        }

    @Test
    fun `rapid tails replace older jobs rather than queueing scrolls`() =
        runTest(testDispatcher) {
            val listState = mockLazyListStateAtBottom()
            val controller = ChatScrollController(listState, this)

            controller.onTailChanged(tailKey = "one", messageCount = 1)
            controller.onTailChanged(tailKey = "two", messageCount = 2)
            advanceUntilIdle()

            assertTrue(controller.isFollowingBottom)
            coVerify(exactly = 1) { listState.scrollToItem(4, any()) }
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
