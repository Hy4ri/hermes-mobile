package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorAccount
import com.m57.hermescontrol.data.model.ConnectorCatalogEntry
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorPolicy
import com.m57.hermescontrol.data.model.ConnectorTool
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import com.m57.hermescontrol.data.ws.contract.ConnectorsAccountsParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsAccountsRemoveParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsCatalogParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsConnectParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsListParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsOperationStatusParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsPolicyGetParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsPolicySetParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsToolsParams
import com.m57.hermescontrol.data.ws.contract.HermesRpcCaller
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface AccountConnectorRepository {
    suspend fun catalog(): AccountConnectorResult<List<ConnectorCatalogEntry>>

    suspend fun listConnectors(): com.m57.hermescontrol.data.model.ConnectorListResult

    suspend fun connect(
        slugs: List<String>,
        reconnect: Boolean = false,
    ): AccountConnectorResult<com.m57.hermescontrol.data.model.ConnectionOperationSnapshot>

    suspend fun accounts(): AccountConnectorResult<List<ConnectorAccount>>

    suspend fun remove(connectionId: String): AccountConnectorResult<Unit>

    suspend fun tools(
        slug: String,
        refresh: Boolean = false,
    ): AccountConnectorResult<List<ConnectorTool>>

    suspend fun policy(): AccountConnectorResult<ConnectorPolicy>

    suspend fun setConnectorEnabled(
        slug: String,
        enabled: Boolean,
        expectedRevision: String,
    ): AccountConnectorResult<ConnectorPolicy>

    suspend fun setDisabledTools(
        slug: String,
        disabled: List<String>,
        expectedRevision: String,
    ): AccountConnectorResult<ConnectorPolicy>

    suspend fun operationRequest(
        method: String,
        params: Map<String, Any>,
    ): Any?

    suspend fun operationStatus(
        opId: String,
    ): AccountConnectorResult<com.m57.hermescontrol.data.model.ConnectionOperationSnapshot>

    companion object : AccountConnectorRepository by HermesAccountConnectorRepository()
}

