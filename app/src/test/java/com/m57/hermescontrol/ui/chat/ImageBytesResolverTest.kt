package com.m57.hermescontrol.ui.chat

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ImageBytesResolverTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        // resolve() runs on Dispatchers.IO; bind the main dispatcher for tests.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @Test
    fun `extensionForMime maps known types`() {
        assertEquals("png", ImageBytesResolver.extensionForMime("image/png"))
        assertEquals("jpg", ImageBytesResolver.extensionForMime("image/jpeg"))
        assertEquals("jpg", ImageBytesResolver.extensionForMime("image/JPG"))
        assertEquals("gif", ImageBytesResolver.extensionForMime("image/gif"))
        assertEquals("webp", ImageBytesResolver.extensionForMime("image/webp"))
        assertEquals("heic", ImageBytesResolver.extensionForMime("image/heic"))
        assertEquals("svg", ImageBytesResolver.extensionForMime("image/svg+xml"))
        assertEquals("img", ImageBytesResolver.extensionForMime("image/unknown"))
    }

    @Test
    fun `data URL base64 decodes to bytes with mime and extension`() =
        runTest {
            val raw = "hello-image".toByteArray()
            val b64 = Base64.getEncoder().encodeToString(raw)
            val model = "data:image/png;base64,$b64"

            val result = ImageBytesResolver.resolve(context, model, "image/*")

            assertTrue(result is ImageBytesResolver.Result.Bytes)
            result as ImageBytesResolver.Result.Bytes
            assertArrayEquals(raw, result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals("png", result.extension)
        }

    @Test
    fun `data URL falls back to fallbackMime when meta omitted`() =
        runTest {
            val raw = byteArrayOf(1, 2, 3, 4)
            val b64 = Base64.getEncoder().encodeToString(raw)
            val model = "data:;base64,$b64"

            val result = ImageBytesResolver.resolve(context, model, "image/webp")

            assertTrue(result is ImageBytesResolver.Result.Bytes)
            result as ImageBytesResolver.Result.Bytes
            assertEquals("image/webp", result.mimeType)
            assertEquals("webp", result.extension)
        }

    @Test
    fun `malformed data URL (no comma) returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "data:image/png;base64XXXX", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }

    @Test
    fun `non-base64 data URL returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "data:image/png,notbase64", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }

    @Test
    fun `unsupported model source returns Error`() =
        runTest {
            val result = ImageBytesResolver.resolve(context, "file:///sdcard/x.png", "image/*")
            assertTrue(result is ImageBytesResolver.Result.Error)
        }
}
