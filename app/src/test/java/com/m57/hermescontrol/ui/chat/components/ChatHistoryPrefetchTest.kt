package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPrefetchTest {
    @Test
    fun shortUpwardDragCanReachMovementThresholdDuringFling() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(30, 5f, userInput = true, canLoad = true))
        assertTrue(policy.onScroll(25, 30f, userInput = false, canLoad = true))
        assertFalse(policy.onScroll(10, 30f, userInput = false, canLoad = true))
    }

    @Test
    fun upwardFlingCanReachPrefetchThresholdAfterDragEnds() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(70, 30f, userInput = true, canLoad = true))
        assertFalse(policy.onScroll(40, 40f, userInput = false, canLoad = true))
        assertTrue(policy.onScroll(25, 40f, userInput = false, canLoad = true))
        assertFalse(policy.onScroll(10, 40f, userInput = false, canLoad = true))
        policy.endGesture()
        assertFalse(policy.onScroll(5, 40f, userInput = false, canLoad = true))
    }

    @Test
    fun reversingDirectionDisarmsPreviousUpwardIntent() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(70, 30f, userInput = true, canLoad = true))
        assertFalse(policy.onScroll(50, -40f, userInput = true, canLoad = true))
        assertFalse(policy.onScroll(25, 40f, userInput = false, canLoad = true))
    }

    @Test
    fun initialTopAndProgrammaticMovementDoNotFetch() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(0, 0f, userInput = true, canLoad = true))
        assertFalse(policy.onScroll(0, 100f, userInput = false, canLoad = true))
        assertFalse(policy.onScroll(0, -100f, userInput = true, canLoad = true))
    }

    @Test
    fun prefetchStartsTwentyFiveRowsBeforeTopAfterMeaningfulMovement() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(30, 30f, userInput = true, canLoad = true))
        assertTrue(policy.onScroll(25, 1f, userInput = true, canLoad = true))
    }

    @Test
    fun initialTopNeedsDeliberateGestureAndRequestsOnlyOnce() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(0, 10f, userInput = true, canLoad = true))
        assertTrue(policy.onScroll(0, 15f, userInput = true, canLoad = true))
        repeat(100) {
            assertFalse(policy.onScroll(0, 50f, userInput = true, canLoad = true))
        }
        policy.endGesture()
        assertTrue(policy.onScroll(0, 25f, userInput = true, canLoad = true))
    }

    @Test
    fun loadingAndExhaustionSuppressRequestsAndNextGestureCanRetry() {
        val policy = ChatHistoryPrefetch()
        assertFalse(policy.onScroll(20, 50f, userInput = true, canLoad = false))
        policy.endGesture()
        assertTrue(policy.onScroll(20, 25f, userInput = true, canLoad = true))
        policy.endGesture()
        assertTrue(policy.onScroll(20, 25f, userInput = true, canLoad = true))
    }
}
