package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ManagedFileDelete
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/**
 * An attachment too large for one WebSocket frame, already on the gateway host.
 *
 * [path] is handed to `file.attach` / `image.attach`. [cleanupPath] is the managed-files
 * staging directory to delete once the gateway has taken its own copy; null when the
 * gateway owns the file (`/api/chat/image-upload` writes into the profile's images dir).
 */
data class GatewayStagedAttachment(
    val path: String,
    val cleanupPath: String?,
)

sealed interface GatewayStageResult {
    data class Staged(
        val staged: GatewayStagedAttachment,
    ) : GatewayStageResult

    /** The gateway refused the size (HTTP 413); [detail] is its own message. */
    data class TooLarge(
        val detail: String,
    ) : GatewayStageResult

    /** The gateway has no upload route for this attachment kind. */
    data object Unsupported : GatewayStageResult

    data class Failed(
        val message: String,
    ) : GatewayStageResult
}

/** Moves attachments over HTTP when they cannot ride inline in a JSON-RPC frame. */
interface ChatAttachmentStager {
    suspend fun stage(
        attachment: Attachment,
        open: () -> InputStream?,
    ): GatewayStageResult

    /** Best-effort removal of a staging directory the gateway no longer needs. */
    suspend fun discard(path: String)
}

/**
 * OkHttp refuses any WebSocket message above 16 MiB, so the inline `data_url` path cannot
 * reach the gateway's own limits. Large files are streamed to `/api/files/upload-stream`
 * and large images to `/api/chat/image-upload`; the gateway enforces its caps there.
 */
class HttpChatAttachmentStager(
    private val api: () -> HermesApiService = { ApiClient.hermesApi },
    private val profile: () -> String? = { AuthManager.activeProfileId.value },
    private val newUploadId: () -> String = { UUID.randomUUID().toString() },
) : ChatAttachmentStager {
    override suspend fun stage(
        attachment: Attachment,
        open: () -> InputStream?,
    ): GatewayStageResult = if (attachment.isImage) stageImage(attachment, open) else stageFile(attachment, open)

    override suspend fun discard(path: String) {
        val result = safeApiCall(retries = 0) { api().deleteManagedFile(ManagedFileDelete(path, recursive = true)) }
        if (result is NetworkResult.Failure) {
            Log.w(TAG, "Could not remove staged attachment: ${result.error.message}")
        }
    }

    private suspend fun stageFile(
        attachment: Attachment,
        open: () -> InputStream?,
    ): GatewayStageResult {
        val listing =
            when (val result = safeApiCall(retries = 0) { api().listManagedFiles(null) }) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Failure -> return result.error.toStageResult()
            }
        val root = (listing.lockedRoot ?: listing.path).trimEnd('/', '\\')
        if (root.isBlank()) return GatewayStageResult.Unsupported
        val directory = "$root/$STAGING_DIRECTORY/${newUploadId()}"
        val fileName = stagedFileName(attachment.name)
        val target = "$directory/$fileName"
        val file =
            MultipartBody.Part.createFormData(
                "file",
                fileName,
                OpenedStreamRequestBody(attachment.mimeType.toMediaTypeOrNull(), open),
            )
        val upload =
            safeApiCall(retries = 0) {
                api().uploadManagedFileStream(
                    target.toRequestBody(TEXT_PLAIN),
                    "false".toRequestBody(TEXT_PLAIN),
                    file,
                )
            }
        return when (upload) {
            is NetworkResult.Success -> {
                val path = upload.data.entry?.path ?: upload.data.path ?: target
                GatewayStageResult.Staged(GatewayStagedAttachment(path, cleanupPath = directory))
            }

            is NetworkResult.Failure -> {
                discard(directory)
                upload.error.toStageResult()
            }
        }
    }

    private suspend fun stageImage(
        attachment: Attachment,
        open: () -> InputStream?,
    ): GatewayStageResult {
        val body = ImageDataUrlRequestBody(attachment.mimeType, attachment.name, open)
        return when (val upload = safeApiCall(retries = 0) { api().uploadChatImage(profile(), body) }) {
            is NetworkResult.Success -> {
                val path = upload.data.path
                if (path.isBlank()) {
                    GatewayStageResult.Failed("Gateway returned no image path")
                } else {
                    GatewayStageResult.Staged(GatewayStagedAttachment(path, cleanupPath = null))
                }
            }

            is NetworkResult.Failure -> {
                upload.error.toStageResult()
            }
        }
    }

    private fun NetworkError.toStageResult(): GatewayStageResult =
        when {
            this is NetworkError.Http && code == HTTP_PAYLOAD_TOO_LARGE -> {
                GatewayStageResult.TooLarge(message)
            }

            this is NetworkError.Http && (code == HTTP_NOT_FOUND || code == HTTP_METHOD_NOT_ALLOWED) -> {
                GatewayStageResult.Unsupported
            }

            else -> {
                GatewayStageResult.Failed(message)
            }
        }

    private companion object {
        const val TAG = "ChatAttachmentStager"
        const val STAGING_DIRECTORY = ".hermes-mobile-uploads"
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        val TEXT_PLAIN = "text/plain".toMediaTypeOrNull()
    }
}

/** A single path segment the managed-files route accepts, keeping the user's file name. */
internal fun stagedFileName(name: String): String {
    val base =
        name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f]"), "_")
            .trim()
    return if (base.isEmpty() || base == "." || base == "..") "attachment" else base
}

/** Streams a fresh [open] stream on every write so OkHttp can replay the body. */
private class OpenedStreamRequestBody(
    private val contentType: MediaType?,
    private val open: () -> InputStream?,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun writeTo(sink: BufferedSink) {
        val input = open() ?: throw IOException("Attachment unavailable")
        input.source().use { sink.writeAll(it) }
    }
}

/** `{"data_url": "data:<mime>;base64,…", "filename": …}`, Base64-encoded while it streams. */
internal class ImageDataUrlRequestBody(
    private val mimeType: String,
    private val fileName: String,
    private val open: () -> InputStream?,
) : RequestBody() {
    override fun contentType(): MediaType? = "application/json".toMediaTypeOrNull()

    override fun writeTo(sink: BufferedSink) {
        val input = open() ?: throw IOException("Attachment unavailable")
        val dataUrlPrefix = Json.encodeToString(String.serializer(), "data:$mimeType;base64,").dropLast(1)
        sink.writeUtf8("{\"data_url\":$dataUrlPrefix")
        input.use { source ->
            Base64.getEncoder().wrap(KeepOpenOutputStream(sink.outputStream())).use { encoded ->
                source.copyTo(encoded)
            }
        }
        sink.writeUtf8("\",\"filename\":${Json.encodeToString(String.serializer(), fileName)}}")
    }
}

/** The Base64 encoder closes its target to flush padding; the sink must stay open. */
private class KeepOpenOutputStream(
    private val target: OutputStream,
) : OutputStream() {
    override fun write(b: Int) = target.write(b)

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = target.write(b, off, len)

    override fun flush() = target.flush()

    override fun close() = target.flush()
}
