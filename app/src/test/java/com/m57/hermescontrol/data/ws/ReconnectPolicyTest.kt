package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun defaultInitialBackoffIs1000() {
        val policy = ReconnectPolicy()
        assertEquals(1000L, policy.currentBackoff)
    }

    @Test
    fun nextDelayMsUsesInjectedRandomAndFloorsAt300() {
        // With random = 0.0, calculated delay is 0, floored to 300 (or ceiling if ceiling < 300)
        val minPolicy = ReconnectPolicy(random = { 0.0 })
        assertEquals(300L, minPolicy.nextDelayMs())

        // With random = 0.5 and ceiling = 1000, 0.5 * 1000 = 500
        val midPolicy = ReconnectPolicy(random = { 0.5 })
        assertEquals(500L, midPolicy.nextDelayMs())

        // With random nearly 1.0 (0.999), 0.999 * 1000 = 999
        val maxPolicy = ReconnectPolicy(random = { 0.999 })
        assertEquals(999L, maxPolicy.nextDelayMs())
    }

    @Test
    fun nextDelayMsRespectsCeilingCapsBelowFloorAndZero() {
        // Ceiling <= 0 results in 0
        val zeroPolicy = ReconnectPolicy(random = { 0.5 })
        zeroPolicy.currentBackoff = 0L
        assertEquals(0L, zeroPolicy.nextDelayMs())

        val negativePolicy = ReconnectPolicy(random = { 0.5 })
        negativePolicy.currentBackoff = -100L
        assertEquals(0L, negativePolicy.nextDelayMs())

        // When ceiling is 100 (which is < 300 floor), delay should be capped to ceiling (100)
        val lowCeilingPolicy = ReconnectPolicy(random = { 0.0 })
        lowCeilingPolicy.currentBackoff = 100L
        assertEquals(100L, lowCeilingPolicy.nextDelayMs())
    }

    @Test
    fun advanceDoublesBackoffUpToCapOf30000() {
        val policy = ReconnectPolicy()
        assertEquals(1000L, policy.currentBackoff)

        policy.advance()
        assertEquals(2000L, policy.currentBackoff)

        policy.advance()
        assertEquals(4000L, policy.currentBackoff)

        policy.advance()
        assertEquals(8000L, policy.currentBackoff)

        policy.advance()
        assertEquals(16000L, policy.currentBackoff)

        // Doubles past 30000 should cap exactly at 30000 (not speculative 15000 or 32000)
        policy.advance()
        assertEquals(30000L, policy.currentBackoff)

        policy.advance()
        assertEquals(30000L, policy.currentBackoff)
    }

    @Test
    fun aServerNotReadyFailurePinsTheCeilingToTheShortLane() {
        // 5xx from the ticket endpoint = the server is up but not ready (restarting gateway).
        // Doubling there is what kept the client away ~20 s after the gateway was serving again.
        val policy = ReconnectPolicy()

        policy.advance(serverNotReady = true)
        assertEquals(3_000L, policy.currentBackoff)

        // Repeated 5xx stay on the short ceiling instead of walking to the 30 s lane.
        repeat(6) { policy.advance(serverNotReady = true) }
        assertEquals(3_000L, policy.currentBackoff)
    }

    @Test
    fun aServerNotReadyFailurePullsAnExistingLongBackoffBackDown() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.advance() } // transport errors: 1s→2s→4s→8s→16s→30s
        assertEquals(30_000L, policy.currentBackoff)

        policy.advance(serverNotReady = true)
        assertEquals(3_000L, policy.currentBackoff)
    }

    @Test
    fun transportFailuresStillDoubleAfterAServerNotReadyFailure() {
        // The two modes must not leak into each other: once the endpoint answers a transport error,
        // the client earns the normal exponential lane again.
        val policy = ReconnectPolicy()
        policy.advance(serverNotReady = true)
        assertEquals(3_000L, policy.currentBackoff)

        policy.advance()
        assertEquals(6_000L, policy.currentBackoff)
    }

    @Test
    fun theServerNotReadyCeilingKeepsTheNextDelayUnderThreeSeconds() {
        val policy = ReconnectPolicy(random = { 0.999 })
        policy.advance(serverNotReady = true)

        // 0.999 * 3000 = 2997: the wait a booting gateway sees never exceeds the short ceiling.
        assertEquals(2_997L, policy.nextDelayMs())
    }

    @Test
    fun resetRestoresInitialBackoffOf1000() {
        val policy = ReconnectPolicy()
        policy.advance()
        policy.advance()
        assertEquals(4000L, policy.currentBackoff)

        policy.reset()
        assertEquals(1000L, policy.currentBackoff)
    }

    @Test
    fun setBackoffForTestOverridesCurrentAndInitialBackoffDeterministically() {
        val policy = ReconnectPolicy(random = { 0.5 })
        policy.setBackoffForTest(1500L)

        assertEquals(1500L, policy.currentBackoff)
        // With test override, delay should be deterministic override value
        assertEquals(1500L, policy.nextDelayMs())

        // advance() still uses deterministic override if set, or advances from set initial/current
        policy.advance()
        assertEquals(1500L, policy.nextDelayMs())

        // reset() retains deterministic override behavior or resets to overridden initial
        policy.reset()
        assertEquals(1500L, policy.nextDelayMs())
    }
}
