package com.m57.hermescontrol.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.m57.hermescontrol.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object MediaExportHelper {
    private const val STREAM_BUFFER_SIZE = 8192

    fun resolveDisplayName(
        displayName: String?,
        mimeType: String?,
        uri: String,
    ): String {
        val rawName = displayName?.takeIf { it.isNotBlank() } ?: mediaNameFromPath(uri)
        val ext = extractFileExtension(rawName)
        val finalExt =
            ext.ifBlank {
                mimeType?.let { extensionForMime(it) } ?: "bin"
            }
        val base = if (ext.isNotBlank()) rawName.substringBeforeLast('.') else rawName
        val cleanedBase = base.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60).ifBlank { "hermes-media" }
        return "$cleanedBase.$finalExt"
    }

    /**
     * Streams media directly to [MediaStore.Downloads] on API 29+ (Android Q+).
     * Marked pending during write and un-pended on success.
     * Guarantees that any partial item is deleted if the write fails or is canceled.
     */
    suspend fun saveMediaToDownloads(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("Direct MediaStore.Downloads requires API 29+")
            }
            MediaStreamResolver.useStream(context, uri, fallbackMime) { inputStream, streamInfo ->
                val finalName = resolveDisplayName(displayName, streamInfo.mimeType, uri)
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                        put(MediaStore.MediaColumns.MIME_TYPE, streamInfo.mimeType)
                        if (streamInfo.length != null && streamInfo.length > 0) {
                            put(MediaStore.MediaColumns.SIZE, streamInfo.length)
                        }
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Hermes")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val resolver = context.contentResolver
                val targetUri =
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Failed to insert into MediaStore.Downloads")

                var writeCompleted = false
                try {
                    resolver.openOutputStream(targetUri)?.use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = STREAM_BUFFER_SIZE)
                        writeCompleted = true
                    } ?: throw IOException("Failed to open MediaStore output stream")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(targetUri, values, null, null)
                    context.getString(R.string.media_player_saved)
                } finally {
                    if (!writeCompleted) {
                        runCatching { resolver.delete(targetUri, null, null) }
                    }
                }
            }
        }

    /**
     * Streams media directly into a user-selected [targetUri] obtained via SAF (e.g. CreateDocument).
     * Used for API 26-28 where MediaStore.Downloads is unavailable.
     * Deletes partial documents if the stream write fails or is canceled.
     */
    suspend fun saveMediaToUri(
        context: Context,
        sourceUri: String,
        targetUri: Uri,
        fallbackMime: String = "application/octet-stream",
    ): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var writeCompleted = false
            try {
                MediaStreamResolver.useStream(context, sourceUri, fallbackMime) { inputStream, _ ->
                    resolver.openOutputStream(targetUri)?.use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = STREAM_BUFFER_SIZE)
                        writeCompleted = true
                    } ?: throw IOException("Failed to open target document output stream")
                }
                context.getString(R.string.media_player_saved)
            } finally {
                if (!writeCompleted) {
                    runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
                        .onFailure {
                            runCatching { resolver.delete(targetUri, null, null) }
                        }
                }
            }
        }

    /**
     * Streams media into an internal cache file in `shared_media/` and builds an ACTION_SEND Intent.
     * Share files are preserved so the receiving app can read them.
     */
    suspend fun shareMedia(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
    ): Intent? =
        withContext(Dispatchers.IO) {
            try {
                MediaStreamResolver.useStream(context, uri, fallbackMime) { inputStream, streamInfo ->
                    val finalName = resolveDisplayName(displayName, streamInfo.mimeType, uri)
                    val dir = File(context.cacheDir, "shared_media").also { it.mkdirs() }
                    val targetFile = File(dir, finalName)

                    var writeCompleted = false
                    try {
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = STREAM_BUFFER_SIZE)
                            writeCompleted = true
                        }
                    } finally {
                        if (!writeCompleted) {
                            targetFile.delete()
                        }
                    }

                    val contentUri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            targetFile,
                        )
                    Intent(Intent.ACTION_SEND).apply {
                        type = streamInfo.mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}
