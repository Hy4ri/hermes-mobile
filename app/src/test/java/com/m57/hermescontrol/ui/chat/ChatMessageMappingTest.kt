package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure transcript transformations do not need the ViewModel transport and Android fixtures. */
class ChatMessageMappingTest {
    @Test
    fun mergeTranscriptWithLive_collapsesDuplicateIdsKeepingLatestMessage() {
        val stale = ChatMessage(id = "duplicate-id", role = MessageRole.ASSISTANT, content = "stale")
        val latest = stale.copy(content = "latest")

        val merged = mergeTranscriptWithLive(emptyList(), listOf(stale, latest))

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun mergeTranscriptWithLive_matchingUserContentCollapsesLocalAndRestCopies() {
        // /queue bubbles show the stripped queued text, so the optimistic
        // local copy and the later REST echo share content — the sync merge
        // must collapse them into one row instead of rendering a duplicate
        // below its answer (PR #892 follow-up).
        val local =
            ChatMessage(
                id = "ws-local-1",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-5",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        // The richer local copy wins when both sides carry the same content.
        assertEquals("ws-local-1", merged.single().id)
    }

    @Test
    fun mergeTranscriptWithLive_collapsesGatewayAttachmentContextCopy() {
        val local =
            ChatMessage(
                id = "ws-local-attachment",
                role = MessageRole.USER,
                content = "What is the secret word in the attached file?",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-attachment",
                role = MessageRole.USER,
                content =
                    """
                    @file:files/agent-vault/hermes/attachments/note.txt

                    What is the secret word in the attached file?

                    --- Attached Context ---

                    📄 @file:files/agent-vault/hermes/attachments/note.txt (8 tokens)
                    ```
                    THE_SECRET_WORD_IS_MANGO_8421
                    ```
                    """.trimIndent(),
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        assertEquals("ws-local-attachment", merged.single().id)
    }

    @Test
    fun mapServerMessages_restOnlyAttachmentHidesGatewayContext() {
        val ref = "@file:files/agent-vault/hermes/attachments/note.txt"
        val persisted =
            """
            $ref

            What is the secret word in the attached file?

            --- Attached Context ---

            📄 $ref (8 tokens)
            ```
            THE_SECRET_WORD_IS_MANGO_8421
            ```
            """.trimIndent()

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive(persisted),
                            timestamp = JsonPrimitive("100"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals(1, mapped.size)
        assertEquals(
            """
            $ref

            What is the secret word in the attached file?
            """.trimIndent(),
            mapped.single().content,
        )
        assertFalse(mapped.single().content.contains("--- Attached Context ---"))
        assertFalse(mapped.single().content.contains("THE_SECRET_WORD_IS_MANGO_8421"))
    }

    @Test
    fun mapServerMessages_prefersCompactionDisplayContent() {
        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive("[model-facing compaction summary]"),
                            display_content = JsonPrimitive("The original user prompt"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals("The original user prompt", mapped.single().content)
    }

    @Test
    fun stripAttachmentRefLines_preservesUserAuthoredAttachedContextHeading() {
        val authored =
            """
            Explain this heading:
            --- Attached Context ---
            this is ordinary user-authored text
            """.trimIndent()

        assertEquals(authored, stripAttachmentRefLines(authored))
    }
}
