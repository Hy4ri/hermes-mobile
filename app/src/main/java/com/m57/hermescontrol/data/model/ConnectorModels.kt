package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.util.ConnectorUrlValidator

/**
 * High-level connection status for a connector item.
 * Deliberate precedence: if [connected] is true, status resolves to [CONNECTED]
 * regardless of expired/revoked/stale statuses reported by backend.
 */
enum class ConnectorStatus {
    CONNECTED,
    INITIATED,
    EXPIRED,
    REVOKED,
    FAILED,
    DISCONNECTED,
    UNKNOWN,
    ;

    val isConnected: Boolean get() = this == CONNECTED

    companion object {
        fun from(
            connected: Boolean,
            rawStatus: String?,
        ): ConnectorStatus = from(connected = connected as Boolean?, rawStatus = rawStatus)

        fun from(
            connected: Boolean?,
            rawStatus: String?,
        ): ConnectorStatus {
            if (connected == true) return CONNECTED
            if (connected == false) {
                return when (rawStatus?.lowercase()?.trim()) {
                    "expired" -> EXPIRED
                    "revoked" -> REVOKED
                    "initiated" -> INITIATED
                    "failed", "error" -> FAILED
                    "disconnected" -> DISCONNECTED
                    null, "", "active", "connected", "authorized" -> DISCONNECTED
                    else -> UNKNOWN
                }
            }
            // Missing boolean: "authorized", "active", or "connected" yields effective CONNECTED
            return when (rawStatus?.lowercase()?.trim()) {
                "authorized", "active", "connected" -> CONNECTED
                "expired" -> EXPIRED
                "revoked" -> REVOKED
                "initiated" -> INITIATED
                "failed", "error" -> FAILED
                "disconnected" -> DISCONNECTED
                null, "" -> DISCONNECTED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Status resulting from a connect/reconnect initiation.
 */
enum class ConnectorConnectStatus {
    ACTIVE,
    INITIATED,
    FAILED,
    UNKNOWN,
    ;

    val isActive: Boolean get() = this == ACTIVE

    companion object {
        fun from(rawStatus: String?): ConnectorConnectStatus =
            when (rawStatus?.lowercase()?.trim()) {
                "active", "connected" -> ACTIVE
                "initiated" -> INITIATED
                "failed", "error" -> FAILED
                null, "" -> UNKNOWN
                else -> UNKNOWN
            }
    }
}

/**
 * Individual connector item returned by `connectors.list`.
 */
data class ConnectorItem(
    val connector: String,
    val name: String = connector,
    val connected: Boolean = false,
    val enabled: Boolean = true,
    val connectionStatus: String? = null,
    val status: ConnectorStatus = ConnectorStatus.from(connected, connectionStatus),
    val connectUrl: String? = null,
) {
    val isConnected: Boolean get() = connected || status.isConnected

    /** Safe validated HTTPS URL, or null if invalid or absent. */
    val safeConnectUrl: String?
        get() = if (ConnectorUrlValidator.isValidHttpsUrl(connectUrl)) connectUrl else null

    override fun toString(): String {
        val redactedUrl = if (connectUrl != null) "[REDACTED]" else "null"
        return "ConnectorItem(" +
            "connector=$connector, " +
            "name=$name, " +
            "connected=$connected, " +
            "enabled=$enabled, " +
            "status=$status, " +
            "connectUrl=$redactedUrl" +
            ")"
    }
}

/**
 * Result of a connect/reconnect action for an individual connector.
 * Instructions from arbitrary backends are intentionally not exposed for UI display.
 */
data class ConnectorConnectItem(
    val connector: String,
    val status: ConnectorConnectStatus,
    val connectUrl: String? = null,
) {
    val isConnectUrlValid: Boolean
        get() = ConnectorUrlValidator.isValidHttpsUrl(connectUrl)

    val safeConnectUrl: String?
        get() = if (isConnectUrlValid) connectUrl else null

    override fun toString(): String {
        val redactedUrl = if (connectUrl != null) "[REDACTED]" else "null"
        return "ConnectorConnectItem(" +
            "connector=$connector, " +
            "status=$status, " +
            "connectUrl=$redactedUrl, " +
            "isConnectUrlValid=$isConnectUrlValid" +
            ")"
    }
}

/**
 * Summary counts returned by `connectors.connect`.
 */
data class ConnectorConnectSummary(
    val total: Int = 0,
    val active: Int = 0,
    val initiated: Int = 0,
    val failed: Int = 0,
)

/**
 * Typed safe connector errors mapped from RPC codes and reasons.
 * Raw exceptions or auth tokens are never exposed.
 */
sealed interface ConnectorError {
    val message: String

    data class InvalidParams(
        override val message: String = "Invalid connector parameters.",
    ) : ConnectorError

    data class NotOwner(
        override val message: String = "Session is not owned by this transport.",
    ) : ConnectorError

    data class Unavailable(
        override val message: String = "Connectors are not available in this session.",
    ) : ConnectorError

    data class UnsupportedRuntime(
        override val message: String = "Connectors must be managed on the session's compute host.",
    ) : ConnectorError

    data class RequestFailed(
        override val message: String = "Connector request failed. Please try again.",
    ) : ConnectorError

    data class InvalidResponse(
        override val message: String = "Connector service returned an invalid response.",
    ) : ConnectorError

    data class UnsupportedBackend(
        override val message: String = "Connector service is not supported by this backend.",
    ) : ConnectorError

    data class MalformedEnvelope(
        override val message: String = "Malformed response envelope received from gateway.",
    ) : ConnectorError

    data class NetworkError(
        override val message: String = "Failed to communicate with gateway.",
    ) : ConnectorError

    data class Other(
        val code: Int = 0,
        override val message: String = "Connector operation failed.",
    ) : ConnectorError
}

/**
 * Result envelope for `connectors.list`.
 */
sealed interface ConnectorListResult {
    data class Success(
        val available: Boolean,
        val connectors: List<ConnectorItem>,
    ) : ConnectorListResult

    data class Error(
        val error: ConnectorError,
    ) : ConnectorListResult

    fun getOrNull(): List<ConnectorItem>? = (this as? Success)?.connectors

    fun errorOrNull(): ConnectorError? = (this as? Error)?.error
}

/**
 * Result envelope for `connectors.connect`.
 */
sealed interface ConnectorConnectResult {
    data class Success(
        val results: List<ConnectorConnectItem>,
        val summary: ConnectorConnectSummary,
    ) : ConnectorConnectResult

    data class Error(
        val error: ConnectorError,
    ) : ConnectorConnectResult

    fun getOrNull(): List<ConnectorConnectItem>? = (this as? Success)?.results

    fun errorOrNull(): ConnectorError? = (this as? Error)?.error
}
