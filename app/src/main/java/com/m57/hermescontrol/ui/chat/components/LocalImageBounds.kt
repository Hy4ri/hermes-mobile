package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri

/** Orientation-aware width/height of a local image, from its header only (no bitmap decode). Null if unknown. */
internal fun readLocalImageRatio(
    context: Context,
    uri: String,
): Float? {
    if (!uri.startsWith("file:", true) && !uri.startsWith("content:", true)) return null
    return runCatching {
        val parsed = Uri.parse(uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        val rotated =
            context.contentResolver.openInputStream(parsed)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE,
                    -> true

                    else -> false
                }
            } ?: false
        if (rotated) options.outHeight.toFloat() / options.outWidth else options.outWidth.toFloat() / options.outHeight
    }.getOrNull()
}
