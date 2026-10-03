package com.m57.hermescontrol.ui.chat.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatAttachmentTarget
import com.m57.hermescontrol.ui.chat.ChatPastedImageStore
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MAX_CHAT_ATTACHMENT_BYTES
import com.m57.hermescontrol.ui.chat.PastedImage
import com.m57.hermescontrol.ui.chat.PastedImageTooLargeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal const val MAX_PASTED_IMAGE_COUNT = 8
internal const val MAX_PASTED_IMAGE_BATCH_BYTES = 20L * 1024L * 1024L

private class PastedImageBatchLimitException : IOException("Pasted image batch exceeds its limit")

/** Receives explicit paste/IME commits only; never reads or observes the system clipboard. */
@OptIn(ExperimentalFoundationApi::class)
internal class ChatImagePasteController(
    private val scope: CoroutineScope,
    private val importImage: suspend (Uri, Long) -> PastedImage?,
    private val captureTarget: () -> ChatAttachmentTarget?,
    private val commit: (ChatAttachmentTarget, List<Attachment>) -> Boolean,
    private val onError: (Int) -> Unit,
    private val isImageUri: (Uri) -> Boolean = { false },
) : ReceiveContentListener {
    var isReceiving by mutableStateOf(false)
        private set

    private var closed = false
    private var job: Job? = null
    private val retainedPayloads = mutableSetOf<Any>()

    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
        if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
        // Drag grants have a different lifecycle; this feature handles paste and IME commits only.
        if (transferableContent.source != TransferableContent.Source.Clipboard &&
            transferableContent.source != TransferableContent.Source.Keyboard
        ) {
            return transferableContent
        }
        val clip = transferableContent.clipEntry.clipData
        val uris = ArrayList<Uri>(MAX_PASTED_IMAGE_COUNT + 1)
        for (index in 0 until clip.itemCount) {
            val uri = clip.getItemAt(index).uri
            if (uri?.scheme == "content" && isImageUri(uri)) {
                uris.add(uri)
                if (uris.size > MAX_PASTED_IMAGE_COUNT) break
            }
        }
        if (!receiveImages(uris, transferableContent)) return transferableContent
        // Only accepted image candidates are consumed. Text and non-image URIs
        // remain available to the ordinary text field/other receivers.
        val accepted = uris.toSet()
        // An item can carry both image URI and text. Keep that item for the text
        // field after importing its image; consuming it would drop its text.
        return transferableContent.consume { it.uri in accepted && it.text == null && it.htmlText == null }
    }

    /** [payload] strongly retains Compose's temporary InputContentInfo permission until IO ends. */
    internal fun receiveImages(
        uris: List<Uri>,
        payload: Any,
    ): Boolean {
        if (closed || !scope.isActive || uris.isEmpty()) return false
        if (isReceiving) {
            onError(R.string.chat_image_paste_busy)
            return true
        }
        if (uris.size > MAX_PASTED_IMAGE_COUNT) {
            onError(R.string.chat_image_paste_batch_limit)
            return true
        }
        val target = captureTarget()
        if (target == null) {
            onError(R.string.chat_session_not_ready)
            return true
        }
        isReceiving = true
        retainedPayloads.add(payload)
        job =
            scope.launch {
                val images = mutableListOf<PastedImage>()
                var remainingBytes = MAX_PASTED_IMAGE_BATCH_BYTES
                var committed = false
                try {
                    for (uri in uris) {
                        if (remainingBytes == 0L) throw PastedImageBatchLimitException()
                        val cap = minOf(remainingBytes, MAX_CHAT_ATTACHMENT_BYTES)
                        val image = importImage(uri, cap) ?: throw IOException("Unreadable pasted image")
                        images += image
                        remainingBytes -= image.file.length()
                        if (remainingBytes < 0L) throw PastedImageBatchLimitException()
                    }
                    currentCoroutineContext().ensureActive()
                    if (!closed && captureTarget() == target) {
                        committed = commit(target, images.map { it.attachment })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!closed && captureTarget() == target) {
                        onError(
                            if (failure is PastedImageBatchLimitException ||
                                (failure is PastedImageTooLargeException && remainingBytes < MAX_CHAT_ATTACHMENT_BYTES)
                            ) {
                                R.string.chat_image_paste_batch_limit
                            } else if (failure is PastedImageTooLargeException) {
                                R.string.chat_image_paste_too_large
                            } else {
                                R.string.chat_image_paste_failed
                            },
                        )
                    }
                } finally {
                    if (!committed) {
                        withContext(NonCancellable + Dispatchers.IO) { images.forEach { it.discard() } }
                    }
                    retainedPayloads.remove(payload)
                    isReceiving = false
                }
            }
        // Covers a scope cancelled before the launched coroutine ever starts.
        job?.invokeOnCompletion { retainedPayloads.remove(payload) }
        return true
    }

    fun close() {
        closed = true
        job?.cancel()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun rememberChatImagePaste(
    viewModel: ChatViewModel,
    onShowMessage: (String) -> Unit,
): ChatImagePasteController {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    val showMessage by rememberUpdatedState(onShowMessage)
    val controller =
        remember(viewModel, context, scope) {
            val store = ChatPastedImageStore(context.applicationContext)
            ChatImagePasteController(
                scope = scope,
                importImage = store::importImage,
                isImageUri = { uri ->
                    try {
                        context.contentResolver.getType(uri)?.let { type ->
                            type.startsWith("image/", ignoreCase = true) && type.length > "image/".length
                        } == true
                    } catch (_: RuntimeException) {
                        // Provider failures must never consume an unclassified URI.
                        false
                    }
                },
                captureTarget = viewModel::captureAttachmentTarget,
                commit = viewModel::addPastedAttachments,
                onError = { showMessage(resources.getString(it)) },
            )
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return controller
}
