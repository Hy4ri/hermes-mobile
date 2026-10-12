package com.m57.hermescontrol.data.remote

import android.content.Context
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CertificateImageInterceptorTest {
    @Test
    fun `HTTPS image memory and disk keys change together while network URL stays intact`() =
        runTest {
            val context = mockk<Context>(relaxed = true)
            val url = "https://example.com/image.png"
            val request = ImageRequest.Builder(context).data(url).build()
            val chain = mockk<Interceptor.Chain>()
            val result = mockk<ImageResult>()
            var updated = request
            every { chain.request } returns request
            every { chain.withRequest(any()) } answers {
                updated = firstArg()
                chain
            }
            coEvery { chain.proceed() } returns result
            var revision = 1
            val interceptor = CertificateImageInterceptor { "$it#mtls=$revision" }
            interceptor.intercept(chain)
            val first = updated.memoryCacheKey
            assertEquals("$url#mtls=1", first)
            assertEquals(first, updated.diskCacheKey)
            assertEquals(url, updated.data)
            revision++
            interceptor.intercept(chain)
            assertNotEquals(first, updated.memoryCacheKey)
            assertEquals(updated.memoryCacheKey, updated.diskCacheKey)
            assertEquals(url, updated.data)
        }

    @Test
    fun `local files keep their existing cache behavior`() =
        runTest {
            val context = mockk<Context>(relaxed = true)
            listOf("/tmp/image.png", "content://media/image/1").forEach { data ->
                val chain = mockk<Interceptor.Chain>()
                val request = ImageRequest.Builder(context).data(data).build()
                val result = mockk<ImageResult>()
                every { chain.request } returns request
                coEvery { chain.proceed() } returns result
                assertEquals(
                    result,
                    CertificateImageInterceptor { error("Local images have no network cache epoch") }.intercept(chain),
                )
            }
        }
}
