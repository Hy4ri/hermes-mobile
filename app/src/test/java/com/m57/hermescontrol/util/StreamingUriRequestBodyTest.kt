package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class StreamingUriRequestBodyTest {
    @Test
    fun testStreamsDirectlyIntoSink() {
        val testData = "test archive data streaming content".toByteArray(Charsets.UTF_8)
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        every { mockResolver.openInputStream(mockUri) } answers {
            ByteArrayInputStream(testData)
        }

        val mediaType = "application/gzip".toMediaTypeOrNull()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = mediaType,
                contentLength = testData.size.toLong(),
            )

        assertEquals(mediaType, requestBody.contentType())
        assertEquals(testData.size.toLong(), requestBody.contentLength())

        val buffer = Buffer()
        requestBody.writeTo(buffer)

        assertEquals("test archive data streaming content", buffer.readUtf8())
    }

    @Test
    fun testThrowsWhenInputStreamCannotBeOpened() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        every { mockResolver.openInputStream(mockUri) } returns null

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
            )

        var thrown = false
        try {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
        } catch (e: IOException) {
            thrown = true
        }

        assertTrue(thrown)
    }
}
