package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalsTypesTest {
    @Test
    fun approvalPendingParamsEncodesSessionIdExactly() {
        val params = ApprovalPendingParams(sessionId = "sess-appr-pending-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalPendingParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-appr-pending-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun approvalReceivedParamsEncodesSessionIdAndRequestId() {
        val params =
            ApprovalReceivedParams(
                sessionId = "sess-appr-rec-1",
                requestId = "req-appr-rec-99",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalReceivedParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-appr-rec-1")
                put("request_id", "req-appr-rec-99")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "request_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun approvalRespondParamsEncodesOnlySessionIdWhenOptionalsNull() {
        val params = ApprovalRespondParams(sessionId = "sess-respond-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-respond-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun approvalRespondParamsEncodesAllFieldsWhenPresent() {
        val params =
            ApprovalRespondParams(
                sessionId = "sess-respond-2",
                choice = "allow",
                all = true,
                requestId = "req-respond-2",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-respond-2")
                put("choice", "allow")
                put("all", true)
                put("request_id", "req-respond-2")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "choice", "all", "request_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun approvalRespondParamsEncodesExplicitFalseForAll() {
        val params =
            ApprovalRespondParams(
                sessionId = "sess-respond-3",
                choice = "deny",
                all = false,
                requestId = "req-respond-3",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-respond-3")
                put("choice", "deny")
                put("all", false)
                put("request_id", "req-respond-3")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "choice", "all", "request_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun approvalRespondParamsOmitsOnlyNullFields() {
        val params =
            ApprovalRespondParams(
                sessionId = "sess-respond-4",
                choice = "allow",
                all = null,
                requestId = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ApprovalRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("session_id", "sess-respond-4")
                put("choice", "allow")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "choice"), (encoded as JsonObject).keys)
    }

    @Test
    fun sessionActiveListParamsEncodesToEmptyJsonObjectWhenCurrentSessionIdNull() {
        val params = SessionActiveListParams()
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionActiveListParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun sessionActiveListParamsEncodesCurrentSessionIdWhenSet() {
        val params = SessionActiveListParams(currentSessionId = "sess-active-curr-1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionActiveListParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("current_session_id", "sess-active-curr-1")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("current_session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("approval.pending", RpcMethods.APPROVAL_PENDING.name)
        assertEquals("approval.received", RpcMethods.APPROVAL_RECEIVED.name)
        assertEquals("approval.respond", RpcMethods.APPROVAL_RESPOND.name)
        assertEquals("session.active_list", RpcMethods.SESSION_ACTIVE_LIST.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.APPROVAL_PENDING))
        assertTrue(RpcMethods.all.contains(RpcMethods.APPROVAL_RECEIVED))
        assertTrue(RpcMethods.all.contains(RpcMethods.APPROVAL_RESPOND))
        assertTrue(RpcMethods.all.contains(RpcMethods.SESSION_ACTIVE_LIST))
    }

    @Test
    fun jsonElementPassthroughMethodsDecodeResultUntouched() {
        val arbitraryPayload =
            buildJsonObject {
                put("status", "pending")
                put("count", 3)
                put("nested", buildJsonObject { put("detail", "value") })
            }

        val decodedPending: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.APPROVAL_PENDING.result,
                arbitraryPayload,
            )
        val decodedReceived: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.APPROVAL_RECEIVED.result,
                arbitraryPayload,
            )
        val decodedRespond: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.APPROVAL_RESPOND.result,
                arbitraryPayload,
            )
        val decodedActiveList: JsonElement =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SESSION_ACTIVE_LIST.result,
                arbitraryPayload,
            )

        assertEquals(arbitraryPayload, decodedPending)
        assertEquals(arbitraryPayload, decodedReceived)
        assertEquals(arbitraryPayload, decodedRespond)
        assertEquals(arbitraryPayload, decodedActiveList)
    }
}
