package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `profiles.configure` params for the sections the app writes: `ui_meta` and the bot `soul`.
 * `profile` is not a field: WsProfileParams injects it when the method is profile scoped.
 */
@Serializable
data class ProfilesConfigureParams(
    @SerialName("name") val name: String? = null,
    @SerialName("ui_meta") val uiMeta: JsonObject? = null,
    @SerialName("soul") val soul: String? = null,
)
