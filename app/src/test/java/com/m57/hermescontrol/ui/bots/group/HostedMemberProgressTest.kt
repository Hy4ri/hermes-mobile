package com.m57.hermescontrol.ui.bots.group

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostedMemberProgressTest {
    @Test
    fun `timestamps use fractional Unix seconds and clamp future clock skew`() {
        assertEquals(3L, hostedAgeSeconds(1_700_000_000.25, 1_700_000_003_750))
        assertEquals(0L, hostedAgeSeconds(1_700_000_004.0, 1_700_000_003_750))
        assertNull(hostedAgeSeconds(null, 1_700_000_003_750))
        assertNull(hostedAgeSeconds(Double.NaN, 0))
        assertNull(hostedAgeSeconds(Double.POSITIVE_INFINITY, 0))
        assertNull(hostedAgeSeconds(-1.0, 0))
    }

    @Test
    fun `clock cancels and restarts with fresh time instead of retaining previous attempt`() =
        runTest {
            val ticks = mutableListOf<Long>()
            var now = 10_000L
            val collection = launch { hostedProgressClock { now }.collect { ticks += it } }
            runCurrent()
            assertEquals(listOf(10_000L), ticks)
            now = 11_000
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(10_000L, 11_000L), ticks)
            collection.cancel()
            runCurrent()
            now = 20_000
            advanceTimeBy(9_000)
            runCurrent()
            assertEquals(2, ticks.size)
            val restarted = launch { hostedProgressClock { now }.collect { ticks += it } }
            runCurrent()
            assertEquals(listOf(10_000L, 11_000L, 20_000L), ticks)
            restarted.cancel()
        }

    @Test
    fun `lifecycle stop cancels ticks and start resumes from current time`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val owner =
                    object : LifecycleOwner {
                        val registry = LifecycleRegistry.createUnsafe(this)
                        override val lifecycle: Lifecycle get() = registry
                    }
                owner.registry.currentState = Lifecycle.State.CREATED
                val ticks = mutableListOf<Long>()
                var now = 1_000L
                val job = launch { collectHostedProgress(owner.lifecycle, { now }) { ticks += it } }
                runCurrent()
                assertEquals(0, ticks.size)
                owner.registry.currentState = Lifecycle.State.STARTED
                runCurrent()
                assertEquals(listOf(1_000L), ticks)
                owner.registry.currentState = Lifecycle.State.CREATED
                runCurrent()
                now = 9_000L
                advanceTimeBy(8_000)
                runCurrent()
                assertEquals(listOf(1_000L), ticks)
                owner.registry.currentState = Lifecycle.State.STARTED
                runCurrent()
                assertEquals(listOf(1_000L, 9_000L), ticks)
                owner.registry.currentState = Lifecycle.State.DESTROYED
                runCurrent()
                now = 20_000L
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(2, ticks.size)
                job.join()
            } finally {
                Dispatchers.resetMain()
            }
        }
}
