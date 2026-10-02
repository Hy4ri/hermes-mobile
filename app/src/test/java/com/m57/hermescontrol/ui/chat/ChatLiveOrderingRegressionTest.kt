package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression coverage for issue #1451:
 * Transcript ordering when REST confirms a newer prompt while older live rows
 * (observed live user/tool + review notices) remain unmatched/unconfirmed.
 */
class ChatLiveOrderingRegressionTest {
    @Test
    fun unconfirmedLiveUserAndToolWithReviewNoticesStayBeforeConfirmedNewerPrompt() {
        // Given an observed live history:
        // 1. Live user prompt (unconfirmed by REST)
        // 2. Live tool call / result (unconfirmed by REST)
        // 3. First system review notice
        // 4. Second system review notice
        // 5. Later live user prompt
        val liveUserOld =
            ChatMessage(
                id = "live-user-old",
                role = MessageRole.USER,
                content = "first task",
                timestamp = 1_000L,
            )
        val liveTool =
            ChatMessage(
                id = "live-tool-1",
                role = MessageRole.TOOL,
                content = """{"result":{"output":"task done","exit_code":0}}""",
                toolName = "terminal",
                toolCallId = "call_task_1",
                timestamp = 1_100L,
            )
        val systemReview1 =
            ChatMessage(
                id = "sys-review-1",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: skill updated",
                timestamp = 1_200L,
            )
        val systemReview2 =
            ChatMessage(
                id = "sys-review-2",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: playbook refreshed",
                timestamp = 1_300L,
            )
        val liveUserNew =
            ChatMessage(
                id = "live-user-new",
                role = MessageRole.USER,
                content = "second prompt",
                timestamp = 1_400L,
            )

        val currentMessages =
            listOf(
                liveUserOld,
                liveTool,
                systemReview1,
                systemReview2,
                liveUserNew,
            )

        // REST sync only returns/confirms the second prompt (e.g. rest-s-5),
        // while the older live user/tool and review notices remain unmatched in REST.
        val restConfirmedNew =
            ChatMessage(
                id = "rest-s-5",
                role = MessageRole.USER,
                content = "second prompt",
                timestamp = 1_450L,
            )
        val restMessages = listOf(restConfirmedNew)

        // When merged with chronological = true, preserveLiveIds = false
        val merged =
            mergeTranscriptWithLive(
                restMessages = restMessages,
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )

        // Then:
        // 1. All rows must be preserved.
        assertEquals(5, merged.size)

        // 2. Ordering contract: the older live user, tool, and two system review notices
        //    MUST remain before the newer confirmed prompt.
        val expectedIds =
            listOf(
                "live-user-old",
                "live-tool-1",
                "sys-review-1",
                "sys-review-2",
                "live-user-new", // Merged with rest-s-5; when role=USER, match's ID is preserved, restId recorded
            )
        assertEquals(expectedIds, merged.map { it.id })

        // 3. The confirmed prompt acquires its canonical REST identity
        val confirmedRow = merged.last()
        assertEquals("rest-s-5", confirmedRow.canonicalRestId)
        assertEquals(MessageRole.USER, confirmedRow.role)
        assertEquals("second prompt", confirmedRow.content)

        // 4. Older unconfirmed rows must NOT invent or acquire rest identities
        val unconfirmedRows = merged.take(4)
        unconfirmedRows.forEach { row ->
            assertNotEquals("rest-s-5", row.restId)
            assertEquals(null, row.canonicalRestId)
        }

        // 5. Repeated merge must be idempotent
        val repeatedMerge =
            mergeTranscriptWithLive(
                restMessages = restMessages,
                currentMessages = merged,
                chronological = true,
                preserveLiveIds = false,
            )
        assertEquals(merged.map { it.id }, repeatedMerge.map { it.id })
        assertEquals(merged, repeatedMerge)
    }

