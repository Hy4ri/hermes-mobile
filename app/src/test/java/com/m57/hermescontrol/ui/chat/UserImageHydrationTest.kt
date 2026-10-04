package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for user image hydration:
 * - Direct unit tests for [userImageAttachments]
 * - Integration via [mapServerMessages] with USER turns
 */
class UserImageHydrationTest {
    @Test
    fun issue1459PlainHistorySurvivesLiveMergeAndCacheRestore() {
        val row =
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SessionMessage>(
                """
                {"id":344596,"role":"user","active":1,"compacted":0,
                 "content":"图片附件测试\n@image:/opt/data/images/upload_20261003_211435_1.jpg\n[screenshot]"}
                """.trimIndent(),
            )
        val session = "20261003_211412_83b369"
        val remote =
            mapServerMessages(
                sessionId = session,
                messages = listOf(row),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { "https://gateway.test/api/files/download?path=$it" },
            ).single()
        assertEquals(1, remote.attachments?.size)
        assertEquals("upload_20261003_211435_1.jpg", remote.attachments!!.single().name)
        val local =
            remote.copy(
                id = "local-prompt",
                restId = remote.id,
                attachments =
                    listOf(
                        com.m57.hermescontrol.data.model.Attachment(
                            "content://photos/1",
                            "photo.jpg",
                            "image/jpeg",
                        ),
                    ),
            )
        val merged = mergeTranscriptWithLive(listOf(remote), listOf(local)).single()
        // #1459: a confirmed gateway image replaces the optimistic local file source.
        assertEquals(remote.attachments, merged.attachments)
        assertEquals(local.id, merged.id)
        val cached = merged.toEntity(session).toUiModel()
        val restored =
            cached.copy(
                isHistoricalCache = true,
                attachments = userImageAttachments(cached.content) { "https://new-gateway.test/download?path=$it" },
            )
        val cacheMerged = mergeCachedTranscriptPage(listOf(restored), emptyList()).single()
        val refreshed = mergeTranscriptWithLive(listOf(remote), listOf(cacheMerged)).single()
        assertEquals(1, refreshed.attachments?.size)
        assertEquals(AttachmentSource.GATEWAY, refreshed.attachments!!.single().source)
        assertEquals(row.contentText, refreshed.content)
        assertEquals(refreshed, mergeTranscriptWithLive(listOf(remote), listOf(refreshed)).single())
    }

    private fun imageRow(
        id: Int,
        vararg paths: String,
        caption: String = "caption",
    ): ChatMessage =
        mapServerMessages(
            sessionId = "session-1",
            messages =
                listOf(
                    SessionMessage(
                        id = id,
                        role = "user",
                        content = JsonPrimitive(caption + paths.joinToString("") { "\n@image:$it" } + "\n[screenshot]"),
                    ),
                ),
            offset = 0,
            latestPaging = true,
            liveMessages = emptyList(),
            mediaUrl = { "https://gateway.test/download?path=$it" },
        ).single()

    private fun localImage(name: String) =
        com.m57.hermescontrol.data.model
            .Attachment("file:///private/chat-send/x/$name", name, "image/jpeg")

    @Test
    fun issue1459ConfirmedImageSetReplacesLocalFilesAndKeepsOtherAttachmentsAndNoModelScaffolding() {
        val remote = imageRow(7, "/opt/data/images/a.jpg", "/opt/data/images/b.jpg")
        val document =
            com.m57.hermescontrol.data.model.Attachment(
                "file:///private/chat-send/x/doc",
                "notes.pdf",
                "application/pdf",
            )
        val local =
            ChatMessage(
                id = "local-1",
                role = MessageRole.USER,
                content = "caption",
                attachments = listOf(localImage("one.jpg"), document, localImage("two.jpg")),
            )
        val merged = mergeTranscriptWithLive(listOf(remote), listOf(local)).single()
        assertEquals(listOf("a.jpg", "b.jpg", "notes.pdf"), merged.attachments!!.map { it.name })
        assertEquals(AttachmentSource.GATEWAY, merged.attachments!!.first().source)
        assertEquals(document, merged.attachments!!.last())
        // Durable refs survive; the model-facing [screenshot] placeholder never enters the live bubble.
        assertTrue(merged.content.contains("@image:/opt/data/images/a.jpg"))
        assertTrue(merged.content.contains("@image:/opt/data/images/b.jpg"))
        assertFalse(merged.content.contains("[screenshot]"))
        assertEquals("caption", hideImageRefLines(merged.content))
        assertEquals(merged, mergeTranscriptWithLive(listOf(remote), listOf(merged)).single())
    }

