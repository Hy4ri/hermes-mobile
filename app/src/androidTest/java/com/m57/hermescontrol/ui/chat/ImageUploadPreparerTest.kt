package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ImageUploadPreparerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun photo(png: Boolean): java.io.File {
        val bitmap = Bitmap.createBitmap(3000, 1600, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(42)
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                for (x in row.indices) {
                    val alpha = if (png && x < 300) 0 else 255
                    row[x] = (alpha shl 24) or random.nextInt(0x1000000)
                }
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            return temp.newFile(if (png) "alpha.png" else "photo.jpg").apply {
                outputStream().use {
                    bitmap.compress(
                        if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        95,
                        it,
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun allExifRotationsAndReflectionsPreservePixelOrder() {
        val expected =
            listOf(
                listOf(1, 2, 3, 4, 5, 6),
                listOf(3, 2, 1, 6, 5, 4),
                listOf(6, 5, 4, 3, 2, 1),
                listOf(4, 5, 6, 1, 2, 3),
                listOf(1, 4, 2, 5, 3, 6),
                listOf(4, 1, 5, 2, 6, 3),
                listOf(6, 3, 5, 2, 4, 1),
                listOf(3, 6, 2, 5, 1, 4),
            )
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            for (i in 0 until 6) source.setPixel(i % 3, i / 3, 0xff000000.toInt() or (i + 1))
            for (orientation in 1..8) {
                val result = orientPhoto(source, orientation)
                try {
                    assertEquals(if (orientation < 5) 3 else 2, result.width)
                    val actual =
                        (0 until result.width * result.height).map {
                            result.getPixel(it % result.width, it / result.width) and 0xff
                        }
                    assertEquals("EXIF $orientation", expected[orientation - 1], actual)
                } finally {
                    if (result !== source) result.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun cameraPhotoIsSmallerAndExifRotationIsAppliedToPixels() =
        runBlocking {
            val source = photo(false)
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val attachment = Attachment(source.toURI().toString(), "camera.jpeg", "image/jpeg", source.length())
            val prepared = preparePhotoUpload(attachment, temp.root) { source.inputStream() }!!
            try {
                assertTrue(prepared.file.length() < source.length())
                assertEquals("image/jpeg", prepared.attachment.mimeType)
                assertEquals("camera.jpg", prepared.attachment.name)
                assertEquals(prepared.file.length(), prepared.attachment.size)
                val decoded = BitmapFactory.decodeFile(prepared.file.path)
                try {
                    assertTrue(decoded.width < decoded.height)
                    assertEquals(800, decoded.width)
                    assertEquals(1500, decoded.height)
                } finally {
                    decoded.recycle()
                }
                assertEquals(2, temp.root.listFiles()!!.size) // source fixture and the retained upload only
            } finally {
                prepared.file.delete()
            }
            assertEquals(listOf(source), temp.root.listFiles()!!.toList())
        }

    @Test
    fun largeTransparentPngRetainsAlphaWithoutJpegFlattening() =
        runBlocking {
            val source = photo(true)
            val prepared =
                preparePhotoUpload(
                    Attachment("uri", "alpha.png", "image/png"),
                    temp.root,
                ) { source.inputStream() }!!
            try {
                assertEquals("image/png", prepared.attachment.mimeType)
                assertTrue(prepared.file.length() < source.length())
                val decoded = BitmapFactory.decodeFile(prepared.file.path)
                try {
                    assertTrue(decoded.hasAlpha())
                    assertEquals(0, decoded.getPixel(0, 0) ushr 24)
                    assertTrue(maxOf(decoded.width, decoded.height) <= PHOTO_LONG_EDGE)
                } finally {
                    decoded.recycle()
                }
            } finally {
                prepared.file.delete()
            }
        }

    @Test
    fun corruptImageFallsBackToExactBytesAndIsStillSubjectToUploadLimit() =
        runBlocking {
            val bytes = ByteArray(300 * 1024) { 42 }
            val prepared =
                preparePhotoUpload(
                    Attachment("uri", "broken.heif", "image/heif"),
                    temp.root,
                ) { bytes.inputStream() }!!
            try {
                assertArrayEquals(bytes, prepared.file.readBytes())
                assertEquals("image/heif", prepared.attachment.mimeType)
                val result =
                    prepared.file.inputStream().use {
                        encodeAttachmentBase64(
                            it,
                            java.io.ByteArrayOutputStream(),
                            100,
                        )
                    }
                assertEquals(AttachmentSizeResult.TOO_LARGE, result)
            } finally {
                prepared.file.delete()
            }
        }
}
