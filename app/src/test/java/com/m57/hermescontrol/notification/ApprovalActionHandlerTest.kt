package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.contract.ApprovalRespondParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalActionHandlerTest {
    private class FakeTransport(
        override var isConnected: Boolean = true,
        override var generation: Int = 1,
        var queueOk: Boolean = true,
        var resolved: Int = 1,
        var legacyThrows: Boolean = false,
    ) : ApprovalTransport {
        val sent = mutableListOf<Pair<String, JsonElement>>()
        val legacy = mutableListOf<ApprovalRespondParams>()

        override fun respondServerRequest(
            id: String,
            result: JsonElement,
        ): Boolean {
            sent += id to result
            return queueOk
        }

        override suspend fun respondLegacy(params: ApprovalRespondParams): Int {
            legacy += params
            if (legacyThrows) error("boom")
            return resolved
        }
    }

    private fun approval(
        id: String,
        choices: List<String>? = listOf("once", "session", "always", "deny"),
        session: String? = "rt1",
    ) = WsEvent.ServerRequest(
        id,
        "approval",
        buildMap {
            put("session_id", session)
            put("request_id", "rid-$id")
            put("description", "rm -rf build")
            if (choices != null) put("choices", choices)
        },
    )

    private fun setup(
        notice: RequestNotice,
        generation: Int = 1,
    ): Pair<PendingRequestNotices, String> {
        val notices = PendingRequestNotices()
        assertTrue(notices.register(notice, generation))
        return notices to notice.key
    }

    private fun run(
        notices: PendingRequestNotices,
        key: String,
        choice: String,
        transport: FakeTransport,
        scope: String = "default",
        locked: Boolean = false,
    ) = runBlocking {
        ApprovalActionHandler.handle(key, choice, scope, locked, transport, notices)
    }

    @Test
    fun classifiesModernKinds() {
        val approvalNotice = requestNoticeFor(approval("a"), "default")!!
        assertEquals(RequestNoticeKind.APPROVAL, approvalNotice.kind)
        assertTrue(approvalNotice.answerable)
        assertEquals(
            RequestNoticeKind.CLARIFY,
            requestNoticeFor(WsEvent.ServerRequest("c", "clarify"), "default")!!.kind,
        )
        val secret = requestNoticeFor(WsEvent.ServerRequest("s", "secret", mapOf("prompt" to "token?")), "default")!!
        assertEquals(RequestNoticeKind.CREDENTIAL, secret.kind)
        assertNull(secret.preview)
        assertFalse(secret.answerable)
        assertNull(requestNoticeFor(WsEvent.ServerRequest("t", "terminal.read"), "default"))
    }

    @Test
    fun replayDoesNotAlertAgainAndCancelClosesOnlyMatching() {
        val notices = PendingRequestNotices()
        val a = requestNoticeFor(approval("a"), "default")!!
        val b = requestNoticeFor(approval("b"), "default")!!
        assertTrue(notices.register(a, 1))
        assertFalse(notices.register(a, 2))
        assertTrue(notices.register(b, 1))
        assertNotNull(notices.closeServerRequest("a"))
        assertNull(notices.find(a.key))
        assertNotNull(notices.find(b.key))
        // Replay after cancel stays silent.
        assertFalse(notices.register(a, 3))
    }

    @Test
    fun cancelBeforeAlertSuppressesIt() {
        val notices = PendingRequestNotices()
        assertNull(notices.closeServerRequest("a"))
        assertFalse(notices.register(requestNoticeFor(approval("a"), "default")!!, 1))
    }

    @Test
    fun approveOnceSendsExactRequestAndNeverAll() {
        val (notices, key) = setup(requestNoticeFor(approval("a"), "default")!!)
        requestNoticeFor(approval("b"), "default")!!.let { notices.register(it, 1) }
        val t = FakeTransport()
        assertEquals(ApprovalActionOutcome.SENT, run(notices, key, "once", t))
        assertEquals(1, t.sent.size)
        assertEquals("a", t.sent[0].first)
        val body = t.sent[0].second as JsonObject
        assertEquals(JsonPrimitive("once"), body["choice"])
        assertEquals(JsonPrimitive(false), body["all"])
        assertNull(notices.find(key))
        // Second tap is stale and sends nothing.
        assertEquals(ApprovalActionOutcome.STALE, run(notices, key, "once", t))
        assertEquals(1, t.sent.size)
    }

    @Test
    fun permanentChoicesAreRefused() {
        val (notices, key) = setup(requestNoticeFor(approval("a"), "default")!!)
        val t = FakeTransport()
        for (choice in listOf("always", "session", "all")) {
            assertEquals(ApprovalActionOutcome.REFUSED, run(notices, key, choice, t))
        }
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun onceOnlyRequestRejectsDeny() {
        val (notices, key) = setup(requestNoticeFor(approval("a", choices = listOf("once")), "default")!!)
        assertEquals(ApprovalActionOutcome.REFUSED, run(notices, key, "deny", FakeTransport()))
    }

    @Test
    fun appLockScopeChangeAndDisconnectNeverSend() {
        val (notices, key) = setup(requestNoticeFor(approval("a"), "default")!!)
        val t = FakeTransport()
        assertEquals(ApprovalActionOutcome.REFUSED, run(notices, key, "once", t, locked = true))
        assertEquals(ApprovalActionOutcome.SCOPE_CHANGED, run(notices, key, "once", t, scope = "other"))
        t.isConnected = false
        assertEquals(ApprovalActionOutcome.DISCONNECTED, run(notices, key, "once", t))
        t.isConnected = true
        t.generation = 2
        assertEquals(ApprovalActionOutcome.DISCONNECTED, run(notices, key, "once", t))
        assertTrue(t.sent.isEmpty())
        assertNotNull(notices.find(key))
    }

    @Test
    fun failedQueueReportsFailureAndKeepsRequestOpen() {
        val (notices, key) = setup(requestNoticeFor(approval("a"), "default")!!)
        val t = FakeTransport(queueOk = false)
        assertEquals(ApprovalActionOutcome.FAILED, run(notices, key, "deny", t))
        assertNotNull(notices.find(key))
    }

    @Test
    fun legacyUsesAwaitedRespondWithExactRequestId() {
        val event =
            WsEvent.ApprovalRequest(
                command = "ls",
                description = null,
                patternKeys = null,
                sessionId = "rt1",
                requestId = "rid-1",
            )
        val (notices, key) = setup(requestNoticeFor(event, "default"))
        val t = FakeTransport()
        assertEquals(ApprovalActionOutcome.RESOLVED, run(notices, key, "deny", t))
        assertEquals(ApprovalRespondParams("rt1", "deny", false, "rid-1"), t.legacy.single())
    }

    @Test
    fun legacyNotResolvedIsStaleNotSuccess() {
        val event = WsEvent.ApprovalRequest("ls", null, null, "rt1", requestId = "rid-1")
        val (notices, key) = setup(requestNoticeFor(event, "default"))
        assertEquals(ApprovalActionOutcome.STALE, run(notices, key, "once", FakeTransport(resolved = 0)))
    }

    @Test
    fun legacyErrorIsFailureAndKeepsOpen() {
        val event = WsEvent.ApprovalRequest("ls", null, null, "rt1", requestId = "rid-1")
        val (notices, key) = setup(requestNoticeFor(event, "default"))
        assertEquals(ApprovalActionOutcome.FAILED, run(notices, key, "once", FakeTransport(legacyThrows = true)))
        assertNotNull(notices.find(key))
    }

    @Test
    fun legacyWithoutRequestIdHasNoActions() {
        val event = WsEvent.ApprovalRequest("ls", null, null, "rt1", requestId = null)
        assertFalse(requestNoticeFor(event, "default").answerable)
    }
}
