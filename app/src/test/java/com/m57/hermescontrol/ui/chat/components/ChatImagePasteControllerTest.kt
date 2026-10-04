package com.m57.hermescontrol.ui.chat.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatAttachmentTarget
import com.m57.hermescontrol.ui.chat.PastedImage
import com.m57.hermescontrol.ui.chat.PastedImageTooLargeException
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

@OptIn(ExperimentalFoundationApi::class, ExperimentalCoroutinesApi::class)
class ChatImagePasteControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val target = ChatAttachmentTarget("session", 1, "http://test.local", null, null)

    private fun image(name: String): PastedImage {
        val file = temp.newFile(name).apply { writeBytes(byteArrayOf(1, 2)) }
        return PastedImage(Attachment("content://test/$name", name, "image/png", file.length()), file)
    }

    @Test
    fun successPublishesAnOrderedBatchOnlyAfterEveryImageIsCopied() =
        runTest {
            val first = image("one.png")
            val second = image("two.png")
            val gate = CompletableDeferred<Unit>()
            val committed = mutableListOf<Attachment>()
            val errors = mutableListOf<Int>()
            var count = 0
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ ->
                        if (count++ == 0) {
                            first
                        } else {
                            gate.await()
                            second
                        }
                    },
                    captureTarget = { target },
                    commit = { _, attachments ->
                        committed.addAll(attachments)
                        true
                    },
                    onError = errors::add,
                )
            assertTrue(controller.receiveImages(listOf(mockk<Uri>(), mockk<Uri>()), Any()))
            assertTrue(controller.isReceiving)
            runCurrent()
            assertTrue(committed.isEmpty())
            gate.complete(Unit)
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertFalse(controller.isReceiving)
            assertEquals(listOf(first.attachment, second.attachment), committed)
            assertTrue(first.file.exists() && second.file.exists())
            assertTrue(errors.isEmpty())
        }

    @Test
    fun laterUnreadableImageRollsBackTheEntireBatch() =
        runTest {
            val first = image("rollback.png")
            var count = 0
            var commits = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ -> if (count++ == 0) first else null },
                    captureTarget = { target },
                    commit = { _, _ ->
                        commits++
                        true
                    },
                    onError = errors::add,
                )
            controller.receiveImages(listOf(mockk<Uri>(), mockk<Uri>()), Any())
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(0, commits)
            assertFalse(first.file.exists())
            assertEquals(listOf(R.string.chat_image_paste_failed), errors)
            assertFalse(controller.isReceiving)
        }

    @Test
    fun sessionChangeDuringCopyDiscardsImageWithoutTouchingTheNewChat() =
        runTest {
            val result = image("stale.png")
            val gate = CompletableDeferred<Unit>()
            var owner = target
            var commits = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ ->
                        gate.await()
                        result
                    },
                    captureTarget = { owner },
                    commit = { _, _ ->
                        commits++
                        true
                    },
                    onError = errors::add,
                )
            controller.receiveImages(listOf(mockk<Uri>()), Any())
            runCurrent()
            owner = target.copy(generation = 3)
            gate.complete(Unit)
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(0, commits)
            assertFalse(result.file.exists())
            assertTrue(errors.isEmpty())
        }

    @Test
    fun disposalCancelsCopyAndDeletesOnlyUncommittedFiles() =
        runTest {
            val first = image("cancel.png")
            var count = 0
            var commits = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ -> if (count++ == 0) first else awaitCancellation() },
                    captureTarget = { target },
                    commit = { _, _ ->
                        commits++
                        true
                    },
                    onError = errors::add,
                )
            controller.receiveImages(listOf(mockk<Uri>(), mockk<Uri>()), Any())
            runCurrent()
            controller.close()
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertFalse(first.file.exists())
            assertEquals(0, commits)
            assertTrue(errors.isEmpty())
            assertFalse(controller.receiveImages(listOf(mockk<Uri>()), Any()))
        }

    @Test
    fun unreadySessionNeverOpensTheProvider() =
        runTest {
            var reads = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ ->
                        reads++
                        null
                    },
                    captureTarget = { null },
                    commit = { _, _ -> error("Must not commit") },
                    onError = errors::add,
                )
            assertTrue(controller.receiveImages(listOf(mockk<Uri>()), Any()))
            assertEquals(0, reads)
            assertEquals(listOf(R.string.chat_session_not_ready), errors)
            assertFalse(controller.isReceiving)
        }

    @Test
    fun secondPasteWhileImportingDoesNotStartAnotherBatch() =
        runTest {
            val result = image("busy.png")
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ ->
                        reads++
                        gate.await()
                        result
                    },
                    captureTarget = { target },
                    commit = { _, _ -> true },
                    onError = errors::add,
                )
            controller.receiveImages(listOf(mockk<Uri>()), Any())
            controller.receiveImages(listOf(mockk<Uri>()), Any())
            gate.complete(Unit)
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(1, reads)
            assertEquals(listOf(R.string.chat_image_paste_busy), errors)
        }

    @Test
    fun ninthImageRejectsBeforeOpeningAnyProvider() =
        runTest {
            var reads = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ ->
                        reads++
                        null
                    },
                    captureTarget = { target },
                    commit = { _, _ -> error("Must not commit") },
                    onError = errors::add,
                )
            assertTrue(controller.receiveImages(List(9) { mockk<Uri>() }, Any()))
            runCurrent()
            assertEquals(0, reads)
            assertEquals(listOf(R.string.chat_image_paste_batch_limit), errors)
            assertFalse(controller.isReceiving)
        }

    @Test
    fun exactBatchLimitKeepsQueuedFilesAndPassesRemainingBudget() =
        runTest {
            val first = image("first-large.png")
            val second = image("second-large.png")
            listOf(first, second).forEach {
                RandomAccessFile(it.file, "rw").use { file ->
                    file.setLength(10L * 1024 * 1024)
                }
            }
            val caps = mutableListOf<Long>()
            val committed = mutableListOf<Attachment>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, cap ->
                        caps += cap
                        if (caps.size == 1) first else second
                    },
                    captureTarget = { target },
                    commit = { _, attachments ->
                        committed.addAll(attachments)
                        true
                    },
                    onError = { error("Unexpected error: $it") },
                )
            controller.receiveImages(List(2) { mockk<Uri>() }, Any())
            runCurrent()
            assertEquals(listOf(10L * 1024 * 1024, 10L * 1024 * 1024), caps)
            assertEquals(listOf(first.attachment, second.attachment), committed)
            assertTrue(first.file.exists() && second.file.exists())
        }

    @Test
    fun exhaustedBudgetRejectsNextWithoutIoAndRollsBack() =
        runTest {
            val first = image("full-a.png")
            val second = image("full-b.png")
            listOf(first, second).forEach {
                RandomAccessFile(it.file, "rw").use { file ->
                    file.setLength(10L * 1024 * 1024)
                }
            }
            var reads = 0
            var commits = 0
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, _ -> if (++reads == 1) first else second },
                    captureTarget = { target },
                    commit = { _, _ ->
                        commits++
                        true
                    },
                    onError = errors::add,
                )
            controller.receiveImages(List(3) { mockk<Uri>() }, Any())
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(2, reads)
            assertEquals(0, commits)
            assertFalse(first.file.exists() || second.file.exists())
            assertEquals(listOf(R.string.chat_image_paste_batch_limit), errors)
        }

    @Test
    fun remainingBudgetOverflowReportsBatchLimitAndCleansUp() =
        runTest {
            val first = image("partial-a.png")
            val second = image("partial-b.png")
            RandomAccessFile(first.file, "rw").use { it.setLength(10L * 1024 * 1024) }
            RandomAccessFile(second.file, "rw").use { it.setLength(2L * 1024 * 1024) }
            val caps = mutableListOf<Long>()
            val errors = mutableListOf<Int>()
            val controller =
                ChatImagePasteController(
                    this,
                    importImage = { _, cap ->
                        caps += cap
                        when (caps.size) {
                            1 -> first
                            2 -> second
                            else -> throw PastedImageTooLargeException()
                        }
                    },
                    captureTarget = { target },
                    commit = { _, _ -> error("Must not commit") },
                    onError = errors::add,
                )
            controller.receiveImages(List(3) { mockk<Uri>() }, Any())
            coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(listOf(10L * 1024 * 1024, 10L * 1024 * 1024, 8L * 1024 * 1024), caps)
            assertFalse(first.file.exists() || second.file.exists())
            assertEquals(listOf(R.string.chat_image_paste_batch_limit), errors)
        }
}
