package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.JsonRpcError
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefusalReasonTest {
    @Test
    fun onlyStringReasonInObjectIsAccepted() {
        assertEquals(
            "SESSION_NOT_OWNED",
            refusalReasonOf(
                JsonRpcError(4090, "refused", Json.parseToJsonElement("""{"reason":"SESSION_NOT_OWNED"}""")),
            ),
        )
        for (raw in listOf(
            "{}",
            "[]",
            "42",
            "null",
            "\"reason\"",
            """{"reason":null}""",
            """{"reason":4}""",
            """{"reason":true}""",
            """{"reason":{}}""",
        )) {
            assertNull(raw, refusalReasonOf(JsonRpcError(4090, "refused", Json.parseToJsonElement(raw))))
        }
        assertNull(refusalReasonOf(JsonRpcError(4090, "refused")))
    }
}
