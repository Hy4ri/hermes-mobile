package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1696: [withHostMedia] is the single derivation step for assistant `MEDIA:`
 * directives — live WS seals, REST rows and Room cache loads all run it, so
 * visible text never keeps a directive while the durable copy always does.
 */
class HostMediaAttachmentsTest {
    private val raw = "Mock ready\nMEDIA:/opt/data/mock.png"

    private fun derive(
        message: ChatMessage,
        url: (String) -> String? = { "gw://$it" },
    ): ChatMessage = message.withHostMedia(url)

    @Test
    fun stripsDirectiveAndAttachesResolvableMedia() {
        val derived = derive(ChatMessage(id = "m1", role = MessageRole.ASSISTANT, content = raw))

        assertEquals("Mock ready", derived.content)
        assertEquals(listOf("gw:///opt/data/mock.png"), derived.attachments?.map { it.uri })
        // Offset is the directive's position in the stripped text ("Mock ready").
        assertEquals(10, derived.attachments?.first()?.contentOffset)
    }

    @Test
    fun keepsRawTextAsPersistedContent() {
        val derived = derive(ChatMessage(id = "m1", role = MessageRole.ASSISTANT, content = raw))

        assertEquals(raw, derived.persistedContent)
    }

    @Test
    fun isIdempotent() {
        val once = derive(ChatMessage(id = "m1", role = MessageRole.ASSISTANT, content = raw))
        val twice = derive(once)

        assertEquals(once, twice)
        assertEquals(1, twice.attachments?.size)
    }

    @Test
    fun withoutGatewayStripsTextAndAddsNoAttachment() {
        val derived = derive(ChatMessage(id = "m1", role = MessageRole.ASSISTANT, content = raw), url = { null })

        assertEquals("Mock ready", derived.content)
        assertNull(derived.attachments)
        assertEquals(raw, derived.persistedContent)
    }

    @Test
    fun alreadyAttachedUrlStillStripsDirective() {
        // The old async attach could bail after adding the attachment but before
        // stripping, or have its write clobbered — derivation must fix the text
        // regardless of what is already attached.
        val preAttached =
            ChatMessage(
                id = "m1",
                role = MessageRole.ASSISTANT,
                content = raw,
                attachments =
                    listOf(
                        Attachment(
                            uri = "gw:///opt/data/mock.png",
                            name = "mock.png",
                            mimeType = "image/png",
                            source = AttachmentSource.GATEWAY,
                        ),
                    ),
            )
        val derived = derive(preAttached)

        assertEquals("Mock ready", derived.content)
        assertEquals(1, derived.attachments?.size)
    }

    @Test
    fun ignoresUserAndToolRows() {
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = raw)
        assertEquals(user, derive(user))
        val tool = ChatMessage(id = "t", role = MessageRole.TOOL, content = raw)
        assertEquals(tool, derive(tool))
    }

    @Test
    fun roomRoundTripKeepsDirectiveAndReDerives() {
        val live = derive(ChatMessage(id = "m1", role = MessageRole.ASSISTANT, content = raw))
        val entity = live.toEntity("s")

        // Room keeps the raw directive (#1696): it is the only durable
        // reference to the file, so a cache paint can always re-derive.
        assertTrue(entity.content.contains("MEDIA:"))

        val rederived = entity.toUiModel().withHostMedia { "gw://$it" }
        assertEquals(live.content, rederived.content)
        assertEquals(live.attachments?.map { it.uri }, rederived.attachments?.map { it.uri })
    }
}
