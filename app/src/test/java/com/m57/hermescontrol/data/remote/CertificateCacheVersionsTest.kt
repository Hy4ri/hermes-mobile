package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CertificateCacheVersionsTest {
    @Test
    fun `only string revisions with the exact prefix are read without normalizing keys or values`() {
        val values =
            mapOf(
                "cache:https://example.test:443/" to "revision",
                "cache:*" to "",
                "cache:" to "empty-key",
                "cache:cache:nested" to "  unchanged  ",
                "Cache:case" to "ignored",
                "xcache:offset" to "ignored",
                "https://example.test/" to "binding",
                "cache:null" to null,
                "cache:int" to 12,
                "cache:boolean" to true,
                "cache:set" to setOf("value"),
            )
        assertEquals(
            mapOf(
                "https://example.test:443/" to "revision",
                "*" to "",
                "" to "empty-key",
                "cache:nested" to "  unchanged  ",
            ),
            certificateCacheVersions(values, "cache:"),
        )
    }

    @Test
    fun `absent or wrongly typed versions keep the empty cache fallback`() {
        val versions = certificateCacheVersions(mapOf("cache:*" to 3L), "cache:")
        val bindings = CertificateBindings(versions = versions, persist = { _, _ -> }, invalidate = {})
        val url = CertificateOrigin("example.test", 443).url
        assertEquals(emptyMap<String, String>(), certificateCacheVersions(emptyMap<String, Any>(), "cache:"))
        assertEquals("$url#mtls=/", bindings.cacheKey(url))
    }

    @Test
    fun `preference iteration failures propagate without falling back to an empty registry`() {
        val failure = IOException("unreadable snapshot")
        val values = mockk<Map<String, Any>>()
        every { values.entries } throws failure
        assertSame(failure, assertThrows(IOException::class.java) { certificateCacheVersions(values, "cache:") })
    }
}
