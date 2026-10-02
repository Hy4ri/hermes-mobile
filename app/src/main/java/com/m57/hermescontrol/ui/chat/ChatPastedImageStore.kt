package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal class PastedImageTooLargeException : IOException("Pasted image exceeds attachment limit")

/** A private cache file owned by the caller after [ChatPastedImageStore.importImage] returns. */
class PastedImage(
    val attachment: Attachment,
    val file: File,
) {
    /** Call if the import cannot be queued (for example, after a session switch). */
    fun discard() {
        file.delete()
    }
}

/** Explicit clipboard/IME image importer; it never observes the clipboard itself. */
class ChatPastedImageStore(
    private val context: Context,
) {
    /** Returns null for unsupported, missing, or empty input; oversized streams fail explicitly. */
    suspend fun importImage(
        uri: Uri,
        maxBytes: Long = MAX_CHAT_ATTACHMENT_BYTES,
    ): PastedImage? {
        var pending: PastedImage? = null
        var delivered = false
        try {
            val result =
                withContext(Dispatchers.IO) {
                    if (uri.scheme != "content") return@withContext null
                    val resolver = context.contentResolver
                    val mimeType =
                        resolver
                            .getType(uri)
                            ?.takeIf { it.startsWith("image/", ignoreCase = true) && it.length > "image/".length }
                            ?: return@withContext null
                    val extension =
                        MimeTypeMap
                            .getSingleton()
                            .getExtensionFromMimeType(mimeType)
                            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
                            ?: "img"
                    val input = resolver.openInputStream(uri) ?: return@withContext null
                    val activeContext = currentCoroutineContext()
                    val file =
                        stagePastedImage(
                            input = input,
                            directory = File(context.cacheDir, "pasted_images"),
                            extension = extension,
                            maxBytes = maxBytes,
                            checkActive = { activeContext.ensureActive() },
                        ) ?: return@withContext null
                    try {
                        activeContext.ensureActive()
                        val privateUri =
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                        PastedImage(
                            attachment =
                                Attachment(
                                    uri = privateUri.toString(),
                                    name = file.name,
                                    mimeType = mimeType,
                                    size = file.length(),
                                ),
                            file = file,
                        ).also { pending = it }
                    } catch (failure: Throwable) {
                        file.delete()
                        throw failure
                    }
                }
            delivered = true
            return result
        } finally {
            // withContext may discard its completed IO result if the parent was cancelled
            // during dispatch back; in that case ownership never reached the caller.
            if (!delivered) pending?.discard()
        }
    }
}

/** JVM-testable, exclusive-create bounded copy. Null means empty input. */
internal fun stagePastedImage(
    input: InputStream,
    directory: File,
    extension: String,
    maxBytes: Long = MAX_CHAT_ATTACHMENT_BYTES,
    checkActive: () -> Unit = {},
    newId: () -> String = { UUID.randomUUID().toString() },
): File? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val effectiveMaxBytes = minOf(maxBytes, MAX_CHAT_ATTACHMENT_BYTES)
    require(extension.matches(Regex("[A-Za-z0-9]{1,10}"))) { "Invalid extension" }
    var owned: File? = null
    var keep = false
    try {
        return input
            .use source@{ source ->
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("Cannot create pasted image cache directory")
                }
                val file = File(directory, "${UUID.fromString(newId())}.$extension")
                if (!file.createNewFile()) throw IOException("Pasted image cache name already exists")
                owned = file
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                FileOutputStream(file).use { output ->
                    while (true) {
                        checkActive()
                        // Probe one byte beyond the limit without trusting provider-reported size.
                        val remaining = effectiveMaxBytes - total
                        val requested = if (remaining < buffer.size) (remaining + 1).toInt() else buffer.size
                        val count = source.read(buffer, 0, requested)
                        if (count < 0) break
                        if (count > remaining) throw PastedImageTooLargeException()
                        if (count == 0) {
                            val byte = source.read()
                            if (byte < 0) break
                            if (remaining == 0L) throw PastedImageTooLargeException()
                            output.write(byte)
                            total++
                            continue
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    checkActive()
                }
                if (total == 0L) null else file
            }.also { keep = it != null }
    } finally {
        if (!keep) owned?.delete()
    }
}
