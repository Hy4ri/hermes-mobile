package com.m57.hermescontrol.ui.chat

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.notification.ActiveReplyInfo
import com.m57.hermescontrol.notification.ChatNotificationService
import com.m57.hermescontrol.notification.ReplyNotificationTarget
import com.m57.hermescontrol.notification.ReplyNotificationTracker
import com.m57.hermescontrol.notification.TurnCorrelationTracker
import com.m57.hermescontrol.notification.TurnRowResolver
import com.m57.hermescontrol.notification.correlateCompletedTurnRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression matrix for reply-notification read dismissal.
 *
 * Split by design:
 * - The `alreadyResolvedServerId*` cases start from a durable server row id that
 *   the notification already carries, and prove hydration binds it to that exact
 *   row only — the downstream half of the contract.
 * - [mobileTurnBoundaryResolvesTheExactRowThenOnlyThatRowDismisses] covers the
 *   production path that PRODUCES that id (turn boundary → unique row).
 * - The mobile-side resolution rules themselves live in
 *   `TurnCorrelationTrackerTest`, and the send-order guarantee in
 *   `ChatViewModelTest`.
 *
 * Nothing here compares Android notification time with Hermes server time;
 * server row timestamps are not identity.
 */
class ReadNotificationReviewRegressionTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns manager
        mockkObject(AuthManager)
        val mockStore = mockk<ServerStore>(relaxed = true)
        every { mockStore.getLatestState() } returns ServerStoreState(baseUrl = "http://localhost:8080")
        every { AuthManager.serverStore } returns mockStore
        every { AuthManager.getToken() } returns "token"
        every { AuthManager.activeProfileId } returns MutableStateFlow("default")
        ReplyNotificationTracker.resetForTest()
    }

    @After
    fun tearDown() {
        ReplyNotificationTracker.resetForTest()
        unmockkObject(AuthManager)
    }

    private fun postTarget(text: String = "Done") {
        ReplyNotificationTracker.onReplyNotificationPosted("default", "session", "new-completion", text, 1L)
    }

    @Test
    fun roomRoundTripMustRetainCompletionIdentity() {
        val live = ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "new-completion")
        val restored = live.toEntity("session").toUiModel()
        assertEquals("Room reload lost notification identity", "new-completion", restored.completionId)
    }

    @Test
    fun repeatedTextInHistoryMustNotCopyLatestIdentityOntoOlderMessage() {
        val live =
            listOf(
                ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "old-completion"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Done", completionId = "new-completion"),
            )
        val history =
            listOf(
                SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")),
                SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", history, 0, true, live)
        assertEquals(listOf("old-completion", "new-completion"), mapped.map { it.completionId })
    }

    @Test
    fun pagingMergePreservesBothIdentitiesAndDoesNotConsumeOlderRepeatedReply() {
        val live = ChatMessage(id = "ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "comp")
        val newest = listOf(SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done")))
        val mapped = mapServerMessages("session", newest, 0, true, listOf(live))
        val hydrated = mergeTranscriptWithLive(mapped, listOf(live), preserveLiveIds = true)
        assertEquals("ws", hydrated.single().id)
        assertEquals("rest-session-200", hydrated.single().restId)
        assertEquals("comp", hydrated.single().completionId)
        val older = listOf(SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")))
        val page = mapServerMessages("session", older, 150, true, hydrated, isPagingOlder = true)
        val merged = mergeTranscriptWithLive(page, hydrated, chronological = false, preserveLiveIds = true)
        assertEquals(listOf("rest-session-100", "ws"), merged.map { it.id })
        assertEquals(listOf(null, "comp"), merged.map { it.completionId })
        assertEquals("rest-session-200", merged.last().restId)
        // A later refresh of ONLY the old row must not reassign the confirmed UUID.
        assertNull(mapServerMessages("session", older, 0, true, hydrated).single().completionId)
        assertEquals("comp", mapServerMessages("session", newest, 0, true, hydrated).single().completionId)
    }

    @Test
    fun olderPageAndCacheMergeCannotClaimUnconfirmedNotificationByText() {
        val live = ChatMessage(id = "ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "comp")
        val older = listOf(SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")))
        val page = mapServerMessages("session", older, 150, true, listOf(live), isPagingOlder = true)
        val merged = mergeTranscriptWithLive(page, listOf(live), chronological = false, preserveLiveIds = true)
        assertEquals(2, merged.size)
        assertNull(merged.single { it.id == "rest-session-100" }.completionId)
        assertNull(merged.single { it.id == "ws" }.restId)
        assertEquals(2, mergeCachedTranscriptPage(page, listOf(live)).size)
    }

    @Test
    fun repeatedCompletionMatchesStayOnTheSameLiveKeysAfterMerge() {
        val live =
            listOf(
                ChatMessage(id = "old-ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "old"),
                ChatMessage(id = "new-ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "new"),
            )
        val page =
            mapServerMessages(
                "session",
                listOf(SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done"))),
                0,
                true,
                live,
            )
        val merged = mergeTranscriptWithLive(page, live, preserveLiveIds = true)
        assertEquals("rest-session-200", merged.single { it.id == "new-ws" }.restId)
        assertNull(merged.single { it.id == "old-ws" }.restId)
        assertEquals("new", merged.single { it.id == "new-ws" }.completionId)
    }

    @Test
    fun durableTargetWinsOverRepeatedLiveTextEvenWithLegacyPositions() {
        ReplyNotificationTracker.setTargetForTest(
            ReplyNotificationTarget("default", "session", "comp", 1L, serverMessageId = 100),
        )
        val live = ChatMessage(id = "ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "comp")
        val rows =
            listOf(
                SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")),
                SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done")),
            )
        for (latest in listOf(true, false)) {
            val mapped = mapServerMessages("session", rows, 10, latest, listOf(live), context = context)
            assertEquals(listOf("comp", null), mapped.map { it.completionId })
            val merged = mergeTranscriptWithLive(mapped, listOf(live), preserveLiveIds = true)
            assertEquals(if (latest) "rest-session-100" else "rest-session-10", merged.single { it.id == "ws" }.restId)
            assertNull(mapServerMessages("session", rows.takeLast(1), 11, latest, listOf(live)).single().completionId)
        }
    }

    @Test
    fun exactCacheAndRestOverlapsRetainCompletionWhenPreferredCopyHasNone() {
        val live =
            ChatMessage(
                id = "ws",
                role = MessageRole.ASSISTANT,
                content = "Done",
                restId = "rest-session-200",
                completionId = "comp",
            )
        val rest = live.copy(id = "rest-session-200", restId = null, completionId = null)
        assertEquals("comp", dedupeCachedMessages(listOf(live, rest)).single().completionId)
        assertEquals("comp", mergeCachedTranscriptPage(listOf(rest), listOf(live)).single().completionId)
        assertEquals("comp", mergeTranscriptWithLive(listOf(rest), listOf(live)).single().completionId)
        val restored = rest.copy(completionId = "comp").toEntity("session").toUiModel()
        val richWithoutCompletion = live.copy(completionId = null)
        val merged = mergeCachedTranscriptPage(listOf(restored), listOf(richWithoutCompletion)).single()
        assertEquals("ws", merged.id)
        assertEquals("rest-session-200", merged.restId)
        assertEquals("comp", merged.completionId)
    }

    @Test
    fun durableHydrationFoldsMixedCacheEchoIntoRichLiveReply() {
        ReplyNotificationTracker.setTargetForTest(
            ReplyNotificationTarget("default", "session", "comp", 1L, serverMessageId = 200),
        )
        val live = ChatMessage(id = "ws", role = MessageRole.ASSISTANT, content = "Done", completionId = "comp")
        val echo = live.copy(id = "rest-session-200", completionId = null)
        val current = listOf(echo, live)
        val mapped =
            mapServerMessages(
                "session",
                listOf(SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done"))),
                0,
                true,
                current,
            )
        val merged = mergeTranscriptWithLive(mapped, current, preserveLiveIds = true).single()
        assertEquals("ws", merged.id)
        assertEquals("rest-session-200", merged.restId)
        assertEquals("comp", merged.completionId)
    }

    @Test
    fun cachedNewestDuplicateWithoutDurableIdKeepsCompletionOnNewestLiveReply() {
        // #129 review: a metadata-free cached echo must not redirect the newest WS completion.
        ReplyNotificationTracker.setTargetForTest(
            ReplyNotificationTarget("default", "session", "comp", 1L, serverMessageId = null),
        )
        val cachedNewest =
            ChatMessage(
                id = "rest-session-200",
                role = MessageRole.ASSISTANT,
                content = "Done",
                completionId = null,
            )
        val live =
            ChatMessage(
                id = "ws",
                role = MessageRole.ASSISTANT,
                content = "Done",
                completionId = "comp",
                restId = null,
            )
        val current = listOf(cachedNewest, live)
        val history =
            listOf(
                SessionMessage(id = 100, role = "assistant", content = JsonPrimitive("Done")),
                SessionMessage(id = 200, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped =
            mapServerMessages(
                sessionId = "session",
                messages = history,
                offset = 0,
                latestPaging = true,
                liveMessages = current,
                isPagingOlder = false,
                context = context,
            )
        val merged = mergeTranscriptWithLive(mapped, current, preserveLiveIds = true)

        assertNull(mapped.single { it.id == "rest-session-100" }.completionId)
        assertEquals("comp", mapped.single { it.id == "rest-session-200" }.completionId)
        assertEquals(2, merged.size)
        assertEquals(setOf("rest-session-100", "ws"), merged.map { it.id }.toSet())
        assertEquals(2, merged.count { it.content == "Done" })
        assertNull(merged.single { it.id == "rest-session-100" }.completionId)
        assertEquals("rest-session-200", merged.single { it.id == "ws" }.restId)
        assertEquals("comp", merged.single { it.id == "ws" }.completionId)
    }

    @Test
    fun viewingOldCachedTextMustNotClearNewReplyNotification() {
        postTarget("Done")
        val old =
            ChatMessage(role = MessageRole.ASSISTANT, content = "Done yesterday; unrelated old response")
                .toEntity("session")
                .toUiModel()
        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                old.completionId,
            )
        assertFalse("Old text incorrectly acknowledged the new reply", cancelled)
        verify(exactly = 0) { manager.cancel(any()) }
    }

    @Test
    fun strippedCommentaryCompletionMustBindTerminalVisibleMessage() {
        val orphan = ChatMessage(id = "orphan", role = MessageRole.ASSISTANT, content = "already shown")
        val state = ChatUiState(currentSessionId = "session", messages = listOf(orphan))
        val streaming = StreamingState(streamingMessage = orphan, sealedOrphanIds = listOf("orphan"))
        val result =
            ChatWsEventReducer.reduce(
                state,
                streaming,
                WsEvent.MessageComplete("already shown", "session", completionId = "new-completion"),
                "session",
            )
        assertEquals(
            "Completion lost when no new final bubble is needed",
            "new-completion",
            result.state.messages
                .single()
                .completionId,
        )
        val persistEffect =
            result.effects.filterIsInstance<ReducerEffect.PersistMessage>().singleOrNull()
        assertEquals(
            "PersistMessage effect must be emitted for the sealed orphan bubble so Room persists it",
            "new-completion",
            persistEffect?.message?.completionId,
        )
    }

    @Test
    fun loadOlderMessagesMustNotStealCompletionIdFromLiveMessage() {
        val live =
            listOf(
                ChatMessage(
                    id = "ws-1",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = "new-completion",
                ),
            )
        val olderHistory =
            listOf(
                SessionMessage(id = 50, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", olderHistory, 50, true, live, isPagingOlder = true)
        assertEquals("Older paged message must not steal live completionId", null, mapped.single().completionId)
    }

    @Test
    fun duplicateTextOutsideFetchedPageMustNotShiftCompletionId() {
        val live =
            listOf(
                ChatMessage(
                    id = "rest-session-10",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = null,
                ),
                ChatMessage(
                    id = "rest-session-20",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    completionId = "new-completion",
                ),
            )
        // Page only includes message 20 (message 10 is outside this page)
        val history =
            listOf(
                SessionMessage(id = 20, role = "assistant", content = JsonPrimitive("Done")),
            )
        val mapped = mapServerMessages("session", history, 0, true, live, isPagingOlder = false)
        assertEquals(
            "Exact REST id match must preserve identity regardless of older duplicates",
            "new-completion",
            mapped.single().completionId,
        )
    }

    @Test
    fun metadataFreeReplacementMustNotBeCancelled() {
        postTarget()
        ReplyNotificationTracker.activeNotificationProvider = {
            ActiveReplyInfo(2, null, null, null, null, null, 0L, 1L)
        }
        assertFalse(ReplyNotificationTracker.onMessageVisible(context, "default", "session", "new-completion"))
        verify(exactly = 0) { manager.cancel(any()) }
    }

    @Test
    fun actionPostingMustNotInterleaveBetweenInspectionAndCancellation() {
        val reply =
            ActiveReplyInfo(
                2,
                ReplyNotificationTracker.KIND_REPLY,
                "default",
                "session",
                "new-completion",
                "Done",
                1L,
                1L,
            )
        val actualSlot = AtomicReference("reply")
        val ready = CountDownLatch(1)
        val allowNotify = CountDownLatch(1)
        val posted = CountDownLatch(1)
        val action = mockk<Notification>()
        every { manager.notify(2, action) } answers { actualSlot.set("action") }
        every { manager.cancel(2) } answers { actualSlot.set("empty") }
        postTarget()
        val writer =
            Thread {
                // Same split ownership/write sequence as showReplyNotification and NotificationReplyReceiver.
                ReplyNotificationTracker.onNonReplyNotificationPosted()
                ready.countDown()
                check(allowNotify.await(5, TimeUnit.SECONDS))
                manager.notify(2, action)
                posted.countDown()
            }
        writer.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        var reads = 0
        ReplyNotificationTracker.activeNotificationProvider = {
            val snapshot = reply
            reads++
            if (reads == 2) {
                // Pause after querying activeNotifications but before cancel(), then complete the action write.
                allowNotify.countDown()
                check(posted.await(5, TimeUnit.SECONDS))
            }
            snapshot
        }
        try {
            ReplyNotificationTracker.onMessageVisible(context, "default", "session", "new-completion")
        } finally {
            allowNotify.countDown()
            writer.join(6000)
        }
        assertEquals("New action notification was cancelled by the old read", "action", actualSlot.get())
    }

    @Test
    fun oldReplyRacingNewerActionMustNotPostOrOverwriteAction() {
        val gen1 = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-1", "Old Reply")
        val actionNotif = mockk<Notification>()
        val replyNotif = mockk<Notification>()

        // Action arrives and posts
        assertTrue(ReplyNotificationTracker.postActionNotification(context, actionNotif))
        verify(exactly = 1) { manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, actionNotif) }

        // Stale reply 1 tries to post after action arrived
        val posted = ReplyNotificationTracker.postReplyNotification(context, replyNotif, gen1)
        assertFalse("Stale reply must be rejected after action notification was posted", posted)
        verify(exactly = 0) { manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, replyNotif) }
        assertNull("Active target must remain null for action", ReplyNotificationTracker.getActiveTarget())
    }

    @Test
    fun oldReplyRacingNewerReplyMustNotOverwriteNewerReply() {
        val gen1 = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-1", "Old Reply")
        val gen2 = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-2", "New Reply")
        val replyNotif1 = mockk<Notification>()
        val replyNotif2 = mockk<Notification>()

        // Gen 2 posts successfully
        assertTrue(ReplyNotificationTracker.postReplyNotification(context, replyNotif2, gen2))
        assertEquals("comp-2", ReplyNotificationTracker.getActiveTarget()?.completionId)

        // Stale Gen 1 tries to post
        assertFalse(ReplyNotificationTracker.postReplyNotification(context, replyNotif1, gen1))
        assertEquals("comp-2", ReplyNotificationTracker.getActiveTarget()?.completionId)
    }

    @Test
    fun readCancelRacingReplyPublicationMustPreventZombieNotification() {
        val gen1 = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-1", "Pending Reply")
        val replyNotif = mockk<Notification>()

        // User views message in foreground chat before background service calls postReplyNotification
        val cancelled =
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                "comp-1",
            )
        assertTrue("Message visibility should cancel the pending reply target", cancelled)

        // Delayed background service finally tries to post reply
        val posted = ReplyNotificationTracker.postReplyNotification(context, replyNotif, gen1)
        assertFalse("Tombstoned/cancelled generation must not post notification ID 2", posted)
        verify(exactly = 0) { manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, replyNotif) }
    }

    @Test
    fun mediaRepliesRetainCompletionIdAcrossRestRehydration() {
        val live =
            listOf(
                ChatMessage(
                    id = "ws-media",
                    role = MessageRole.ASSISTANT,
                    content = "Here is your chart",
                    completionId = "comp-media-123",
                ),
            )
        val history =
            listOf(
                SessionMessage(
                    id = 1,
                    role = "assistant",
                    content = JsonPrimitive("Here is your chart MEDIA:/opt/hermes/chart.png"),
                ),
            )
        val mapped = mapServerMessages("session", history, 0, true, live, isPagingOlder = false)
        assertEquals("comp-media-123", mapped.single().completionId)
    }

    @Test
    fun mediaOnlyReplyRetainsCompletionIdAcrossRestRehydration() {
        val live =
            listOf(
                ChatMessage(
                    id = "ws-media-only",
                    role = MessageRole.ASSISTANT,
                    content = "",
                    completionId = "comp-media-only",
                ),
            )
        val history =
            listOf(
                SessionMessage(
                    id = 1,
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:/opt/hermes/photo.jpg"),
                ),
            )
        val mapped = mapServerMessages("session", history, 0, true, live, isPagingOlder = false)
        assertEquals("comp-media-only", mapped.single().completionId)
    }

    @Test
    fun mediaReplyWithReasoningOnlyRowBeforeItRetainsCompletionId() {
        val live =
            listOf(
                ChatMessage(
                    id = "ws-media-reasoning",
                    role = MessageRole.ASSISTANT,
                    content = "The result",
                    completionId = "comp-reasoning-media",
                ),
            )
        val history =
            listOf(
                SessionMessage(
                    id = 1,
                    role = "assistant",
                    content = JsonPrimitive(""),
                    reasoning = JsonPrimitive("thinking step"),
                ),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive("The result MEDIA:/opt/pic.png")),
            )
        val mapped = mapServerMessages("session", history, 0, true, live, isPagingOlder = false)
        assertEquals("comp-reasoning-media", mapped.single().completionId)
    }

    @Test
    fun blankMessageCompleteMustNotCorruptHistoricalAssistantMessage() {
        val oldAssistant =
            ChatMessage(
                id = "old-assistant",
                role = MessageRole.ASSISTANT,
                content = "Old reply from yesterday",
                completionId = "old-comp",
            )
        val state = ChatUiState(currentSessionId = "session", messages = listOf(oldAssistant))
        val streaming = StreamingState() // No current streaming message or sealed orphans

        val result =
            ChatWsEventReducer.reduce(
                state,
                streaming,
                WsEvent.MessageComplete("", "session", completionId = "new-unrelated-completion"),
                "session",
            )

        // Historical assistant message must be completely untouched
        assertEquals(
            "old-comp",
            result.state.messages
                .single()
                .completionId,
        )
        assertFalse(
            "PersistMessage effect must not be emitted for unrelated historical messages",
            result.effects.any { it is ReducerEffect.PersistMessage },
        )
    }

    @Test
    fun coldStartRecoveredGenerationMustAdvanceCounterAndAllowFutureReplies() {
        ReplyNotificationTracker.resetForTest()
        // Active notification in system with generation 37
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-37",
                textSnippet = "Reply 37",
                generation = 37L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        // User views message -> recovers and cancels generation 37
        val cancelled = ReplyNotificationTracker.onMessageVisible(context, "default", "session", "comp-37")
        assertTrue("Generation 37 should be recovered and cancelled", cancelled)

        // New reply arrives
        val newGen = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-38", "Reply 38")
        assertTrue("New generation must exceed recovered generation (was $newGen)", newGen > 37L)

        val replyNotif = mockk<Notification>()
        val posted = ReplyNotificationTracker.postReplyNotification(context, replyNotif, newGen)
        assertTrue("New reply notification must be accepted and posted", posted)
        verify { manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, replyNotif) }
    }

    @Test
    fun recoveredGenerationFollowedByActionAndReplyMustRemainStrictlyMonotonic() {
        ReplyNotificationTracker.resetForTest()
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-37",
                textSnippet = "Reply 37",
                generation = 37L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        // Recover and cancel 37
        assertTrue(ReplyNotificationTracker.onMessageVisible(context, "default", "session", "comp-37"))

        // Post action
        val actionNotif = mockk<Notification>()
        assertTrue(ReplyNotificationTracker.postActionNotification(context, actionNotif))

        // Register new reply
        val newGen = ReplyNotificationTracker.registerPendingReply("default", "session", "comp-39", "Reply 39")
        assertTrue("New generation must strictly exceed 37 and action tombstone", newGen > 38L)

        val replyNotif = mockk<Notification>()
        assertTrue(ReplyNotificationTracker.postReplyNotification(context, replyNotif, newGen))
    }

    @Test
    fun alreadyResolvedServerIdBindsOnlyTheExactRowAmongShortDuplicates() {
        ReplyNotificationTracker.resetForTest()
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-newest-done",
                serverMessageId = 2,
                textSnippet = "Done",
                generation = 5L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        // Two identical "Done" rows. The durable id (row 2) is what decides —
        // there is no server row timestamp comparison anywhere in the mapper.
        val history =
            listOf(
                SessionMessage(
                    id = 1,
                    role = "assistant",
                    content = JsonPrimitive("Done"),
                ),
                SessionMessage(
                    id = 2,
                    role = "assistant",
                    content = JsonPrimitive("Done"),
                ),
            )

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        assertEquals("Older message must have null completionId", null, mapped[0].completionId)
        assertEquals("Newest message must receive target completionId", "comp-newest-done", mapped[1].completionId)

        // Showing ONLY the older Done must NOT cancel the notification
        val cancelledOld =
            ReplyNotificationTracker.onMessageVisible(
                context = context,
                scopeId = "default",
                sessionId = "session",
                completionId = mapped[0].completionId,
            )
        assertFalse("Viewing older duplicate message must NOT cancel notification", cancelledOld)
        verify(exactly = 0) { manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }

        // Showing the newest Done MUST cancel the notification
        val cancelledNew =
            ReplyNotificationTracker.onMessageVisible(
                context = context,
                scopeId = "default",
                sessionId = "session",
                completionId = mapped[1].completionId,
            )
        assertTrue("Viewing newest message must cancel notification", cancelledNew)
        verify(exactly = 1) { manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun alreadyResolvedServerIdIgnoresSharedHundredCharacterPrefix() {
        ReplyNotificationTracker.resetForTest()
        val textPrefix = "x".repeat(100)
        val text1 = textPrefix + "A"
        val text2 = textPrefix + "B"
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-long-2",
                serverMessageId = 10,
                textSnippet = text2.take(100),
                generation = 5L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        val history =
            listOf(
                SessionMessage(
                    id = 10,
                    role = "assistant",
                    content = JsonPrimitive(text1),
                ),
                SessionMessage(
                    id = 20,
                    role = "assistant",
                    content = JsonPrimitive(text2),
                ),
            )

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        assertEquals("comp-long-2", mapped[0].completionId)
        assertEquals(null, mapped[1].completionId)

        assertTrue(
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                mapped[0].completionId,
            ),
        )
        assertFalse(
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                mapped[1].completionId,
            ),
        )
    }

    @Test
    fun alreadyResolvedServerIdBindsMediaRowAndDismissesOnVisible() {
        ReplyNotificationTracker.resetForTest()
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-media-cold",
                serverMessageId = 100,
                textSnippet = "Here is the image MEDIA:/opt/hermes/image.png",
                generation = 5L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        val history =
            listOf(
                SessionMessage(
                    id = 100,
                    role = "assistant",
                    content = JsonPrimitive("Here is the image MEDIA:/opt/hermes/image.png"),
                ),
            )

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        val row = mapped.single()
        assertEquals("comp-media-cold", row.completionId)
        assertEquals("Here is the image", row.content)

        val dismissed =
            ReplyNotificationTracker.onMessageVisible(
                context = context,
                scopeId = "default",
                sessionId = "session",
                completionId = row.completionId,
            )
        assertTrue("Viewing hydrated MEDIA row must dismiss notification", dismissed)
        verify { manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun alreadyResolvedServerIdBindsMediaOnlyRowAndDismissesOnVisible() {
        ReplyNotificationTracker.resetForTest()
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-media-only-cold",
                serverMessageId = 100,
                textSnippet = "MEDIA:/opt/hermes/image.png",
                generation = 5L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        val history =
            listOf(
                SessionMessage(
                    id = 100,
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:/opt/hermes/image.png"),
                ),
            )

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        val row = mapped.single()
        assertEquals("comp-media-only-cold", row.completionId)
        assertEquals("", row.content)

        val dismissed =
            ReplyNotificationTracker.onMessageVisible(
                context = context,
                scopeId = "default",
                sessionId = "session",
                completionId = row.completionId,
            )
        assertTrue("Viewing hydrated MEDIA-only row must dismiss notification", dismissed)
        verify { manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun alreadyResolvedServerIdBindsOnlyTheExactMediaRowAmongDuplicates() {
        ReplyNotificationTracker.resetForTest()
        val activeInfo =
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-media-dup-2",
                serverMessageId = 1,
                textSnippet = "MEDIA:/opt/hermes/image.png",
                generation = 5L,
                timestamp = System.currentTimeMillis(),
            )
        ReplyNotificationTracker.activeNotificationProvider = { activeInfo }

        val history =
            listOf(
                SessionMessage(
                    id = 1,
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:/opt/hermes/image.png"),
                ),
                SessionMessage(
                    id = 2,
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:/opt/hermes/image.png"),
                ),
            )

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        assertEquals("comp-media-dup-2", mapped[0].completionId)
        assertEquals(null, mapped[1].completionId)

        assertTrue(
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                mapped[0].completionId,
            ),
        )
        assertFalse(
            ReplyNotificationTracker.onMessageVisible(
                context,
                "default",
                "session",
                mapped[1].completionId,
            ),
        )
    }

    /**
     * The path production actually runs, end to end: the turn boundary armed
     * before the prompt names exactly one REST row above it, that row id travels
     * in the notification, only that row is hydrated with the completion id, and
     * only that row dismisses the notification.
     */
    @Test
    fun mobileTurnBoundaryResolvesTheExactRowThenOnlyThatRowDismisses() {
        ReplyNotificationTracker.resetForTest()
        TurnCorrelationTracker.resetForTest()
        TurnCorrelationTracker.armBoundary("default", "session", 50)

        val history =
            listOf(
                SessionMessage(id = 10, role = "assistant", content = JsonPrimitive("Done")),
                SessionMessage(id = 51, role = "user", content = JsonPrimitive("next")),
                SessionMessage(id = 52, role = "assistant", content = JsonPrimitive("Done")),
            )
        val resolved =
            runBlocking {
                correlateCompletedTurnRow(
                    scopeId = "default",
                    sessionId = "session",
                    completionText = "Done",
                    resolver = TurnRowResolver({ history }, listOf(0L)),
                )
            }
        assertEquals("The historical duplicate below the boundary must not win", 52, resolved)

        // The service stores that id in the notification extras; after process
        // death the extras are all the tracker can recover from.
        ReplyNotificationTracker.activeNotificationProvider = {
            ActiveReplyInfo(
                id = ChatNotificationService.PENDING_NOTIFICATION_ID,
                kind = ReplyNotificationTracker.KIND_REPLY,
                scopeId = "default",
                sessionId = "session",
                completionId = "comp-turn",
                textSnippet = "Done",
                generation = 5L,
                timestamp = 0L,
                serverMessageId = resolved,
            )
        }

        val mapped =
            mapServerMessages("session", history, 0, true, emptyList(), isPagingOlder = false, context = context)
        assertEquals(listOf(null, null, "comp-turn"), mapped.map { it.completionId })

        assertFalse(
            "Viewing the older duplicate must not dismiss",
            ReplyNotificationTracker.onMessageVisible(context, "default", "session", mapped[0].completionId),
        )
        assertTrue(
            "Viewing the turn's own row must dismiss",
            ReplyNotificationTracker.onMessageVisible(context, "default", "session", mapped[2].completionId),
        )
        verify(exactly = 1) { manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID) }
    }

    @Test
    fun unknownScopeMustNotAcknowledgeScopedReply() {
        val target = ReplyNotificationTarget("other-profile", "session", "completion", 1L, "Done")
        assertFalse(target.matches(null, "session", "completion"))
    }
}
