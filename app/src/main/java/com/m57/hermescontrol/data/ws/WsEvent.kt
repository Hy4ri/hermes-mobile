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

    data class ReasoningDelta(
        val token: String,
        val sessionId: String?,
    ) : WsEvent()

    data class ReasoningAvailable(
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
        val sessionId: String? = null,
    ) : WsEvent()

    data class ToolComplete(
        val name: String?,
        val data: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Backend flagged tool output as having potential risk (secrets, PII).
     * Emitted alongside tool.progress — carries risk level, findings, and
     * whether content was redacted.
     *
     * Events: `tool.output_risk`
     * Payload: `{ tool_id, name, risk, findings[], redacted }`
     */
    data class ToolOutputRisk(
        val toolId: String = "",
        val name: String = "",
        val risk: String = "low",
        val findings: List<String> = emptyList(),
        val redacted: Boolean = false,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Live tool execution progress with optional preview content.
     *
     * Events: `tool.progress`
     * Payload: `{ name?: string, preview?: string }`
     */
    data class ToolProgress(
        val name: String? = null,
        val preview: String? = null,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Tool generation active state.
     *
     * Events: `tool.generating`
     * Payload: `{ name?: string }`
     */
    data class ToolGenerating(
        val name: String? = null,
        val sessionId: String? = null,
    ) : WsEvent()

    /**
     * Subagent execution and delegation events.
     *
     * Events: `subagent.spawn_requested`, `subagent.start`, `subagent.progress`, `subagent.complete`
     * Payload includes goal, task_index, task_count, subagent_id, child_session_id, text, status, summary, duration_seconds.
     */
    data class SubagentEvent(
        val type: String,
        val payload: Map<String, Any?>?,
        val sessionId: String? = null,
    ) : WsEvent()

    // ── Interactive ──────────────────────────────────────────────────────

    data class ClarifyRequest(
        val text: String?,
        val options: List<String>?,
        val clarifyId: String? = null,
        val sessionId: String? = null,
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

    // ── Approval request ────────────────────────────────────────────────

    data class ApprovalRequest(
        val command: String?,
        val description: String?,
        val patternKeys: List<String>?,
        val sessionId: String?,
    ) : WsEvent()

    // ── Sudo / secret requests ─────────────────────────────────────────

    /**
     * Backend needs the user's sudo password to continue a turn
     * (desktop: `sudo.request` → `sudo.respond {request_id, password}`).
     * Mobile previously dropped this and the agent hung forever.
     */
    data class SudoRequest(
        val requestId: String?,
        val sessionId: String?,
    ) : WsEvent()

    /**
     * Backend needs a secret value (password / token) to continue a turn
     * (desktop: `secret.request` → `secret.respond {request_id, value}`).
     * Mobile previously dropped this and the agent hung forever.
     */
    data class SecretRequest(
        val requestId: String?,
        val sessionId: String?,
    ) : WsEvent()

    // ── Gateway-level errors ───────────────────────────────────────────

    /**
     * Backend/unhandled failure surfaced by the gateway.
     *
     * Desktop shows these as red toasts (the gateway also writes
     * `[gateway.error]` lines to the console). Mobile was previously
     * dropping this event, so a crashing turn just silently stopped with no
     * explanation. Issue #527 surfaces it in the existing error banner.
     */
    data class GatewayError(
        val message: String?,
    ) : WsEvent()

    // ── Background job completion ──────────────────────────────────────

    /**
     * A scheduled/background job finished on the gateway.
     *
     * Desktop shows a "Background job finished" toast. Mobile surfaces this as
     * a non-blocking snackbar. The payload may carry `label`/`name` describing
     * the job. Issue #527.
     */
    data class BackgroundComplete(
        val data: Map<String, Any?>?,
    ) : WsEvent()

    // ── Reaction event ────────────────────────────────────────────────────

    /**
     * Backend emitted an affection reaction (ily / <3 / good bot).
     * Payload: `{ "kind": "<str>" }`. Purely cosmetic — play a hearts
     * animation in the chat UI; no persistence needed.
     */
    data class ReactionEvent(
        val kind: String = "",
    ) : WsEvent()

    // ── Fallback ─────────────────────────────────────────────────────────

    data class Unknown(
        val raw: String,
    ) : WsEvent()
}
