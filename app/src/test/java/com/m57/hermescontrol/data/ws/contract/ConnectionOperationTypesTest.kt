package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOperationTypesTest {
    @Test
    fun connectionRespondParamsWithApprovedEnvAndAccountOwnerEncodesExactWireJson() {
        val params =
            ConnectionRespondParams(
                owner = ConnectorOwner.account(),
                opId = "op-approved-1",
                result =
                    ConnectionAnswer(
                        targets =
                            listOf(
                                ConnectionAnswerTarget(
                                    name = "linear",
                                    status = "approved",
                                    detail = "User accepted linear integration",
                                    env = mapOf("API_KEY" to "dummy_secret_key_123"),
                                ),
                            ),
                        settledBy = null,
                    ),
                profile = "work",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("op_id", "op-approved-1")
                put(
                    "result",
                    buildJsonObject {
                        put(
                            "targets",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "linear")
                                        put("status", "approved")
                                        put("detail", "User accepted linear integration")
                                        put(
                                            "env",
                                            buildJsonObject {
                                                put("API_KEY", "dummy_secret_key_123")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put("profile", "work")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id", "result", "profile"), (encoded as JsonObject).keys)
        val resultObj = (encoded as JsonObject)["result"] as JsonObject
        assertEquals(setOf("targets"), resultObj.keys)
        assertFalse(resultObj.containsKey("settled_by"))
    }

    @Test
    fun connectionRespondParamsWithSkippedOmittedEnvAndSessionOwnerEncodesExactWireJson() {
        val params =
            ConnectionRespondParams(
                owner = ConnectorOwner.session("sess-xyz"),
                opId = "op-skip-2",
                result =
                    ConnectionAnswer(
                        targets =
                            listOf(
                                ConnectionAnswerTarget(
                                    name = "github",
                                    status = "skipped",
                                    detail = null,
                                    env = null,
                                ),
                            ),
                        settledBy = null,
                    ),
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-xyz")
                    },
                )
                put("op_id", "op-skip-2")
                put(
                    "result",
                    buildJsonObject {
                        put(
                            "targets",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "github")
                                        put("status", "skipped")
                                    },
                                )
                            },
                        )
                    },
                )
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id", "result"), (encoded as JsonObject).keys)
        assertFalse((encoded as JsonObject).containsKey("profile"))

        val resultObj = (encoded as JsonObject)["result"] as JsonObject
        assertEquals(setOf("targets"), resultObj.keys)
        assertFalse(resultObj.containsKey("settled_by"))

        val targetObj = (resultObj["targets"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
        assertEquals(setOf("name", "status"), targetObj.keys)
        assertFalse(targetObj.containsKey("env"))
        assertFalse(targetObj.containsKey("detail"))
    }

    @Test
    fun connectionRespondParamsWithContinueAndOmittedTargetsEncodesExactWireJson() {
        val params =
            ConnectionRespondParams(
                owner = ConnectorOwner.session("sess-cont-3"),
                opId = "op-cont-3",
                result =
                    ConnectionAnswer(
                        targets = null,
                        settledBy = "continue",
                    ),
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-cont-3")
                    },
                )
                put("op_id", "op-cont-3")
                put(
                    "result",
                    buildJsonObject {
                        put("settled_by", "continue")
                    },
                )
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id", "result"), (encoded as JsonObject).keys)
        assertFalse((encoded as JsonObject).containsKey("profile"))

        val resultObj = (encoded as JsonObject)["result"] as JsonObject
        assertEquals(setOf("settled_by"), resultObj.keys)
        assertFalse(resultObj.containsKey("targets"))
    }

    @Test
    fun connectorsOperationWakeUsesConnectorsOperationStatusParamsAndEncodesAllFieldsWhenProfilePresent() {
        val params =
            ConnectorsOperationStatusParams(
                owner = ConnectorOwner.session("sess-wake-1"),
                opId = "op-wake-1",
                profile = "staging",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsOperationStatusParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-wake-1")
                    },
                )
                put("op_id", "op-wake-1")
                put("profile", "staging")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsOperationWakeUsesConnectorsOperationStatusParamsAndOmitsProfileWhenNull() {
        val params =
            ConnectorsOperationStatusParams(
                owner = ConnectorOwner.account(),
                opId = "op-wake-2",
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsOperationStatusParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("op_id", "op-wake-2")
            }

        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id"), (encoded as JsonObject).keys)
        assertFalse((encoded as JsonObject).containsKey("profile"))
    }

    @Test
    fun connectionRespondAndWakeAreRegisteredInGatewayContractRpcMethodsAll() {
        val allMethods = RpcMethods.all
        val respondMethod = allMethods.firstOrNull { it.name == "connection.respond" }
        val wakeMethod = allMethods.firstOrNull { it.name == "connectors.operation.wake" }

        org.junit.Assert.assertNotNull("connection.respond must be registered in RpcMethods.all", respondMethod)
        org.junit.Assert.assertNotNull("connectors.operation.wake must be registered in RpcMethods.all", wakeMethod)

        assertEquals(RpcMethods.CONNECTION_RESPOND, respondMethod)
        assertEquals(RpcMethods.CONNECTORS_OPERATION_WAKE, wakeMethod)
    }
}
