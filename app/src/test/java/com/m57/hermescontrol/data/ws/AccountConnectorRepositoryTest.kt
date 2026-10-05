package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorTool
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswer
import com.m57.hermescontrol.data.ws.contract.ConnectionRespondParams
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import com.m57.hermescontrol.data.ws.contract.ConnectorsOperationStatusParams
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountConnectorRepositoryTest {
    @Before fun setup() {
        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
        every { AuthManager.currentDataScope() } returns null
    }

    @After fun teardown() = unmockkAll()

    @Test fun `account loading handles entitlement errors without broadcasting raw errors`() =
        runTest {
            mockkObject(HermesWsClient)
            coEvery {
                HermesWsClient.call(any<RpcMethod<Any?, JsonElement>>(), any(), any(), any())
            } throws
                HermesWsClient.HermesRpcException(
                    "Connectors are not available.",
                    4031,
                    JsonObject(mapOf("reason" to JsonPrimitive("CONNECTORS_UNAVAILABLE"))),
                )
            val repo = HermesAccountConnectorRepository()
            assertTrue(repo.listConnectors().errorOrNull() is ConnectorError.Unavailable)
            assertTrue((repo.catalog() as AccountConnectorResult.Failure).error is ConnectorError.Unavailable)
            assertTrue((repo.accounts() as AccountConnectorResult.Failure).error is ConnectorError.Unavailable)
            assertTrue((repo.policy() as AccountConnectorResult.Failure).error is ConnectorError.Unavailable)
            coVerify(exactly = 4) {
                HermesWsClient.call(any<RpcMethod<Any?, JsonElement>>(), any(), any(), suppressErrorEvent = true)
            }
        }

    @Test fun `connect decodes operation snapshot and preserves authorization link without a session`() =
        runTest {
            val calls = mutableListOf<Pair<String, Map<String, Any>>>()
            val repo =
                accountRepo { method, params ->
                    calls += method to params
                    operationFixture()
                }
            val result = repo.connect(listOf("drive"), true) as AccountConnectorResult.Success
            assertTrue(result.value.accountOwned)
            assertEquals("op-a", result.value.opId)
            assertEquals(
                "https://example.com/authorize",
                result.value.targets
                    .single()
                    .safeConnectUrl,
            )
            assertEquals(
                mapOf(
                    "profile" to "work",
                    "owner" to mapOf("type" to "account"),
                    "connectors" to listOf("drive"),
                    "reconnect" to true,
                ),
                calls.single().second,
            )
            assertEquals(WsMethods.CONNECTORS_CONNECT, calls.single().first)
        }

    @Test fun `account operation respond status and wake retain profile and owner`() =
        runTest {
            val calls = mutableListOf<Pair<String, Map<String, Any>>>()
            val repo =
                accountRepo { method, params ->
                    calls += method to params
                    operationFixture()
                }
            repo.operationStatus("op-a")
            repo.operationRespond(
                ConnectionRespondParams(
                    owner = ConnectorOwner.account(),
                    opId = "op-a",
                    result = ConnectionAnswer(settledBy = "continue"),
                ),
            )
            repo.operationWake(
                ConnectorsOperationStatusParams(
                    owner = ConnectorOwner.account(),
                    opId = "op-a",
                ),
            )
            assertEquals(
                listOf(
                    WsMethods.CONNECTORS_OPERATION_STATUS,
                    WsMethods.CONNECTION_RESPOND,
                    WsMethods.CONNECTORS_OPERATION_WAKE,
                ),
                calls.map {
                    it.first
                },
            )
            calls.forEach { (_, params) ->
                assertEquals("work", params["profile"])
                assertEquals(mapOf("type" to "account"), params["owner"])
                assertEquals("op-a", params["op_id"])
                assertFalse("session_id" in params)
            }
            assertEquals(
                mapOf(
                    "owner" to mapOf("type" to "account"),
                    "op_id" to "op-a",
                    "result" to mapOf("settled_by" to "continue"),
                    "profile" to "work",
                ),
                calls[1].second,
            )
            assertEquals(
                mapOf(
                    "owner" to mapOf("type" to "account"),
                    "op_id" to "op-a",
                    "profile" to "work",
                ),
                calls[2].second,
            )
        }

    @Test fun `catalog accounts tools and removal use native account endpoints`() =
        runTest {
            val calls = mutableListOf<Pair<String, Map<String, Any>>>()
            val repo =
                accountRepo { method, params ->
                    calls += method to params
                    when (method) {
                        WsMethods.CONNECTORS_CATALOG -> {
                            mapOf(
                                "connectors" to
                                    listOf(
                                        mapOf(
                                            "slug" to "drive",
                                            "name" to "Google Drive",
                                            "description" to "Files",
                                            "category" to "storage",
                                        ),
                                    ),
                            )
                        }

                        WsMethods.CONNECTORS_ACCOUNTS -> {
                            mapOf(
                                "accounts" to
                                    listOf(
                                        mapOf(
                                            "connection_id" to "conn-1",
                                            "connector" to "drive",
                                            "label" to "Work account",
                                            "status" to "active",
                                            "active" to true,
                                            "alias" to null,
                                        ),
                                    ),
                            )
                        }

                        WsMethods.CONNECTORS_TOOLS -> {
                            mapOf(
                                "tools" to
                                    listOf(
                                        mapOf(
                                            "slug" to "delete",
                                            "name" to "Delete file",
                                            "hints" to listOf("destructive"),
                                        ),
                                    ),
                            )
                        }

                        WsMethods.CONNECTORS_ACCOUNTS_REMOVE -> {
                            mapOf(
                                "connection_id" to "conn-1",
                                "connector" to "drive",
                                "status" to "removed",
                            )
                        }

                        else -> {
                            error(method)
                        }
                    }
                }
            assertEquals("Google Drive", (repo.catalog() as AccountConnectorResult.Success).value.single().name)
            assertEquals("Work account", (repo.accounts() as AccountConnectorResult.Success).value.single().label)
            assertEquals(
                "Delete file",
                (repo.tools("drive", true) as AccountConnectorResult.Success).value.single().name,
            )
            assertTrue(repo.remove("conn-1") is AccountConnectorResult.Success)
            assertTrue(calls.all { "owner" !in it.second && it.second["profile"] == "work" })
            assertEquals(mapOf("profile" to "work", "slug" to "drive", "refresh" to true), calls[2].second)
            assertEquals(mapOf("profile" to "work", "connection_id" to "conn-1"), calls[3].second)
        }

    @Test fun `policy keeps member revision separate from effective and respects inherited rules`() =
        runTest {
            val repo = accountRepo { _, _ -> policyFixture() }
            val policy = (repo.policy() as AccountConnectorResult.Success).value
            assertEquals("effective-revision", policy.revision)
            assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", policy.member?.revision)
            assertEquals(listOf("mine"), policy.member?.tools?.get("drive"))
            val destructive = ConnectorTool("delete", "Delete", "", "destructive", false, listOf("destructive"))
            assertFalse(policy.inherited.single().toolEnabled("drive", destructive))
            assertFalse(policy.copy(mode = "deny-all").connectorEnabled("drive"))
            assertFalse(policy.copy(mode = "future-mode").connectorEnabled("drive"))
        }

    @Test fun `writes use observed member revision not effective revision or fresh overwritten version`() =
        runTest {
            val calls = mutableListOf<Pair<String, Map<String, Any>>>()
            val repo =
                accountRepo { method, params ->
                    calls += method to params
                    if (method == WsMethods.CONNECTORS_POLICY_GET) {
                        policyFixture()
                    } else {
                        mapOf(
                            "revision" to "new-member-revision",
                            "effective" to mapOf("mode" to "deny-all", "revision" to "effective-revision"),
                        )
                    }
                }
            assertTrue(
                repo.setDisabledTools(
                    "drive",
                    listOf("delete"),
                    "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                ) is AccountConnectorResult.Success,
            )
            assertEquals(WsMethods.CONNECTORS_POLICY_SET, calls.first().first)
            assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", calls.first().second["expected_revision"])
            assertEquals(
                mapOf("type" to "tools", "connector" to "drive", "disabled_tools" to listOf("delete")),
                calls.first().second["change"],
            )
        }

    @Test fun `malformed authorization does not look like a successful connect`() =
        runTest {
            val repo = accountRepo { _, _ -> mapOf("status" to "initiated") }
            assertTrue(repo.connect(listOf("drive")) is AccountConnectorResult.Failure)
        }

    private fun accountRepo(handler: suspend (String, Map<String, Any>) -> Any?) =
        HermesAccountConnectorRepository(caller = fakeCaller(handler))

    private fun operationFixture() =
        mapOf(
            "op_id" to "op-a",
            "seq" to 1,
            "deadline_at" to 2000000000.0,
            "settled" to false,
            "targets" to
                listOf(
                    mapOf(
                        "name" to "drive",
                        "kind" to "connector",
                        "action" to "authorize",
                        "state" to "initiated",
                        "connect_url" to "https://example.com/authorize",
                    ),
                ),
        )

    private fun policyFixture() =
        mapOf(
            "effective" to
                mapOf(
                    "mode" to "deny",
                    "revision" to "effective-revision",
                    "disabled_connectors" to emptyList<String>(),
                ),
            "layers" to
                listOf(
                    mapOf(
                        "kind" to "member",
                        "revision" to "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                        "body" to mapOf("mode" to "deny", "tools" to mapOf("drive" to listOf("mine"))),
                    ),
                    mapOf(
                        "kind" to "org",
                        "revision" to "org-revision",
                        "body" to mapOf("mode" to "deny", "tags" to mapOf("disable" to listOf("destructive"))),
                    ),
                ),
        )
}
