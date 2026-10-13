package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Provider metadata only: querying a chosen document must never materialize its bytes (#1554). */
data class UriUploadMetadata(
    val name: String,
    val mimeType: String,
    val size: Long,
)

suspend fun ContentResolver.uploadMetadata(
    uri: Uri,
    fallbackName: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): UriUploadMetadata =
    withContext(dispatcher) {
        var name = fallbackName
        var size = -1L
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: fallbackName
                }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn).takeIf { it >= 0L } ?: -1L
                }
            }
        }
        if (size > StreamingUriRequestBody.MAX_UPLOAD_BYTES) {
            throw IOException("Selected file exceeds the upload size limit")
        }
        UriUploadMetadata(name, getType(uri) ?: "application/octet-stream", size)
    }