    @Test
    fun controlPlainAppendAndNonConfirmationMaintainsObservedLiveOrder() {
        // Control scenario:
        // Current live transcript contains an old user prompt, tool call, two review notices,
        // and a later user prompt.
        // REST returns no confirmations (emptyList) or an unmatching server message.
        val liveUserOld =
            ChatMessage(
                id = "live-user-old",
                role = MessageRole.USER,
                content = "first task",
                timestamp = 1_000L,
            )
        val liveTool =
            ChatMessage(
                id = "live-tool-1",
                role = MessageRole.TOOL,
                content = """{"result":{"output":"task done","exit_code":0}}""",
                toolName = "terminal",
                toolCallId = "call_task_1",
                timestamp = 1_100L,
            )
        val systemReview1 =
            ChatMessage(
                id = "sys-review-1",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: note 1",
                timestamp = 1_200L,
            )
        val systemReview2 =
            ChatMessage(
                id = "sys-review-2",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: note 2",
                timestamp = 1_300L,
            )
        val liveUserNew =
            ChatMessage(
                id = "live-user-new",
                role = MessageRole.USER,
                content = "second prompt",
                timestamp = 1_400L,
            )

        val currentMessages =
            listOf(
                liveUserOld,
                liveTool,
                systemReview1,
                systemReview2,
                liveUserNew,
            )

        // Case A: REST sync is empty
        val mergedEmpty =
            mergeTranscriptWithLive(
                restMessages = emptyList(),
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )
        assertEquals(
            listOf(
                "live-user-old",
                "live-tool-1",
                "sys-review-1",
                "sys-review-2",
                "live-user-new",
            ),
            mergedEmpty.map {
                it.id
            },
        )

        // Case B: REST returns an unrelated / earlier server message (e.g. session start rest-s-0)
        val canonicalPrior =
            ChatMessage(
                id = "rest-s-0",
                role = MessageRole.USER,
                content = "very first conversation starter",
                timestamp = 500L,
            )
        val mergedWithPrior =
            mergeTranscriptWithLive(
                restMessages = listOf(canonicalPrior),
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )

        // Canonical server rows maintain server order, while observed live unconfirmed block stays intact
        assertEquals(
            listOf("rest-s-0", "live-user-old", "live-tool-1", "sys-review-1", "sys-review-2", "live-user-new"),
            mergedWithPrior.map { it.id },
        )

        // Idempotence check on control
        val idempotentMergedWithPrior =
            mergeTranscriptWithLive(
                restMessages = listOf(canonicalPrior),
                currentMessages = mergedWithPrior,
                chronological = true,
                preserveLiveIds = false,
            )
        assertEquals(mergedWithPrior.map { it.id }, idempotentMergedWithPrior.map { it.id })
    }

    @Test
    fun sessionStartMarkerRemainsBeforeOldUnresolvedUserToolReviewBlockWhenNewerUserConfirmsAsRestS0() {
        val sessionStart =
            ChatMessage(
                id = "system-session-start",
                role = MessageRole.SYSTEM,
                content = "Session created",
                timestamp = 100L,
            )
        val liveUserOld =
            ChatMessage(
                id = "live-user-old",
                role = MessageRole.USER,
                content = "unconfirmed first prompt",
                timestamp = 200L,
            )
        val liveTool =
            ChatMessage(
                id = "live-tool-1",
                role = MessageRole.TOOL,
                content = """{"result":"done"}""",
                toolName = "terminal",
                toolCallId = "call_1",
                timestamp = 300L,
            )
        val sysReview =
            ChatMessage(
                id = "sys-review-1",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: note",
                timestamp = 400L,
            )
        val liveUserNew =
            ChatMessage(
                id = "live-user-new",
                role = MessageRole.USER,
                content = "confirmed second prompt",
                timestamp = 500L,
            )

        val currentMessages =
            listOf(
                sessionStart,
                liveUserOld,
                liveTool,
                sysReview,
                liveUserNew,
            )

        // Newer observed USER confirms as canonical index 0 (rest-s-0)
        val restConfirmedNew =
            ChatMessage(
                id = "rest-s-0",
                role = MessageRole.USER,
                content = "confirmed second prompt",
                timestamp = 550L,
            )

        val merged =
            mergeTranscriptWithLive(
                restMessages = listOf(restConfirmedNew),
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )

        val expectedIds =
            listOf(
                "system-session-start",
                "live-user-old",
                "live-tool-1",
                "sys-review-1",
                "live-user-new",
            )
        assertEquals(expectedIds, merged.map { it.id })
        assertEquals("rest-s-0", merged.last().canonicalRestId)
    }

