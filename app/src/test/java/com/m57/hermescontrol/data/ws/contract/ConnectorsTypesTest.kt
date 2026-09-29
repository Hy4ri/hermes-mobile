package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorsTypesTest {
    @Test
    fun connectorOwnerAccountEncodesToTypeAccountWithoutSessionId() {
        val owner = ConnectorOwner.account()
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorOwner.serializer(), owner)

        val expected =
            buildJsonObject {
                put("type", "account")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("type"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorOwnerSessionEncodesToTypeSessionAndSessionId() {
        val owner = ConnectorOwner.session("s1")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorOwner.serializer(), owner)

        val expected =
            buildJsonObject {
                put("type", "session")
                put("session_id", "s1")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("type", "session_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsListParamsEncodesOwnerAndProfileWhenProfileProvided() {
        val params = ConnectorsListParams(owner = ConnectorOwner.account(), profile = "work")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsListParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsListParamsOmitsProfileWhenNull() {
        val params = ConnectorsListParams(owner = ConnectorOwner.session("s1"), profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsListParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "s1")
                    },
                )
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsConnectParamsEncodesAllFieldsWhenPresent() {
        val params =
            ConnectorsConnectParams(
                owner = ConnectorOwner.account(),
                connectors = listOf("slack", "github"),
                reconnect = true,
                profile = "personal",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsConnectParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put(
                    "connectors",
                    buildJsonArray {
                        add(JsonPrimitive("slack"))
                        add(JsonPrimitive("github"))
                    },
                )
                put("reconnect", true)
                put("profile", "personal")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "connectors", "reconnect", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsConnectParamsEncodesExplicitFalseForReconnect() {
        val params =
            ConnectorsConnectParams(
                owner = ConnectorOwner.session("s2"),
                connectors = listOf("jira"),
                reconnect = false,
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsConnectParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "s2")
                    },
                )
                put(
                    "connectors",
                    buildJsonArray {
                        add(JsonPrimitive("jira"))
                    },
                )
                put("reconnect", false)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "connectors", "reconnect"), (encoded as JsonObject).keys)
        assertFalse((encoded as JsonObject).containsKey("profile"))
    }

    @Test
    fun connectorsConnectParamsOmitsNullReconnectAndProfile() {
        val params =
            ConnectorsConnectParams(
                owner = ConnectorOwner.account(),
                connectors = emptyList(),
                reconnect = null,
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsConnectParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("connectors", buildJsonArray {})
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "connectors"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsOperationStatusParamsEncodesAllFieldsWhenPresent() {
        val params =
            ConnectorsOperationStatusParams(
                owner = ConnectorOwner.session("sess-1"),
                opId = "op-99",
                profile = "work",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsOperationStatusParams.serializer(), params)

        val expected =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-1")
                    },
                )
                put("op_id", "op-99")
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsOperationStatusParamsOmitsNullProfile() {
        val params =
            ConnectorsOperationStatusParams(
                owner = ConnectorOwner.account(),
                opId = "op-100",
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
                put("op_id", "op-100")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("owner", "op_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsCatalogParamsEncodesProfileWhenPresent() {
        val params = ConnectorsCatalogParams(profile = "default")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsCatalogParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("profile", "default")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsCatalogParamsOmitsNullProfileResultingInEmptyJsonObject() {
        val params = ConnectorsCatalogParams(profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsCatalogParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun connectorsAccountsParamsEncodesAllFieldsWhenPresent() {
        val params = ConnectorsAccountsParams(connector = "google", profile = "work")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsAccountsParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("connector", "google")
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("connector", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsAccountsParamsOmitsNullFieldsResultingInEmptyJsonObject() {
        val params = ConnectorsAccountsParams(connector = null, profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsAccountsParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun connectorsAccountsParamsEncodesConnectorOnlyWhenProfileNull() {
        val params = ConnectorsAccountsParams(connector = "github", profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsAccountsParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("connector", "github")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("connector"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsAccountsRemoveParamsEncodesConnectionIdAndProfile() {
        val params = ConnectorsAccountsRemoveParams(connectionId = "conn-123", profile = "work")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsAccountsRemoveParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("connection_id", "conn-123")
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("connection_id", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsAccountsRemoveParamsOmitsNullProfile() {
        val params = ConnectorsAccountsRemoveParams(connectionId = "conn-456", profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsAccountsRemoveParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("connection_id", "conn-456")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("connection_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsPolicyGetParamsEncodesProfileWhenPresent() {
        val params = ConnectorsPolicyGetParams(profile = "custom-profile")
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsPolicyGetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("profile", "custom-profile")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsPolicyGetParamsOmitsNullProfileResultingInEmptyJsonObject() {
        val params = ConnectorsPolicyGetParams(profile = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsPolicyGetParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun connectorsPolicySetParamsEncodesNestedChangeWithArraysUntouched() {
        val nestedChange =
            buildJsonObject {
                put("action", "allow")
                put(
                    "tools",
                    buildJsonArray {
                        add(JsonPrimitive("gmail.send"))
                        add(JsonPrimitive("gmail.list"))
                    },
                )
                put(
                    "scope",
                    buildJsonObject {
                        put("level", "read-write")
                        put(
                            "allowed_users",
                            buildJsonArray {
                                add(JsonPrimitive("alice"))
                                add(JsonPrimitive("bob"))
                            },
                        )
                    },
                )
            }
        val params =
            ConnectorsPolicySetParams(
                change = nestedChange,
                expectedRevision = "4",
                profile = "work",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsPolicySetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("change", nestedChange)
                put("expected_revision", "4")
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("change", "expected_revision", "profile"), (encoded as JsonObject).keys)
        assertEquals(nestedChange, (encoded as JsonObject)["change"])
    }

    @Test
    fun connectorsPolicySetParamsOmitsNullProfile() {
        val change = buildJsonObject { put("enabled", true) }
        val params =
            ConnectorsPolicySetParams(
                change = change,
                expectedRevision = "1",
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsPolicySetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("change", change)
                put("expected_revision", "1")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("change", "expected_revision"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsToolsParamsEncodesAllFieldsWhenPresent() {
        val params =
            ConnectorsToolsParams(
                slug = "gmail",
                refresh = true,
                profile = "work",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsToolsParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("slug", "gmail")
                put("refresh", true)
                put("profile", "work")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("slug", "refresh", "profile"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsToolsParamsEncodesExplicitFalseForRefresh() {
        val params =
            ConnectorsToolsParams(
                slug = "slack",
                refresh = false,
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsToolsParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("slug", "slack")
                put("refresh", false)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("slug", "refresh"), (encoded as JsonObject).keys)
    }

    @Test
    fun connectorsToolsParamsOmitsNullRefreshAndProfile() {
        val params =
            ConnectorsToolsParams(
                slug = "github",
                refresh = null,
                profile = null,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectorsToolsParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("slug", "github")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("slug"), (encoded as JsonObject).keys)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("connectors.list", RpcMethods.CONNECTORS_LIST.name)
        assertEquals("connectors.connect", RpcMethods.CONNECTORS_CONNECT.name)
        assertEquals("connectors.operation.status", RpcMethods.CONNECTORS_OPERATION_STATUS.name)
        assertEquals("connectors.catalog", RpcMethods.CONNECTORS_CATALOG.name)
        assertEquals("connectors.accounts", RpcMethods.CONNECTORS_ACCOUNTS.name)
        assertEquals("connectors.accounts.remove", RpcMethods.CONNECTORS_ACCOUNTS_REMOVE.name)
        assertEquals("connectors.policy.get", RpcMethods.CONNECTORS_POLICY_GET.name)
        assertEquals("connectors.policy.set", RpcMethods.CONNECTORS_POLICY_SET.name)
        assertEquals("connectors.tools", RpcMethods.CONNECTORS_TOOLS.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_LIST))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_CONNECT))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_OPERATION_STATUS))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_CATALOG))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_ACCOUNTS))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_ACCOUNTS_REMOVE))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_POLICY_GET))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_POLICY_SET))
        assertTrue(RpcMethods.all.contains(RpcMethods.CONNECTORS_TOOLS))
    }

    @Test
    fun jsonElementPassthroughMethodsDecodeResultUntouched() {
        val arbitraryPayload =
            buildJsonObject {
                put("status", "ok")
                put(
                    "items",
                    buildJsonArray {
                        add(JsonPrimitive("elem1"))
                        add(JsonPrimitive("elem2"))
                    },
                )
                put("nested", buildJsonObject { put("revision", 10) })
            }

        val methods =
            listOf(
                RpcMethods.CONNECTORS_LIST,
                RpcMethods.CONNECTORS_CONNECT,
                RpcMethods.CONNECTORS_OPERATION_STATUS,
                RpcMethods.CONNECTORS_CATALOG,
                RpcMethods.CONNECTORS_ACCOUNTS,
                RpcMethods.CONNECTORS_ACCOUNTS_REMOVE,
                RpcMethods.CONNECTORS_POLICY_GET,
                RpcMethods.CONNECTORS_POLICY_SET,
                RpcMethods.CONNECTORS_TOOLS,
            )

        for (method in methods) {
            val decoded: JsonElement =
                OkHttpProvider.json.decodeFromJsonElement(
                    method.result,
                    arbitraryPayload,
                )
            assertEquals("Method " + method.name + " must pass raw result untouched", arbitraryPayload, decoded)
        }
    }
}
