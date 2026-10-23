package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CanonicalSessionRef(
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class AdmissionStatusResult(
    @SerialName("admission_id") val admissionId: String? = null,
    @SerialName("ref") val ref: CanonicalSessionRef? = null,
    @SerialName("sequence") val sequence: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("outcome") val outcome: String? = null,
    @SerialName("authority_epoch") val authorityEpoch: Int? = null,
    @SerialName("execution_generation") val executionGeneration: Int? = null,
)

@Serializable
data class PromptReceiptParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("admission_id") val admissionId: String,
    @SerialName("include_result") val includeResult: Boolean? = null,
)

@Serializable
data class PromptReceiptResult(
    @SerialName("admission_id") val admissionId: String? = null,
    @SerialName("ref") val ref: CanonicalSessionRef? = null,
    @SerialName("sequence") val sequence: Int? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("outcome") val outcome: String? = null,
    @SerialName("authority_epoch") val authorityEpoch: Int? = null,
    @SerialName("execution_generation") val executionGeneration: Int? = null,
    @SerialName("result") val result: JsonObject? = null,
    @SerialName("usage") val usage: Map<String, kotlinx.serialization.json.JsonElement>? = null,
)

@Serializable
data class PromptCancelParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("admission_id") val admissionId: String,
)
