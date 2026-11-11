package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionArchiveTypesTest {
    @Test
    fun archiveParamsOmitTheDefaultFlag() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SessionArchiveParams.serializer(),
                SessionArchiveParams(sessionId = "sess-archive-1"),
            )

        assertEquals(buildJsonObject { put("session_id", "sess-archive-1") }, encoded)
    }

    @Test
    fun undoParamsSendArchivedFalse() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SessionArchiveParams.serializer(),
                SessionArchiveParams(sessionId = "sess-archive-1", archived = false),
            )

        assertTrue(encoded is JsonObject)
        assertEquals(setOf("session_id", "archived"), (encoded as JsonObject).keys)
        assertEquals(
            buildJsonObject {
                put("session_id", "sess-archive-1")
                put("archived", false)
            },
            encoded,
        )
    }

    @Test
    fun archiveResultDecodesStoredSessionKey() {
        val result =
            OkHttpProvider.json.decodeFromString(
                SessionArchiveResult.serializer(),
                """{"archived":true,"session_key":"stored-1"}""",
            )

        assertEquals(true, result.archived)
        assertEquals("stored-1", result.sessionKey)
    }

    @Test
    fun archiveIsRegisteredAndProfileScoped() {
        assertEquals("session.archive", RpcMethods.SESSION_ARCHIVE.name)
        assertTrue(RpcMethods.SESSION_ARCHIVE in RpcMethods.all)
        assertTrue(WsMethods.SESSION_ARCHIVE in WsMethods.PROFILE_SCOPED_METHODS)
    }
}
