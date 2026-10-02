package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPromptTypesTest {
    @Test
    fun createWithSourceOnlyEncodesExactlySource() {
        val params = SessionCreateParams(source = "cli")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCreateParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("source", "cli")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("source"), (encoded as JsonObject).keys)
    }

    @Test
    fun groupStyleCreateEncodesExpectedFourKeysWithHiddenBoolean() {
        val params =
            SessionCreateParams(
                profile = "work",
                title = "Team sync",
                source = "matrix",
                hidden = true,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCreateParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("source", "matrix")
                put("profile", "work")
                put("title", "Team sync")
                put("hidden", true)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        val hiddenElement = (encoded as JsonObject)["hidden"] as? JsonPrimitive
        assertTrue(hiddenElement != null && hiddenElement.isString.not())
    }

    @Test
    fun allNullCreateParamsEncodeToEmptyObject() {
        val params = SessionCreateParams()
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCreateParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun createWithExplicitFalseHiddenIsEncoded() {
        val params = SessionCreateParams(hidden = false)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCreateParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("hidden", false)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("hidden"), (encoded as JsonObject).keys)
    }

    @Test
    fun resumeEncodesSessionIdAndOmitMessagesExactly() {
        val params = SessionResumeParams(sessionId = "s-123", omitMessages = true)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionResumeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-123")
                put("omit_messages", true)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "omit_messages"), (encoded as JsonObject).keys)
    }

    @Test
    fun resumeWithProfileEncodesExpectedThreeKeys() {
        val params =
            SessionResumeParams(
                sessionId = "s-456",
                omitMessages = true,
                profile = "personal",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionResumeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-456")
                put("omit_messages", true)
                put("profile", "personal")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "omit_messages", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun resumeWithDesktopSourceEncodesSourceKey() {
        val params =
            SessionResumeParams(
                sessionId = "s-457",
                source = DESKTOP_SESSION_SOURCE,
                omitMessages = true,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionResumeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-457")
                put("source", "desktop")
                put("omit_messages", true)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "source", "omit_messages"), (encoded as JsonObject).keys)
    }

    @Test
    fun resumeOmitsSourceWhenUnset() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SessionResumeParams.serializer(),
                SessionResumeParams(sessionId = "s-458"),
            )

        assertTrue(encoded is JsonObject)
        assertNull((encoded as JsonObject)["source"])
    }

    @Test
    fun createAndResumeDeclareTheSameDesktopSource() {
        // #1450: create passed source and resume did not, so the gateway resolved the resumed
        // runtime from its host env ("tui") and staged a bogus surface switch. Same constant,
        // so the two call sites cannot drift again.
        assertEquals("desktop", DESKTOP_SESSION_SOURCE)
    }

    @Test
    fun resumeWithExplicitFalseOmitMessagesIsEncoded() {
        val params = SessionResumeParams(sessionId = "s-789", omitMessages = false)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionResumeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-789")
                put("omit_messages", false)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "omit_messages"), (encoded as JsonObject).keys)
    }

    @Test
    fun submitEncodesSessionIdAndText() {
        val params = PromptSubmitParams(sessionId = "s-100", text = "hello hermes")
        val encoded = OkHttpProvider.json.encodeToJsonElement(PromptSubmitParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-100")
                put("text", "hello hermes")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "text"), (encoded as JsonObject).keys)
    }

    @Test
    fun submitWithQueuedTrueEncodesQueued() {
        val params = PromptSubmitParams(sessionId = "s-101", text = "queued prompt", queued = true)
        val encoded = OkHttpProvider.json.encodeToJsonElement(PromptSubmitParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-101")
                put("text", "queued prompt")
                put("queued", true)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "text", "queued"), (encoded as JsonObject).keys)
    }

    @Test
    fun submitWithExplicitFalseQueuedIsEncoded() {
        val params = PromptSubmitParams(sessionId = "s-102", text = "direct prompt", queued = false)
        val encoded = OkHttpProvider.json.encodeToJsonElement(PromptSubmitParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "s-102")
                put("text", "direct prompt")
                put("queued", false)
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "text", "queued"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionCreateResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("session_id", "sess-created-1")
                put("stored_session_id", "stored-1")
                put("created_at", 123456789)
                put("unknown_flag", true)
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionCreateResult.serializer(),
                payload,
            )

        assertEquals("sess-created-1", decoded.sessionId)
        assertEquals("stored-1", decoded.storedSessionId)
    }

    @Test
    fun sessionCreateResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionCreateResult.serializer(),
                payload,
            )

        assertNull(decoded.sessionId)
        assertNull(decoded.storedSessionId)
    }

    @Test
    fun sessionResumeResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("session_id", "sess-resumed-1")
                put("active_model", "gemini")
                put("unread_count", 0)
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionResumeResult.serializer(),
                payload,
            )

        assertEquals("sess-resumed-1", decoded.sessionId)
    }

    @Test
    fun sessionResumeResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                SessionResumeResult.serializer(),
                payload,
            )

        assertNull(decoded.sessionId)
    }

    @Test
    fun promptSubmitResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("status", "accepted")
                put("ticket_id", "tick-42")
                put("queued_at", "2026-09-29T10:00:00Z")
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                PromptSubmitResult.serializer(),
                payload,
            )

        assertEquals("accepted", decoded.status)
    }

    @Test
    fun promptSubmitResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                PromptSubmitResult.serializer(),
                payload,
            )

        assertNull(decoded.status)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("session.create", RpcMethods.SESSION_CREATE.name)
        assertEquals("session.resume", RpcMethods.SESSION_RESUME.name)
        assertEquals("prompt.submit", RpcMethods.PROMPT_SUBMIT.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_CREATE))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_RESUME))
        assertTrue(RpcMethods.all.contains(RpcMethods.PROMPT_SUBMIT))
    }
}