    @Test
    fun issue1459PartialConfirmedImageSetKeepsEveryLocalImage() {
        val remote = imageRow(8, "/opt/data/images/a.jpg")
        val local =
            ChatMessage(
                id = "local-2",
                role = MessageRole.USER,
                content = "caption",
                attachments = listOf(localImage("one.jpg"), localImage("two.jpg")),
            )
        val merged = mergeTranscriptWithLive(listOf(remote), listOf(local)).single()
        assertEquals(local.attachments, merged.attachments)
    }

    @Test
    fun issue1459CachedCaptionOnlyAliasRecoversImageRefsFromItsRestTwin() {
        val twin = imageRow(9, "/opt/data/images/upload.jpg", caption = "图片附件测试")
        val alias = ChatMessage(id = "uuid-9", role = MessageRole.USER, content = "图片附件测试")
        val deduped = dedupeCachedMessages(listOf(alias, twin))
        val row = deduped.single()
        assertEquals("uuid-9", row.id)
        assertTrue(row.content.contains("@image:/opt/data/images/upload.jpg"))
        assertEquals("upload.jpg", row.attachments?.single()?.name)
        assertEquals(row, dedupeCachedMessages(listOf(row, twin)).single())
    }

    @Test
    fun restHydrationEnrichesCachedUserWithoutReplacingLocalAttachments() {
        val rest =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(id = 42, role = "user", content = JsonPrimitive("caption\n@image:/image.png")),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { "https://gateway.test/image.png" },
            ).single()
        val cached =
            rest.copy(
                attachments = null,
                content = "[{\"image_url\":\"data:image/png;base64,old\"}]",
                isHistoricalCache = true,
            )
        val hydrated = mergeTranscriptWithLive(listOf(rest), listOf(cached)).single()
        assertEquals(rest.attachments, hydrated.attachments)
        assertEquals(rest.content, hydrated.content)
        assertEquals(cached.id, hydrated.id)
        val localAttachments =
            listOf(
                com.m57.hermescontrol.data.model
                    .Attachment("content://local/image", "image.png", "image/png"),
            )
        val local = rest.copy(attachments = localAttachments)
        // #1459: the confirmed gateway image supersedes a picker URI that may no longer be readable.
        assertEquals(rest.attachments, mergeTranscriptWithLive(listOf(rest), listOf(local)).single().attachments)
    }

    @Test
    fun roomRoundTripKeepsReferencesForGatewayReconstruction() {
        val content = "caption\n@image:/image.png"
        val message = ChatMessage(id = "cached", role = MessageRole.USER, content = content)
        val restored = message.toEntity("session-1").toUiModel()
        assertEquals(content, restored.content)
        val attachments = userImageAttachments(restored.content) { "https://current-gateway.test$it" }
        assertEquals("https://current-gateway.test/image.png", attachments.single().gatewayUrl)
    }

    @Test
    fun userImageAttachments_recognizesTrimmedWholeLineImageDirectiveWithAbsolutePaths() {
        val content =
            """
            Here is my photo
            @image:/absolute/path/to/cat.png
            And another one
              @image:/absolute/path/to/dog.jpg  
            """.trimIndent()

        val attachments =
            userImageAttachments(content) { path -> "https://gateway.local/api/files/download?path=$path" }

        assertEquals(2, attachments.size)

        val first = attachments[0]
        assertEquals("https://gateway.local/api/files/download?path=/absolute/path/to/cat.png", first.uri)
        assertEquals("cat.png", first.name)
        assertEquals("image/png", first.mimeType)
        assertEquals(0L, first.size)
        assertEquals("https://gateway.local/api/files/download?path=/absolute/path/to/cat.png", first.gatewayUrl)
        assertEquals(AttachmentSource.GATEWAY, first.source)
        assertTrue(first.isImage)

        val second = attachments[1]
        assertEquals("https://gateway.local/api/files/download?path=/absolute/path/to/dog.jpg", second.uri)
        assertEquals("dog.jpg", second.name)
        assertEquals("image/jpeg", second.mimeType)
        assertEquals(AttachmentSource.GATEWAY, second.source)
        assertTrue(second.isImage)
    }

    @Test
    fun userImageAttachments_allowsSpacesInAbsolutePaths() {
        val content = "@image:/Users/test user/My Documents/holiday photo 2026.png"

        val attachments =
            userImageAttachments(content) { path -> "https://gateway.local/api/files/download?path=$path" }

        assertEquals(1, attachments.size)
        val attachment = attachments.single()
        assertEquals(
            "https://gateway.local/api/files/download?path=/Users/test user/My Documents/holiday photo 2026.png",
            attachment.uri,
        )
        assertEquals("holiday photo 2026.png", attachment.name)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(AttachmentSource.GATEWAY, attachment.source)
    }

    @Test
    fun userImageAttachments_deduplicatesRepeatPathsKeepingFirst() {
        val content =
            """
            First mention
            @image:/images/duplicated.png
            Second mention
            @image:/images/duplicated.png
            Third mention with whitespace padding
              @image:/images/duplicated.png  
            Different image
            @image:/images/unique.png
            """.trimIndent()

        val attachments = userImageAttachments(content) { path -> "https://gateway.local/file?p=$path" }

        assertEquals(2, attachments.size)
        assertEquals("https://gateway.local/file?p=/images/duplicated.png", attachments[0].uri)
        assertEquals("duplicated.png", attachments[0].name)
        assertEquals("https://gateway.local/file?p=/images/unique.png", attachments[1].uri)
        assertEquals("unique.png", attachments[1].name)
    }

    @Test
    fun userImageAttachments_ignoresInlineProseEmptyAndRelativeReferences() {
        val content =
            """
            Do not match inline prose such as check out @image:/absolute/path/foo.png in middle of text
            Do not match trailing inline text @image:/path/bar.png extra
            Do not match relative path @image:relative/path/image.png
            @image:
            @image:   
            @image:relative/foo.jpg
            @image:./local/foo.png
            @image:../parent/foo.png
            @image:not-an-absolute-path.jpg
            Valid absolute path at start of line:
            @image:/valid/match.png
            """.trimIndent()

        val attachments = userImageAttachments(content) { path -> "http://gateway.test$path" }

        assertEquals(1, attachments.size)
        assertEquals("http://gateway.test/valid/match.png", attachments.single().uri)
        assertEquals("match.png", attachments.single().name)
    }

    @Test
    fun userImageAttachments_whenCallbackReturnsNull_returnsNoAttachments() {
        val content =
            """
            @image:/valid/photo1.jpg
            @image:/valid/photo2.png
            """.trimIndent()

        val attachments = userImageAttachments(content) { null }

        assertTrue(attachments.isEmpty())
    }

    @Test
    fun userImageAttachments_whenContentHasNoImageDirectives_returnsEmptyList() {
        val content = "Just a regular text conversation with no images attached."

        val attachments = userImageAttachments(content) { path -> "https://gw/$path" }

        assertTrue(attachments.isEmpty())
    }

    @Test
    fun mapServerMessages_withUserTurn_attachesGatewayImageFromPlainStringContent() {
        val userRow =
            SessionMessage(
                id = 1,
                role = "user",
                content = JsonPrimitive("Please review this design\n@image:/data/mockups/home.png"),
            )

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages = listOf(userRow),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { path -> "https://gateway.local/files?p=$path" },
            )

        assertEquals(1, mapped.size)
        val chatMessage = mapped.single()
        assertEquals(MessageRole.USER, chatMessage.role)
        assertTrue(chatMessage.content.contains("Please review this design"))
        assertTrue(chatMessage.content.contains("@image:/data/mockups/home.png"))

        val attachments = chatMessage.attachments
        assertNotNull(attachments)
        assertEquals(1, attachments!!.size)

        val att = attachments.single()
        assertEquals("https://gateway.local/files?p=/data/mockups/home.png", att.uri)
        assertEquals("home.png", att.name)
        assertEquals("image/png", att.mimeType)
        assertEquals(AttachmentSource.GATEWAY, att.source)
        assertTrue(att.isGateway)
    }

    @Test
    fun mapServerMessages_withUserMultipart748kPayload_preservesCaptionAndNoBase64InContent() {
        val largeBase64 = "data:image/png;base64," + "iVBORw0KGgoAAAANSUhEUgAA".repeat(31_000) // ~748k chars
        val caption = "Check out this screenshot\n@image:/opt/data/images/screen.png"

        val multipart =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", largeBase64)
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", caption)
                    },
                )
            }

        val userRow =
            SessionMessage(
                id = 42,
                role = "user",
                content = multipart,
            )

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages = listOf(userRow),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { path -> "https://gw.internal/download?path=$path" },
            )

        assertEquals(1, mapped.size)
        val chatMessage = mapped.single()

        // Content must preserve the caption and reference text, but contain no base64 payload
        assertTrue(chatMessage.content.contains("Check out this screenshot"))
        assertTrue(chatMessage.content.contains("@image:/opt/data/images/screen.png"))
        assertFalse(chatMessage.content.contains("data:image/png;base64"))
        assertFalse(chatMessage.content.contains("iVBORw0KGgoAAAANSUhEUgAA"))

        // Attachment hydrated from @image directive
        val attachments = chatMessage.attachments
        assertNotNull(attachments)
        assertEquals(1, attachments!!.size)

        val att = attachments.single()
        assertEquals("https://gw.internal/download?path=/opt/data/images/screen.png", att.uri)
        assertEquals("screen.png", att.name)
        assertEquals("image/png", att.mimeType)
        assertEquals(AttachmentSource.GATEWAY, att.source)
    }

    @Test
    fun mapServerMessages_withUserTurn_deduplicatesRepeatImagePaths() {
        val content =
            """
            Examining these photos:
            @image:/data/photos/target.jpg
            @image:/data/photos/target.jpg
            @image:/data/photos/target.jpg
            @image:/data/photos/another.png
            """.trimIndent()

        val userRow =
            SessionMessage(
                id = 5,
                role = "user",
                content = JsonPrimitive(content),
            )

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages = listOf(userRow),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { path -> "https://gw.internal$path" },
            )

        val chatMessage = mapped.single()
        val attachments = chatMessage.attachments
        assertNotNull(attachments)
        assertEquals(2, attachments!!.size)
        assertEquals("https://gw.internal/data/photos/target.jpg", attachments[0].uri)
        assertEquals("target.jpg", attachments[0].name)
        assertEquals("https://gw.internal/data/photos/another.png", attachments[1].uri)
        assertEquals("another.png", attachments[1].name)
    }

    @Test
    fun mapServerMessages_withUserTurn_whenGatewayUnavailable_preservesCaptionAndAddsNoAttachments() {
        val caption = "Look at this error log\n@image:/var/log/screenshot.png"
        val userRow =
            SessionMessage(
                id = 10,
                role = "user",
                content = JsonPrimitive(caption),
            )

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages = listOf(userRow),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
                mediaUrl = { null }, // Gateway unavailable
            )

        val chatMessage = mapped.single()
        assertTrue(chatMessage.content.contains("Look at this error log"))
        assertTrue(chatMessage.content.contains("@image:/var/log/screenshot.png"))
        assertNull(chatMessage.attachments)
    }

    @Test
    fun hideImageRefLines_removesOnlyPathLinesAndKeepsCaption() {
        val content = "caption\n@image:/opt/data/images/a.jpg\nmore text @image:/inline.png"
        assertEquals("caption\nmore text @image:/inline.png", hideImageRefLines(content))
        assertEquals("plain", hideImageRefLines("plain"))
    }
}
