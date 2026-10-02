package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.BusySendMode
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchDelegateTest {
    /** Independent worker queue: draining it must not run UI continuations. */
    private class ManualWorkerDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runCurrent() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

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
    fun `inactive and blank search skip filtering until reactivation scans latest transcript`() =
        runTest {
            val uiState = stateWith("needle old")
            val delegate = ChatSearchDelegate(backgroundScope, uiState, dispatcher = StandardTestDispatcher(testScheduler))
            runCurrent()
            uiState.value = uiState.value.copy(messages = uiState.value.messages + ChatMessage("m1", MessageRole.USER, "needle new"))
            runCurrent()
            assertEquals(0, delegate.searchableMessagesCallsForTest)

            delegate.toggleSearch()
            runCurrent()
            uiState.value = uiState.value.copy(messages = uiState.value.messages + ChatMessage("m2", MessageRole.USER, "needle latest"))
            runCurrent()
            assertEquals(0, delegate.searchableMessagesCallsForTest)

            delegate.setSearchQuery("needle")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(0, 1, 2), delegate.searchState.matchIndices)
            assertEquals(setOf("m0", "m1", "m2"), delegate.searchState.matchedIds)
            delegate.clearSearch()
            val callsAfterClose = delegate.searchableMessagesCallsForTest
            uiState.value = uiState.value.copy(messages = uiState.value.messages + ChatMessage("m3", MessageRole.USER, "needle reopened"))
            runCurrent()
            assertEquals(callsAfterClose, delegate.searchableMessagesCallsForTest)
            delegate.toggleSearch()
            delegate.setSearchQuery("needle")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(0, 1, 2, 3), delegate.searchState.matchIndices)
            assertTrue("m3" in delegate.searchState.matchedIds)
        }

    @Test
    fun `queue becoming sending before current hit updates indices without changing query`() =
        runTest {
            val uiState = stateWith("needle queued", "needle server", "needle last")
            uiState.value =
                uiState.value.copy(
                    pendingSends =
                        listOf(
                            PendingSend("m0", "scope", "session", "needle queued", mode = BusySendMode.QUEUE),
                        ),
                )
            val delegate =
                ChatSearchDelegate(backgroundScope, uiState, dispatcher = StandardTestDispatcher(testScheduler))
            delegate.toggleSearch()
            delegate.setSearchQuery("needle")
            advanceTimeBy(150)
            runCurrent()
            delegate.navigateSearchMatch(1)
            assertEquals("m2", delegate.searchState.currentMatchId)
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices)

            uiState.value =
                uiState.value.copy(
                    pendingSends = uiState.value.pendingSends.map { it.copy(state = PendingSendState.SENDING) },
                )
            runCurrent()
            assertEquals("needle", delegate.searchState.query)
            assertEquals(listOf(0, 1, 2), delegate.searchState.matchIndices)
            assertEquals("m2", delegate.searchState.currentMatchId)
            assertEquals(2, delegate.searchState.currentIndex)
            delegate.navigateSearchMatch(-1)
            assertEquals("m1", delegate.searchState.currentMatchId)
            assertEquals(1, delegate.searchState.currentIndex)
            delegate.navigateSearchMatch(-1)
            assertEquals("m0", delegate.searchState.currentMatchId)
        }

    @Test
    fun `navigation before queue collector rescans on worker then applies direction on UI`() =
        runTest {
            val worker = ManualWorkerDispatcher()
            val uiState = stateWith("needle queued", "needle server", "needle last")
            uiState.value =
                uiState.value.copy(
                    pendingSends =
                        listOf(
                            PendingSend("m0", "scope", "session", "needle queued", mode = BusySendMode.QUEUE),
                        ),
                )
            val delegate =
                ChatSearchDelegate(backgroundScope, uiState, dispatcher = worker)
            delegate.setSearchQuery("needle")
            advanceTimeBy(150)
            runCurrent()
            worker.runCurrent()
            runCurrent()
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices)

            uiState.value =
                uiState.value.copy(
                    pendingSends = uiState.value.pendingSends.map { it.copy(state = PendingSendState.SENDING) },
                )
            delegate.navigateSearchMatch(1) // Collector has not run yet; caller must not scan.
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices)
            assertEquals("m1", delegate.searchState.currentMatchId)
            runCurrent() // Schedules the scan, but worker has not run.
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices)
            worker.runCurrent()
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices) // Publication stays on UI.
            runCurrent()
            assertEquals(listOf(0, 1, 2), delegate.searchState.matchIndices)
            assertEquals("m2", delegate.searchState.currentMatchId)
            assertEquals(2, delegate.searchState.currentIndex)
        }

    @Test
    fun `query change cancels pending rescan and clearing search ignores later messages`() =
        runTest {
            val uiState = stateWith("alpha", "beta")
            val delegate =
                ChatSearchDelegate(backgroundScope, uiState, dispatcher = StandardTestDispatcher(testScheduler))
            delegate.toggleSearch()
            delegate.setSearchQuery("alpha")
            advanceTimeBy(150)
            runCurrent()
            uiState.value =
                uiState.value.copy(messages = uiState.value.messages + ChatMessage("m2", MessageRole.USER, "alpha"))
            delegate.setSearchQuery("beta")
            runCurrent()
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(1), delegate.searchState.matchIndices)
            assertEquals("m1", delegate.searchState.currentMatchId)
            delegate.clearSearch()
            uiState.value =
                uiState.value.copy(messages = uiState.value.messages + ChatMessage("m3", MessageRole.USER, "beta"))
            runCurrent()
            assertTrue(delegate.searchState.matchIndices.isEmpty())
            assertNull(delegate.searchState.currentMatchId)
        }

    @Test
    fun `queue and recovery rows do not shift visible search indices or navigation`() =
        runTest {
            val uiState = stateWith("needle queued", "needle parked", "needle unknown", "needle server", "needle next")
            uiState.value =
                uiState.value.copy(
                    messages = uiState.value.messages.map { if (it.id == "m3") it.copy(restId = "42") else it },
                    pendingSends =
                        listOf(
                            PendingSend("m0", "scope", "session", "needle queued", mode = BusySendMode.QUEUE),
                            PendingSend(
                                "m1",
                                "scope",
                                "session",
                                "needle parked",
                                mode = BusySendMode.QUEUE,
                                state = PendingSendState.PARKED,
                            ),
                            PendingSend(
                                "m2",
                                "scope",
                                "session",
                                "needle unknown",
                                mode = BusySendMode.QUEUE,
                                state = PendingSendState.UNKNOWN,
                            ),
                            PendingSend("m3", "scope", "session", "needle server", mode = BusySendMode.QUEUE),
                        ),
                )
            val delegate =
                ChatSearchDelegate(backgroundScope, uiState, dispatcher = StandardTestDispatcher(testScheduler))
            delegate.setSearchQuery("needle")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(0, 1), delegate.searchState.matchIndices)
            assertEquals(setOf("m3", "m4"), delegate.searchState.matchedIds)
            assertEquals("m3", delegate.searchState.currentMatchId)
            delegate.navigateSearchMatch(1)
            assertEquals("m4", delegate.searchState.currentMatchId)
            delegate.navigateSearchMatch(-1)
            assertEquals("m3", delegate.searchState.currentMatchId)
            assertEquals(5, uiState.value.messages.size)
        }

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
    fun `stale worker result cannot publish after query changes`() =
        runTest {
            val worker = ManualWorkerDispatcher()
            val uiState = stateWith("alpha", "beta")
            val delegate =
                ChatSearchDelegate(
                    scope = backgroundScope,
                    uiState = uiState,
                    dispatcher = worker,
                    debounceMs = 150,
                )

            delegate.setSearchQuery("alpha")
            advanceTimeBy(150)
            runCurrent() // Debounce completes on the caller scope; search is waiting on the worker.
            delegate.setSearchQuery("beta")
            worker.runCurrent() // The cancelled alpha scan must not publish.
            runCurrent()
            assertEquals("beta", delegate.searchState.query)
            assertTrue(delegate.searchState.matchIndices.isEmpty())

            advanceTimeBy(150)
            runCurrent()
            worker.runCurrent()
            runCurrent()
            assertEquals(listOf(1), delegate.searchState.matchIndices)
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
