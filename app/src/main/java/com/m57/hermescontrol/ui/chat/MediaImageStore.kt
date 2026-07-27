package com.m57.hermescontrol.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Persist / dispatch chat images on the *device* (Downloads / Gallery / share
 * sheet) — never back to the Hermes server (issue #723). Every operation works
 * only on bytes the viewer already resolved locally, so save/share are fully
 * device-local and independent of the backend.
 */
object MediaImageStore {
    /**
     * Write [bytes] into the device's Download collection via [MediaStore] so the
     * file appears in system Downloads / Gallery apps. Returns the public [Uri]
     * on success, or `null` if the write failed.
     *
     * On API 29+ the file lands under `Download/Hermes` using
     * `RELATIVE_PATH`; on older APIs it falls back to the top-level
     * `Images` collection.
     */
    fun saveToDownloads(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): Uri? {
        val safe = sanitizeName(displayName)
        val (collection, relativePath) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Hermes"
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to null
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null.also {
                runCatching { resolver.delete(uri, null, null) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Build a share [Intent] for [bytes] via a [FileProvider] cache file so the
     * image can be sent to another app (Messages, WhatsApp, …). Returns `null`
     * if the temp file could not be written.
     */
    fun buildShareIntent(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): Intent? {
        val safe = sanitizeName(displayName)
        val dir = File(context.cacheDir, "shared_images").also { it.mkdirs() }
        val file = File(dir, safe)
        return try {
            file.writeBytes(bytes)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Replace unsafe characters in a display name and cap its length so it is a
     * valid [MediaStore] / file name. Preserves the original extension when one
     * is present.
     */
    private fun sanitizeName(name: String): String {
        val hasExt = name.contains('.')
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) name.substringAfterLast('.') else ""
        val cleanedBase = base.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60).ifBlank { "hermes-image" }
        val cleanedExt = ext.replace(Regex("[^A-Za-z0-9]"), "").take(10).ifBlank { "png" }
        return "$cleanedBase.$cleanedExt"
    }
}
