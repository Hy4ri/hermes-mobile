package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `config.set` params. [key] and [value] are always encoded (no defaults); the rest are omitted
 * when null. `profile` is injected by WsProfileParams, so it is not a field here.
 */
@Serializable
data class ConfigSetParams(
    @SerialName("key") val key: String,
    @SerialName("value") val value: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("confirm_expensive_model") val confirmExpensiveModel: Boolean? = null,
)

@Serializable
data class ConfigGetParams(
    @SerialName("key") val key: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("cwd") val cwd: String? = null,
)

/**
 * `model.options` params. Flags are `Boolean?` so an explicit `false` is still sent while an
 * unset flag is omitted (the client Json has `encodeDefaults = false`).
 */
@Serializable
data class ModelOptionsParams(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("explicit_only") val explicitOnly: Boolean? = null,
    @SerialName("include_unconfigured") val includeUnconfigured: Boolean? = null,
    @SerialName("refresh") val refresh: Boolean? = null,
)

/**
 * `client.capabilities` params. Each call updates only what it names: the identity hello omits
 * [serverRequests] so it cannot withdraw what the first call advertised.
 */
@Serializable
data class ClientCapabilitiesParams(
    @SerialName("server_requests") val serverRequests: Boolean? = null,
    @SerialName("client") val client: ClientIdentity? = null,
)

/** `client.capabilities` `client` label: attribution only, grants nothing. */
@Serializable
data class ClientIdentity(
    @SerialName("name") val name: String,
    @SerialName("version") val version: String? = null,
    @SerialName("kind") val kind: String? = null,
)

/** `client.activity` params: the connection's whole statement about where the user is. */
@Serializable
data class ClientActivityParams(
    @SerialName("sessions") val sessions: List<String>,
    @SerialName("idle_seconds") val idleSeconds: Double,
    @SerialName("basis") val basis: String,
    @SerialName("can_alert") val canAlert: Boolean,
    @SerialName("away") val away: Boolean,
)
