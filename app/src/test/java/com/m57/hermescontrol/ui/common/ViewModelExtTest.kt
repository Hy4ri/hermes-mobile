package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelExtTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestViewModel : ViewModel()

    @Test
    fun `safeLaunchAction triggers onStart, onSuccess and onComplete on success`() =
        runTest(testDispatcher) {
            val vm = TestViewModel()
            var started = false
            var successResult: String? = null
            var completed = false

            vm.safeLaunchAction(
                onStart = { started = true },
                apiCall = { NetworkResult.Success("ok") },
                onSuccess = { successResult = it },
                onError = {},
                onComplete = { completed = true },
            )

            assertTrue(started)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("ok", successResult)
            assertTrue(completed)
        }

    @Test
    fun `safeLaunchAction triggers onError and onComplete on failure`() =
        runTest(testDispatcher) {
            val vm = TestViewModel()
            var errorMessage: String? = null
            var completed = false

            vm.safeLaunchAction(
                apiCall = { NetworkResult.Failure(NetworkError.Unknown("network error", Exception("boom"))) },
                onSuccess = {},
                onError = { errorMessage = it },
                onComplete = { completed = true },
            )

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("network error", errorMessage)
            assertTrue(completed)
        }
}
