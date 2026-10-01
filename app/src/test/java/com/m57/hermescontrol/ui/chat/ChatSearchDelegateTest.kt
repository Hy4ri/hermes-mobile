package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchDelegateTest {
    private fun stateWith(vararg contents: String): MutableStateFlow<ChatUiState> =
        MutableStateFlow(
            ChatUiState(
                messages =
                    contents.mapIndexed { index, content ->
                        ChatMessage(
                            id = "m$index",
                            role = MessageRole.USER,
                            content = content,
                        )
                    },
            ),
        )

    @Test
    fun `typing pauses coalesce into a single search for the final query`() =
        runTest {
            val uiState = stateWith("alpha beta", "nothing here")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.setSearchQuery("alp")
            advanceTimeBy(50)
            delegate.setSearchQuery("al")
            advanceTimeBy(50)
            delegate.setSearchQuery("alpha")

            // Debounce window not closed yet — no scan has run.
            advanceTimeBy(149)
            runCurrent()
            assertEquals(emptyList<Int>(), delegate.searchState.matchIndices)

            // Typing paused long enough — exactly one scan, for the final query.
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0), delegate.searchState.matchIndices)
            assertEquals(listOf(0), delegate.searchState.matchOffsets)
            assertEquals(1, delegate.searchState.matchTotal)
            assertFalse(delegate.searchState.matchCapped)
            assertEquals("alpha", delegate.searchState.query)
            assertEquals(setOf("m0"), delegate.searchState.matchedIds)
            assertEquals("m0", delegate.searchState.currentMatchId)
            assertEquals(0, delegate.searchState.currentIndex)
        }

    @Test
    fun `cancelled intermediate queries never produce matches`() =
        runTest {
            val uiState = stateWith("alpha one", "beta two")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.setSearchQuery("alpha")
            advanceTimeBy(100)
            // New keystroke cancels the pending "alpha" scan before it fires.
            delegate.setSearchQuery("beta")
            advanceTimeBy(150)
            runCurrent()

            // Only "beta" ever ran — a stale "alpha" scan must not land.
            assertEquals(listOf(1), delegate.searchState.matchIndices)
            assertEquals("beta", delegate.searchState.query)
            assertEquals("m1", delegate.searchState.currentMatchId)
        }

    @Test
    fun `blank query clears matches immediately without waiting for debounce`() =
        runTest {
            val uiState = stateWith("alpha one")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.setSearchQuery("alpha")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(0), delegate.searchState.matchIndices)

            delegate.setSearchQuery("")
            assertEquals(emptyList<Int>(), delegate.searchState.matchIndices)
            assertEquals(-1, delegate.searchState.currentIndex)
            assertNull(delegate.searchState.currentMatchId)
            assertEquals("", delegate.searchState.query)
        }

    @Test
    fun `navigation updates currentIndex and the current match id`() =
        runTest {
            val uiState = stateWith("alpha alpha alpha")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.setSearchQuery("alpha")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(3, delegate.searchState.matchIndices.size)
            assertEquals("m0", delegate.searchState.currentMatchId)

            delegate.navigateSearchMatch(1)
            assertEquals(1, delegate.searchState.currentIndex)
            assertEquals("m0", delegate.searchState.currentMatchId)

            delegate.navigateSearchMatch(-1)
            assertEquals(0, delegate.searchState.currentIndex)
        }

    @Test
    fun `search tracks target kind while navigating reasoning tool and prose`() =
        runTest {
            val uiState =
                MutableStateFlow(
                    ChatUiState(
                        messages =
                            listOf(
                                ChatMessage("m0", MessageRole.ASSISTANT, "answer", reasoningText = "thinking"),
                                ChatMessage("m1", MessageRole.TOOL, "opaque", toolName = "thinking_tool"),
                            ),
                    ),
                )
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.setSearchQuery("thinking")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(SearchTarget.REASONING, SearchTarget.TOOL), delegate.searchState.matchTargets)
            assertEquals("m0", delegate.searchState.currentMatchId)
            delegate.navigateSearchMatch(1)
            assertEquals("m1", delegate.searchState.currentMatchId)
            assertEquals(SearchTarget.TOOL, delegate.searchState.matchTargets[delegate.searchState.currentIndex])
            delegate.clearSearch()
            assertEquals(emptyList<SearchTarget>(), delegate.searchState.matchTargets)
        }

    @Test
    fun `clearSearch resets everything including matched sets`() =
        runTest {
            val uiState = stateWith("alpha one")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    debounceMs = 150,
                )

            delegate.toggleSearch()
            delegate.setSearchQuery("alpha")
            advanceTimeBy(150)
            runCurrent()
            assertTrue(delegate.searchState.isActive)
            assertEquals(setOf("m0"), delegate.searchState.matchedIds)

            delegate.clearSearch()
            assertFalse(delegate.searchState.isActive)
            assertEquals("", delegate.searchState.query)
            assertEquals(emptyList<Int>(), delegate.searchState.matchIndices)
            assertEquals(0, delegate.searchState.matchTotal)
            assertFalse(delegate.searchState.matchCapped)
            assertEquals(-1, delegate.searchState.currentIndex)
            assertEquals(emptySet<String>(), delegate.searchState.matchedIds)
            assertNull(delegate.searchState.currentMatchId)
        }
}
