package com.m57.hermescontrol.data.ws

/** Parsed WebSocket events emitted by [HermesWsClient]. */
sealed class WsEvent {
    // ── Gateway lifecycle ────────────────────────────────────────────────

    data class GatewayReady(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Session information ──────────────────────────────────────────────

    data class SessionInfo(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Message streaming ────────────────────────────────────────────────

    data class MessageStart(
        val sessionId: String?,
    ) : WsEvent()

    data class MessageToken(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class ThinkingDelta(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class MessageComplete(
        val text: String,
        val sessionId: String?,
    ) : WsEvent()

    data class MessageDone(
        val sessionId: String?,
    ) : WsEvent()

    // ── Tool execution ───────────────────────────────────────────────────

    data class ToolStart(
        val name: String?,
        val data: Map<String, Any?>?,
    ) : WsEvent()

    data class ToolComplete(
        val name: String?,
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Interactive ──────────────────────────────────────────────────────

    data class ClarifyRequest(
        val text: String?,
        val options: List<String>?,
        val clarifyId: String? = null,
    ) : WsEvent()

    // ── Status ───────────────────────────────────────────────────────────

    data class StatusUpdate(
        val status: String?,
        val data: Map<String, Any?>?,
    ) : WsEvent()

    data class SessionUpdated(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── RPC responses ────────────────────────────────────────────────────

    data class RpcResult(
        val id: String,
        val result: Any?,
    ) : WsEvent()

    data class RpcError(
        val id: String,
        val error: JsonRpcError,
    ) : WsEvent()

    // ── Fallback ─────────────────────────────────────────────────────────

    data class Unknown(
        val raw: String,
    ) : WsEvent()
}
