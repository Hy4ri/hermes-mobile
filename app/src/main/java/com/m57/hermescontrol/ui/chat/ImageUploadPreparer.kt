package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal const val MAX_PHOTO_SOURCE_BYTES = 50L * 1024L * 1024L
internal const val PHOTO_LONG_EDGE = 2048

/** Efficient/animated formats stay byte-for-byte intact. Never flatten WebP, GIF, or APNG. */
internal fun Attachment.canOptimizePhoto(): Boolean =
    !isGateway && mimeType.lowercase() in setOf("image/jpeg", "image/png", "image/heic", "image/heif")

internal fun Attachment.sourceByteLimit(): Long =
    if (!sendOriginal && canOptimizePhoto()) MAX_PHOTO_SOURCE_BYTES else MAX_CHAT_ATTACHMENT_BYTES

internal fun photoSampleSize(
    width: Int,
    height: Int,
): Int {
    var sample = 1
    while (width / sample > PHOTO_LONG_EDGE || height / sample > PHOTO_LONG_EDGE) sample *= 2
    return sample
}

internal class PhotoSourceTooLarge : IOException()

/** Returned files belong to the caller. This preparer removes all other files, including on cancellation. */
internal data class PreparedPhoto(
    val file: File,
    val attachment: Attachment,
)

internal suspend fun preparePhotoUpload(
    attachment: Attachment,
    cacheDir: File,
    openSource: () -> InputStream?,
): PreparedPhoto? {
    val source = File.createTempFile("photo-source-", ".tmp", cacheDir)
    var output: File? = null
    var retained: File? = null
    try {
        val context = currentCoroutineContext()
        openSource()?.use { input ->
            source.outputStream().buffered().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PHOTO_SOURCE_BYTES) throw PhotoSourceTooLarge()
                    sink.write(buffer, 0, count)
                }
            }
        } ?: return null
        context.ensureActive()
        val original = attachment.copy(size = source.length())
        // Small JPEG/PNG files already cost little bandwidth; keep their exact bytes.
        if (source.length() <= 256L * 1024L && attachment.mimeType in setOf("image/jpeg", "image/png")) {
            retained = source
            return PreparedPhoto(source, original)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val mime = bounds.outMimeType
        val supported = mime in setOf("image/jpeg", "image/png", "image/heic", "image/heif")
        // Unknown/corrupt or animated data falls back to the original and its existing outbound limit.
        if (!supported || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            (mime == "image/png" && source.isAnimatedPng()) ||
            (
                bounds.outWidth <= PHOTO_LONG_EDGE && bounds.outHeight <= PHOTO_LONG_EDGE &&
                    mime != "image/heic" && mime != "image/heif"
            )
        ) {
            retained = source
            return PreparedPhoto(source, original)
        }
        val decoded =
            BitmapFactory.decodeFile(
                source.path,
                BitmapFactory.Options().apply { inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight) },
            )
        if (decoded == null) {
            retained = source
            return PreparedPhoto(source, original)
        }
        var oriented: Bitmap? = null
        try {
            val orientation =
                try {
                    ExifInterface(
                        source.path,
                    ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: IOException) {
                    // Without orientation metadata, preserve the source rather than guess.
                    retained = source
                    return PreparedPhoto(source, original)
                }
            oriented = orientPhoto(decoded, orientation)
            context.ensureActive()
            val png = mime == "image/png" || oriented.hasAlpha()
            output = File.createTempFile("photo-upload-", if (png) ".png" else ".jpg", cacheDir)
            val encoded =
                output.outputStream().use { sink ->
                    oriented.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, sink)
                }
            context.ensureActive()
            if (!encoded || output.length() >= source.length()) {
                retained = source
                return PreparedPhoto(source, original)
            }
            retained = output
            return PreparedPhoto(
                output,
                attachment.copy(
                    name = attachment.name.substringBeforeLast('.', attachment.name) + if (png) ".png" else ".jpg",
                    mimeType = if (png) "image/png" else "image/jpeg",
                    size = output.length(),
                ),
            )
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    } finally {
        if (source != retained) source.delete()
        if (output != retained) output?.delete()
    }
}

internal fun orientPhoto(
    bitmap: Bitmap,
    orientation: Int,
): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(-90f)
        }

        else -> {
            return bitmap
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** acTL must precede the first IDAT. Invalid PNG structures are preserved, not re-encoded. */
internal fun File.isAnimatedPng(): Boolean =
    try {
        DataInputStream(inputStream().buffered()).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            if (!signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return true
            var remaining = length() - 8
            while (remaining >= 12) {
                val size = input.readInt().toLong() and 0xffffffffL
                val type = input.readInt()
                if (size > remaining - 12) return true
                if (type == 0x6163544c) return true // acTL
                if (type == 0x49444154 || type == 0x49454e44) return false // IDAT / IEND
                var skip = size + 4 // data and CRC
                while (skip > 0) {
                    val count = input.skip(skip)
                    if (count <= 0) return true
                    skip -= count
                }
                remaining -= size + 12
            }
            true
        }
    } catch (_: IOException) {
        true
    }
