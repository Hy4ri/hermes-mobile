package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelExtTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class TestViewModel : ViewModel()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testNormalSwrCacheHitInvokesOnCacheHitImmediately() =
        testScope.runTest {
            val vm = TestViewModel()
            val cache = SwrCache<String, String>()
            val scope = DataScope("default", "http://server", "main", 0L)
            val scopedKey = scope.scopedKey("default")
            cache.put(scopedKey, "cached-val")

            var cacheHit: String? = null
            var success: String? = null

            vm.safeLaunchSwrLoad(
                cache = cache,
                requestScope = scope,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Success("fresh-val") },
                onStart = {},
                onSuccess = { success = it },
                onError = {},
            )

            // Cache hit happens synchronously before coroutine dispatches
            assertEquals("cached-val", cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("fresh-val", success)
            assertEquals("fresh-val", cache.get(scopedKey))
        }

    @Test
    fun testForceRefreshBypassesCacheHitAndEmitsErrors() =
        testScope.runTest {
            val vm = TestViewModel()
            val cache = SwrCache<String, String>()
            val scope = DataScope("default", "http://server", "main", 0L)
            val scopedKey = scope.scopedKey("default")
            cache.put(scopedKey, "cached-val")

            var cacheHit: String? = null
            var errorReceived: String? = null

            vm.safeLaunchSwrLoad(
                cache = cache,
                forceRefresh = true,
                requestScope = scope,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Failure(NetworkError.Http(500, "Server Error")) },
                onStart = {},
                onSuccess = {},
                onError = { errorReceived = it },
            )

            // Force refresh does NOT hit cache
            assertEquals(null, cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Server Error", errorReceived)
        }

    @Test
    fun testSilentBackgroundFailurePreservesCachedContent() =
        testScope.runTest {
            val vm = TestViewModel()
            val cache = SwrCache<String, String>()
            val scope = DataScope("default", "http://server", "main", 0L)
            val scopedKey = scope.scopedKey("default")
            cache.put(scopedKey, "cached-val")

            var cacheHit: String? = null
            var errorReceived: String? = null

            vm.safeLaunchSwrLoad(
                cache = cache,
                forceRefresh = false,
                requestScope = scope,
                scopeIsCurrent = { true },
                onCacheHit = { cacheHit = it },
                apiCall = { NetworkResult.Failure(NetworkError.Http(500, "Server Error")) },
                onStart = {},
                onSuccess = {},
                onError = { errorReceived = it },
            )

            assertEquals("cached-val", cacheHit)

            testDispatcher.scheduler.advanceUntilIdle()
            // Error is suppressed on silent revalidation failure
            assertNull(errorReceived)
            assertEquals("cached-val", cache.get(scopedKey))
        }

    @Test
    fun testForcedRefreshSupersedesActiveJob() =
        testScope.runTest {
            val vm = TestViewModel()
            val cache = SwrCache<String, String>()
            val scope = DataScope("default", "http://server", "main", 0L)

            val deferred1 = CompletableDeferred<NetworkResult<String>>()
            val deferred2 = CompletableDeferred<NetworkResult<String>>()

            var req1Success = false
            var req2Success = false

            val job1 =
                vm.safeLaunchSwrLoad(
                    cache = cache,
                    forceRefresh = false,
                    requestScope = scope,
                    scopeIsCurrent = { true },
                    onCacheHit = {},
                    apiCall = { deferred1.await() },
                    onStart = {},
                    onSuccess = { req1Success = true },
                    onError = {},
                )

            val job2 =
                vm.safeLaunchSwrLoad(
                    cache = cache,
                    forceRefresh = true,
                    currentJob = job1,
                    requestScope = scope,
                    scopeIsCurrent = { true },
                    onCacheHit = {},
                    apiCall = { deferred2.await() },
                    onStart = {},
                    onSuccess = { req2Success = true },
                    onError = {},
                )

            assertTrue(job1.isCancelled)
            assertFalse(job2.isCancelled)

            deferred2.complete(NetworkResult.Success("resp-2"))
            deferred1.complete(NetworkResult.Success("resp-1"))

            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(req2Success)
            assertFalse(req1Success)
        }
}
