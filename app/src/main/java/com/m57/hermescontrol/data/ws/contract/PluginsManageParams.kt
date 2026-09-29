package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `plugins.manage` params. [action] has no default so it is always encoded; every other field is
 * omitted when null. [profile] is an explicit override only; otherwise WsProfileParams injects it.
 */
@Serializable
data class PluginsManageParams(
    @SerialName("action") val action: String,
    @SerialName("profile") val profile: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("repo") val repo: String? = null,
    @SerialName("catalog_name") val catalogName: String? = null,
    @SerialName("force") val force: Boolean? = null,
    @SerialName("ref") val ref: String? = null,
    @SerialName("accept_capabilities") val acceptCapabilities: Boolean? = null,
    @SerialName("values") val values: JsonObject? = null,
)
