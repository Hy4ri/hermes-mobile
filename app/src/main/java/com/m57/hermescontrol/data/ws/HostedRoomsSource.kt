package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.HostedApproveParams
import com.m57.hermescontrol.data.ws.contract.HostedCapabilitiesParams
import com.m57.hermescontrol.data.ws.contract.HostedContextParams
import com.m57.hermescontrol.data.ws.contract.HostedListParams
import com.m57.hermescontrol.data.ws.contract.HostedLogParams
import com.m57.hermescontrol.data.ws.contract.HostedRetryParams
import com.m57.hermescontrol.data.ws.contract.HostedSendParams
import com.m57.hermescontrol.data.ws.contract.HostedStateParams
import com.m57.hermescontrol.data.ws.contract.HostedStopParams
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Default caller for hosted rooms probing with suppressErrorEvent = true.
 * Guards against queuing RPCs while offline so offline calls immediately fail fast.
 */
object HostedRoomsRpcCaller : TypedRpcCaller {
    override suspend fun <P, R> call(
        method: RpcMethod<P, R>,
        params: P,
    ): R {
        if (HermesWsClient.connectionStatus.value != ConnectionStatus.CONNECTED) {
            throw HermesWsClient.HermesRpcException("WebSocket not connected")
        }
        return HermesWsClient.call(method, params, suppressErrorEvent = true)
    }
}

/** Transport operations for hosted rooms (board #416). */
interface HostedRoomsSource {
    suspend fun capabilities(profile: String): HostedCapabilities

    suspend fun list(
        profile: String,
        offset: Int = 0,
    ): HostedRoomList

    suspend fun state(
        profile: String,
        roomId: String,
    ): HostedRoomSnapshot

    suspend fun context(
        profile: String,
        roomId: String,
        eventId: String,
        purpose: String,
        notes: String,
        routing: String? = null,
        leadMemberId: String? = null,
    ): HostedContextReceipt

    suspend fun log(
        profile: String,
        roomId: String,
        sinceSeq: Long,
        limit: Int,
    ): HostedLogPage

    suspend fun send(
        profile: String,
        roomId: String,
        eventId: String,
        text: String,
    )

    suspend fun stop(
        profile: String,
        roomId: String,
        cancelId: String,
    )

    suspend fun retry(
        profile: String,
        roomId: String,
        task: HostedTask,
    )

    suspend fun approve(
        profile: String,
        roomId: String,
        task: HostedTask,
        requestId: String,
        choice: String,
    )
}

class HermesHostedRoomsSource(
    private val caller: TypedRpcCaller = HostedRoomsRpcCaller,
) : HostedRoomsSource {
    override suspend fun capabilities(profile: String): HostedCapabilities =
        try {
            val wire = caller.call(RpcMethods.GROUPS_CAPABILITIES, HostedCapabilitiesParams(profile = profile))
            val protocolValid = wire.protocolVersion == 2
            val hasRequiredMethods = REQUIRED_METHODS.all { it in wire.methods }
            val hasRequiredFeatures = REQUIRED_FEATURES.all { it in wire.features }
            val hasValidLimit = wire.maxLogLimit > 0
            val supported = protocolValid && hasRequiredMethods && hasRequiredFeatures && hasValidLimit
            HostedCapabilities(
                supported = supported,
                driver = wire.driver,
                maxLogLimit = wire.maxLogLimit,
                contextSupported = RpcMethods.GROUPS_CONTEXT.name in wire.methods,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            if (e.code == -32601) {
                HostedCapabilities(
                    supported = false,
                    driver = false,
                    maxLogLimit = 0,
                )
            } else {
                throw e
            }
        }

    override suspend fun list(
        profile: String,
        offset: Int,
    ): HostedRoomList = caller.call(RpcMethods.GROUPS_LIST, HostedListParams(profile = profile, offset = offset))

    override suspend fun state(
        profile: String,
        roomId: String,
    ): HostedRoomSnapshot = caller.call(RpcMethods.GROUPS_STATE, HostedStateParams(profile = profile, roomId = roomId))

    override suspend fun context(
        profile: String,
        roomId: String,
        eventId: String,
        purpose: String,
        notes: String,
        routing: String?,
        leadMemberId: String?,
    ): HostedContextReceipt =
        caller.call(
            RpcMethods.GROUPS_CONTEXT,
            HostedContextParams(
                profile = profile,
                roomId = roomId,
                eventId = eventId,
                purpose = purpose,
                notes = notes,
                routing = routing,
                leadMemberId = leadMemberId,
            ),
        )

    override suspend fun log(
        profile: String,
        roomId: String,
        sinceSeq: Long,
        limit: Int,
    ): HostedLogPage =
        caller.call(
            RpcMethods.GROUPS_LOG,
            HostedLogParams(profile = profile, roomId = roomId, sinceSeq = sinceSeq, limit = limit),
        )

    override suspend fun send(
        profile: String,
        roomId: String,
        eventId: String,
        text: String,
    ) {
        val payload =
            buildJsonObject {
                put("text", text)
            }
        val receipt =
            caller.call(
                RpcMethods.GROUPS_SEND,
                HostedSendParams(profile = profile, roomId = roomId, eventId = eventId, payload = payload),
            )
        if (!receipt.accepted) {
            throw HermesWsClient.HermesRpcException("Send rejected by hosted room")
        }
    }

    override suspend fun stop(
        profile: String,
        roomId: String,
        cancelId: String,
    ) {
        caller.call(
            RpcMethods.GROUPS_STOP,
            HostedStopParams(profile = profile, roomId = roomId, cancelId = cancelId),
        )
    }

    override suspend fun retry(
        profile: String,
        roomId: String,
        task: HostedTask,
    ) {
        val receipt =
            caller.call(
                RpcMethods.GROUPS_RETRY,
                HostedRetryParams(
                    profile = profile,
                    roomId = roomId,
                    taskId = task.taskId,
                    memberId = task.memberId.takeIf { it.isNotEmpty() },
                    executionGeneration = task.executionGeneration,
                ),
            )
        if (!receipt.retried) {
            throw HermesWsClient.HermesRpcException("Retry rejected by hosted room")
        }
    }

    override suspend fun approve(
        profile: String,
        roomId: String,
        task: HostedTask,
        requestId: String,
        choice: String,
    ) {
        val receipt =
            caller.call(
                RpcMethods.GROUPS_APPROVE,
                HostedApproveParams(
                    profile = profile,
                    roomId = roomId,
                    memberId = task.memberId,
                    taskId = task.taskId,
                    executionGeneration = task.executionGeneration,
                    choice = choice,
                    requestId = requestId,
                ),
            )
        if (!receipt.approved) {
            throw HermesWsClient.HermesRpcException("Approval rejected by hosted room")
        }
    }

    companion object {
        val REQUIRED_METHODS =
            setOf(
                RpcMethods.GROUPS_CAPABILITIES.name,
                RpcMethods.GROUPS_LIST.name,
                RpcMethods.GROUPS_STATE.name,
                RpcMethods.GROUPS_SEND.name,
                RpcMethods.GROUPS_LOG.name,
                RpcMethods.GROUPS_STOP.name,
                RpcMethods.GROUPS_RETRY.name,
                RpcMethods.GROUPS_APPROVE.name,
            )

        val REQUIRED_FEATURES =
            setOf(
                "idempotent_send",
                "monotonic_log",
                "room_identity",
            )
    }
}
