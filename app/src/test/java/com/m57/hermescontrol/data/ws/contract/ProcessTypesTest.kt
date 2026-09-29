package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTypesTest {
    @Test
    fun killEncodesExactlySessionAndProcessIds() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                ProcessKillParams.serializer(),
                ProcessKillParams(sessionId = "s1", processId = "p1"),
            )
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("process_id", "p1")
            },
            encoded,
        )
    }

    @Test
    fun descriptorsAreRegisteredWithContractNames() {
        assertTrue(RpcMethods.PROCESS_LIST in RpcMethods.all)
        assertTrue(RpcMethods.PROCESS_KILL in RpcMethods.all)
        assertEquals("process.list", RpcMethods.PROCESS_LIST.name)
        assertEquals("process.kill", RpcMethods.PROCESS_KILL.name)
    }
}
