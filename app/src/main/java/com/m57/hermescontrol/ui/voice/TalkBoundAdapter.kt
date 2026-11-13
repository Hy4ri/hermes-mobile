package com.m57.hermescontrol.ui.voice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Provider-wire continuity for one immutable server binding, not a task executor.
 * The owner serializes every entry point on its event loop, including across suspension.
 * [post] supplies the binding and observes /state replies for canonical task UI updates.
 */
class TalkBoundAdapter(
    private val post: suspend (String, JsonObject) -> JsonObject,
    private val send: (JsonObject) -> Boolean,
    private val onTranscript: (role: String, text: String, final: Boolean) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private class Input(
        val id: String,
        val text: String,
        val interaction: String,
    ) {
        val items = mutableListOf(id)
        var requested = false
        var incomplete = false
        var remembered = false
    }

    private class Request(
        val row: Input,
        val previous: String,
    ) {
        var claimed = false
    }

    private class Call(
        val name: String,
        val arguments: JsonObject,
        val itemId: String,
    ) {
        var output: String? = null
    }

    private class Response(
        val id: String,
        val row: Input,
        val requestToken: String,
    ) {
        val calls = linkedMapOf<String, Call>()
        val finals = mutableMapOf<String, String>()
        var declared: List<String>? = null
        var declarations = emptyMap<String, JsonObject>()
        var done = false
        var finished = false
    }

    private class Presentation(
        val event: String,
        val attempt: String,
        val response: JsonObject,
    ) {
        var responseId: String? = null
        var dispatched = false
    }

    private val inputs = linkedMapOf<String, Input>()
    private val committed = mutableSetOf<String>()
    private val awaiting = mutableSetOf<String>()
    private val requests = mutableMapOf<String, Request>()
    private val responses = mutableMapOf<String, Response>()
    private val callOwners = mutableMapOf<String, String>()
    private val completedGroups = ArrayDeque<Pair<Input, List<String>>>()
    private var closed = false
    private var speaking = false
    private var playing: String? = null
    private var sequence = 0L
    private var presentation: Presentation? = null
    private var pendingAnnouncements = emptyList<String>()
    private val submittedAnnouncements = linkedSetOf<String>()
    private val syntheticResponses = linkedSetOf<String>()
    private val cancelledPresentations = linkedSetOf<String>()

    suspend fun handle(event: JsonObject) {
        if (closed) return
        guarded {
            val type = event.string("type")
            if ((type == "output_audio_buffer.stopped" || type == "output_audio_buffer.cleared") &&
                event.string("response_id") == playing
            ) {
                playing = null
                showActivity()
            }
            if (handlePresentation(event)) return@guarded
            when (event.string("type")) {
                "input_audio_buffer.committed" -> {
                    val id = event.required("item_id")
                    committed += id
                    if (id !in inputs) awaiting += id
                    inputs[id]?.let { respondToAudio(it) }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    stageVoice(event)
                }

                "conversation.item.input_audio_transcription.failed" -> {
                    val id = event.required("item_id")
                    awaiting -= id
                    inputs[id]?.let { incomplete(it, "input_stage_failed") }
                    onError("Input transcription failed; no unstaged input can execute tools.")
                }

                "response.created" -> {
                    created(event.objectValue("response") ?: error("Missing response."))
                }

                "response.function_call_arguments.done" -> {
                    tool(event)
                }

                "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                    val response = linked(event)
                    if (!response.row.incomplete) {
                        event.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                            onTranscript("assistant", it, false)
                        }
                    }
                }

                "response.output_text.done", "response.output_audio_transcript.done" -> {
                    val response = linked(event)
                    val text = event.string("text") ?: event.string("transcript")
                    if (text == null) {
                        incomplete(response.row, "linkage_ambiguous")
                    } else {
                        final(response, event.string("item_id"), text)
                    }
                }

                "response.done" -> {
                    done(event.objectValue("response") ?: error("Missing response."))
                }

                "input_audio_buffer.speech_started" -> {
                    speaking = true
                    responses.values.lastOrNull { !it.done }?.let {
                        send(message("response.cancel", "response_id" to JsonPrimitive(it.id)))
                    }
                    if (playing != null) send(message("output_audio_buffer.clear"))
                    interrupt()
                    onState("listening")
                }

                "input_audio_buffer.speech_stopped" -> {
                    speaking = false
                    val id = event.string("item_id")
                    if (id == null) {
                        awaiting += "unknown"
                    } else if (id !in inputs) {
                        awaiting += id
                    }
                    onState("executing")
                }

                "output_audio_buffer.started" -> {
                    val id = event.required("response_id")
                    check(id in responses || id in syntheticResponses) { "Unlinked playback refused." }
                    playing = id
                    onState("speaking")
                }

                "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                    if (event.string("response_id") == playing) playing = null
                    showActivity()
                }

                "error" -> {
                    val providerError = event.objectValue("error")
                    if (providerError?.string("message")?.contains("no active response", ignoreCase = true) == true) {
                        return@guarded
                    }
                    val token = providerError?.string("event_id")
                    requests[token]?.let { incomplete(it.row, "response_failed") }
                    onError("Realtime provider reported an error.")
                }
            }
            drainPresentation()
        }
    }

    /** State and announcements are supplied by the server; no model text becomes task state. */
    suspend fun refresh(state: JsonObject) {
        if (closed) return
        guarded {
            offerAnnouncements(state)
            drainPresentation()
        }
    }

    /** Only synthetic speech is cancelled. This never cancels a canonical task or ordinary turn. */
    suspend fun interrupt() {
        val current = presentation
        if (current == null) {
            // Generation can finish before the native audio buffer drains.
            if (playing != null && playing in syntheticResponses) {
                send(message("output_audio_buffer.clear"))
                playing = null
            }
            return
        }
        presentation = null
        remember(cancelledPresentations, current.attempt, 64)
        current.responseId?.let {
            send(message("response.cancel", "response_id" to JsonPrimitive(it)))
        }
        if (current.dispatched) send(message("output_audio_buffer.clear"))
        if (playing == current.responseId) playing = null
        guarded { receipt(current, if (current.dispatched) "unknown" else "deferred") }
    }

    suspend fun close() {
        if (closed) return
        try {
            interrupt()
        } finally {
            closed = true
            pendingAnnouncements = emptyList()
            requests.clear()
            guarded {
                check(post("/close", emptyObject()).boolean("ok") == true) { "Task close was not accepted." }
            }
        }
    }

    private suspend fun stageVoice(event: JsonObject) {
        val id = event.required("item_id")
        val text = event.required("transcript")
        awaiting -= id
        awaiting -= "unknown"
        val prior = inputs[id]
        if (prior != null) {
            if (prior.text != text) {
                incomplete(prior, "linkage_ambiguous")
            } else {
                respondToAudio(prior)
            }
            return
        }
        // Reserve identity before the request: an uncertain stage is not retried on duplicate ASR.
        val row = Input(id, text, "")
        inputs[id] = row
        val staged =
            try {
                val reply =
                    post(
                        "/event",
                        buildJsonObject {
                            put("kind", "input.final")
                            put("input_id", id)
                            put("input_type", "voice")
                            put("text", text)
                        },
                    )
                check(reply.boolean("ok") == true && reply.string("input_id") == id) { "Invalid input stage receipt." }
                Input(id, text, reply.required("interaction_id"))
            } catch (failure: Exception) {
                row.incomplete = true
                throw failure
            }
        inputs[id] = staged
        onTranscript("user", text, true)
        respondToAudio(staged)
        refreshState()
    }

    private suspend fun respondToAudio(row: Input) {
        if (row.id in committed && !row.requested && !row.incomplete) requestResponse(row)
    }

    private suspend fun requestResponse(
        row: Input,
        previous: String = "",
    ) {
        if (closed || row.incomplete || (previous.isEmpty() && row.requested)) return
        interrupt()
        row.requested = true
        val token = identifier("req_")
        requests[token] = Request(row, previous)
        val references =
            completedGroups
                .filter { !it.first.incomplete && it.first !== row }
                .flatMap { it.second } + row.items
        val request =
            buildJsonObject {
                put(
                    "metadata",
                    buildJsonObject {
                        put("talk_request_id", token)
                        put("talk_interaction_id", row.interaction)
                        put("talk_input_id", row.id)
                        put("talk_previous_response_id", previous)
                    },
                )
                put(
                    "input",
                    JsonArray(
                        references.map { id ->
                            buildJsonObject {
                                put("type", "item_reference")
                                put("id", id)
                            }
                        },
                    ),
                )
            }
        if (!send(message("response.create", "response" to request))) {
            incomplete(row, "response_failed")
        } else {
            onState("executing")
        }
    }

    private suspend fun created(value: JsonObject) {
        val id = value.required("id")
        val meta = value.objectValue("metadata") ?: emptyObject()
        val request = requests[meta.string("talk_request_id")]
        if (request == null || meta.string("talk_interaction_id") != request.row.interaction ||
            meta.string("talk_input_id") != request.row.id ||
            meta.string("talk_previous_response_id") != request.previous
        ) {
            send(message("response.cancel", "response_id" to JsonPrimitive(id)))
            onError("Response is unlinked: metadata must identify a staged input.")
            return
        }
        if (request.row.incomplete) return
        if (id in responses) {
            if (responses[id]?.requestToken != meta.string("talk_request_id")) {
                incomplete(request.row, "linkage_ambiguous")
            }
            return
        }
        if (request.claimed) {
            incomplete(request.row, "linkage_ambiguous")
            return
        }
        request.claimed = true
        responses[id] = Response(id, request.row, meta.required("talk_request_id"))
        event(
            request.row,
            buildJsonObject {
                put("kind", "response.started")
                put("response_id", id)
                if (request.previous.isNotEmpty()) put("previous_response_id", request.previous)
            },
        )
        onState("executing")
    }

    private suspend fun final(
        response: Response,
        itemId: String?,
        text: String,
    ) {
        if (response.row.incomplete) return
        if (itemId.isNullOrEmpty()) {
            incomplete(response.row, "linkage_ambiguous")
            return
        }
        val previous = response.finals[itemId]
        if (previous != null) {
            if (previous != text) incomplete(response.row, "linkage_ambiguous")
            return
        }
        response.finals[itemId] = text
        if (itemId !in response.row.items) response.row.items += itemId
        event(
            response.row,
            buildJsonObject {
                put("kind", "response.final")
                put("response_id", response.id)
                put("output_item_id", itemId)
                put("text", text)
            },
        )
        onTranscript("assistant", text, true)
    }

    private suspend fun tool(value: JsonObject) {
        val response = linked(value)
        if (response.row.incomplete) return
        val id = value.string("call_id")
        val name = value.string("name")
        val arguments =
            value.string("arguments")?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
        if (id.isNullOrEmpty() || name.isNullOrEmpty() || arguments == null) {
            incomplete(response.row, "linkage_ambiguous")
            return
        }
        if (response.done && id !in response.declared.orEmpty()) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        val prior = response.calls[id]
        if (prior != null) {
            if (prior.name != name || prior.arguments != arguments ||
                (
                    value.string("item_id") != null && prior.itemId.isNotEmpty() &&
                        value.string("item_id") != prior.itemId
                )
            ) {
                incomplete(response.row, "linkage_ambiguous")
            }
            return
        }
        if (callOwners.putIfAbsent(id, response.id) != null) {
            incomplete(response.row, "linkage_ambiguous")
            return
        }
        val call = Call(name, arguments, value.string("item_id").orEmpty())
        response.calls[id] = call
        if (!matchesDeclaration(response, id, call)) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        onState("executing")
        try {
            // A failed/uncertain POST remains reserved; replay never executes this call again.
            val result =
                post(
                    "/tool",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                        put("interaction_id", response.row.interaction)
                        put("response_id", response.id)
                        put("call_id", id)
                    },
                )
            val output = result.string("output") ?: error("Invalid tool receipt: output is required.")
            call.output =
                if (result["selection"] != null || name == "switch_target" ||
                    name == "return_to_previous"
                ) {
                    "No target change was activated. This connection's owner is immutable. " +
                        "Do not claim a target change; ask the operator to choose the target outside this call."
                } else {
                    output
                }
        } catch (cancelled: CancellationException) {
            response.row.incomplete = true
            throw cancelled
        } catch (_: Exception) {
            incomplete(response.row, "tool_failed")
            onError("Tool outcome is uncertain; this call will not be retried.")
            return
        }
        finish(response)
        refreshState()
    }

    private suspend fun done(value: JsonObject) {
        val response = responses[value.string("id")] ?: error("Completed response is unlinked.")
        if (response.done || response.row.incomplete) return
        val output = value["output"] as? JsonArray
        val status = value.string("status")
        if (output == null || output.any { it !is JsonObject } ||
            (status != "completed" && status != "cancelled" && status != "failed" && status != "incomplete")
        ) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        val items = output.map { it as JsonObject }
        val declarations = items.filter { it.string("type") == "function_call" }
        val ids = declarations.map { it.string("call_id").orEmpty() }
        if (ids.any { it.isEmpty() } || ids.distinct().size != ids.size ||
            items.any { it.string("id").isNullOrEmpty() || it.string("type").isNullOrEmpty() } ||
            items.map { it.string("id") }.distinct().size != items.size
        ) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        response.declared = ids
        response.declarations = ids.zip(declarations).toMap()
        if (response.calls.any { (id, call) -> !matchesDeclaration(response, id, call) }) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        for (item in items) {
            val itemId = item.required("id")
            if (itemId !in response.row.items) response.row.items += itemId
            if (item.string("type") == "message") {
                val parts = item["content"] as? JsonArray
                if (parts == null || parts.any { it !is JsonObject }) {
                    incomplete(response.row, "linkage_ambiguous")
                    return
                }
                val text =
                    parts.joinToString("") {
                        (it as JsonObject).string("text") ?: it.string("transcript").orEmpty()
                    }
                if (text.isNotEmpty()) final(response, itemId, text)
            }
        }
        response.done = true
        event(
            response.row,
            buildJsonObject {
                put("kind", "response.done")
                put("response_id", response.id)
                put(
                    "status",
                    if (status == "completed") {
                        "completed"
                    } else if (status == "cancelled") {
                        "cancelled"
                    } else {
                        "failed"
                    },
                )
                put("tool_call_ids", JsonArray(ids.map(::JsonPrimitive)))
            },
        )
        if (status != "completed") {
            incomplete(response.row, "response_failed")
        } else {
            finish(response)
        }
        showActivity()
    }

    private fun matchesDeclaration(
        response: Response,
        id: String,
        call: Call,
    ): Boolean {
        if (response.declared == null) return true
        val declaration = response.declarations[id] ?: return false
        if (declaration.string("name") != call.name) return false
        if (call.itemId.isNotEmpty() && declaration.string("id") != call.itemId) return false
        val arguments = declaration.string("arguments") ?: return false
        return runCatching { Json.parseToJsonElement(arguments) == call.arguments }.getOrDefault(false)
    }

    private suspend fun finish(response: Response) {
        if (closed || response.finished || !response.done || response.row.incomplete) return
        val declared = response.declared ?: return
        if (response.calls.keys.any { it !in declared }) {
            incomplete(response.row, "missing_tool_calls")
            return
        }
        if (declared.any { response.calls[it]?.output == null }) return
        response.finished = true
        if (declared.isNotEmpty()) {
            for (id in declared) {
                val itemId = identifier("item_")
                response.row.items += itemId
                val accepted =
                    send(
                        message(
                            "conversation.item.create",
                            "item" to
                                buildJsonObject {
                                    put("id", itemId)
                                    put("type", "function_call_output")
                                    put("call_id", id)
                                    put("output", response.calls.getValue(id).output!!)
                                },
                        ),
                    )
                if (!accepted) {
                    incomplete(response.row, "tool_failed")
                    return
                }
            }
            requestResponse(response.row, response.id)
        } else {
            event(
                response.row,
                buildJsonObject {
                    put("kind", "interaction.settle")
                    put("response_id", response.id)
                },
            )
            rememberCompleted(response.row)
            refreshState()
        }
    }

    private fun rememberCompleted(row: Input) {
        if (row.incomplete || row.remembered) return
        row.remembered = true
        completedGroups.addLast(row to row.items.toList())
        var count = completedGroups.sumOf { it.second.size }
        // Evict whole groups, never leave a function output without its original input/call.
        while (count > 64) count -= completedGroups.removeFirst().second.size
    }

    private suspend fun event(
        row: Input,
        body: JsonObject,
    ): JsonObject {
        try {
            val reply = post("/event", JsonObject(body + ("interaction_id" to JsonPrimitive(row.interaction))))
            check(
                reply.boolean("ok") == true && reply.string("interaction_id") == row.interaction &&
                    reply.string("input_id") == row.id,
            ) { "Invalid interaction event receipt." }
            return reply
        } catch (failure: Exception) {
            row.incomplete = true
            throw failure
        }
    }

    private suspend fun incomplete(
        row: Input,
        reason: String,
    ) {
        if (closed || row.incomplete) return
        row.incomplete = true
        if (row.interaction.isNotEmpty()) {
            event(
                row,
                buildJsonObject {
                    put("kind", "interaction.incomplete")
                    put("reason", reason)
                },
            )
            refreshState()
        }
        onError("Interaction incomplete: $reason. No continuation will be submitted.")
    }

    private fun linked(event: JsonObject): Response =
        responses[event.string("response_id")] ?: error("Provider event is unlinked; it cannot act on a task.")

    private suspend fun refreshState() {
        if (!closed) guarded { offerAnnouncements(post("/state", emptyObject())) }
    }

    private fun offerAnnouncements(state: JsonObject) {
        pendingAnnouncements =
            (state["announcements"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("event_id")?.takeIf(String::isNotEmpty) }
                .distinct()
                .filter { it !in submittedAnnouncements && it != presentation?.event }
                .take(8)
    }

    private fun idle(): Boolean =
        !closed && !speaking && playing == null && awaiting.isEmpty() &&
            inputs.values.none { !it.incomplete && !it.remembered } &&
            requests.values.none { !it.claimed && !it.row.incomplete } &&
            responses.values.none { !it.row.incomplete && (!it.done || !it.finished) }

    private suspend fun drainPresentation() {
        if (presentation != null || pendingAnnouncements.isEmpty() || !idle()) return
        val next = pendingAnnouncements.first()
        pendingAnnouncements = pendingAnnouncements.drop(1)
        val reply =
            post(
                "/speech",
                buildJsonObject {
                    put("event_id", next)
                    put("presentation_protocol", 1)
                    put("playback_supported", false)
                    put(
                        "timing",
                        buildJsonObject {
                            put("sequence", ++sequence)
                            put("operator_speaking", false)
                            put("playback_active", false)
                            put("response_pending", false)
                            put("input_pending", false)
                            put("tools_pending", false)
                        },
                    )
                },
            )
        check(reply.boolean("ok") == true) { "Invalid task presentation receipt." }
        if (reply.boolean("speak") == false) return
        check(reply.boolean("speak") == true && reply.string("event_id") == next) {
            "Task presentation did not identify the requested event."
        }
        val attempt = reply.required("attempt_id")
        val response = reply.objectValue("response") ?: emptyObject()
        val current = Presentation(next, attempt, response)
        val metadata = response.objectValue("metadata")
        if (response.string("conversation") != "none" || response.string("tool_choice") != "none" ||
            response["tools"] != JsonArray(emptyList()) || response["input"] !is JsonArray ||
            metadata?.string("talk_presentation_id") != attempt || metadata?.string("talk_event_id") != next
        ) {
            receipt(current, "unknown")
            error("Invalid isolated task presentation.")
        }
        presentation = current
        try {
            receipt(current, "submitting")
            current.dispatched = true
            remember(submittedAnnouncements, next, 128)
            val modalities = JsonArray(listOf(JsonPrimitive("audio")))
            val accepted =
                send(
                    message(
                        "response.create",
                        "event_id" to JsonPrimitive(attempt),
                        "response" to JsonObject(response + ("output_modalities" to modalities)),
                    ),
                )
            if (!accepted) {
                finishPresentation(current, "unknown")
                return
            }
            receipt(current, "context_submitted")
        } catch (failure: Exception) {
            if (presentation === current) finishPresentation(current, if (current.dispatched) "unknown" else "deferred")
            throw failure
        }
    }

    private suspend fun handlePresentation(event: JsonObject): Boolean {
        val value = event.objectValue("response")
        val meta = value?.objectValue("metadata")
        var current = presentation
        if (event.string("type") == "error" && current != null &&
            event.objectValue("error")?.string("event_id") == current.attempt
        ) {
            interrupt()
            onError("Task presentation failed; delivery remains unconfirmed.")
            return true
        }
        if (event.string("type") == "response.created" &&
            (meta?.get("talk_presentation_id") != null || meta?.get("talk_event_id") != null)
        ) {
            val id = value?.required("id") ?: error("Missing task presentation response.")
            remember(syntheticResponses, id, 64)
            if (current != null && current.responseId == null &&
                meta?.string("talk_presentation_id") == current.attempt &&
                meta?.string("talk_event_id") == current.event && current.attempt !in cancelledPresentations
            ) {
                current.responseId = id
            } else {
                send(message("response.cancel", "response_id" to JsonPrimitive(id)))
                onError("Unlinked task presentation refused.")
            }
            return true
        }
        val id = event.string("response_id") ?: value?.string("id") ?: return false
        if (id !in syntheticResponses) return false
        current = presentation
        if (current == null || current.responseId != id) return true
        when (event.string("type")) {
            "response.function_call_arguments.done" -> {
                onError("Task presentations cannot call tools.")
            }

            "response.output_item.added", "response.output_item.done" -> {
                if (event.objectValue("item")?.string("type") == "function_call") {
                    onError("Task presentations cannot call tools.")
                }
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                event.string("delta")?.takeIf { it.isNotEmpty() }?.let { onTranscript("assistant", it, false) }
            }

            "response.output_text.done", "response.output_audio_transcript.done" -> {
                (event.string("text") ?: event.string("transcript"))?.takeIf { it.isNotEmpty() }?.let {
                    onTranscript("assistant", it, true)
                }
            }

            "output_audio_buffer.started" -> {
                playing = id
                onState("speaking")
            }

            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                if (playing == id) playing = null
                showActivity()
            }

            "response.done" -> {
                // Native WebRTC playback events are not verified delivery observations.
                finishPresentation(current, "unknown")
                refreshState()
                showActivity()
            }
        }
        return true
    }

    private suspend fun receipt(
        current: Presentation,
        state: String,
    ) {
        val reply =
            post(
                "/speech/receipt",
                buildJsonObject {
                    put("event_id", current.event)
                    put("attempt_id", current.attempt)
                    put("state", state)
                    current.responseId?.let { put("response_id", it) }
                },
            )
        check(reply.boolean("ok") == true && reply.string("state") == state) {
            "Task presentation receipt was not accepted."
        }
    }

    private suspend fun finishPresentation(
        current: Presentation,
        state: String,
    ) {
        if (presentation !== current) return
        presentation = null
        receipt(current, state)
    }

    private fun showActivity() {
        onState(
            when {
                playing != null -> "speaking"
                responses.values.any { !it.row.incomplete && (!it.done || !it.finished) } -> "executing"
                else -> "listening"
            },
        )
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(failure.message ?: "Task continuity failed.")
        }
    }

    private fun remember(
        set: MutableSet<String>,
        id: String,
        maximum: Int,
    ) {
        set += id
        if (set.size > maximum) set.remove(set.first())
    }

    private fun identifier(prefix: String) = (prefix + UUID.randomUUID().toString().replace("-", "")).take(32)

    private fun emptyObject() = JsonObject(emptyMap())

    private fun message(
        type: String,
        vararg fields: Pair<String, JsonElement>,
    ) = JsonObject(mapOf("type" to JsonPrimitive(type), *fields))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.required(key: String): String =
        string(key)?.takeIf { it.isNotEmpty() }
            ?: error("Missing stable $key in task protocol.")
}
