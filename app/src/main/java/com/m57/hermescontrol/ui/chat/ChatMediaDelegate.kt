package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns gateway/host media handling for [ChatViewModel]: attaching MEDIA paths
 * extracted from message content, opening and saving attachments through the
 * gateway file client, and the FileProvider/ACTION_VIEW plumbing.
 *
 * Verbatim extraction of the inline implementation (corral 1 of the 2026-08
 * ChatViewModel delegate refactor) — same collaborators, same behavior: the
 * shared `_uiState` flow, an application context provider, and a scope for
 * background fetches are supplied by the ViewModel at construction.
 */
class ChatMediaDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val getApplication: () -> Application,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val ioDispatcher: kotlin.coroutines.CoroutineContext,
) {
    fun attachHostMedia(
        sessionId: String,
        messageId: String,
    ) {
        val current = uiState.value.messages.find { it.id == messageId } ?: return
        val content = current.content
        val items = HostMediaExtractor.extract(content)
        if (items.isEmpty()) return

        val baseUrl = AuthManager.getBaseUrl()
        if (baseUrl.isBlank()) return
        // NOTE: no token gate here. Gated (basic-auth) dashboards download via
        // the session cookie, and even loopback hosts can serve files with an
        // empty query token — requiring a non-blank token dropped every MEDIA
        // attachment on gate-auth connections where getToken() returns null.

        val existingUrls =
            current.attachments
                .orEmpty()
                .mapNotNull { it.gatewayUrl }
                .toSet()
        val newAttachments =
            items.mapNotNull { item ->
                val token = AuthManager.getToken().orEmpty()
                val url = GatewayFileClient.buildMediaUrl(baseUrl, token, item.path) ?: return@mapNotNull null
                if (url in existingUrls) return@mapNotNull null
                Attachment(
                    uri = url,
                    name = mediaNameFromPath(item.path),
                    mimeType = mediaMimeForPath(item.path),
                    size = 0,
                    gatewayUrl = url,
                    source = AttachmentSource.GATEWAY,
                )
            }
        if (newAttachments.isEmpty()) return

        val stripped = HostMediaExtractor.strip(content)
        uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { msg ->
                        if (msg.id == messageId) {
                            msg.copy(
                                content = stripped,
                                attachments =
                                    (msg.attachments.orEmpty() + newAttachments)
                                        .distinctBy { it.gatewayUrl ?: it.uri },
                            )
                        } else {
                            msg
                        }
                    },
            )
        }
    }

    /**
     * Open an attachment when its chip/thumbnail is tapped.
     *
     * - LOCAL (user-picked) files: open the original `content://` URI
     *   directly via [android.content.Intent.ACTION_VIEW] — the resolver
     *   already grants read access for the picked document. If that fails
     *   (e.g. the permission lapsed), we copy to cache and retry via
     *   FileProvider so the tap is never a silent no-op.
     * - GATEWAY (agent `MEDIA:`) files: stream the file to cache via
     *   [GatewayFileClient] (chunked — never held in memory), then open with
     *   [android.content.Intent.ACTION_VIEW] through FileProvider — so a
     *   remote phone can view agent-delivered files in-place.
     *
     * Failures surface through [ChatUiState.openError] (non-blocking
     * snackbar); the tap is never swallowed.
     */
    fun openAttachment(attachment: Attachment) {
        val ctx = getApplication().applicationContext
        if (attachment.source == AttachmentSource.LOCAL) {
            // Best-effort direct open of the picked content URI.
            runCatching { openWithView(ctx, android.net.Uri.parse(attachment.uri), attachment.mimeType) }
                .onSuccess { return }
                .onFailure { /* fall through to cache-copy below */ }
        }
        // GATEWAY, or LOCAL direct-open failed → fetch/copy then open.
        val path = gatewayPathFor(attachment)
        // Show a loading indicator on the attachment card while the file
        // streams down (mirrors the save spinner; cleared in all outcomes).
        uiState.update { it.copy(openingAttachmentPath = path) }
        val cacheDir = java.io.File(ctx.cacheDir, "gateway_files")
        scope.launch(ioDispatcher) {
            try {
                when (val result = GatewayFileClient.fetch(path, cacheDir)) {
                    is GatewayFileResult.Success -> {
                        openBytes(ctx, result.file)
                    }

                    is GatewayFileResult.NotFound -> {
                        showOpenError("File not found on gateway: ${attachment.name}")
                    }

                    is GatewayFileResult.Forbidden -> {
                        showOpenError("Access denied: ${attachment.name}")
                    }

                    is GatewayFileResult.TooLarge -> {
                        showOpenError("File too large to open: ${attachment.name}")
                    }

                    is GatewayFileResult.Unauthorized -> {
                        showOpenError("Session expired — reconnect to open: ${attachment.name}")
                    }

                    is GatewayFileResult.Failure -> {
                        showOpenError("Could not open ${attachment.name}: ${result.throwable.message}")
                    }
                }
            } finally {
                uiState.update {
                    if (it.openingAttachmentPath == path) it.copy(openingAttachmentPath = null) else it
                }
            }
        }
    }

    /** Save an agent-delivered file through Android's system document picker. */
    fun saveAttachment(
        attachment: Attachment,
        destination: android.net.Uri,
    ) {
        if (uiState.value.savingAttachmentPath != null) return
        val path = gatewayPathFor(attachment)
        val cacheDir =
            java.io.File(getApplication().applicationContext.cacheDir, "gateway_files")
        uiState.update { it.copy(savingAttachmentPath = path) }
        scope.launch(ioDispatcher) {
            try {
                when (val result = GatewayFileClient.fetch(path, cacheDir)) {
                    is GatewayFileResult.Success -> {
                        val resolver = getApplication().contentResolver
                        runCatching {
                            resolver
                                .openOutputStream(destination, "wt")
                                ?.use { output ->
                                    result.file.cacheFile.inputStream().use { input ->
                                        GatewayFileClient.copyChunked(input, output)
                                    }
                                }
                                ?: error("destination is unavailable")
                        }.onSuccess {
                            showOpenError("Saved ${result.file.name}")
                        }.onFailure {
                            showOpenError("Could not save ${attachment.name}: ${it.message}")
                        }
                    }

                    is GatewayFileResult.NotFound -> showOpenError("File not found on gateway: ${attachment.name}")
                    is GatewayFileResult.Forbidden -> showOpenError("Access denied: ${attachment.name}")
                    is GatewayFileResult.TooLarge -> showOpenError("File too large to save: ${attachment.name}")
                    is GatewayFileResult.Unauthorized ->
                        showOpenError(
                            "Session expired — reconnect to save: ${attachment.name}",
                        )
                    is GatewayFileResult.Failure ->
                        showOpenError("Could not save ${attachment.name}: ${result.throwable.message}")
                }
            } finally {
                uiState.update {
                    if (it.savingAttachmentPath == path) {
                        it.copy(savingAttachmentPath = null)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun gatewayPathFor(attachment: Attachment): String =
        attachment.gatewayUrl?.let(::gatewayPathFromUrl)
            ?: attachment.uri.removePrefix("gateway:").takeIf { it != attachment.uri }
            ?: attachment.name

    /** Open a gateway file already streamed to cache via FileProvider + ACTION_VIEW. */
    private fun openBytes(
        ctx: android.content.Context,
        file: GatewayFile,
    ) {
        runCatching {
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    file.cacheFile,
                )
            openWithView(ctx, uri, file.mimeType)
        }.onFailure { showOpenError("Could not open ${file.name}: ${it.message}") }
    }

    /** Fire an ACTION_VIEW intent; throws if no activity can handle the type. */
    private fun openWithView(
        ctx: android.content.Context,
        uri: android.net.Uri,
        mimeType: String,
    ) {
        val viewIntent =
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            ctx.startActivity(viewIntent)
        } catch (e: Throwable) {
            val fallbackIntent =
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val chooser =
                android.content.Intent.createChooser(fallbackIntent, "Open file").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ctx.startActivity(chooser)
        }
    }

    private fun showOpenError(message: String) {
        uiState.update { it.copy(openError = message) }
    }

    fun clearOpenError() {
        uiState.update { it.copy(openError = null) }
    }
}

internal fun sameMessages(
    left: List<ChatMessage>,
    right: List<ChatMessage>,
): Boolean =
    left.size == right.size &&
        left.zip(right).all { (a, b) ->
            a.id == b.id &&
                a.role == b.role &&
                a.content == b.content &&
                a.reasoningText == b.reasoningText
        }
