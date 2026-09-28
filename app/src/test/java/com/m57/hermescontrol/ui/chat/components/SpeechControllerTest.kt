package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechControllerTest {
    private class FakeAudio(
        val tag: String,
    ) : SpeechAudio {
        var isDisposed: Boolean = false
            private set

        override fun dispose() {
            isDisposed = true
        }
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        var calls: MutableList<SpeechRequest> = mutableListOf()
        var synthesizeBlock: (suspend (SpeechRequest) -> SpeechAudio)? = null

        override suspend fun synthesize(request: SpeechRequest): SpeechAudio {
            calls.add(request)
            return synthesizeBlock?.invoke(request) ?: FakeAudio(request.text)
        }
    }

    private class FakePlayer : SpeechPlayer {
        var currentlyPlaying: SpeechAudio? = null
        var lastEndedCallback: (() -> Unit)? = null
        var lastErrorCallback: (() -> Unit)? = null
        var playCallCount: Int = 0
        var stopCallCount: Int = 0
        var playThrows: Throwable? = null

        override fun play(
            audio: SpeechAudio,
            onEnded: () -> Unit,
            onError: () -> Unit,
        ) {
            playCallCount++
            currentlyPlaying = audio
            lastEndedCallback = onEnded
            lastErrorCallback = onError
            playThrows?.let { throw it }
        }

        override fun stop() {
            stopCallCount++
            currentlyPlaying = null
            lastEndedCallback = null
            lastErrorCallback = null
        }
    }

    private class Fixture(
        val scope: TestScope,
    ) {
        val synthesizer = FakeSynthesizer()
        val player = FakePlayer()
        val errors = mutableListOf<Unit>()

        val controller =
            SpeechController(
                scope = scope,
                synthesizer = synthesizer,
                player = player,
                onError = { errors.add(Unit) },
            )
    }

    @Test
    fun `toggle with blank text is a no-op`() =
        runTest {
            val fixture = Fixture(this)
            val blankReq = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "   ")

            fixture.controller.toggle(blankReq)
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.synthesizer.calls.size)
            assertEquals(0, fixture.player.playCallCount)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `toggle with empty text is a no-op`() =
        runTest {
            val fixture = Fixture(this)
            val emptyReq = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "")

            fixture.controller.toggle(emptyReq)
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.synthesizer.calls.size)
            assertEquals(0, fixture.player.playCallCount)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `toggle synthesizes and plays audio, setting speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")

            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(req, fixture.synthesizer.calls[0])
            assertEquals(1, fixture.player.playCallCount)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `toggle on same active request stops playback and clears speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")

            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)

            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertTrue(fixture.player.stopCallCount >= 1)
        }

    @Test
    fun `toggle on same active request while still synthesizing stops and cancels synthesis`() =
        runTest {
            val fixture = Fixture(this)
            val gate = CompletableDeferred<SpeechAudio>()
            fixture.synthesizer.synthesizeBlock = { gate.await() }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")
            fixture.controller.toggle(req)
            runCurrent()

            // Toggle same request while synthesis is inflight
            fixture.controller.toggle(req)
            runCurrent()

            assertNull(fixture.controller.speakingId.value)

            // Even if synthesis completes later, generation fencing ensures it never plays
            val audio = FakeAudio("Hello world")
            gate.complete(audio)
            advanceUntilIdle()

            assertEquals(0, fixture.player.playCallCount)
            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `noncooperative synthesis cancelled during synthesis still disposes returned audio without playing`() =
        runTest {
            val fixture = Fixture(this)
            val gate = CompletableDeferred<Unit>()
            val resumeGate = CompletableDeferred<Unit>()
            val audio = FakeAudio("Stale noncooperative audio")

            fixture.synthesizer.synthesizeBlock = {
                try {
                    gate.await()
                } catch (t: Throwable) {
                    withContext(NonCancellable) {
                        resumeGate.await()
                    }
                }
                audio
            }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First text")
            fixture.controller.toggle(req)
            runCurrent()

            // Cancel by stopping
            fixture.controller.stop()
            runCurrent()
            assertNull(fixture.controller.speakingId.value)

            // Now let noncooperative synthesis produce audio after cancellation
            resumeGate.complete(Unit)
            advanceUntilIdle()

            // Audio must be disposed, never played, no error emitted, and speakingId remains null
            assertTrue(audio.isDisposed)
            assertEquals(0, fixture.player.playCallCount)
            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `noncooperative synthesis superseded by replacement disposes stale audio without touching newer state`() =
        runTest {
            val fixture = Fixture(this)
            val gate1 = CompletableDeferred<Unit>()
            val resumeGate1 = CompletableDeferred<Unit>()
            val audio1 = FakeAudio("Stale noncooperative audio 1")
            val audio2 = FakeAudio("New active audio 2")

            fixture.synthesizer.synthesizeBlock = { req ->
                if (req.messageId == "msg-1") {
                    try {
                        gate1.await()
                    } catch (t: Throwable) {
                        withContext(NonCancellable) {
                            resumeGate1.await()
                        }
                    }
                    audio1
                } else {
                    audio2
                }
            }

            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First text")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second text")

            fixture.controller.toggle(req1)
            runCurrent()

            // Supersede with req2
            fixture.controller.toggle(req2)
            advanceUntilIdle()

            // req2 should now be playing
            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertEquals(1, fixture.player.playCallCount)

            // Now release stale noncooperative synthesis from req1
            resumeGate1.complete(Unit)
            advanceUntilIdle()

            // Stale audio1 must be disposed, no extra play calls, speakingId untouched, no errors
            assertTrue(audio1.isDisposed)
            assertFalse(audio2.isDisposed)
            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertEquals(1, fixture.player.playCallCount)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `noncooperative synthesis throwing failure after replacement is ignored and does not invoke onError`() =
        runTest {
            val fixture = Fixture(this)
            val gate1 = CompletableDeferred<Unit>()
            val resumeGate1 = CompletableDeferred<Unit>()
            val audio2 = FakeAudio("Active audio 2")

            fixture.synthesizer.synthesizeBlock = { req ->
                if (req.messageId == "msg-1") {
                    try {
                        gate1.await()
                    } catch (t: Throwable) {
                        withContext(NonCancellable) {
                            resumeGate1.await()
                        }
                    }
                    throw IllegalStateException("Stale synthesis explosion")
                } else {
                    audio2
                }
            }

            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First text")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second text")

            fixture.controller.toggle(req1)
            runCurrent()

            // Replace with req2
            fixture.controller.toggle(req2)
            advanceUntilIdle()

            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)

            // Release stale failure
            resumeGate1.complete(Unit)
            advanceUntilIdle()

            // Stale failure must not invoke onError or clear msg-2
            assertEquals(0, fixture.errors.size)
            assertEquals("msg-2", fixture.controller.speakingId.value)
        }

    @Test
    fun `stop and restart race on same request ensures stale inflight completion does not disrupt new cycle`() =
        runTest {
            val fixture = Fixture(this)
            val gate1 = CompletableDeferred<Unit>()
            val resumeGate1 = CompletableDeferred<Unit>()
            val audio1 = FakeAudio("Cycle 1 audio")
            val audio2 = FakeAudio("Cycle 2 audio")

            var callIndex = 0
            fixture.synthesizer.synthesizeBlock = {
                val idx = ++callIndex
                if (idx == 1) {
                    try {
                        gate1.await()
                    } catch (t: Throwable) {
                        withContext(NonCancellable) {
                            resumeGate1.await()
                        }
                    }
                    audio1
                } else {
                    audio2
                }
            }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Same text")

            // Cycle 1: start synthesis
            fixture.controller.toggle(req)
            runCurrent()

            // Toggle same request while synthesizing -> cancels cycle 1
            fixture.controller.toggle(req)
            runCurrent()
            assertNull(fixture.controller.speakingId.value)

            // Cycle 2: restart SAME request immediately
            fixture.controller.toggle(req)
            advanceUntilIdle()

            // Cycle 2 has synthesized audio2 and is playing it
            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertEquals(1, fixture.player.playCallCount)

            // Stale cycle 1 finally returns audio1
            resumeGate1.complete(Unit)
            advanceUntilIdle()

            // Stale audio1 must be disposed and cycle 2 remains active and playing audio2
            assertTrue(audio1.isDisposed)
            assertFalse(audio2.isDisposed)
            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertEquals(1, fixture.player.playCallCount)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `stop stops playback and clears speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")

            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)

            val stopCallsBefore = fixture.player.stopCallCount
            fixture.controller.stop()
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertTrue(fixture.player.stopCallCount > stopCallsBefore)
        }

    @Test
    fun `replacement immediately stops active playback and cancels old request`() =
        runTest {
            val fixture = Fixture(this)
            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First text")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second text")

            fixture.controller.toggle(req1)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)

            fixture.controller.toggle(req2)
            advanceUntilIdle()

            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(2, fixture.synthesizer.calls.size)
            assertEquals(2, fixture.player.playCallCount)
        }

    @Test
    fun `replacement cancels inflight synthesis and generation fencing rejects stale completion`() =
        runTest {
            val fixture = Fixture(this)
            val gate1 = CompletableDeferred<SpeechAudio>()
            val gate2 = CompletableDeferred<SpeechAudio>()

            fixture.synthesizer.synthesizeBlock = { req ->
                if (req.messageId == "msg-1") gate1.await() else gate2.await()
            }

            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First text")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second text")

            fixture.controller.toggle(req1)
            runCurrent()

            fixture.controller.toggle(req2)
            runCurrent()

            // Complete old stale synthesis
            val audio1 = FakeAudio("First text")
            gate1.complete(audio1)
            runCurrent()

            // Complete new synthesis
            val audio2 = FakeAudio("Second text")
            gate2.complete(audio2)
            advanceUntilIdle()

            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(1, fixture.player.playCallCount)
            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `player onEnded callback resets speakingId to null`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")

            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)

            val onEnded = fixture.player.lastEndedCallback
            onEnded?.invoke()
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `stale onEnded callback from superseded generation does not clear active speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second")

            fixture.controller.toggle(req1)
            advanceUntilIdle()
            val staleOnEnded = fixture.player.lastEndedCallback

            fixture.controller.toggle(req2)
            advanceUntilIdle()
            assertEquals("msg-2", fixture.controller.speakingId.value)

            staleOnEnded?.invoke()
            advanceUntilIdle()

            // Still speaking msg-2!
            assertEquals("msg-2", fixture.controller.speakingId.value)
        }

    @Test
    fun `stale onError callback from superseded generation is ignored and does not invoke onError`() =
        runTest {
            val fixture = Fixture(this)
            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "First")
            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Second")

            fixture.controller.toggle(req1)
            advanceUntilIdle()
            val staleOnError = fixture.player.lastErrorCallback

            fixture.controller.toggle(req2)
            advanceUntilIdle()
            assertEquals("msg-2", fixture.controller.speakingId.value)

            staleOnError?.invoke()
            advanceUntilIdle()

            assertEquals("msg-2", fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `player onError callback clears speakingId and triggers onError`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")

            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)

            val onError = fixture.player.lastErrorCallback
            onError?.invoke()
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(1, fixture.errors.size)
        }

    @Test
    fun `synthesizer failure triggers onError and clears speakingId`() =
        runTest {
            val fixture = Fixture(this)
            fixture.synthesizer.synthesizeBlock = {
                throw IllegalStateException("Synthesis failed")
            }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")
            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(1, fixture.errors.size)
            assertEquals(0, fixture.player.playCallCount)
        }

    @Test
    fun `cancellations during synthesis do not trigger onError`() =
        runTest {
            val fixture = Fixture(this)
            val gate = CompletableDeferred<SpeechAudio>()
            fixture.synthesizer.synthesizeBlock = { gate.await() }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello world")
            fixture.controller.toggle(req)
            runCurrent()

            // Stop cancels the inflight coroutine
            fixture.controller.stop()
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)
        }

    @Test
    fun `replaying same request uses cached audio without re-synthesizing and stop preserves cache`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Cached audio")

            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(1, fixture.player.playCallCount)

            // Stop playback — must preserve replay cache
            fixture.controller.stop()
            advanceUntilIdle()
            assertNull(fixture.controller.speakingId.value)

            // Toggle again with same request -> should replay from cache without re-synthesizing
            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(2, fixture.player.playCallCount)
        }

    @Test
    fun `request with different text invalidates cache and disposes old audio`() =
        runTest {
            val fixture = Fixture(this)
            val audio1 = FakeAudio("Text 1")
            val audio2 = FakeAudio("Text 2")
            fixture.synthesizer.synthesizeBlock = { req ->
                if (req.text == "Text 1") audio1 else audio2
            }

            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Text 1")
            fixture.controller.toggle(req1)
            advanceUntilIdle()
            assertEquals(audio1, fixture.player.currentlyPlaying)
            assertFalse(audio1.isDisposed)

            val req2 = SpeechRequest(scopeKey = "session-1", messageId = "msg-2", text = "Text 2")
            fixture.controller.toggle(req2)
            advanceUntilIdle()

            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertTrue(audio1.isDisposed)
            assertEquals(2, fixture.synthesizer.calls.size)
        }

    @Test
    fun `request with different scope invalidates cache and disposes old audio`() =
        runTest {
            val fixture = Fixture(this)
            val audio1 = FakeAudio("Scope 1 audio")
            val audio2 = FakeAudio("Scope 2 audio")
            fixture.synthesizer.synthesizeBlock = { req ->
                if (req.scopeKey == "session-1") audio1 else audio2
            }

            val req1 = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Hello")
            fixture.controller.toggle(req1)
            advanceUntilIdle()
            assertFalse(audio1.isDisposed)

            val req2 = SpeechRequest(scopeKey = "session-2", messageId = "msg-1", text = "Hello")
            fixture.controller.toggle(req2)
            advanceUntilIdle()

            assertEquals(audio2, fixture.player.currentlyPlaying)
            assertTrue(audio1.isDisposed)
            assertEquals(2, fixture.synthesizer.calls.size)
        }

    @Test
    fun `replaying cached request when player throws does not escape toggle and clears speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Cached text")

            // Initial successful playback
            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(1, fixture.player.playCallCount)

            // Complete initial playback
            fixture.player.lastEndedCallback?.invoke()
            advanceUntilIdle()
            assertNull(fixture.controller.speakingId.value)
            assertEquals(0, fixture.errors.size)

            // Configure player to throw synchronously on replay
            val stopCallsBeforeReplay = fixture.player.stopCallCount
            fixture.player.playThrows = IllegalStateException("Player init failed")

            // Replay exact cached request
            fixture.controller.toggle(req)
            advanceUntilIdle()

            // Must NOT escape toggle, speakingId null, onError exactly once,
            // partially initialized player stopped, no resynthesis
            assertNull(fixture.controller.speakingId.value)
            assertEquals(1, fixture.errors.size)
            assertTrue(fixture.player.stopCallCount > stopCallsBeforeReplay)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(2, fixture.player.playCallCount)

            // Replay cache is retained: if player recovers, replaying exact request does not re-synthesize
            fixture.player.playThrows = null
            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(3, fixture.player.playCallCount)
            assertEquals(1, fixture.errors.size)
        }

    @Test
    fun `initial play when player throws does not escape toggle and clears speakingId`() =
        runTest {
            val fixture = Fixture(this)
            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Initial throw text")

            fixture.player.playThrows = IllegalStateException("Player failed synchronously on first play")

            // Initial noncached play throw
            fixture.controller.toggle(req)
            advanceUntilIdle()

            // Must NOT escape toggle, speakingId null, onError exactly once, stopped partially initialized player
            assertNull(fixture.controller.speakingId.value)
            assertEquals(1, fixture.errors.size)
            assertTrue(fixture.player.stopCallCount >= 1)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(1, fixture.player.playCallCount)

            // Replay cache retained: if player recovers, next toggle on exact
            // request uses cached audio without re-synthesizing
            fixture.player.playThrows = null
            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertEquals(1, fixture.synthesizer.calls.size)
            assertEquals(2, fixture.player.playCallCount)
            assertEquals(1, fixture.errors.size)
        }

    @Test
    fun `reset clears speakingId, stops player, and disposes cached audio`() =
        runTest {
            val fixture = Fixture(this)
            val audio = FakeAudio("Reset audio")
            fixture.synthesizer.synthesizeBlock = { audio }

            val req = SpeechRequest(scopeKey = "session-1", messageId = "msg-1", text = "Reset audio")
            fixture.controller.toggle(req)
            advanceUntilIdle()
            assertEquals("msg-1", fixture.controller.speakingId.value)
            assertFalse(audio.isDisposed)

            fixture.controller.reset()
            advanceUntilIdle()

            assertNull(fixture.controller.speakingId.value)
            assertTrue(audio.isDisposed)
            assertTrue(fixture.player.stopCallCount >= 1)

            // Re-toggling same request after reset must re-synthesize because cache was cleared/disposed
            val audio2 = FakeAudio("Reset audio 2")
            fixture.synthesizer.synthesizeBlock = { audio2 }
            fixture.controller.toggle(req)
            advanceUntilIdle()

            assertEquals(2, fixture.synthesizer.calls.size)
            assertEquals("msg-1", fixture.controller.speakingId.value)
        }
}
