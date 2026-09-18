package com.m57.hermescontrol.data.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural proof that PR #1192's caches are partitioned by [DataScope].
 *
 * These tests exercise the cache invariants end-to-end through [safeLaunchSwrLoad]
 * rather than only asserting key derivation, so a regression that keeps keys
 * correct but reuses the wrong entry still fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheScopeIsolationTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class TestViewModel : ViewModel()

    /**
     * Every ViewModel created by a test. `viewModelScope` jobs are never cancelled
     * automatically, so a still-pending dispatch would hit `Dispatchers.Main` *after*
     * `resetMain()` and surface as an `UncaughtExceptionsBeforeTest` in a later test.
     * Cancel them before tearing the main dispatcher down.
     */
    private val createdViewModels = mutableListOf<TestViewModel>()

    private fun newViewModel(): TestViewModel = TestViewModel().also { createdViewModels += it }

    /** Connection A — server 192.168.1.57, Hermes profile "main". */
    private val scopeA = DataScope("conn-a", "http://192.168.1.57:9119", "main", 0L)

    /** Connection B — a different Hermes *server* entirely. */
    private val scopeB = DataScope("conn-b", "http://10.0.0.9:9119", "main", 0L)

    /** Same server as [scopeA], different server-side Hermes profile. */
    private val scopeAOtherProfile = DataScope("conn-a", "http://192.168.1.57:9119", "work", 0L)

    /** Same connection+profile as [scopeA], but a new authenticated session (logout/login). */
    private val scopeANewSession = scopeA.copy(inMemoryAuthGeneration = 1L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    // ── Cross-context isolation ──────────────────────────────────────────────

    @Test
    fun testCacheWrittenInOneScopeIsInvisibleInAnother() {
        val cache = SwrCache<String, String>()
        cache.put(scopeA.inMemoryKey("profiles"), "server-A-profiles")

        assertNull("connection B must not see connection A's cache", cache.get(scopeB.inMemoryKey("profiles")))
        assertNull(
            "another Hermes profile on the same server must not see A's cache",
            cache.get(scopeAOtherProfile.inMemoryKey("profiles")),
        )
        assertEquals("server-A-profiles", cache.get(scopeA.inMemoryKey("profiles")))
    }

    @Test
    fun testScopeSwitchDoesNotRenderPreviousContextData() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()
            cache.put(scopeA.inMemoryKey("default"), "stale-server-A-data")

            // Screen is now rendering scope B and loads with scope B's request scope.
            var renderedFromCache: String? = null
            var renderedFromNetwork: String? = null

            vm.safeLaunchSwrLoad(
                cache = cache,
                requestScope = scopeB,
                scopeIsCurrent = { true },
                onCacheHit = { renderedFromCache = it },
                apiCall = { NetworkResult.Success("server-B-data") },
                onStart = {},
                onSuccess = { renderedFromNetwork = it },
                onError = {},
            )

            assertNull("scope B must never be served scope A's cached payload", renderedFromCache)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("server-B-data", renderedFromNetwork)
            assertEquals("server-B-data", cache.get(scopeB.inMemoryKey("default")))
            assertEquals(
                "server A's entry is untouched",
                "stale-server-A-data",
                cache.get(scopeA.inMemoryKey("default")),
            )
        }

    @Test
    fun testLogoutReloginCannotReuseAnotherSessionsInMemoryCache() {
        val cache = SwrCache<String, String>()
        cache.put(scopeA.inMemoryKey("sessions"), "session-1-confidential-titles")

        assertNull(
            "a new authenticated session must not reuse the previous session's in-memory data",
            cache.get(scopeANewSession.inMemoryKey("sessions")),
        )
    }

    @Test
    fun testPersistentKeySurvivesProcessRestartButNotServerChange() {
        // Same physical server + profile, fresh process (generation resets to 0).
        val afterRestart = scopeA.copy(inMemoryAuthGeneration = 0L)

        assertEquals(
            "disk cache must survive a normal process restart for cold-start speed",
            scopeA.persistentKey("sessions"),
            afterRestart.persistentKey("sessions"),
        )
        assertNotEquals(
            "disk cache must never bridge two different servers",
            scopeA.persistentKey("sessions"),
            scopeB.persistentKey("sessions"),
        )
    }

    // ── Same-context SWR stays instant ───────────────────────────────────────

    @Test
    fun testSameScopeRevisitRendersCacheImmediately() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()
            cache.put(scopeA.inMemoryKey("default"), "cached-for-A")

            var cacheHit: String? = null
            vm.safeLaunchSwrLoad(
                cache = cache,
                requestScope = scopeA,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Success("revalidated-for-A") },
                onStart = {},
                onSuccess = {},
                onError = {},
            )

            assertEquals("revisiting the same context must paint from cache", "cached-for-A", cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("revalidated-for-A", cache.get(scopeA.inMemoryKey("default")))
        }

    // ── Manual refresh ───────────────────────────────────────────────────────

    @Test
    fun testForceRefreshPurgesOnlyItsOwnScope() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()
            cache.put(scopeA.inMemoryKey("default"), "A-data")
            cache.put(scopeB.inMemoryKey("default"), "B-data")

            var cacheHit: String? = null
            vm.safeLaunchSwrLoad(
                cache = cache,
                forceRefresh = true,
                requestScope = scopeB,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Success("B-fresh") },
                onStart = {},
                onSuccess = {},
                onError = {},
            )

            assertNull("force refresh must not replay the cached payload", cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("B-fresh", cache.get(scopeB.inMemoryKey("default")))
            assertEquals("other scopes' entries must survive", "A-data", cache.get(scopeA.inMemoryKey("default")))
        }

    // ── Mutation reconciliation ──────────────────────────────────────────────

    @Test
    fun testSuccessfulMutationCannotReplayPreMutationCache() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()
            val key = scopeA.inMemoryKey("webhooks")

            // Pre-mutation server state.
            cache.put(key, "webhook-disabled")

            var cacheHit: String? = null
            var success: String? = null

            // Post-mutation reload: must bypass the now-stale cache entry.
            vm.safeLaunchSwrLoad(
                cache = cache,
                cacheKey = "webhooks",
                forceRefresh = true,
                requestScope = scopeA,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Success("webhook-enabled") },
                onStart = {},
                onSuccess = { success = it },
                onError = {},
            )

            assertNull("pre-mutation state must not be replayed", cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("webhook-enabled", success)
            assertEquals("webhook-enabled", cache.get(key))
        }

    @Test
    fun testMutationReloadFailureAfterForcedRefreshSurfacesError() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()
            cache.put(scopeA.inMemoryKey("default"), "pre-mutation")

            var error: String? = null
            var cacheHit: String? = null

            vm.safeLaunchSwrLoad(
                cache = cache,
                forceRefresh = true,
                requestScope = scopeA,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Failure(NetworkError.Http(500, "boom")) },
                onStart = {},
                onSuccess = {},
                onError = { error = it },
            )

            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(cacheHit)
            assertNotNull(
                "a failed post-mutation reload must not silently show pre-mutation state",
                error,
            )
        }

    // ── Stale in-flight results ──────────────────────────────────────────────

    @Test
    fun testLateResponseFromPreviousScopeCannotOverwriteNewState() =
        testScope.runTest {
            val vm = newViewModel()
            val cache = SwrCache<String, String>()

            var cacheHit: String? = null
            var success: String? = null
            var error: String? = null

            // Request dispatched while scope A was active; the scope flips to B before it returns.
            var scopeIsCurrent = true
            val job =
                vm.safeLaunchSwrLoad(
                    cache = cache,
                    requestScope = scopeA,
                    scopeIsCurrent = { scopeIsCurrent },
                    onCacheHit = { cacheHit = it },
                    apiCall = { NetworkResult.Success("server-A-data") },
                    onStart = {},
                    onSuccess = { success = it },
                    onError = { error = it },
                )

            scopeIsCurrent = false
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(job.isCompleted)
            assertNull("stale scope A response must not be rendered", success)
            assertNull(
                "stale scope A response must not enter scope A's cache",
                cache.get(scopeA.inMemoryKey("default")),
            )
            assertNull(cacheHit)
            assertNull(error)
        }

    // ── Config editor isolation ──────────────────────────────────────────────

    @Test
    fun testConfigSchemaAndDefaultsNeverCrossScopeBoundaries() {
        val schemaCache = SwrCache<String, String>(maxCapacity = 10)
        val defaultsCache = SwrCache<String, String>(maxCapacity = 10)

        val keyA = scopeA.inMemoryKey("schema")
        val keyB = scopeB.inMemoryKey("schema")

        schemaCache.put(keyA, "schema-A")
        defaultsCache.put(keyA, "defaults-A")

        assertNull("new context must not inherit another server's schema", schemaCache.get(keyB))
        assertNull("new context must not inherit another server's defaults", defaultsCache.get(keyB))

        schemaCache.put(keyB, "schema-B")
        defaultsCache.put(keyB, "defaults-B")

        assertEquals("schema-A", schemaCache.get(keyA))
        assertEquals("schema-B", schemaCache.get(keyB))
        assertEquals("defaults-A", defaultsCache.get(keyA))
        assertEquals("defaults-B", defaultsCache.get(keyB))
    }

    @Test
    fun testConfigForceRefreshDoesNotEvictOtherScopes() {
        val schemaCache = SwrCache<String, String>(maxCapacity = 10)
        val keyA = scopeA.inMemoryKey("schema")
        val keyB = scopeB.inMemoryKey("schema")
        schemaCache.put(keyA, "schema-A")
        schemaCache.put(keyB, "schema-B")

        // Manual refresh while scoped to B.
        schemaCache.remove(keyB)

        assertNull(schemaCache.get(keyB))
        assertEquals(
            "editing config on one server must not wipe another server's cache",
            "schema-A",
            schemaCache.get(keyA),
        )
    }

    // ── History pagination keys ──────────────────────────────────────────────

    @Test
    fun testHistoryPaginationKeysAreScopedPerSectionAndScope() {
        val cache = SwrCache<String, String>()
        val sectionKey = "recent:cli:false"

        cache.put(scopeA.inMemoryKey(sectionKey), "page-1-for-A")

        assertNull(cache.get(scopeB.inMemoryKey(sectionKey)))
        assertNull(cache.get(scopeAOtherProfile.inMemoryKey(sectionKey)))
        assertEquals("page-1-for-A", cache.get(scopeA.inMemoryKey(sectionKey)))
    }
}
