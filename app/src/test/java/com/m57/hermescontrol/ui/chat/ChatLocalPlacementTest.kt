package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatLocalPlacementTest {
    private val earlier =
        ChatMessage(id = "rest-s-10", role = MessageRole.ASSISTANT, content = "Earlier", timestamp = 900L)
    private val later = earlier.copy(id = "rest-s-11", content = "Later", timestamp = 1L)
    private val command = ChatMessage(id = "command", role = MessageRole.USER, content = "/model test", timestamp = 3L)

    @Test
    fun explicitPlacementRoundTripsWithoutAcquiringDeliveryIdentity() {
        val pending = ChatMessage(id = "pending", role = MessageRole.USER, content = "Pending")
        val placed = command.withLocalTranscriptAnchor(listOf(earlier, pending))
        val restored = placed.toEntity("s").toUiModel()
        assertEquals(10L, restored.localAnchorOrder)
        assertEquals(pending.id, restored.localPredecessorId)
        assertNull(restored.canonicalRestId)
        assertEquals(MessageProvenance.UNKNOWN, restored.messageProvenance)
    }

    @Test
    fun explicitAnchorSurvivesNewerHistoryAndRepeatedPayloadUpdates() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            repository.persistMessages(listOf(earlier, later), "s")
            val placed = command.withLocalTranscriptAnchor(listOf(earlier))
            repository.persistMessage(placed, "s")
            repository.persistMessage(command.copy(content = "/model updated", timestamp = Long.MAX_VALUE), "s")
            val rows = repository.loadPage("s", null, 150).messages
            assertEquals(10L, rows.single { it.id == command.id }.localAnchorOrder)
            assertEquals(
                listOf(earlier.id, command.id, later.id),
                mergeCachedTranscriptPage(rows, emptyList()).map { it.id },
            )
        }

    @Test
    fun pendingPredecessorStaysBeforeCommandUntilItsOwnIdentityIsConfirmed() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val pending =
                ChatMessage(
                    id = "pending",
                    role = MessageRole.USER,
                    content = "Pending",
                    messageProvenance = MessageProvenance.LOCAL_PENDING,
                )
            val placed = command.withLocalTranscriptAnchor(listOf(earlier, pending))
            repository.persistMessages(listOf(earlier, pending, placed, later), "s")
            val rows = repository.loadPage("s", null, 150).messages
            assertEquals(
                listOf(earlier.id, later.id, pending.id, command.id),
                mergeCachedTranscriptPage(rows, emptyList()).map { it.id },
            )
            repository.confirmIdentities(listOf(pending.copy(restId = "rest-s-12")), "s")
            repository.persistMessage(later.copy(id = "rest-s-13"), "s")
            val resumed = mergeCachedTranscriptPage(repository.loadPage("s", null, 150).messages, emptyList())
            assertEquals(listOf(earlier.id, later.id, pending.id, command.id, "rest-s-13"), resumed.map { it.id })
            assertNull(resumed.single { it.id == command.id }.canonicalRestId)
        }

    @Test
    fun unresolvedDurablePredecessorInheritsObservedLaterUserSuccessor() {
        val pending =
            ChatMessage(id = "pending", role = MessageRole.USER, content = "Pending", localOrder = 1L)
        val placed = command.copy(localOrder = 2L).withLocalTranscriptAnchor(listOf(earlier, pending))
        val feedback =
            ChatMessage(
                id = "feedback",
                role = MessageRole.ASSISTANT,
                content = "Changed",
                displayKind = DisplayKind.LOCAL_FEEDBACK,
                localOrder = 3L,
            ).withLocalTranscriptAnchor(listOf(earlier, pending, placed))
        val laterUser =
            ChatMessage(id = "later-user", role = MessageRole.USER, content = "Later prompt")
        val confirmedUser = laterUser.copy(id = "rest-s-20")
        val observed = listOf(earlier, pending, placed, feedback, laterUser)
        val expected = observed.map { it.id }

        assertEquals(pending.id, placed.localPredecessorId)
        assertEquals(pending.id, placed.toEntity("s").toUiModel().localPredecessorId)
        val merged = mergeTranscriptWithLive(listOf(confirmedUser), observed)
        assertEquals(expected, merged.map { it.id })
        assertEquals(confirmedUser.id, merged.last().canonicalRestId)
        assertEquals(expected, mergeTranscriptWithLive(listOf(confirmedUser), merged).map { it.id })
        assertNull(merged.single { it.id == placed.id }.canonicalRestId)

        // A cold cache page has no observed successor: do not infer one from its grouped rows.
        val cached = listOf(earlier, confirmedUser, pending, placed, feedback)
        val restored = mergeCachedTranscriptPage(cached, emptyList())
        assertEquals(cached.map { it.id }, restored.map { it.id })
        assertEquals(pending.id, restored.single { it.id == placed.id }.localPredecessorId)
        assertEquals(
            cached.map { it.id },
            mergeTranscriptWithLive(listOf(confirmedUser), restored).map { it.id },
        )
    }

    @Test
    fun confirmedPredecessorOutsideCachePageStillPositionsCommand() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val pending = ChatMessage(id = "pending", role = MessageRole.USER, content = "Pending")
            repository.persistMessages(listOf(earlier, pending), "s")
            repository.persistMessage(command.withLocalTranscriptAnchor(listOf(earlier, pending)), "s")
            repository.confirmIdentities(listOf(pending.copy(restId = "rest-s-12")), "s")
            repository.persistMessage(later.copy(id = "rest-s-13"), "s")
            val commandOnly = repository.loadPage("s", null, 1)
            assertEquals(12L, commandOnly.messages.single().localAnchorOrder)
            val rest = listOf(earlier, pending.copy(restId = "rest-s-12"), later.copy(id = "rest-s-13"))
            val resumed = mergeTranscriptWithLive(rest, commandOnly.messages)
            assertEquals(listOf(earlier.id, pending.id, command.id, "rest-s-13"), resumed.map { it.id })
        }

    @Test
    fun multipleIdenticalCommandsAndFeedbackRetainDistinctOccurrences() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val feedback =
                ChatMessage(
                    id = "feedback",
                    role = MessageRole.ASSISTANT,
                    content = "Changed",
                    displayKind = DisplayKind.LOCAL_FEEDBACK,
                )
            val second = command.copy(id = "second-command")
            val first = command.withLocalTranscriptAnchor(listOf(earlier))
            repository.persistMessages(listOf(earlier, first, feedback, second, later), "s")
            val expected = listOf(earlier.id, first.id, feedback.id, second.id, later.id)
            val restored = mergeCachedTranscriptPage(repository.loadPage("s", null, 150).messages, emptyList())
            assertEquals(expected, restored.map { it.id })
            assertEquals(expected, mergeTranscriptWithLive(listOf(earlier, later), restored).map { it.id })
        }

    @Test
    fun refreshedCommandAdoptsResolvedPredecessorPlacementWithoutLaterStaleRegression() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val pending = ChatMessage(id = "pending", role = MessageRole.USER, content = "Pending")
            repository.persistMessages(listOf(earlier, pending), "s")
            repository.persistMessage(command.withLocalTranscriptAnchor(listOf(earlier, pending)), "s")
            val stale = repository.loadPage("s", null, 1).messages
            val after = later.copy(id = "rest-s-13")
            val onScreen = mergeTranscriptWithLive(listOf(earlier, later, after), stale)
            repository.confirmIdentities(listOf(pending.copy(restId = "rest-s-12")), "s")
            val refreshed = repository.loadPage("s", null, 1).messages
            val merged = mergeCachedTranscriptPage(refreshed, onScreen)
            val expected = listOf(earlier.id, later.id, command.id, after.id)
            assertEquals(expected, merged.map { it.id })
            assertEquals(12L, merged.single { it.id == command.id }.localAnchorOrder)
            assertNull(merged.single { it.id == command.id }.localPredecessorId)
            assertEquals(expected, mergeCachedTranscriptPage(stale, merged).map { it.id })
            assertNull(merged.single { it.id == command.id }.canonicalRestId)
        }

    @Test
    fun legacyCommandRemainsVisibleBeforeHydratedWindowWithoutInventedChronology() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            dao.addMessageDirect(ChatMessageEntity("legacy", "s", "USER", "/model old", timestamp = 0L))
            repository.persistMessages(listOf(earlier, later), "s")
            val restored = mergeCachedTranscriptPage(repository.loadPage("s", null, 150).messages, emptyList())
            assertEquals(listOf("legacy", earlier.id, later.id), restored.map { it.id })
            assertNull(restored.first().localAnchorOrder)
            assertNull(restored.first().canonicalRestId)
            assertEquals(MessageProvenance.UNKNOWN, restored.first().messageProvenance)
            assertEquals(
                restored.map { it.id },
                mergeTranscriptWithLive(listOf(earlier, later), restored).map { it.id },
            )
        }

    @Test
    fun migratedNullPlacementUsesTimestampInsideCanonicalWindow() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val first = earlier.copy(timestamp = 100L)
            val second = later.copy(timestamp = 300L)
            val last = later.copy(id = "rest-s-12", timestamp = 500L)
            dao.addMessageDirect(ChatMessageEntity("legacy-middle", "s", "USER", "/model old", timestamp = 250L))
            repository.persistMessages(listOf(first, second, last), "s")
            val cached = repository.loadPage("s", null, 150).messages
            val legacy = cached.single { it.id == "legacy-middle" }
            assertNull(legacy.localAnchorOrder)
            assertNull(legacy.localPredecessorId)
            val expected = listOf(first.id, legacy.id, second.id, last.id)
            val restored = mergeCachedTranscriptPage(cached, emptyList())
            assertEquals(expected, restored.map { it.id })
            assertEquals(expected, mergeTranscriptWithLive(listOf(first, second, last), restored).map { it.id })
        }

    @Test
    fun explicitAnchorBeatsContradictoryTimestampsAfterColdRestoreAndRestResume() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            repository.persistMessages(listOf(earlier, later), "s")
            repository.persistMessage(command.copy(localAnchorOrder = 10L, timestamp = Long.MAX_VALUE), "s")
            val cached = repository.loadPage("s", null, 150).messages
            assertEquals(10L, cached.single { it.id == command.id }.localAnchorOrder)
            val expected = listOf(earlier.id, command.id, later.id)
            val restored = mergeCachedTranscriptPage(cached, emptyList())
            assertEquals(expected, restored.map { it.id })
            assertEquals(expected, mergeTranscriptWithLive(listOf(earlier, later), restored).map { it.id })
        }

    @Test
    fun resolvedPendingPredecessorBeatsContradictoryTimestamps() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val pending = ChatMessage(id = "pending", role = MessageRole.USER, content = "Pending", timestamp = 1L)
            repository.persistMessages(listOf(earlier, pending), "s")
            repository.persistMessage(
                command.withLocalTranscriptAnchor(listOf(earlier, pending)).copy(timestamp = Long.MIN_VALUE),
                "s",
            )
            repository.confirmIdentities(listOf(pending.copy(restId = "rest-s-12")), "s")
            val after = later.copy(id = "rest-s-13")
            repository.persistMessages(listOf(later, after), "s")
            val cached = repository.loadPage("s", null, 150).messages
            val placed = cached.single { it.id == command.id }
            assertEquals(12L, placed.localAnchorOrder)
            assertNull(placed.localPredecessorId)
            val expected = listOf(earlier.id, later.id, pending.id, command.id, after.id)
            val restored = mergeCachedTranscriptPage(cached, emptyList())
            assertEquals(expected, restored.map { it.id })
            assertEquals(expected, mergeTranscriptWithLive(listOf(earlier, later, after), restored).map { it.id })
        }

    @Test
    fun sessionWithoutPredecessorAndOlderPageArrivalDoNotMoveCommandToTail() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            repository.persistMessage(command.withLocalTranscriptAnchor(emptyList()), "s")
            repository.persistMessages(listOf(later, earlier), "s")
            val rows = repository.loadPage("s", null, 150).messages
            val restored = mergeCachedTranscriptPage(rows, emptyList())
            assertEquals(listOf(command.id, earlier.id, later.id), restored.map { it.id })
            assertEquals(-1L, restored.first().localAnchorOrder)
            val older = earlier.copy(id = "rest-s-2")
            assertEquals(
                listOf(command.id, older.id, earlier.id, later.id),
                mergeTranscriptWithLive(listOf(older), restored, chronological = false).map { it.id },
            )
        }

    @Test
    fun foreignSessionPredecessorCannotProvidePlacementEvidence() =
        runTest {
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            repository.persistMessage(earlier.copy(id = "foreign", restId = "rest-other-100"), "other")
            repository.persistMessages(listOf(earlier, later), "s")
            repository.persistMessage(command.copy(localAnchorOrder = 10L, localPredecessorId = "foreign"), "s")
            val restored = repository.loadPage("s", null, 150).messages
            assertEquals(10L, restored.single { it.id == command.id }.localAnchorOrder)
            assertEquals(
                listOf(earlier.id, command.id, later.id),
                mergeCachedTranscriptPage(restored, emptyList()).map { it.id },
            )
        }
}
