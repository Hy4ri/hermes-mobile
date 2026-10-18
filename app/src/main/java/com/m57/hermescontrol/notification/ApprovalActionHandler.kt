package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.contract.ApprovalRespondParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Result of answering an approval from the notification shade. */
internal enum class ApprovalActionOutcome {
    /** A modern response frame was queued on the socket. This is not server acceptance. */
    SENT,

    /** The gateway confirmed it resolved the pending approval (legacy `approval.respond`). */
    RESOLVED,

    /** The request is no longer open (answered, cancelled, or unknown after a restart). */
    STALE,

    /** The profile changed since the alert; the action must not apply. */
    SCOPE_CHANGED,

    /** No live socket for the connection that delivered the request. */
    DISCONNECTED,

    /** The send failed or the gateway did not confirm. */
    FAILED,

    /** App lock is on or the choice is not allowed for this request. */
    REFUSED,
}

/** Transport seam so the action rules are unit-testable without a socket. */
internal interface ApprovalTransport {
    val isConnected: Boolean
    val generation: Int

    fun respondServerRequest(
        id: String,
        result: JsonElement,
    ): Boolean

    /** Number of approvals the gateway reports as resolved. */
    suspend fun respondLegacy(params: ApprovalRespondParams): Int
}

internal object HermesApprovalTransport : ApprovalTransport {
    private const val LEGACY_TIMEOUT_MS = 5_000L

    override val isConnected: Boolean get() = HermesWsClient.isConnected
    override val generation: Int get() = HermesWsClient.connectionGeneration

    override fun respondServerRequest(
        id: String,
        result: JsonElement,
    ): Boolean = HermesWsClient.respondToServerRequest(id, result)

    override suspend fun respondLegacy(params: ApprovalRespondParams): Int {
        val result = HermesWsClient.call(RpcMethods.APPROVAL_RESPOND, params, timeoutMs = LEGACY_TIMEOUT_MS)
        val resolved = (result as? JsonObject)?.get("resolved") as? JsonPrimitive
        return resolved?.intOrNull ?: if (resolved?.booleanOrNull == true) 1 else 0
    }
}

/**
 * Shared state for request notifications: the open notices and a signal to
 * the chat UI when the shade already answered one.
 */
internal object RequestNoticeCoordinator {
    val notices = PendingRequestNotices()

    private val answered = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** JSON-RPC ids of server requests answered from a notification. */
    val answeredServerRequests: SharedFlow<String> = answered.asSharedFlow()

    fun publishAnswered(serverRequestId: String) {
        answered.tryEmit(serverRequestId)
    }
}

internal object ApprovalActionHandler {
    const val CHOICE_ONCE = "once"
    const val CHOICE_DENY = "deny"

    /**
     * Answer exactly the request named by [key] with "once" or "deny".
     * Only those two choices exist here: no session, permanent, or allow-all.
     */
    suspend fun handle(
        key: String,
        choice: String,
        currentScopeId: String,
        appLocked: Boolean,
        transport: ApprovalTransport,
        notices: PendingRequestNotices = RequestNoticeCoordinator.notices,
    ): ApprovalActionOutcome {
        if (appLocked) return ApprovalActionOutcome.REFUSED
        if (choice != CHOICE_ONCE && choice != CHOICE_DENY) return ApprovalActionOutcome.REFUSED
        val (notice, generation) = notices.find(key) ?: return ApprovalActionOutcome.STALE
        if (!notice.answerable) return ApprovalActionOutcome.REFUSED
        if (choice == CHOICE_ONCE && !notice.canApproveOnce) return ApprovalActionOutcome.REFUSED
        if (choice == CHOICE_DENY && !notice.canDeny) return ApprovalActionOutcome.REFUSED
        if (notice.scopeId != currentScopeId) return ApprovalActionOutcome.SCOPE_CHANGED
        // Request ids belong to the socket that delivered them.
        if (!transport.isConnected || transport.generation != generation) {
            return ApprovalActionOutcome.DISCONNECTED
        }

        val serverRequestId = notice.serverRequestId
        if (serverRequestId != null) {
            val queued =
                transport.respondServerRequest(
                    serverRequestId,
                    buildJsonObject {
                        put("choice", choice)
                        put("all", false)
                    },
                )
            if (!queued) return ApprovalActionOutcome.FAILED
            notices.close(key)
            RequestNoticeCoordinator.publishAnswered(serverRequestId)
            return ApprovalActionOutcome.SENT
        }

        val sessionId = notice.sessionId ?: return ApprovalActionOutcome.REFUSED
        val requestId = notice.legacyRequestId ?: return ApprovalActionOutcome.REFUSED
        val resolved =
            try {
                transport.respondLegacy(
                    ApprovalRespondParams(
                        sessionId = sessionId,
                        choice = choice,
                        all = false,
                        requestId = requestId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return ApprovalActionOutcome.FAILED
            }
        if (resolved <= 0) {
            // The gateway no longer holds this approval.
            notices.close(key)
            return ApprovalActionOutcome.STALE
        }
        notices.close(key)
        return ApprovalActionOutcome.RESOLVED
    }
}
