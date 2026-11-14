package com.m57.hermescontrol.ui.voice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Global Talk provider-wire continuity, not a task runtime or a chat-session binding.
 * The owner serializes all entry points on one actor, including across suspension, and supplies /runs snapshots.
 * Server VAD owns ordinary responses. Only function outputs and server run results request continuations here.
 */
internal class TalkRealtimeAdapter(
    private val post: suspend (String, JsonObject) -> JsonObject,
    private val send: (JsonObject) -> Boolean,
    private val onTranscript: (role: String, text: String, final: Boolean) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private class Call(
        val responseId: String,
        val name: String,
        val rawArguments: String?,
        val arguments: JsonObject?,
    ) {
        var output: String? = null
        var emitted = false
    }

    private class Response {
        val calls = linkedMapOf<String, Call>()
        var declarations: List<JsonObject>? = null
        var done = false
        var finished = false
        var suppressed = false
    }

    private class Watch(
        val kind: String,
        val startedAt: Long = System.nanoTime(),
    ) {
        var terminal: JsonObject? = null
    }

    private val responses = linkedMapOf<String, Response>()
    private val calls = mutableMapOf<String, Call>()
    private val active = linkedSetOf<String>()
    private val watches = linkedMapOf<Long, Watch>()
    private val announced = mutableSetOf<Long>()
    private var closed = false
    private var continuationPending = false
    private var playing = false
    private var speaking = false
    private var awaitingInput = false

    suspend fun handle(event: JsonObject) {
        if (closed) return
        when (event.text("type")) {
            "conversation.item.input_audio_transcription.completed" -> {
                event.text("transcript")?.takeIf { it.isNotEmpty() }?.let { onTranscript("user", it, true) }
            }

            "conversation.item.input_audio_transcription.failed" -> {
                awaitingInput = false
                onError("Input transcription failed.")
            }

            "input_audio_buffer.committed" -> {
                awaitingInput = true
            }

            "response.created" -> {
                val id = event.obj("response")?.text("id") ?: return invalidEvent()
                val response = responses.getOrPut(id) { Response() }
                if (!response.done && !response.suppressed) active += id
                continuationPending = false
                awaitingInput = false
                onState("executing")
            }

            "response.function_call_arguments.done" -> {
                tool(event)
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                event.text("delta")?.takeIf { it.isNotEmpty() }?.let { onTranscript("assistant", it, false) }
            }

            "response.output_text.done", "response.output_audio_transcript.done" -> {
                (event.text("text") ?: event.text("transcript"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { onTranscript("assistant", it, true) }
            }

            "response.done" -> {
                done(event.obj("response") ?: return invalidEvent())
            }

            "input_audio_buffer.speech_started" -> {
                speaking = true
                awaitingInput = true
                interrupt()
                onState("listening")
            }

            "input_audio_buffer.speech_stopped" -> {
                speaking = false
                awaitingInput = true
                onState("executing")
            }

            "output_audio_buffer.started" -> {
                playing = true
                onState("speaking")
            }

            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                playing = false
            }

            "error" -> {
                // Never surface provider messages, HTTP bodies, or credential-bearing exception text.
                if (event.obj("error")?.text("code") != "response_cancel_not_active") {
                    onError("Realtime provider reported an error.")
                }
            }
        }
        drainNotices()
    }

    /** Only runs announced by this connection's accepted tool outputs (or their results) are watched. */
    suspend fun updateRuns(runs: List<JsonObject>) {
        if (closed) return
        expireWatches()
        for (run in runs) {
            val id = run.text("runId")?.toLongOrNull() ?: continue
            val watch = watches[id] ?: continue
            if (run.text("status") in TERMINAL_STATUSES && watch.terminal == null) watch.terminal = run
        }
        drainNotices()
    }

    /** Barge-in affects provider generation/playback only. Accepted work remains owned by the server. */
    suspend fun interrupt() {
        if (closed) return
        if (active.isNotEmpty() || continuationPending) send(message("response.cancel"))
        if (active.isNotEmpty() || continuationPending || playing) send(message("output_audio_buffer.clear"))
        for (response in responses.values) {
            if (!response.finished) response.suppressed = true
        }
        active.clear()
        continuationPending = false
        playing = false
    }

    /** Local fence only: no /close, stop_work, or other backend request belongs to global call teardown. */
    suspend fun close() {
        if (closed) return
        closed = true
        watches.clear()
        responses.clear()
        calls.clear()
        active.clear()
        continuationPending = false
    }

    private suspend fun tool(event: JsonObject) {
        val responseId = event.text("response_id") ?: return invalidEvent()
        val id = event.text("call_id")?.takeIf { it.isNotEmpty() } ?: return invalidEvent()
        val response = responses.getOrPut(responseId) { Response() }
        if (response.suppressed) return
        val name = event.text("name").orEmpty()
        val raw = event.text("arguments")
        val arguments = parseArguments(raw)
        val prior = calls[id]
        if (prior != null) {
            if (prior.responseId != responseId || prior.name != name ||
                (prior.arguments != arguments || (arguments == null && prior.rawArguments != raw))
            ) {
                if (prior.responseId == responseId && !prior.emitted) prior.output = CONFLICT_OUTPUT
                if (prior.responseId != responseId) response.suppressed = true
                onError("Conflicting tool call ignored; no request was repeated.")
            }
            finish(response)
            return
        }
        if (response.finished) return invalidEvent()
        val call = Call(responseId, name, raw, arguments)
        // Reserve before suspension. A lost HTTP reply must never cause a duplicate accepted effect.
        calls[id] = call
        response.calls[id] = call
        if (response.declarations != null && !matchesDeclaration(response, id, call)) {
            call.output = CONFLICT_OUTPUT
            onError("Conflicting tool call ignored; no request was repeated.")
        } else if (name.isEmpty() || arguments == null) {
            call.output = "Tool request was not executed because its name or arguments were invalid."
        } else {
            onState("executing")
            try {
                val reply =
                    post(
                        "/tool",
                        buildJsonObject {
                            put("name", name)
                            put("arguments", arguments)
                        },
                    )
                if (closed) return
                val output = reply.text("output")
                if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull == false || output == null) {
                    call.output = UNCERTAIN_OUTPUT
                    onError("Tool outcome is uncertain; this call will not be retried.")
                } else {
                    call.output = output.ifEmpty { "(no output)" }
                    watchForRun(output)
                }
            } catch (cancelled: CancellationException) {
                call.output = UNCERTAIN_OUTPUT
                throw cancelled
            } catch (_: Exception) {
                call.output = UNCERTAIN_OUTPUT
                onError("Tool outcome is uncertain; this call will not be retried.")
            }
        }
        finish(response)
    }

    private fun done(value: JsonObject) {
        val id = value.text("id") ?: return invalidEvent()
        val response = responses.getOrPut(id) { Response() }
        active -= id
        if (response.done) return
        response.done = true
        val output = value["output"] as? JsonArray
        if (output == null || output.any { it !is JsonObject }) {
            response.suppressed = true
            return invalidEvent()
        }
        val declarations = output.filterIsInstance<JsonObject>().filter { it.text("type") == "function_call" }
        val ids = declarations.map { it.text("call_id") }
        if (ids.any { it.isNullOrEmpty() } || ids.distinct().size != ids.size ||
            response.calls.keys.any { it !in ids }
        ) {
            response.suppressed = true
            return invalidEvent()
        }
        response.declarations = declarations
        if (value.text("status") != "completed") response.suppressed = true
        for ((callId, call) in response.calls) {
            if (!matchesDeclaration(response, callId, call)) {
                call.output = CONFLICT_OUTPUT
                onError("Conflicting tool call ignored; no request was repeated.")
            }
        }
        finish(response)
        if (active.isEmpty() && !continuationPending && !playing && !speaking) onState("listening")
    }

    private fun matchesDeclaration(
        response: Response,
        id: String,
        call: Call,
    ): Boolean {
        val declaration = response.declarations?.find { it.text("call_id") == id } ?: return false
        if (declaration.text("name") != call.name) return false
        val raw = declaration.text("arguments") ?: return true
        val arguments = parseArguments(raw)
        return if (arguments ==
            null
        ) {
            call.arguments == null && raw == call.rawArguments
        } else {
            arguments == call.arguments
        }
    }

    private fun finish(response: Response) {
        if (closed || response.suppressed || response.finished || !response.done) return
        val declarations = response.declarations ?: return
        if (declarations.any { response.calls[it.text("call_id")]?.output == null }) return
        response.finished = true
        if (declarations.isEmpty()) return
        for (declaration in declarations) {
            val id = declaration.text("call_id") ?: return
            val call = response.calls.getValue(id)
            if (call.emitted) continue
            call.emitted = true
            val accepted =
                send(
                    buildJsonObject {
                        put("type", "conversation.item.create")
                        put(
                            "item",
                            buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", id)
                                put("output", call.output)
                            },
                        )
                    },
                )
            if (!accepted) {
                onError("Tool output delivery failed; the accepted request will not be repeated.")
                return
            }
        }
        createResponse()
    }

    private fun watchForRun(output: String) {
        val match = WORK_STARTED.find(output) ?: return
        val id = match.groupValues[1].toLongOrNull() ?: return
        if (id !in announced) watches.getOrPut(id) { Watch(match.groupValues[2]) }
    }

    private fun expireWatches() {
        val now = System.nanoTime()
        watches.entries.removeAll { (_, watch) ->
            val cap = if (watch.kind == "agent") AGENT_WATCH_MS else DEFAULT_WATCH_MS
            now - watch.startedAt > cap * NANOS_PER_MILLISECOND
        }
    }

    private fun drainNotices() {
        expireWatches()
        if (closed || speaking || awaitingInput || playing || active.isNotEmpty() || continuationPending ||
            responses.values.any { !it.finished && !it.suppressed }
        ) {
            return
        }
        val entry = watches.entries.firstOrNull { it.value.terminal != null } ?: return
        val id = entry.key
        val watch = entry.value
        val run = watch.terminal ?: return
        // Reserve before sending: an uncertain provider delivery never re-announces a result.
        watches.remove(id)
        announced += id
        val result = run.text("output")?.takeIf { it.isNotEmpty() } ?: "(no output)"
        val text =
            "Work run #$id (${run.text("kind") ?: watch.kind}) finished with " +
                "status '${run.text("status")}'. Result: $result\n\n" +
                "Summarize this aloud in one to three spoken sentences."
        val accepted =
            send(
                buildJsonObject {
                    put("type", "conversation.item.create")
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", text)
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                },
            )
        if (!accepted) {
            onError("Work result delivery failed; the server still owns the work.")
            return
        }
        watchForRun(result)
        createResponse()
    }

    private fun createResponse() {
        if (send(message("response.create"))) {
            continuationPending = true
            onState("executing")
        } else {
            onError("Realtime continuation could not be sent.")
        }
    }

    private fun invalidEvent() = onError("Realtime event could not be processed safely.")

    private fun parseArguments(raw: String?): JsonObject? =
        raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun message(type: String) = buildJsonObject { put("type", type) }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private companion object {
        val WORK_STARTED = Regex("WORK_STARTED #(\\d+) kind=(\\w+)")
        val TERMINAL_STATUSES = setOf("done", "failed", "lost")
        const val AGENT_WATCH_MS = 2_700_000L
        const val DEFAULT_WATCH_MS = 600_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNCERTAIN_OUTPUT =
            "Tool outcome is uncertain. The request may have been accepted; it will not be retried. " +
                "Do not claim that the work completed."
        const val CONFLICT_OUTPUT =
            "Tool call conflict detected. No request was repeated; any already accepted work remains server-owned. " +
                "Do not assume or claim completion."
    }
}
