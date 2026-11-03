package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClarifyLockParams(
    @SerialName("request_id") val requestId: String,
    @SerialName("question_id") val questionId: String,
    @SerialName("answer") val answer: String? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class ClarifyLockResult(
    @SerialName("status") val status: String,
    @SerialName("remaining") val remaining: List<String>? = null,
)
