package com.m57.hermescontrol.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun `serialize TtsSpeakRequest with literal text`() {
        val request = TtsSpeakRequest(text = "Hello, world!")
        val encoded = json.encodeToString(request)

        assertEquals("""{"text":"Hello, world!"}""", encoded)
    }

    @Test
    fun `deserialize TtsSpeakResponse with all fields present`() {
        val rawJson =
            """
            {
                "ok": true,
                "data_url": "data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=",
                "mime_type": "audio/wav",
                "provider": "piper"
            }
            """.trimIndent()

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertTrue(response.ok)
        assertEquals(
            "data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=",
            response.data_url,
        )
        assertEquals("audio/wav", response.mime_type)
        assertEquals("piper", response.provider)
    }

    @Test
    fun `deserialize TtsSpeakResponse with optional provider missing`() {
        val rawJson =
            """
            {
                "ok": true,
                "data_url": "data:audio/mp3;base64,SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYwLjMuMTAwAAAAAAAAAAAAAAD/",
                "mime_type": "audio/mp3"
            }
            """.trimIndent()

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertTrue(response.ok)
        assertEquals(
            "data:audio/mp3;base64,SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYwLjMuMTAwAAAAAAAAAAAAAAD/",
            response.data_url,
        )
        assertEquals("audio/mp3", response.mime_type)
        assertNull(response.provider)
    }

    @Test
    fun `deserialize TtsSpeakResponse with provider explicitly null`() {
        val rawJson =
            """
            {
                "ok": true,
                "data_url": "data:audio/ogg;base64,T2dnUwACAAAAAAAAAAA=",
                "mime_type": "audio/ogg",
                "provider": null
            }
            """.trimIndent()

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertTrue(response.ok)
        assertEquals("data:audio/ogg;base64,T2dnUwACAAAAAAAAAAA=", response.data_url)
        assertEquals("audio/ogg", response.mime_type)
        assertNull(response.provider)
    }

    @Test
    fun `deserialize TtsSpeakResponse with missing ok defaults to false`() {
        val rawJson =
            """
            {
                "data_url": null,
                "mime_type": null
            }
            """.trimIndent()

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertFalse(response.ok)
        assertNull(response.data_url)
        assertNull(response.mime_type)
        assertNull(response.provider)
    }

    @Test
    fun `deserialize TtsSpeakResponse with empty json defaults to ok false and nulls`() {
        val rawJson = "{}"

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertFalse(response.ok)
        assertNull(response.data_url)
        assertNull(response.mime_type)
        assertNull(response.provider)
    }

    @Test
    fun `deserialize TtsSpeakResponse with unknown fields ignores extra properties`() {
        val rawJson =
            """
            {
                "ok": false,
                "error": "Provider not reachable",
                "status_code": 500
            }
            """.trimIndent()

        val response = json.decodeFromString<TtsSpeakResponse>(rawJson)

        assertFalse(response.ok)
        assertNull(response.data_url)
        assertNull(response.mime_type)
        assertNull(response.provider)
    }
}
