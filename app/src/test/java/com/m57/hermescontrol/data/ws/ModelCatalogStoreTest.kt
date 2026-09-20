package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogStoreTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val sampleScope =
        DataScope(
            connectionProfileId = "conn-1",
            baseUrl = "http://localhost:9119",
            activeProfileId = "default",
            inMemoryAuthGeneration = 1L,
        )

    private val fakeResponse =
        ModelOptionsResponse(
            providers =
                listOf(
                    ModelProvider(
                        slug = "openai",
                        name = "OpenAI",
                        models = listOf("gpt-4o"),
                        capabilities = mapOf("gpt-4o" to ModelCapabilities(fast = true)),
                    ),
                ),
        )

    @Test
    fun `two concurrent callers share single in-flight load`() =
        testScope.runTest {
            var loadCalls = 0
            val gate = CompletableDeferred<Unit>()

            val repo =
                ModelOptionsRepository(
                    connected = { false },
                    rest = {
                        loadCalls++
                        gate.await()
                        NetworkResult.Success(fakeResponse)
                    },
                )

            val store =
                ModelCatalogStore(
                    repository = repo,
                    getCurrentScope = { sampleScope },
                    scope = testScope,
                )

            coroutineScope {
                val deferred1 = async { store.ensureLoaded(forceRefresh = false) }
                val deferred2 = async { store.ensureLoaded(forceRefresh = false) }

                assertEquals(1, loadCalls)
                gate.complete(Unit)
                deferred1.await()
                deferred2.await()
            }

            val state = store.state.value
            assertEquals(1, state.providers.size)
            assertTrue(state.hasLoaded)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `fresh cache returns immediately without extra network call`() =
        testScope.runTest {
            var loadCalls = 0
            var now = 1000L

            val repo =
                ModelOptionsRepository(
                    connected = { false },
                    rest = {
                        loadCalls++
                        NetworkResult.Success(fakeResponse)
                    },
                )

            val store =
                ModelCatalogStore(
                    repository = repo,
                    getCurrentScope = { sampleScope },
                    clock = { now },
                    scope = testScope,
                    ttlMs = 60_000L,
                )

            store.ensureLoaded(forceRefresh = false)
            assertEquals(1, loadCalls)

            // Advance clock by 10s (well within 60s TTL)
            now += 10_000L
            store.ensureLoaded(forceRefresh = false)
            assertEquals(1, loadCalls)

            // Force refresh should bypass TTL
            store.ensureLoaded(forceRefresh = true)
            assertEquals(2, loadCalls)
        }

    @Test
    fun `forced refresh supersedes normal load without cancelling existing callers`() =
        testScope.runTest {
            val normalGate = CompletableDeferred<Unit>()
            val forcedGate = CompletableDeferred<Unit>()
            val requests = mutableListOf<Boolean>()

            val forcedResponse =
                ModelOptionsResponse(
                    providers =
                        listOf(
                            ModelProvider(
                                slug = "anthropic",
                                name = "Anthropic",
                                models = listOf("claude-sonnet"),
                                capabilities = emptyMap(),
                            ),
                        ),
                )

            val repo =
                ModelOptionsRepository(
                    connected = { false },
                    rest = { refresh ->
                        requests += refresh
                        if (refresh) {
                            forcedGate.await()
                            NetworkResult.Success(forcedResponse)
                        } else {
                            normalGate.await()
                            NetworkResult.Success(fakeResponse)
                        }
                    },
                )

            val store =
                ModelCatalogStore(
                    repository = repo,
                    getCurrentScope = { sampleScope },
                    scope = testScope,
                )

            coroutineScope {
                val normalCaller = async { store.ensureLoaded(forceRefresh = false) }
                assertEquals(listOf(false), requests)

                val forcedCaller = async { store.ensureLoaded(forceRefresh = true) }
                assertEquals(listOf(false, true), requests)

                // The superseded physical normal request may finish, but its
                // callers remain attached to the logical request and wait for
                // the authoritative forced result.
                normalGate.complete(Unit)
                assertFalse(normalCaller.isCancelled)
                assertFalse(normalCaller.isCompleted)

                forcedGate.complete(Unit)

                val normalResult = normalCaller.await() as NetworkResult.Success
                val forcedResult = forcedCaller.await() as NetworkResult.Success

                assertEquals(forcedResponse.providers, normalResult.data.providers)
                assertEquals(forcedResponse.providers, forcedResult.data.providers)
            }

            assertEquals(forcedResponse.providers, store.state.value.providers)
            assertTrue(store.state.value.hasLoaded)
            assertFalse(store.state.value.isRefreshing)
        }

    @Test
    fun `scope change invalidates cached state and cancels in-flight work`() =
        testScope.runTest {
            var currentScope = sampleScope
            val repo =
                ModelOptionsRepository(
                    connected = { false },
                    rest = { NetworkResult.Success(fakeResponse) },
                )

            val store =
                ModelCatalogStore(
                    repository = repo,
                    getCurrentScope = { currentScope },
                    scope = testScope,
                )

            store.ensureLoaded(forceRefresh = false)
            assertTrue(store.state.value.hasLoaded)
            assertEquals(1, store.state.value.providers.size)

            // Switch scope
            val otherScope = sampleScope.copy(activeProfileId = "work")
            currentScope = otherScope
            store.onScopeChanged(otherScope)

            assertEquals(0, store.state.value.providers.size)
            assertFalse(store.state.value.hasLoaded)
            assertEquals(otherScope, store.state.value.scope)
        }
}
