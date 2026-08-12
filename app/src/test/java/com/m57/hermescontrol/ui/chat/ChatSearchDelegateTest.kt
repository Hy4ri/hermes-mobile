package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            assertEquals(emptyList<Int>(), uiState.value.searchMatchIndices)

            // Typing paused long enough — exactly one scan, for the final query.
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0), uiState.value.searchMatchIndices)
            assertEquals(listOf(0), uiState.value.searchMatchOffsets)
            assertEquals("alpha", uiState.value.searchQuery)
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
            assertEquals(listOf(1), uiState.value.searchMatchIndices)
            assertEquals("beta", uiState.value.searchQuery)
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
            assertEquals(listOf(0), uiState.value.searchMatchIndices)

            delegate.setSearchQuery("")
            assertEquals(emptyList<Int>(), uiState.value.searchMatchIndices)
            assertEquals(-1, uiState.value.currentSearchMatchIndex)
            assertEquals("", uiState.value.searchQuery)
        }
}
