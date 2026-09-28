package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorAccount
import com.m57.hermescontrol.data.model.ConnectorCatalogEntry
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorPolicy
import com.m57.hermescontrol.data.model.ConnectorTool
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
                rpc(
                    WsMethods.CONNECTORS_LIST,
                    accountParams(
                        mapOf("owner" to mapOf("type" to "account")),
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
            request(
                WsMethods.CONNECTORS_CONNECT,
                accountParams(
                    mapOf(
                        "owner" to mapOf("type" to "account"),
                        "connectors" to slugs,
                        "reconnect" to reconnect,
                    ),
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
            parseOperation(operationRequest(WsMethods.CONNECTORS_OPERATION_STATUS, mapOf("op_id" to opId)))
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
            obj(request(WsMethods.CONNECTORS_CATALOG, accountParams()))["connectors"]!!.jsonArray.map {
                val row = it.jsonObject
                ConnectorCatalogEntry(row.str("slug"), row.str("name"), row.str("description"), row.str("category"))
            }
        }

    override suspend fun accounts() =
        guarded {
            obj(request(WsMethods.CONNECTORS_ACCOUNTS, accountParams()))["accounts"]!!.jsonArray.map { e ->
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
                    request(
                        WsMethods.CONNECTORS_ACCOUNTS_REMOVE,
                        accountParams(
                            mapOf("connection_id" to connectionId),
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
        val params =
            buildMap<String, Any> {
                put("slug", slug)
                if (refresh) put("refresh", true)
            }
        obj(request(WsMethods.CONNECTORS_TOOLS, accountParams(params)))["tools"]!!.jsonArray.map { e ->
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
            val root = obj(request(WsMethods.CONNECTORS_POLICY_GET, accountParams()))
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
                request(
                    WsMethods.CONNECTORS_POLICY_SET,
                    accountParams(
                        mapOf(
                            "change" to change,
                            "expected_revision" to expectedRevision,
                        ),
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
