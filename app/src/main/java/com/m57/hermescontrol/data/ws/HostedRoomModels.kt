package com.m57.hermescontrol.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Wire response from `groups.capabilities`. */
@Serializable
data class HostedCapabilitiesWire(
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("driver") val driver: Boolean = false,
    @SerialName("methods") val methods: List<String> = emptyList(),
    @SerialName("features") val features: List<String> = emptyList(),
    @SerialName("max_log_limit") val maxLogLimit: Int = 0,
    @SerialName("persistent_process") val persistentProcess: Boolean? = null,
    @SerialName("authority_gateway_id") val authorityGatewayId: String? = null,
    @SerialName("room_link") val roomLink: JsonObject? = null,
)

/** Domain model for hosted-room protocol capability and driver readiness. */
@Serializable
data class HostedCapabilities(
    val supported: Boolean,
    val driver: Boolean,
    val maxLogLimit: Int,
    val contextSupported: Boolean = false,
)

/** Domain list returned by `groups.list`. */
@Serializable
data class HostedRoomList(
    @SerialName("rooms") val rooms: List<HostedRoom> = emptyList(),
    @SerialName("next_offset") val nextOffset: Int? = null,
)

/** Domain model for a hosted room. */
@Serializable
data class HostedRoom(
    @SerialName("room_id") val roomId: String,
    @SerialName("name") val name: String,
    @SerialName("latest_seq") val latestSeq: Long? = null,
    @SerialName("disbanded_at") val disbandedAt: Double? = null,
    @SerialName("authority_gateway_id") val authorityGatewayId: String? = null,
    @SerialName("authority_epoch") val authorityEpoch: Int? = null,
    @SerialName("revision") val revision: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("idempotent") val idempotent: Boolean? = null,
    @SerialName("members") val members: List<HostedRoomMember> = emptyList(),
)

/** An open roster row; older rooms can omit normalized member identity fields. */
@Serializable
data class HostedRoomMember(
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("profile") val profile: String? = null,
    @SerialName("handle") val handle: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("target") val target: JsonObject? = null,
)

/** Backend-confirmed settings from groups.state or groups.context. */
@Serializable
data class HostedRoomContext(
    @SerialName("purpose") val purpose: String,
    @SerialName("notes") val notes: String,
    @SerialName("routing") val routing: String = "everyone",
    @SerialName("lead_member_id") val leadMemberId: String = "",
)

/** Receipt for one idempotent groups.context save. */
@Serializable
data class HostedContextReceipt(
    @SerialName("context") val context: HostedRoomContext,
    @SerialName("event") val event: HostedEvent,
)

/** Domain snapshot for a hosted room and its driver status. */
@Serializable
data class HostedRoomSnapshot(
    @SerialName("room") val room: HostedRoom,
    @SerialName("driver_status") val driverStatus: HostedDriverStatus? = null,
    @SerialName("context") val context: HostedRoomContext? = null,
) {
    /** Helper returning pending tasks if driverStatus is present. */
    val tasks: List<HostedTask>
        get() = driverStatus?.pendingActions.orEmpty()
}

/** Driver execution status for one room. */
@Serializable
data class HostedDriverStatus(
    @SerialName("running") val running: Boolean = false,
    @SerialName("working") val working: Boolean = false,
    @SerialName("blocked") val blocked: Boolean = false,
    @SerialName("pending_actions") val pendingActions: List<HostedTask> = emptyList(),
    @SerialName("counts") val counts: Map<String, Int> = emptyMap(),
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("max_rounds") val maxRounds: Int? = null,
    @SerialName("member_activity") val memberActivity: List<HostedMemberActivity>? = null,
)

/** One live room member task/activity entry. */
@Serializable
data class HostedMemberActivity(
    @SerialName("member_id") val memberId: String,
    @SerialName("status") val status: String,
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("round_index") val roundIndex: Int? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("tool") val tool: String? = null,
    @SerialName("progress_at") val progressAt: Double? = null,
)

/** A pending action (retry or approval) modeled as a task. */
@Serializable
data class HostedTask(
    @SerialName("kind") val kind: String = "retry",
    @SerialName("member_id") val memberId: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("execution_generation") val executionGeneration: Int = 0,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("approval") val approval: HostedApproval? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

/** Approval descriptor within a pending approval action. */
@Serializable
data class HostedApproval(
    @SerialName("choices") val choices: List<String> = emptyList(),
    @SerialName("command") val command: String = "",
    @SerialName("reason") val reason: String = "",
    @SerialName("description") val description: String? = null,
)

/** One monotonic event in a room log. */
@Serializable
data class HostedEvent(
    @SerialName("room_id") val roomId: String,
    @SerialName("seq") val seq: Long,
    @SerialName("event_id") val eventId: String,
    @SerialName("kind") val kind: String,
    @SerialName("payload") val payload: JsonObject,
    @SerialName("actor") val actor: JsonObject,
    @SerialName("authority_epoch") val authorityEpoch: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("idempotent") val idempotent: Boolean? = null,
)

/** Page of replay events from `groups.log`. */
@Serializable
data class HostedLogPage(
    @SerialName("events") val events: List<HostedEvent> = emptyList(),
    @SerialName("cursor") val cursor: Long = 0L,
    @SerialName("latest_seq") val latestSeq: Long = 0L,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("authority") val authority: JsonObject? = null,
)

/** Receipt returned by `groups.send`. */
@Serializable
data class HostedSendReceipt(
    @SerialName("accepted") val accepted: Boolean = true,
    @SerialName("client_event_id") val clientEventId: String? = null,
    @SerialName("event") val event: HostedEvent? = null,
    @SerialName("driver_started") val driverStarted: Boolean? = null,
)

/** Receipt returned by `groups.stop`. */
@Serializable
data class HostedStopReceipt(
    @SerialName("cancelled") val cancelled: Int = 0,
)

/** Receipt returned by `groups.retry`. */
@Serializable
data class HostedRetryReceipt(
    @SerialName("retried") val retried: Boolean = true,
    @SerialName("task") val task: JsonObject? = null,
)

/** Receipt returned by `groups.approve`. */
@Serializable
data class HostedApproveReceipt(
    @SerialName("approved") val approved: Boolean = true,
    @SerialName("result") val result: JsonObject? = null,
)
