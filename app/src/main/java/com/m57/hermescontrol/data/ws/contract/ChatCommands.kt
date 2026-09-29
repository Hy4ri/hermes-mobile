package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `commands.catalog` is sent without explicit params; the active profile is injected by WsProfileParams. */
@Serializable
data object CommandsCatalogParams

/** `arg` is sent whenever non-null, including an empty string, exactly like the old map payload. */
@Serializable
data class CommandDispatchParams(
    @SerialName("name") val name: String,
    @SerialName("arg") val arg: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class SlashExecParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("command") val command: String,
)

/** `data_url` carries the bytes for an uploaded file; `path` is for gateway-visible files. */
@Serializable
data class FileAttachParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("path") val path: String? = null,
    @SerialName("data_url") val dataUrl: String? = null,
    @SerialName("name") val name: String? = null,
)

/** The mobile client always sends the payload as `content_base64`; the `data` alias is not modelled. */
@Serializable
data class ImageAttachBytesParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("content_base64") val contentBase64: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("ext") val ext: String? = null,
)
