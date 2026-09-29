package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionCreateParams(
    @SerialName("source") val source: String? = null,
    @SerialName("profile") val profile: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("hidden") val hidden: Boolean? = null,
)

@Serializable
data class SessionResumeParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("omit_messages") val omitMessages: Boolean? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class PromptSubmitParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
    @SerialName("queued") val queued: Boolean? = null,
)

// tolerant readers: all nullable with null defaults, only fields callers read
@Serializable
data class SessionCreateResult(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
)

@Serializable
data class SessionResumeResult(
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class PromptSubmitResult(
    val status: String? = null,
)
