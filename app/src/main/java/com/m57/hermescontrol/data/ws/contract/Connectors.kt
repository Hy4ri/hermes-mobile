package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConnectorOwner(
    @SerialName("type") val type: String,
    @SerialName("session_id") val sessionId: String? = null,
) {
    companion object {
        fun account() = ConnectorOwner("account")

        fun session(sessionId: String) = ConnectorOwner("session", sessionId)
    }
}

@Serializable
data class ConnectorsListParams(
    @SerialName("owner") val owner: ConnectorOwner,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsConnectParams(
    @SerialName("owner") val owner: ConnectorOwner,
    @SerialName("connectors") val connectors: List<String>,
    @SerialName("reconnect") val reconnect: Boolean? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsOperationStatusParams(
    @SerialName("owner") val owner: ConnectorOwner,
    @SerialName("op_id") val opId: String,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsCatalogParams(
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsAccountsParams(
    @SerialName("connector") val connector: String? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsAccountsRemoveParams(
    @SerialName("connection_id") val connectionId: String,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsPolicyGetParams(
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsPolicySetParams(
    @SerialName("change") val change: JsonObject,
    @SerialName("expected_revision") val expectedRevision: String,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ConnectorsToolsParams(
    @SerialName("slug") val slug: String,
    @SerialName("refresh") val refresh: Boolean? = null,
    @SerialName("profile") val profile: String? = null,
)
