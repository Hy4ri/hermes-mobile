package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.ui.chat.extractTodosFromMap

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
        return parseParams(params, rawJson)
    }

    /**
     * Parses an event object (bare params map containing `type`, `session_id`, `seq`, `payload`).
     * Used both for live notifications and for event lists returned by `session.events.since`.
     */
    fun parseParams(
        params: Map<String, Any?>,
        rawJson: String = "",
    ): WsEvent {
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
                val text = payload?.get("text") as? String
                WsEvent.ReasoningAvailable(sessionId, text)
            }

            "message.complete" -> {
                val text = payload?.get("text") as? String ?: ""
                val reasoning = payload?.get("reasoning") as? String
                WsEvent.MessageComplete(text, sessionId, reasoning)
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

            "tool.progress" -> {
                val name = payload?.get("name") as? String
                val preview = payload?.get("preview") as? String
                WsEvent.ToolProgress(name, preview, sessionId)
            }

            "tool.generating" -> {
                val name = payload?.get("name") as? String
                WsEvent.ToolGenerating(name, sessionId)
            }

            "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.complete" -> {
                WsEvent.SubagentEvent(eventType, payload, sessionId)
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
                // Batch clarify (issue #18450): gateway sends "questions" array of {qid, question, choices, multi_select}.
                val rawQuestions = payload?.get("questions") as? List<*>
                val clarifyId = payload?.get("clarify_id") as? String ?: payload?.get("request_id") as? String

                val parsedQuestions =
                    if (rawQuestions != null && rawQuestions.isNotEmpty()) {
                        rawQuestions.mapIndexedNotNull { index, item ->
                            val map = item as? Map<*, *> ?: return@mapIndexedNotNull null
                            val qText = map["question"] as? String ?: return@mapIndexedNotNull null
                            val qid = map["qid"] as? String ?: "q$index"

                            @Suppress("UNCHECKED_CAST")
                            val qChoices = (map["choices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            val qMulti = map["multi_select"] as? Boolean ?: false
                            WsEvent.ClarifyQuestion(
                                qid = qid,
                                question = qText,
                                choices = qChoices,
                                multiSelect = qMulti,
                            )
                        }
                    } else {
                        val text =
                            payload?.get("question") as? String
                                ?: payload?.get("text") as? String
                        val rawOptions = payload?.get("choices") ?: payload?.get("options")

                        @Suppress("UNCHECKED_CAST")
                        val options = (rawOptions as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        val qid = payload?.get("qid") as? String ?: payload?.get("question_id") as? String
                        val multi = payload?.get("multi_select") as? Boolean ?: false
                        if ((!text.isNullOrBlank() || options.isNotEmpty()) && qid != null) {
                            listOf(
                                WsEvent.ClarifyQuestion(
                                    qid = qid,
                                    question = text.orEmpty(),
                                    choices = options,
                                    multiSelect = multi,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }

                val primary = parsedQuestions.firstOrNull()
                val legacyText =
                    if (parsedQuestions.size > 1) {
                        parsedQuestions.mapIndexed { index, q -> "${index + 1}. ${q.question}" }.joinToString("\n\n")
                    } else if (primary != null) {
                        primary.question
                    } else {
                        payload?.get("question") as? String
                            ?: payload?.get("text") as? String
                    }

                @Suppress("UNCHECKED_CAST")
                val legacyOptions =
                    primary?.choices
                        ?: (payload?.get("choices") ?: payload?.get("options"))
                            .let { (it as? List<*>)?.filterIsInstance<String>() }
                val legacyQid =
                    primary?.qid
                        ?: payload?.get("qid") as? String
                        ?: payload?.get("question_id") as? String
                val legacyMulti =
                    primary?.multiSelect
                        ?: payload?.get("multi_select") as? Boolean
                        ?: false

                WsEvent.ClarifyRequest(
                    text = legacyText,
                    options = legacyOptions,
                    clarifyId = clarifyId,
                    sessionId = sessionId,
                    questionId = legacyQid,
                    multiSelect = legacyMulti,
                    questions = parsedQuestions,
                )
            }

            "clarify.expire" -> {
                val clarifyId = payload?.get("request_id") as? String ?: payload?.get("clarify_id") as? String
                WsEvent.ClarifyExpire(clarifyId, sessionId)
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

            // Change events (issue #784): gateway watches on-disk signatures
            // and broadcasts these so screens can refresh on change. pet.changed
            // intentionally absent — mobile has no pet feature.
            "cron.changed", "sessions.changed", "platforms.changed", "pairing.changed" -> {
                WsEvent.ChangeEvent(eventType, payload)
            }

            "review.summary" -> {
                val text = (payload?.get("text") as? String)?.trim() ?: ""
                WsEvent.ReviewSummary(text, sessionId)
            }

            "btw.complete" -> {
                val taskId = payload?.get("task_id") as? String ?: ""
                val question = payload?.get("question") as? String ?: ""
                val text = (payload?.get("text") as? String)?.trim() ?: ""
                WsEvent.BtwComplete(taskId, question, text, sessionId)
            }

            "session.updated" -> {
                WsEvent.SessionUpdated(payload)
            }

            "session.usage" -> {
                WsEvent.SessionUsage(payload, sessionId)
            }

            "todo.updated" -> {
                val revision =
                    (payload?.get("revision") as? Number)?.toInt()
                        ?: (params["revision"] as? Number)?.toInt()
                val rawTodos = payload ?: params
                val todos = extractTodosFromMap(rawTodos) ?: emptyList()
                WsEvent.TodoUpdated(todos, revision, sessionId)
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
