package com.m57.hermescontrol.ui.voice

import android.content.Context
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Contains identity only. Never put session credentials into navigation or saved state. */
data class TalkLaunch(
    val scope: DataScope,
    val sessionId: String,
    val botName: String,
)

data class TalkCallState(
    val phase: String = "idle",
    val muted: Boolean = false,
    val level: Float = 0f,
    val userText: String = "",
    val assistantText: String = "",
    val error: String? = null,
    val jobs: List<JsonObject> = emptyList(),
    val receipt: String = "",
)

/** Foreground voice lifetime only. Talk owns all durable work and operation idempotency. */
class TalkCallController(
    context: Context,
    val launch: TalkLaunch,
) {
    private val appContext = context.applicationContext
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(TalkCallState())
    val state: StateFlow<TalkCallState> = mutableState.asStateFlow()
    private var call: Job? = null
    private var peer: NativeRealtimePeer? = null
    private var commands: Channel<suspend () -> Unit>? = null
    private var binding: JsonObject? = null
    private var api: TalkGatewayApi? = null
    private var adapter: TalkBoundAdapter? = null
    private var epoch = 0L
    private var disposed = false
    private var operations = emptySet<String>()
    private val actionRequests = mutableMapOf<String, JsonObject>()
    private val completedActions = mutableSetOf<String>()

    fun start() {
        if (disposed || call?.isActive == true) return
        check(launch.sessionId.isNotBlank())
        val current = ++epoch
        mutableState.update { it.copy(phase = "connecting", error = null, muted = false) }
        call =
            lifetime.launch {
                var owner: JsonObject? = null
                var gateway: TalkGatewayApi? = null
                val queue = Channel<suspend () -> Unit>(64)
                commands = queue
                try {
                    gateway = TalkGatewayApi(launch.scope)
                    api = gateway
                    val status = gateway.get("/mobile/status")
                    TalkProtocol.validateStatus(status)
                    operations =
                        (status.obj("textInput")["operations"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content }
                            .orEmpty()
                            .toSet()
                    val catalog =
                        gateway.post(
                            "/targets",
                            buildJsonObject {
                                put("peer_id", "local")
                                put("profile", launch.scope.activeProfileId)
                                put("session_id", launch.sessionId)
                            },
                        )
                    val target = TalkProtocol.exactTarget(catalog, launch.scope.activeProfileId, launch.sessionId)
                    // Fresh tab/generation, same exact canonical owner. Never replay provider calls.
                    val tab = "mobile_" + UUID.randomUUID().toString().replace("-", "")
                    val session =
                        gateway.post(
                            "/mobile/session",
                            buildJsonObject {
                                put(
                                    "task",
                                    buildJsonObject {
                                        put("target_id", target)
                                        put("tab_id", tab)
                                    },
                                )
                            },
                        )
                    owner = session.obj("task").takeIf { it.text("connection_id").isNotBlank() }
                    TalkProtocol.validateSession(session, target, tab, launch.scope.activeProfileId, launch.sessionId)
                    val expires = (session["expiresAt"] as? JsonPrimitive)?.longOrNull
                    check(expires != null && expires > System.currentTimeMillis()) { "Ephemeral voice session expired" }
                    binding =
                        buildJsonObject {
                            put("connection_id", owner!!.text("connection_id"))
                            put("generation", owner!!["generation"]!!)
                        }
                    actionRequests.clear()
                    completedActions.clear()
                    val voice =
                        NativeRealtimePeer(
                            appContext,
                            onEvent = { raw ->
                                if (current == epoch &&
                                    !queue
                                        .trySend {
                                            adapter?.handle(Json.parseToJsonElement(raw) as JsonObject)
                                        }.isSuccess
                                ) {
                                    lifetime.launch { fail("Voice event queue overflow; reconnect to reconcile work") }
                                }
                            },
                            onState = { value ->
                                lifetime.launch {
                                    if (current == epoch && call?.isActive == true) {
                                        if (value.startsWith("error:")) {
                                            fail("Voice media disconnected; reconnect to the original chat")
                                        } else {
                                            mutableState.update {
                                                it.copy(
                                                    phase =
                                                        if (value ==
                                                            "connected"
                                                        ) {
                                                            "listening"
                                                        } else {
                                                            value
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onLevel = { level ->
                                if (current == epoch && call?.isActive == true) {
                                    mutableState.update { it.copy(level = level) }
                                }
                            },
                        )
                    peer = voice
                    adapter =
                        TalkBoundAdapter(
                            post = { path, body -> boundPost(path, body) },
                            send = { event -> current == epoch && voice.send(event.toString()) },
                            onTranscript = { role, text, final ->
                                mutableState.update {
                                    if (role == "user") {
                                        it.copy(userText = if (final) text else it.userText + text)
                                    } else {
                                        it.copy(assistantText = if (final) text else it.assistantText + text)
                                    }
                                }
                            },
                            onState = { phase -> mutableState.update { it.copy(phase = phase) } },
                            onError = {
                                mutableState.update { state ->
                                    state.copy(
                                        error = "Voice turn incomplete; no work replay. Speak again or reconnect.",
                                    )
                                }
                            },
                        )
                    withTimeout(35_000) {
                        voice.connect { sdp ->
                            negotiateRealtime(session.text("offerUrl"), session.text("clientSecret"), sdp)
                        }
                    }
                    val refresh =
                        launch {
                            while (true) {
                                queue.send { refreshState() }
                                delay(2_000)
                            }
                        }
                    val fence =
                        launch {
                            AuthManager.dataScopeFlow.collect {
                                if (AuthManager.currentDataScope() != launch.scope) {
                                    fail("Connection or profile changed; reopen voice from the intended chat")
                                }
                            }
                        }
                    try {
                        for (command in queue) {
                            gateway.checkScope()
                            command()
                        }
                    } finally {
                        refresh.cancel()
                        fence.cancel()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current == epoch) {
                        mutableState.update {
                            it.copy(phase = "error", error = safeMessage(e), level = 0f)
                        }
                    }
                } finally {
                    peer?.takeIf { current == epoch }?.close()
                    queue.close()
                    // No cancellation/stop-work request here: accepted jobs remain owned by Talk.
                    withContext(NonCancellable) {
                        withTimeoutOrNull(5_000) {
                            var revoked = false
                            try {
                                if (current == epoch && adapter != null) {
                                    adapter?.close()
                                    revoked = true
                                }
                            } catch (_: Exception) {
                                // Retire any synthetic speech without replaying it.
                            }
                            try {
                                owner?.takeUnless { revoked }?.let {
                                    gateway?.post(
                                        "/close",
                                        buildJsonObject {
                                            put("connection_id", it.text("connection_id"))
                                            put("generation", it["generation"]!!)
                                        },
                                    )
                                }
                            } catch (_: Exception) {
                                // Binding expires server-side if disconnected; never retry a work operation.
                            }
                        }
                    }
                    if (current == epoch) {
                        peer = null
                        adapter = null
                        binding = null
                        api = null
                        commands = null
                    }
                }
            }
    }

    fun mute() {
        val value = !state.value.muted
        peer?.mute(value)
        mutableState.update { it.copy(muted = value, level = if (value) 0f else it.level) }
    }

    fun interrupt() =
        enqueue {
            adapter?.interrupt()
            peer?.interrupt()
        }

    fun reconnect() {
        if (disposed) return
        val previous = call
        end()
        lifetime.launch {
            previous?.join()
            start()
        }
    }

    fun end() {
        peer?.close()
        call?.cancel()
        mutableState.update { it.copy(phase = "ended", level = 0f) }
    }

    fun dispose() {
        disposed = true
        end()
        lifetime.cancel()
    }

    fun approve(
        job: JsonObject,
        requestId: String,
        choice: String,
    ) = enqueue {
        refreshState()
        val current = currentJob(job)
        val approval = current.obj("approval")
        check(
            approval.flag("actionable") && choice in setOf("once", "session", "deny") &&
                approval.rows("approvals").any {
                    it.text("request_id") == requestId &&
                        (it["choices"] as? JsonArray)?.contains(JsonPrimitive(choice)) == true
                },
        ) { "Approval is no longer actionable" }
        submitAction(
            "approval:$requestId",
            "approval",
            current,
            buildJsonObject {
                put("request_id", requestId)
                put("choice", choice)
            },
            choice,
        )
    }

    fun stopWork(job: JsonObject) =
        enqueue {
            refreshState()
            val current = currentJob(job)
            check(current.text("status") in setOf("queued", "accepted", "running", "waiting_for_approval"))
            submitAction(
                "cancel:${current.text("run_id")}",
                "cancel",
                current,
                JsonObject(emptyMap()),
                "Cancel this job.",
            )
        }

    fun showResult(job: JsonObject) =
        enqueue {
            val current = currentJob(job)
            check(current.flag("result_available"))
            val context = checkNotNull(binding)
            val result =
                checkNotNull(api).get(
                    "/result",
                    mapOf(
                        "connection_id" to context.text("connection_id"),
                        "generation" to context.text("generation"),
                        "run_id" to current.text("run_id"),
                    ),
                )
            mutableState.update { it.copy(receipt = result.toString()) }
        }

    private suspend fun submitAction(
        key: String,
        operation: String,
        job: JsonObject,
        fields: JsonObject,
        text: String,
    ) {
        check(operation in operations) { "Hermes host does not support this action" }
        if (key in completedActions) return
        val body =
            actionRequests.getOrPut(key) {
                buildJsonObject {
                    put("run_id", job.text("run_id"))
                    put("action_id", job.text("action_id"))
                    fields.forEach { (name, value) -> put(name, value) }
                    put(
                        "input_id",
                        "action_" +
                            UUID
                                .randomUUID()
                                .toString()
                                .replace("-", "")
                                .take(24),
                    )
                    put("operation", operation)
                    put("text", text)
                    put("attachments", JsonArray(emptyList()))
                }
            }
        val receipt = boundPost("/text/input", body)
        check(
            receipt.text("input_id") == body.text("input_id") && receipt.text("operation") == operation &&
                receipt.text("state") in setOf("queued", "posted", "accepted", "completed", "saved"),
        )
        completedActions.add(key)
        mutableState.update { it.copy(receipt = receipt.toString()) }
        refreshState()
    }

    private fun currentJob(job: JsonObject): JsonObject =
        state.value.jobs.single {
            it.text("run_id") == job.text("run_id") && it.text("action_id") == job.text("action_id")
        }

    private suspend fun refreshState() {
        val canonical = boundPost("/state", JsonObject(emptyMap()))
        mutableState.update { it.copy(jobs = canonical.rows("jobs")) }
        adapter?.refresh(canonical)
    }

    private suspend fun boundPost(
        path: String,
        body: JsonObject,
    ): JsonObject = checkNotNull(api).post(path, JsonObject(body + checkNotNull(binding)))

    private fun enqueue(command: suspend () -> Unit) {
        if (commands?.trySend(command)?.isSuccess != true) {
            mutableState.update { it.copy(error = "Voice is not connected; reconnect to the original chat") }
        }
    }

    private fun fail(message: String) {
        end()
        mutableState.update { it.copy(phase = "error", error = message) }
    }

    private fun safeMessage(error: Exception): String =
        when (error) {
            is java.io.IOException -> error.message ?: "Voice connection failed"
            else -> "Voice authorization or protocol validation failed; check Talk configuration and reconnect"
        }
}
