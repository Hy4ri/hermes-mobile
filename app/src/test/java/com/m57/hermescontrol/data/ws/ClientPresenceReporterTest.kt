package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.ClientActivityParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPresenceReporterTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<ClientActivityParams>()
    private val reporter = ClientPresenceReporter(clock = { now }, send = { sent += it })

    @Test
    fun sendsNothingUntilTheBackendAdvertisesClientActivity() {
        reporter.setForeground(true)
        reporter.setSessions(listOf("s1"))
        reporter.onInteraction()
        assertTrue(sent.isEmpty())

        reporter.setSupported(true)
        assertEquals(1, sent.size)
        assertEquals(listOf("s1"), sent.single().sessions)
        assertEquals("page", sent.single().basis)
        assertEquals(false, sent.single().away)
    }

    @Test
    fun reportsOnForegroundSwitchAndSessionChange() {
        reporter.setSupported(true)
        sent.clear()
        reporter.setForeground(true)
        reporter.setSessions(listOf("a"))
        reporter.setSessions(listOf("a"))
        reporter.setSessions(listOf("b"))
        reporter.setForeground(false)
        assertEquals(listOf(false, false, false, true), sent.map { it.away })
        assertEquals(listOf(emptyList(), listOf("a"), listOf("b"), listOf("b")), sent.map { it.sessions })
    }

    @Test
    fun interactionReportsAtMostOncePerMinuteWhileForeground() {
        reporter.setSupported(true)
        reporter.setForeground(true)
        sent.clear()
        now += 5_000
        reporter.onInteraction()
        assertTrue(sent.isEmpty())
        now += ACTIVITY_REPORT_INTERVAL_MS
        reporter.onInteraction()
        assertEquals(1, sent.size)
        assertEquals(0.0, sent.single().idleSeconds, 0.0)
        now += 1_000
        reporter.onInteraction()
        assertEquals(1, sent.size)
    }

    @Test
    fun interactionInBackgroundIsNotReportedAndIdleCountsFromLastInput() {
        reporter.setSupported(true)
        reporter.setForeground(true)
        now += 30_000
        sent.clear()
        reporter.setCanAlert(false)
        assertEquals(30.0, sent.single().idleSeconds, 0.0)
        assertEquals(false, sent.single().canAlert)
        reporter.setForeground(false)
        sent.clear()
        reporter.onInteraction()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun capsSessionsAtSixteen() {
        reporter.setSupported(true)
        sent.clear()
        reporter.setSessions((1..20).map { "s$it" })
        assertEquals(16, sent.single().sessions.size)
    }

    @Test
    fun identityVersionIsPrintableAsciiWithoutSpacesAndAtMost64() {
        val label = clientIdentity("1.2 3\u00e9-beta\n" + "x".repeat(100))
        assertEquals("hermes-mobile", label.name)
        assertEquals("mobile", label.kind)
        val version = label.version!!
        assertEquals(64, version.length)
        assertTrue(version.startsWith("1.23-beta"))
        assertTrue(version.all { it in '!'..'~' })
        assertNull(clientIdentity(" \u00e9").version)
    }
}
