package com.m57.hermescontrol.data.ws

import android.util.Log

/**
 * Converts raw [JsonRpcResponse] objects into typed [WsEvent] instances.
 *
 * The Hermes TUI gateway sends events as JSON-RPC **notifications** (no `id`
 * field). The `method` is always `"event"` and the event type lives in
 * `params.type`. The event payload is in `params.payload`.
 *
 * Regular RPC responses have an `id` and either a `result` or `error`.
 */
object EventParser {
    private const val TAG = "EventParser"

    fun parse(
        response: JsonRpcResponse,
        rawJson: String = "",
    ): WsEvent {
        // ── RPC response (has id) ────────────────────────────────────────
        val id = response.id
        if (id != null) {
            return if (response.error != null) {
                WsEvent.RpcError(id, response.error)
            } else {
                WsEvent.RpcResult(id, response.result?.toAny())
            }
        }

        // ── Notification / event (no id, has method) ─────────────────────
        @Suppress("UNCHECKED_CAST")
        val params = response.params?.toAny() as? Map<String, Any?> ?: return WsEvent.Unknown(rawJson)
        val eventType = params["type"] as? String ?: return WsEvent.Unknown(rawJson)

        @Suppress("UNCHECKED_CAST")
        val payload = params["payload"] as? Map<String, Any?>

        // B7 (Jun 21 2026, kanban t_240): extract session_id from params first, fallback to payload
        val sessionId = params["session_id"] as? String ?: payload?.get("session_id") as? String

        return when (eventType) {
            "gateway.ready" -> {
                WsEvent.GatewayReady(payload)
            }

            "session.info" -> {
                WsEvent.SessionInfo(payload)
            }

            "message.start" -> {
                WsEvent.MessageStart(sessionId)
            }

            "message.token", "message.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.MessageToken(token, sessionId)
            }

            "thinking.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ThinkingDelta(token, sessionId)
            }

            "reasoning.delta" -> {
                val token = payload?.get("text") as? String ?: ""
                WsEvent.ReasoningDelta(token, sessionId)
            }

            "reasoning.available" -> {
                WsEvent.ReasoningAvailable(sessionId)
            }

            "message.complete" -> {
                val text = payload?.get("text") as? String ?: ""
                WsEvent.MessageComplete(text, sessionId)
            }

            "message.done" -> {
                WsEvent.MessageDone(sessionId)
            }

            "tool.start" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolStart(name, payload, sessionId)
            }

            "tool.complete" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolComplete(name, payload, sessionId)
            }

            "tool.output_risk" -> {
                val toolId = payload?.get("tool_id") as? String ?: ""
                val name = payload?.get("name") as? String ?: ""
                val risk = (payload?.get("risk") as? String)?.lowercase() ?: "low"
                val redacted = payload?.get("redacted") as? Boolean ?: false

                @Suppress("UNCHECKED_CAST")
                val findings = (payload?.get("findings") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                WsEvent.ToolOutputRisk(toolId, name, risk, findings, redacted, sessionId)
            }

            "clarify.request" -> {
                // Gateway sends "question"/"choices" — fall back to "text"/"options" for any
                // older client or test that still uses the legacy field names. (Issue #206)
                val text =
                    payload?.get("question") as? String
                        ?: payload?.get("text") as? String
                val rawOptions =
                    payload?.get("choices")
                        ?: payload?.get("options")
                val clarifyId = payload?.get("clarify_id") as? String ?: payload?.get("request_id") as? String

                @Suppress("UNCHECKED_CAST")
                val options = (rawOptions as? List<*>)?.filterIsInstance<String>()
                WsEvent.ClarifyRequest(text, options, clarifyId, sessionId)
            }

            "status.update" -> {
                val status = payload?.get("status") as? String
                WsEvent.StatusUpdate(status, payload)
            }

            "error" -> {
                val message =
                    payload?.get("message") as? String
                        ?: payload?.get("error") as? String
                WsEvent.GatewayError(message)
            }

            "background.complete" -> {
                WsEvent.BackgroundComplete(payload)
            }

            "session.updated" -> {
                WsEvent.SessionUpdated(payload)
            }

            "reaction" -> {
                val kind = payload?.get("kind") as? String ?: ""
                WsEvent.ReactionEvent(kind)
            }

            "approval.request" -> {
                val command = payload?.get("command") as? String
                val description = payload?.get("description") as? String

                @Suppress("UNCHECKED_CAST")
                val patternKeys = (payload?.get("pattern_keys") as? List<*>)?.filterIsInstance<String>()
                WsEvent.ApprovalRequest(command, description, patternKeys, sessionId)
            }

            "sudo.request" -> {
                val requestId = payload?.get("request_id") as? String
                WsEvent.SudoRequest(requestId, sessionId)
            }

            "secret.request" -> {
                val requestId = payload?.get("request_id") as? String
                WsEvent.SecretRequest(requestId, sessionId)
            }

            else -> {
                Log.w(TAG, "Unknown event type: $eventType")
                WsEvent.Unknown(rawJson)
            }
        }
    }
}
