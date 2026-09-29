package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionAnswerTarget(
    @SerialName("name") val name: String,
    @SerialName("status") val status: String,
    @SerialName("detail") val detail: String? = null,
    @SerialName("env") val env: Map<String, String>? = null,
)

@Serializable
data class ConnectionAnswer(
    @SerialName("targets") val targets: List<ConnectionAnswerTarget>? = null,
    @SerialName("settled_by") val settledBy: String? = null,
)

@Serializable
data class ConnectionRespondParams(
    @SerialName("owner") val owner: ConnectorOwner,
    @SerialName("op_id") val opId: String,
    @SerialName("result") val result: ConnectionAnswer,
    @SerialName("profile") val profile: String? = null,
)