class HermesAccountConnectorRepository(
    private val rpc: suspend (String, Map<String, Any>) -> Any? = { method, params ->
        val deferred = HermesWsClient.request(method, params)
        try {
            deferred.await()
        } catch (
            e: CancellationException,
        ) {
            deferred.cancel(e)
            throw e
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
        }
    },
    private val caller: TypedRpcCaller = HermesRpcCaller,
) : AccountConnectorRepository {
    private suspend fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
    ) = rpc(method, params)

    private suspend fun <T> guarded(block: suspend () -> T): AccountConnectorResult<T> =
        try {
            AccountConnectorResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            AccountConnectorResult.Failure(ConnectorParser.mapRpcError(e.code, e.message, e.data))
        } catch (_: IllegalArgumentException) {
            AccountConnectorResult.Failure(ConnectorError.InvalidResponse())
        } catch (_: IllegalStateException) {
            AccountConnectorResult.Failure(ConnectorError.InvalidResponse())
        } catch (
            _: Exception,
        ) {
            AccountConnectorResult.Failure(ConnectorError.NetworkError("Failed to communicate with gateway."))
        }

    private fun accountParams(params: Map<String, Any> = emptyMap()): Map<String, Any> =
        buildMap {
            putAll(params)
            AuthManager.activeProfileId.value?.let { put("profile", it) }
        }

    private fun obj(value: Any?): JsonObject = ConnectorParser.toJsonElement(value).jsonObject

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.contentOrNull == "true"

    private fun JsonObject.array(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.stringList(k: String) = array(k).mapNotNull { it.jsonPrimitive.contentOrNull }

    override suspend fun listConnectors(): com.m57.hermescontrol.data.model.ConnectorListResult =
        try {
            ConnectorParser.parseListResult(
                caller.call(
                    RpcMethods.CONNECTORS_LIST,
                    ConnectorsListParams(
                        owner = ConnectorOwner.account(),
                        profile = AuthManager.activeProfileId.value,
                    ),
                ),
            )
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (
            e: HermesWsClient.HermesRpcException,
        ) {
            com.m57.hermescontrol.data.model.ConnectorListResult.Error(
                ConnectorParser.mapRpcError(e.code, e.message, e.data),
            )
        } catch (
            _: Exception,
        ) {
            com.m57.hermescontrol.data.model.ConnectorListResult
                .Error(ConnectorError.NetworkError())
        }

    override suspend fun connect(
        slugs: List<String>,
        reconnect: Boolean,
    ) = guarded {
        require(slugs.isNotEmpty() && slugs.all(ConnectorRepository::isValidSlug))
        parseOperation(
            caller.call(
                RpcMethods.CONNECTORS_CONNECT,
                ConnectorsConnectParams(
                    owner = ConnectorOwner.account(),
                    connectors = slugs,
                    reconnect = reconnect,
                    profile = AuthManager.activeProfileId.value,
                ),
            ),
        )
    }

    override suspend fun operationRequest(
        method: String,
        params: Map<String, Any>,
    ): Any? = request(method, accountParams(params + ("owner" to mapOf("type" to "account"))))

    override suspend fun operationStatus(opId: String) =
        guarded {
            parseOperation(
                caller.call(
                    RpcMethods.CONNECTORS_OPERATION_STATUS,
                    ConnectorsOperationStatusParams(
                        owner = ConnectorOwner.account(),
                        opId = opId,
                        profile = AuthManager.activeProfileId.value,
                    ),
                ),
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseOperation(raw: Any?): com.m57.hermescontrol.data.model.ConnectionOperationSnapshot =
        checkNotNull(
            ConnectionOperationParser.parse(
                (ConnectorParser.toJsonElement(raw).toAny() as Map<String, Any?>) +
                    ("owner" to mapOf("type" to "account")),
            ),
        )

    override suspend fun catalog() =
        guarded {
            val response =
                caller.call(
                    RpcMethods.CONNECTORS_CATALOG,
                    ConnectorsCatalogParams(
                        profile = AuthManager.activeProfileId.value,
                    ),
                )
            obj(response)["connectors"]!!.jsonArray.map {
                val row = it.jsonObject
                ConnectorCatalogEntry(row.str("slug"), row.str("name"), row.str("description"), row.str("category"))
            }
        }

    override suspend fun accounts() =
        guarded {
            val response =
                caller.call(
                    RpcMethods.CONNECTORS_ACCOUNTS,
                    ConnectorsAccountsParams(
                        profile = AuthManager.activeProfileId.value,
                    ),
                )
            obj(response)["accounts"]!!.jsonArray.map { e ->
                val r = e.jsonObject
                ConnectorAccount(
                    r.str("connection_id"),
                    r.str("connector"),
                    r.str("status"),
                    r["status_reason"]?.jsonPrimitive?.contentOrNull,
                    r.str("label"),
                    r["alias"]?.jsonPrimitive?.contentOrNull,
                    r.bool("active"),
                    r.str("created_at"),
                    r.str("updated_at"),
                )
            }
        }

    override suspend fun remove(connectionId: String) =
        guarded {
            require(connectionId.isNotBlank())
            val response =
                obj(
                    caller.call(
                        RpcMethods.CONNECTORS_ACCOUNTS_REMOVE,
                        ConnectorsAccountsRemoveParams(
                            connectionId = connectionId,
                            profile = AuthManager.activeProfileId.value,
                        ),
                    ),
                )
            check(response.str("status") == "removed" && response.str("connection_id") == connectionId)
            Unit
        }

    override suspend fun tools(
        slug: String,
        refresh: Boolean,
    ) = guarded {
        require(ConnectorRepository.isValidSlug(slug))
        val response =
            caller.call(
                RpcMethods.CONNECTORS_TOOLS,
                ConnectorsToolsParams(
                    slug = slug,
                    refresh = if (refresh) true else null,
                    profile = AuthManager.activeProfileId.value,
                ),
            )
        obj(response)["tools"]!!.jsonArray.map { e ->
            val r = e.jsonObject
            ConnectorTool(
                r.str("slug"),
                r.str("name"),
                r.str("description"),
                r.str("facet"),
                r.bool("deprecated"),
                r.stringList("hints"),
            )
        }
    }

    override suspend fun policy() =
        guarded {
            val root =
                obj(
                    caller.call(
                        RpcMethods.CONNECTORS_POLICY_GET,
                        ConnectorsPolicyGetParams(
                            profile = AuthManager.activeProfileId.value,
                        ),
                    ),
                )
            val layers = root.array("layers").map { it.jsonObject }
            val member = layers.firstOrNull { it.str("kind") == "member" }
            parsePolicy(root["effective"]!!.jsonObject).copy(
                member = member?.let { parsePolicy(it["body"]!!.jsonObject, it.str("revision")) },
                inherited =
                    layers.filter { it.str("kind") != "member" }.map {
                        parsePolicy(it["body"]!!.jsonObject, it.str("revision"))
                    },
            )
        }

    override suspend fun setConnectorEnabled(
        slug: String,
        enabled: Boolean,
        expectedRevision: String,
    ) = setPolicy(
        expectedRevision,
        mapOf(
            "type" to "connector",
            "connector" to slug,
            "enabled" to enabled,
        ),
    )

    override suspend fun setDisabledTools(
        slug: String,
        disabled: List<String>,
        expectedRevision: String,
    ) = setPolicy(
        expectedRevision,
        mapOf(
            "type" to "tools",
            "connector" to slug,
            "disabled_tools" to disabled,
        ),
    )

    private suspend fun setPolicy(
        expectedRevision: String,
        change: Map<String, Any>,
    ) = guarded {
        val scope = AuthManager.currentDataScope()
        require(ConnectorRepository.isValidSlug(change["connector"] as? String))
        require(expectedRevision.isNotBlank())
        check(scope == AuthManager.currentDataScope()) { "Account scope changed" }
        val result =
            obj(
                caller.call(
                    RpcMethods.CONNECTORS_POLICY_SET,
                    ConnectorsPolicySetParams(
                        change = ConnectorParser.toJsonElement(change) as JsonObject,
                        expectedRevision = expectedRevision,
                        profile = AuthManager.activeProfileId.value,
                    ),
                ),
            )
        check(scope == AuthManager.currentDataScope()) { "Account scope changed" }
        when (val refreshed = policy()) {
            is AccountConnectorResult.Success -> refreshed.value
            is AccountConnectorResult.Failure -> parsePolicy(result["effective"]!!.jsonObject)
        }
    }

    private fun parsePolicy(
        r: JsonObject,
        revision: String = r.str("revision"),
    ) = ConnectorPolicy(
        revision,
        r.str("mode"),
        r.stringList("connectors"),
        r.stringList("disabled_connectors"),
        (r["tools"] as? JsonObject)?.mapValues { (_, v) ->
            (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
        }
            ?: emptyMap(),
        enabledTags =
            (r["tags"] as? JsonObject)
                ?.let {
                    (it["enable"] as? JsonArray)?.mapNotNull { tag ->
                        tag.jsonPrimitive.contentOrNull
                    }
                }.orEmpty(),
        disabledTags =
            (r["tags"] as? JsonObject)
                ?.let {
                    (it["disable"] as? JsonArray)?.mapNotNull { tag ->
                        tag.jsonPrimitive.contentOrNull
                    }
                }.orEmpty(),
    )
}
