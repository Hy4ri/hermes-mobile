package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorListResult
import kotlinx.coroutines.CancellationException

/**
 * Repository interface for connector operations: `connectors.list` and `connectors.connect` (issue #1091).
 * Public injectable interface enabling isolated testing in ViewModels and controllers.
 */
interface ConnectorRepository {
    /**
     * Request connector catalog and authorization status for [sessionId].
     */
    suspend fun listConnectors(sessionId: String): ConnectorListResult

    /**
     * Initiate authorization flow for [connectors] slugs in [sessionId].
     * If [reconnect] is true, restarts authorization flow even if already connected.
     */
    suspend fun connect(
        sessionId: String,
        connectors: List<String>,
        reconnect: Boolean = false,
    ): ConnectorConnectResult

    companion object : ConnectorRepository by HermesConnectorRepository() {
        private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

        fun isValidSlug(slug: String?): Boolean {
            if (slug.isNullOrBlank()) return false
            return SLUG_REGEX.matches(slug)
        }
    }
}

/**
 * Default implementation of [ConnectorRepository] backed by [HermesWsClient].
 */
class HermesConnectorRepository(
    private val rpcRequest: suspend (method: String, params: Map<String, Any>) -> Any? = { method, params ->
        val deferred = HermesWsClient.request(method, params)
        try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } finally {
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
    },
) : ConnectorRepository {
    override suspend fun listConnectors(sessionId: String): ConnectorListResult {
        if (sessionId.isBlank()) {
            return ConnectorListResult.Error(
                ConnectorError.InvalidParams("session_id required"),
            )
        }

        return try {
            val params = mapOf("session_id" to sessionId)
            val result = rpcRequest(WsMethods.CONNECTORS_LIST, params)
            ConnectorParser.parseListResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            ConnectorListResult.Error(
                ConnectorParser.mapRpcError(code = e.code, message = e.message ?: "", data = e.data),
            )
        } catch (_: Exception) {
            ConnectorListResult.Error(
                ConnectorError.NetworkError("Failed to communicate with gateway."),
            )
        }
    }

    override suspend fun connect(
        sessionId: String,
        connectors: List<String>,
        reconnect: Boolean,
    ): ConnectorConnectResult {
        if (sessionId.isBlank()) {
            return ConnectorConnectResult.Error(
                ConnectorError.InvalidParams("session_id required"),
            )
        }

        if (connectors.isEmpty()) {
            return ConnectorConnectResult.Error(
                ConnectorError.InvalidParams("connectors must be nonempty slugs; reconnect must be boolean"),
            )
        }

        for (slug in connectors) {
            if (!ConnectorRepository.isValidSlug(slug)) {
                return ConnectorConnectResult.Error(
                    ConnectorError.InvalidParams("connectors must be nonempty slugs; reconnect must be boolean"),
                )
            }
        }

        return try {
            val params =
                mapOf(
                    "session_id" to sessionId,
                    "connectors" to connectors,
                    "reconnect" to reconnect,
                )
            val result = rpcRequest(WsMethods.CONNECTORS_CONNECT, params)
            ConnectorParser.parseConnectResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            ConnectorConnectResult.Error(
                ConnectorParser.mapRpcError(code = e.code, message = e.message ?: "", data = e.data),
            )
        } catch (_: Exception) {
            ConnectorConnectResult.Error(
                ConnectorError.NetworkError("Failed to communicate with gateway."),
            )
        }
    }
}
