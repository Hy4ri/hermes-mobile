package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMiscTypesTest {
    @Test
    fun sessionListParamsEncodesToEmptyJsonObject() {
        val params = SessionListParams
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionListParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun sessionIdParamsEncodesSessionIdExactly() {
        val params = SessionIdParams(sessionId = "sess-misc-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionIdParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-misc-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchParamsEncodesSessionIdOnlyWhenNameNull() {
        val params = SessionBranchParams(sessionId = "sess-branch-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-branch-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchParamsEncodesSessionIdAndNameWhenPresent() {
        val params = SessionBranchParams(sessionId = "sess-branch-2", name = "experiment-fork")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-branch-2")
                put("name", "experiment-fork")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "name"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchParamsPassesBlankNameThroughAsIs() {
        val params = SessionBranchParams(sessionId = "sess-branch-3", name = "   ")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-branch-3")
                put("name", "   ")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "name"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchWholeParamsEncodesSessionIdOnlyWhenNameNull() {
        val params = SessionBranchWholeParams(sessionId = "sess-whole-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchWholeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-whole-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchWholeParamsEncodesSessionIdAndNameWhenPresent() {
        val params = SessionBranchWholeParams(sessionId = "sess-whole-2", name = "full-tree-fork")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchWholeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-whole-2")
                put("name", "full-tree-fork")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "name"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionBranchWholeParamsPassesBlankNameThroughAsIs() {
        val params = SessionBranchWholeParams(sessionId = "sess-whole-3", name = "  \t ")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionBranchWholeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-whole-3")
                put("name", "  \t ")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "name"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionCompressParamsEncodesSessionIdOnlyWhenFocusTopicNull() {
        val params = SessionCompressParams(sessionId = "sess-comp-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCompressParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-comp-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionCompressParamsEncodesSessionIdAndFocusTopicWhenPresent() {
        val params = SessionCompressParams(sessionId = "sess-comp-2", focusTopic = "summarize-db-schema")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCompressParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-comp-2")
                put("focus_topic", "summarize-db-schema")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "focus_topic"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionCompressParamsPassesBlankFocusTopicThroughAsIs() {
        val params = SessionCompressParams(sessionId = "sess-comp-3", focusTopic = "   ")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionCompressParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-comp-3")
                put("focus_topic", "   ")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "focus_topic"), (encoded as JsonObject).keys)
    }

    @Test
    fun promptBtwParamsEncodesSessionIdAndText() {
        val params = PromptBtwParams(sessionId = "sess-btw-1", text = "by the way, take note")
        val encoded = OkHttpProvider.json.encodeToJsonElement(PromptBtwParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-btw-1")
                put("text", "by the way, take note")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "text"), (encoded as JsonObject).keys)
    }

    @Test
    fun promptBtwResultDecodesFullPayloadAndIgnoresUnknownKeys() {
        val payload =
            buildJsonObject {
                put("task_id", "task-xyz-987")
                put("unknown_flag", true)
                put("extra_num", 100)
            }

        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                PromptBtwResult.serializer(),
                payload,
            )

        assertEquals("task-xyz-987", decoded.taskId)
    }

    @Test
    fun promptBtwResultDecodesEmptyObjectToNulls() {
        val payload = buildJsonObject {}
        val decoded =
            OkHttpProvider.json.decodeFromJsonElement(
                PromptBtwResult.serializer(),
                payload,
            )

        assertNull(decoded.taskId)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("session.list", RpcMethods.SESSION_LIST.name)
        assertEquals("session.branch", RpcMethods.SESSION_BRANCH.name)
        assertEquals("session.branch_whole", RpcMethods.SESSION_BRANCH_WHOLE.name)
        assertEquals("session.compress", RpcMethods.SESSION_COMPRESS.name)
        assertEquals("session.context_breakdown", RpcMethods.SESSION_CONTEXT_BREAKDOWN.name)
        assertEquals("session.usage", RpcMethods.SESSION_USAGE.name)
        assertEquals("prompt.btw", RpcMethods.PROMPT_BTW.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_LIST))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_BRANCH))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_BRANCH_WHOLE))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_COMPRESS))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_CONTEXT_BREAKDOWN))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_USAGE))
        assertTrue(RpcMethods.all.contains(RpcMethods.PROMPT_BTW))
    }

    @Test
    fun jsonElementPassthroughMethodsDecodeResultUntouched() {
        val arbitraryPayload =
            buildJsonObject {
                put("status", "ok")
                put("tokens", 4567)
                put("nested", buildJsonObject { put("item", "val") })
            }

        val decodedList: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_LIST.result,
                arbitraryPayload,
            )
        val decodedBranch: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_BRANCH.result,
                arbitraryPayload,
            )
        val decodedBranchWhole: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_BRANCH_WHOLE.result,
                arbitraryPayload,
            )
        val decodedCompress: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_COMPRESS.result,
                arbitraryPayload,
            )
        val decodedBreakdown: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_CONTEXT_BREAKDOWN.result,
                arbitraryPayload,
            )
        val decodedUsage: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_USAGE.result,
                arbitraryPayload,
            )

        assertEquals(arbitraryPayload, decodedList)
        assertEquals(arbitraryPayload, decodedBranch)
        assertEquals(arbitraryPayload, decodedBranchWhole)
        assertEquals(arbitraryPayload, decodedCompress)
        assertEquals(arbitraryPayload, decodedBreakdown)
        assertEquals(arbitraryPayload, decodedUsage)
    }
}
