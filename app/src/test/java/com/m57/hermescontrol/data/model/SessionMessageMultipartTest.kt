package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for SessionMessage multipart content and display_content text extraction.
 *
 * Contract requirements (Issue #1432):
 * - [SessionMessage.contentText] and [SessionMessage.displayContentText] extract recognized text parts
 *   of multipart JsonArray in order.
 * - Recognized text parts: {"type": "text", "text": "..."} or {"text": "..."}.
 * - Omit "image_url", inline base64 ("data:image/..."), and unknown/malformed parts rather than JSON dumping.
 * - Null content -> empty string ("").
 * - Absent display_content (null) -> null.
 * - Explicit empty string display wins ("" -> "").
 * - Plain string primitives are retained unchanged (including very long plain text).
 * - Avoid imposing changes on unknown object tool content (non-array objects dump or retain their object representation).
 */
class SessionMessageMultipartTest {
    @Test
    fun contentText_whenContentIsNull_returnsEmptyString() {
        val message = SessionMessage(content = null)
        assertEquals("", message.contentText)
    }

    @Test
    fun displayContentText_whenDisplayContentIsNull_returnsNull() {
        val message = SessionMessage(display_content = null)
        assertNull(message.displayContentText)
    }

    @Test
    fun displayContentText_whenDisplayContentIsExplicitEmptyString_returnsEmptyString() {
        val message = SessionMessage(display_content = JsonPrimitive(""))
        assertEquals("", message.displayContentText)
    }

    @Test
    fun contentText_whenContentIsPrimitiveString_retainsUnchanged() {
        val text = "Hello world, this is a plain message turn."
        val message = SessionMessage(content = JsonPrimitive(text))
        assertEquals(text, message.contentText)
    }

    @Test
    fun contentText_whenContentIsLongPlainText_retainsEntireStringUnchanged() {
        val longText = "A".repeat(100_000)
        val message = SessionMessage(content = JsonPrimitive(longText))
        assertEquals(longText, message.contentText)
    }

    @Test
    fun contentText_whenMultipartWith748kBase64AndImageCaption_extractsCaptionOnly() {
        val largeBase64 = "data:image/png;base64," + "iVBORw0KGgoAAAANSUhEUgAA".repeat(31_000) // ~748k chars
        val caption = "Check out this screenshot\n@image:/opt/data/images/screen.png"

        val multipart =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", largeBase64)
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", caption)
                    },
                )
            }

        val message = SessionMessage(content = multipart)
        assertEquals(caption, message.contentText)
    }

    @Test
    fun contentText_whenMultipleTextPartsInArray_extractsInOrder() {
        val multipart =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "First paragraph.")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "https://example.com/photo.png")
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Second paragraph.")
                    },
                )
                add(
                    buildJsonObject {
                        // Shorthand text block without explicit type
                        put("text", "Third paragraph.")
                    },
                )
            }

        val message = SessionMessage(content = multipart)
        val expected = "First paragraph.\nSecond paragraph.\nThird paragraph."
        assertEquals(expected, message.contentText)
    }

    @Test
    fun contentText_whenMultipartContainsMalformedOrUnknownElements_omitsThemWithoutJsonDumping() {
        val multipart =
            buildJsonArray {
                add(JsonPrimitive(12345))
                add(JsonPrimitive(true))
                add(
                    buildJsonObject {
                        put("type", "unknown_blob")
                        put("raw", "skip_me")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("invalid_key", "no text property here")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Valid extracted text.")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("source", "base64data")
                    },
                )
            }

        val message = SessionMessage(content = multipart)
        assertEquals("Valid extracted text.", message.contentText)
    }

    @Test
    fun contentText_whenMultipartHasNoTextParts_returnsEmptyString() {
        val multipart =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "https://example.com/icon.png")
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "audio")
                        put("data", "abc123audio")
                    },
                )
            }

        val message = SessionMessage(content = multipart)
        assertEquals("", message.contentText)
    }

    @Test
    fun displayContentText_whenProjectedDisplayContentIsMultipartArray_extractsTextParts() {
        val projectedDisplayArray =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Summary of conversation")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,12345")
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Key takeaways follow.")
                    },
                )
            }

        val message =
            SessionMessage(
                content = JsonPrimitive("raw fallback content"),
                display_content = projectedDisplayArray,
            )

        assertEquals("Summary of conversation\nKey takeaways follow.", message.displayContentText)
    }

    @Test
    fun contentText_whenContentIsJsonObjectToolCallOrResult_doesNotImposeMultipartChanges() {
        val toolObject =
            buildJsonObject {
                put("status", "success")
                put("output", "tool execution output")
            }

        val message = SessionMessage(content = toolObject)
        // Ensure non-array JsonObject continues existing behavior (e.g. toString/not mangled as multipart array)
        assertEquals(toolObject.toString(), message.contentText)
    }
}