    @Test
    fun canonicalRowsRemainServerOrderedAcrossGapsWithOldLiveBlockBeforeNextConfirmedUserAcrossSkewedTimestamps() {
        // Skewed/inverted timestamps across rows
        val canonicalPrior =
            ChatMessage(
                id = "rest-s-2",
                role = MessageRole.USER,
                content = "prior prompt",
                timestamp = 8_000L, // skewed higher timestamp than later turns
            )
        val liveUserOld =
            ChatMessage(
                id = "live-user-old",
                role = MessageRole.USER,
                content = "unresolved old prompt",
                timestamp = 2_000L,
            )
        val liveTool =
            ChatMessage(
                id = "live-tool-1",
                role = MessageRole.TOOL,
                content = """{"result":"ok"}""",
                toolName = "terminal",
                toolCallId = "call_skew",
                timestamp = 2_000L, // equal timestamp
            )
        val sysReview =
            ChatMessage(
                id = "sys-review-1",
                role = MessageRole.SYSTEM,
                content = "💾 Self-improvement review: updated",
                timestamp = 1_000L, // earlier timestamp
            )
        val liveUserNew =
            ChatMessage(
                id = "live-user-new",
                role = MessageRole.USER,
                content = "next user prompt",
                timestamp = 3_000L,
            )

        val currentMessages =
            listOf(
                canonicalPrior,
                liveUserOld,
                liveTool,
                sysReview,
                liveUserNew,
            )

        // Gap in canonical sequence: rest-s-2 then rest-s-9 (gap 3..8 missing/unloaded)
        val canonicalFollow =
            ChatMessage(
                id = "rest-s-9",
                role = MessageRole.USER,
                content = "next user prompt",
                timestamp = 2_500L, // skewed timestamp compared to rest-s-2
            )
        val restMessages = listOf(canonicalPrior, canonicalFollow)

        val merged =
            mergeTranscriptWithLive(
                restMessages = restMessages,
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )

        val expectedIds =
            listOf(
                "rest-s-2",
                "live-user-old",
                "live-tool-1",
                "sys-review-1",
                "live-user-new",
            )
        assertEquals(expectedIds, merged.map { it.id })

        // Repeated merge must remain identical
        val repeatedMerge =
            mergeTranscriptWithLive(
                restMessages = restMessages,
                currentMessages = merged,
                chronological = true,
                preserveLiveIds = false,
            )
        assertEquals(merged.map { it.id }, repeatedMerge.map { it.id })
        assertEquals(merged, repeatedMerge)
    }

    @Test
    fun assistantConfirmationDoesNotPullUnresolvedUserOutOfPendingTail() {
        val unresolvedUser =
            ChatMessage(
                id = "live-user-unresolved",
                role = MessageRole.USER,
                content = "prompt not matching rest",
                timestamp = 1_000L,
            )
        val liveTool =
            ChatMessage(
                id = "live-tool-1",
                role = MessageRole.TOOL,
                content = """{"result":"in progress"}""",
                toolName = "terminal",
                toolCallId = "call_assistant_test",
                timestamp = 1_100L,
            )
        val liveAssistant =
            ChatMessage(
                id = "live-assistant-1",
                role = MessageRole.ASSISTANT,
                content = "Here is the response",
                timestamp = 1_200L,
            )

        val currentMessages =
            listOf(
                unresolvedUser,
                liveTool,
                liveAssistant,
            )

        // REST confirms only the ASSISTANT message as rest-s-4 (e.g. user was dropped or mismatched)
        val restConfirmedAssistant =
            ChatMessage(
                id = "rest-s-4",
                role = MessageRole.ASSISTANT,
                content = "Here is the response",
                timestamp = 1_200L,
            )

        val merged =
            mergeTranscriptWithLive(
                restMessages = listOf(restConfirmedAssistant),
                currentMessages = currentMessages,
                chronological = true,
                preserveLiveIds = false,
            )

        // Assistant confirmation alone must not anchor/pull the unresolved user or tool
        // before rest-s-4. The confirmed assistant appears first, unresolved user/tool remain in pending tail.
        val expectedIds =
            listOf(
                "rest-s-4",
                "live-user-unresolved",
                "live-tool-1",
            )
        assertEquals(expectedIds, merged.map { it.id })
    }
}
