package com.m57.hermescontrol.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceLifecycleTest {
    @Test
    fun `stop before creation waits for promotion`() {
        val lifecycle = ForegroundServiceLifecycle()
        val calls = mutableListOf<String>()
        lifecycle.start { calls += "request" }
        lifecycle.stop()
        assertEquals(listOf("request"), calls)
        lifecycle.onStart(Any(), { calls += "promote" }) {
            calls += "stop"
            true
        }
        assertEquals(listOf("request", "promote", "stop"), calls)
    }

    @Test
    fun `every delivered start promotes an existing instance`() {
        val lifecycle = ForegroundServiceLifecycle()
        val owner = Any()
        var promotions = 0
        lifecycle.start {}
        repeat(2) { lifecycle.onStart(owner, { promotions++ }) { error("Unexpected stop") } }
        assertEquals(2, promotions)
    }

    @Test
    fun `a queued newer start cannot be stopped with an older start id`() {
        val lifecycle = ForegroundServiceLifecycle()
        val calls = mutableListOf<String>()
        val owner = Any()
        lifecycle.start {}
        lifecycle.onStart(owner, { calls += "promote1" }) {
            calls += "stop1-rejected"
            false // Android stopSelfResult rejects an ID older than its latest queued start.
        }
        lifecycle.start {}
        lifecycle.stop()
        lifecycle.onStart(owner, { calls += "promote2" }) {
            calls += "stop2"
            true
        }
        assertEquals(listOf("promote1", "stop1-rejected", "promote2", "stop2"), calls)
    }

    @Test
    fun `delayed completion cannot retire a newer request`() {
        val lifecycle = ForegroundServiceLifecycle()
        var stops = 0
        lifecycle.start {}
        lifecycle.onStart(Any(), {}) {
            stops++
            true
        }
        val oldGeneration = lifecycle.generation
        lifecycle.start {}
        lifecycle.complete(oldGeneration)
        assertEquals(0, stops)
        lifecycle.complete(lifecycle.generation)
        assertEquals(1, stops)
    }

    @Test
    fun `stop followed by a new start before creation keeps service alive`() {
        val lifecycle = ForegroundServiceLifecycle()
        lifecycle.start {}
        lifecycle.stop()
        lifecycle.start {}
        lifecycle.onStart(Any(), {}) { error("New request must survive") }
    }

    @Test
    fun `destroying an old instance does not detach its replacement`() {
        val lifecycle = ForegroundServiceLifecycle()
        val old = Any()
        var stops = 0
        lifecycle.start {}
        lifecycle.onStart(old, {}) { true }
        lifecycle.onStart(Any(), {}) {
            stops++
            true
        }
        lifecycle.onDestroyed(old)
        lifecycle.stop()
        assertEquals(1, stops)
    }

    @Test
    fun `idle stop does not launch a service`() {
        ForegroundServiceLifecycle().stop()
    }

    @Test
    fun `rejected start preserves completion of the existing request`() {
        val lifecycle = ForegroundServiceLifecycle()
        var stops = 0
        lifecycle.start {}
        lifecycle.onStart(Any(), {}) {
            stops++
            true
        }
        val generation = lifecycle.generation
        val failure = IllegalStateException("Start rejected")
        val result = runCatching { lifecycle.start { throw failure } }
        assertEquals(failure, result.exceptionOrNull())
        lifecycle.complete(generation)
        assertEquals(1, stops)
    }
}
