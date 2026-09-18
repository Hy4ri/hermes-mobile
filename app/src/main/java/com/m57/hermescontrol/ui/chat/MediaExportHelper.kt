package com.m57.hermescontrol.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.GatewayFileClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class MediaSaveStrategy {
    MEDIA_STORE,
    CREATE_DOCUMENT,
}

fun mediaSaveStrategy(sdkInt: Int = Build.VERSION.SDK_INT): MediaSaveStrategy =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        MediaSaveStrategy.MEDIA_STORE
    } else {
        MediaSaveStrategy.CREATE_DOCUMENT
    }

object MediaExportHelper {
    private const val SHARE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    fun resolveDisplayName(
        displayName: String?,
        mimeType: String?,
        uri: String,
    ): String {
        val fallbackCandidate = mediaNameFromPath(uri).takeIf { it.isNotBlank() && it != "file" } ?: "hermes-media"
        val candidate = displayName?.takeIf { it.isNotBlank() } ?: fallbackCandidate
        val leaf = candidate.split('/', '\\').lastOrNull { it.isNotBlank() } ?: "hermes-media"
        val rawExt = extractFileExtension(leaf)
        val hasValidExt = rawExt.isNotBlank() && rawExt.length <= 10
        val finalExt =
            if (hasValidExt) {
                rawExt
            } else {
                mimeType?.let { extensionForMime(it) } ?: "bin"
            }
        val rawBase = if (hasValidExt) leaf.substringBeforeLast('.') else leaf
        val cleanedBase =
            rawBase
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                .trim('_')
                .take(60)
                .ifBlank { "hermes-media" }
        val cleanedExt =
            finalExt
                .replace(Regex("[^A-Za-z0-9]"), "")
                .take(10)
                .ifBlank { "bin" }
        return "$cleanedBase.$cleanedExt"
    }

    /**
     * Best-effort cleanup of stale share directories older than 24 hours.
     * Only touches subdirectories inside [sharedMediaDir].
     */
    internal fun sweepOldShareFiles(
        sharedMediaDir: File,
        now: Long = System.currentTimeMillis(),
    ) {
        val cutoff = now - SHARE_CACHE_TTL_MS
        runCatching {
            sharedMediaDir.listFiles()?.forEach { entry ->
                if (entry.isDirectory) {
                    if (entry.lastModified() < cutoff) {
                        entry.deleteRecursively()
                    }
                } else if (entry.isFile && entry.lastModified() < cutoff) {
                    entry.delete()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun createMediaStoreValues(
        displayName: String,
        mimeType: String,
        length: Long?,
    ): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (length != null && length > 0) {
                put(MediaStore.MediaColumns.SIZE, length)
            }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Hermes")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

    /**
     * Streams media directly to [MediaStore.Downloads] on API 29+ (Android Q+).
     * Uses bounded 64 KiB chunks with periodic coroutine cancellation checks.
     * Marked pending during write and un-pended on commit.
     * Guarantees that any partial item is deleted if the write fails or is canceled.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveMediaToDownloads(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
        currentSdk: Int = Build.VERSION.SDK_INT,
        valuesFactory: (String, String, Long?) -> ContentValues = ::createMediaStoreValues,
    ): String =
        withContext(Dispatchers.IO) {
            if (currentSdk < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("Direct MediaStore.Downloads requires API 29+")
            }
            MediaStreamResolver.useStream(context, uri, fallbackMime) { inputStream, streamInfo ->
                val finalName = resolveDisplayName(displayName, streamInfo.mimeType, uri)
                val frameworkMime = normalizeFrameworkMime(streamInfo.mimeType)
                val values = valuesFactory(finalName, frameworkMime, streamInfo.length)
                val resolver = context.contentResolver
                val targetUri =
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Failed to insert into MediaStore.Downloads")

                var success = false
                try {
                    resolver.openOutputStream(targetUri)?.use { outputStream ->
                        GatewayFileClient.copyChunked(inputStream, outputStream)
                    } ?: throw IOException("Failed to open MediaStore output stream")

                    val publishValues = valuesFactory(finalName, frameworkMime, streamInfo.length)
                    publishValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    val updated = resolver.update(targetUri, publishValues, null, null)
                    if (updated <= 0) {
                        throw IOException("Failed to publish media item in MediaStore")
                    }
                    success = true
                    context.getString(R.string.media_player_saved)
                } finally {
                    if (!success) {
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
            var success = false
            try {
                MediaStreamResolver.useStream(context, sourceUri, fallbackMime) { inputStream, _ ->
                    resolver.openOutputStream(targetUri)?.use { outputStream ->
                        GatewayFileClient.copyChunked(inputStream, outputStream)
                    } ?: throw IOException("Failed to open target document output stream")
                }
                success = true
                context.getString(R.string.media_player_saved)
            } finally {
                if (!success) {
                    runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
                        .onFailure {
                            runCatching { resolver.delete(targetUri, null, null) }
                        }
                }
            }
        }

    /**
     * Streams media into an internal cache file in `shared_media/<uuid>/<safeName>` and builds
     * an ACTION_SEND Intent. Uses collision-proof session subdirectories and cleans stale entries
     * older than 24 hours. Share files are preserved so receiving apps can read them.
     */
    suspend fun shareMedia(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
        fileUriProvider: (Context, File) -> Uri = { ctx, f ->
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        },
        intentFactory: (Uri, String) -> Intent = { contentUri, mime ->
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        },
    ): Intent? =
        withContext(Dispatchers.IO) {
            try {
                MediaStreamResolver.useStream(context, uri, fallbackMime) { inputStream, streamInfo ->
                    val finalName = resolveDisplayName(displayName, streamInfo.mimeType, uri)
                    val sharedMediaDir = File(context.cacheDir, "shared_media").also { it.mkdirs() }
                    sweepOldShareFiles(sharedMediaDir)

                    val sessionDir = File(sharedMediaDir, UUID.randomUUID().toString()).also { it.mkdirs() }
                    val targetFile = File(sessionDir, finalName)

                    var success = false
                    try {
                        targetFile.outputStream().use { outputStream ->
                            GatewayFileClient.copyChunked(inputStream, outputStream)
                        }
                        success = true
                    } finally {
                        if (!success) {
                            targetFile.delete()
                            sessionDir.delete()
                        }
                    }

                    val contentUri = fileUriProvider(context, targetFile)
                    val normalizedMime = normalizeFrameworkMime(streamInfo.mimeType)
                    intentFactory(contentUri, normalizedMime)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}
