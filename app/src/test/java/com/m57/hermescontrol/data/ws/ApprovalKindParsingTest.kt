package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.notification.requestNoticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalKindParsingTest {
    private fun live(payload: Map<String, Any?>) =
        EventParser.parseParams(mapOf("type" to "approval.request", "payload" to payload))

    @Test
    fun absentKindIsCommand() {
        val event = live(mapOf("command" to "ls")) as WsEvent.ApprovalRequest
        assertEquals(ApprovalKind.COMMAND, event.kind)
    }

    @Test
    fun folderAndSiteKindsKeepTheirFields() {
        val folder =
            live(
                mapOf("kind" to "access", "path" to "/data", "access" to "read_write", "risk" to "Writes files"),
            ) as WsEvent.ApprovalRequest
        assertEquals(ApprovalKind.ACCESS, folder.kind)
        assertEquals("/data", folder.path)
        assertEquals("read_write", folder.access)
        assertEquals("Writes files", folder.risk)
        val site = live(mapOf("kind" to "network", "host" to "a.com", "label" to "A")) as WsEvent.ApprovalRequest
        assertEquals(ApprovalKind.NETWORK, site.kind)
        assertEquals("a.com", site.host)
        assertEquals("A", site.label)
    }

    @Test
    fun malformedOrUnknownKindIsRefused() {
        assertTrue(live(mapOf("kind" to "access", "path" to " ")) is WsEvent.Unknown)
        assertTrue(live(mapOf("kind" to "network")) is WsEvent.Unknown)
        assertTrue(live(mapOf("kind" to "teleport", "command" to "ls")) is WsEvent.Unknown)
    }

    @Test
    fun serverRequestNoticeTitlesAndRefusal() {
        val ok =
            requestNoticeFor(
                WsEvent.ServerRequest("srq-1", "approval", mapOf("kind" to "network", "host" to "a.com")),
                "default",
            )
        assertNotNull(ok)
        assertEquals(ApprovalKind.NETWORK, ok!!.approvalKind)
        assertEquals("a.com", ok.preview)
        assertNull(
            requestNoticeFor(WsEvent.ServerRequest("srq-2", "approval", mapOf("kind" to "access")), "default"),
        )
    }

    @Test
    fun pausedFlagParsedFromMessageComplete() {
        val paused =
            EventParser.parseParams(
                mapOf("type" to "message.complete", "payload" to mapOf("text" to "x", "paused" to true)),
            ) as WsEvent.MessageComplete
        assertTrue(paused.paused)
        val normal =
            EventParser.parseParams(
                mapOf("type" to "message.complete", "payload" to mapOf("text" to "x")),
            ) as WsEvent.MessageComplete
        assertEquals(false, normal.paused)
    }
}
