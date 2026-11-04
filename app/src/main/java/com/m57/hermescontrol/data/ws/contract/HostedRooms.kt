package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Params for `groups.capabilities`. Explicit [profile] string parameter. */
@Serializable
data class HostedCapabilitiesParams(
    @SerialName("profile") val profile: String,
)

/** Params for `groups.list`. Explicit [profile] string parameter. */
@Serializable
data class HostedListParams(
    @SerialName("profile") val profile: String,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("limit") val limit: Int? = null,
    @SerialName("include_disbanded") val includeDisbanded: Boolean? = null,
)

/** Params for `groups.state`. Explicit [profile] string parameter. */
@Serializable
data class HostedStateParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("include_disbanded") val includeDisbanded: Boolean? = null,
)

/** Params for `groups.log`. Explicit [profile] string parameter. */
@Serializable
data class HostedLogParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("since_seq") val sinceSeq: Long = 0L,
    @SerialName("limit") val limit: Int = 100,
    @SerialName("include_disbanded") val includeDisbanded: Boolean? = null,
)

/** Params for `groups.send`. Appends a user message idempotently. */
@Serializable
data class HostedSendParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("payload") val payload: JsonObject,
)

/** Params for `groups.stop`. Durably cancels queued or running work for a hosted room. */
@Serializable
data class HostedStopParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("cancel_id") val cancelId: String,
)

/** Params for `groups.retry`. Retries one indeterminate room task. */
@Serializable
data class HostedRetryParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("execution_generation") val executionGeneration: Int? = null,
)

/** Params for `groups.approve`. Resolves one exact approval for a room task. */
@Serializable
data class HostedApproveParams(
    @SerialName("profile") val profile: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("execution_generation") val executionGeneration: Int,
    @SerialName("choice") val choice: String,
    @SerialName("request_id") val requestId: String,
)
