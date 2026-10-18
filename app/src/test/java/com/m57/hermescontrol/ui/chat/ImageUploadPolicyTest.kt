package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.BusySendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.IOException

class ImageUploadPolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun animatedAndNonImageFormatsAreNeverOptimizationCandidates() {
        for (mime in listOf("image/gif", "image/webp", "image/avif", "application/pdf")) {
            assertFalse(Attachment("uri", "image", mime).canOptimizePhoto())
        }
        assertTrue(Attachment("uri", "photo.jpg", "image/jpeg").canOptimizePhoto())
        assertTrue(Attachment("uri", "photo.heif", "image/heif").canOptimizePhoto())
        assertFalse(Attachment("uri", "photo.jpg", "image/jpeg", source = AttachmentSource.GATEWAY).canOptimizePhoto())
    }

    @Test
    fun originalChoiceRetainsTheTransportLimitWhilePhotosHaveABoundedSourceAllowance() {
        val photo = Attachment("uri", "camera.jpg", "image/jpeg")
        assertEquals(MAX_PHOTO_SOURCE_BYTES, photo.sourceByteLimit())
        assertEquals(MAX_CHAT_ATTACHMENT_BYTES, photo.copy(sendOriginal = true).sourceByteLimit())
        assertEquals(MAX_CHAT_ATTACHMENT_BYTES, photo.copy(mimeType = "image/gif").sourceByteLimit())
        assertEquals(MAX_CHAT_ATTACHMENT_BYTES, photo.copy(mimeType = "application/pdf").sourceByteLimit())
    }

    @Test
    fun decodeIsBoundedOnBothAxesWithoutUpscaling() {
        assertEquals(1, photoSampleSize(640, 480))
        assertEquals(1, photoSampleSize(2048, 2048))
        assertEquals(4, photoSampleSize(8000, 6000))
        assertEquals(4, photoSampleSize(6000, 8000))
        assertEquals(8, photoSampleSize(16000, 100))
    }

    @Test
    fun originalChoiceIsPerImageAndImmutableInQueuedRetrySnapshots() {
        val state = MutableStateFlow(ChatUiState())
        val delegate = ChatAttachmentsDelegate(state)
        val first = Attachment("one", "one.jpg", "image/jpeg")
        val second = Attachment("two", "two.png", "image/png")
        delegate.addAttachments(listOf(first, second))
        delegate.setSendOriginal(0, true)
        val queued =
            PendingSend("id", "server/profile", "session", "look", state.value.pendingAttachments, BusySendMode.QUEUE)
        delegate.setSendOriginal(0, false)
        delegate.setSendOriginal(1, true)
        val restored = Json.decodeFromString<PendingSend>(Json.encodeToString(queued.copy(attempts = 1)))
        assertTrue(restored.attachments[0].sendOriginal)
        assertFalse(restored.attachments[1].sendOriginal)
        assertFalse(state.value.pendingAttachments[0].sendOriginal)
        assertTrue(state.value.pendingAttachments[1].sendOriginal)
        assertFalse(
            Json.decodeFromString<Attachment>("""{"uri":"x","name":"old.jpg","mimeType":"image/jpeg"}""").sendOriginal,
        )
    }

    @Test
    fun smallImageBytesAndChosenMetadataArePreservedAndOnlyOneFileIsRetained() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val attachment = Attachment("uri", "small.png", "image/png", 999)
            val prepared = preparePhotoUpload(attachment, temp.root) { ByteArrayInputStream(bytes) }!!
            assertArrayEquals(bytes, prepared.file.readBytes())
            assertEquals(attachment.copy(size = 4), prepared.attachment)
            assertEquals(listOf(prepared.file), temp.root.listFiles()!!.toList())
            prepared.file.delete()
            assertTrue(temp.root.listFiles()!!.isEmpty())
        }

    @Test
    fun readFailureAndUnavailableSourceLeaveNoFiles() =
        runTest {
            assertNull(preparePhotoUpload(Attachment("uri", "photo.jpg", "image/jpeg"), temp.root) { null })
            try {
                preparePhotoUpload(
                    Attachment("uri", "photo.jpg", "image/jpeg"),
                    temp.root,
                ) { throw IOException("unavailable") }
                fail("expected IOException")
            } catch (_: IOException) {
                assertTrue(temp.root.listFiles()!!.isEmpty())
            }
        }

    @Test
    fun apngAndMalformedPngArePreserved() {
        fun png(vararg chunks: String) =
            temp.newFile().apply {
                outputStream().use { stream ->
                    DataOutputStream(stream).use { out ->
                        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                        for (chunk in chunks) {
                            out.writeInt(0)
                            out.writeBytes(chunk)
                            out.writeInt(0)
                        }
                    }
                }
            }
        assertTrue(png("IHDR", "acTL", "IDAT").isAnimatedPng())
        assertFalse(png("IHDR", "IDAT").isAnimatedPng())
        assertTrue(temp.newFile().apply { writeText("broken PNG") }.isAnimatedPng())
    }

    @Test
    fun sourceLimitAndCancellationCleanTheirPartialSnapshot() =
        runTest {
            val attachment = Attachment("uri", "photo.jpg", "image/jpeg")
            val input =
                object : java.io.InputStream() {
                    var remaining = MAX_PHOTO_SOURCE_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(remaining, length.toLong()).toInt()
                        remaining -= count
                        return count
                    }
                }
            try {
                preparePhotoUpload(attachment, temp.root) { input }
                fail("expected source limit")
            } catch (_: PhotoSourceTooLarge) {
                assertTrue(temp.root.listFiles()!!.isEmpty())
            }
            try {
                preparePhotoUpload(
                    attachment,
                    temp.root,
                ) { throw kotlinx.coroutines.CancellationException("cancelled") }
                fail("expected cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) {
                assertTrue(temp.root.listFiles()!!.isEmpty())
            }
        }
}
