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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authentication identity only; Talk is independent of the current Bot and chat session. */
data class TalkLaunch(
    val scope: DataScope,
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

/** Foreground audio only. The existing global Talk executor owns all durable work. */
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
    private var api: TalkGatewayApi? = null
    private var adapter: TalkRealtimeAdapter? = null
    private var epoch = 0L
    private var disposed = false

    fun start() {
        if (disposed || call?.isActive == true) return
        val current = ++epoch
        mutableState.update { it.copy(phase = "connecting", error = null, muted = false, level = 0f) }
        call =
            lifetime.launch {
                val queue = Channel<suspend () -> Unit>(64)
                commands = queue
                var voice: NativeRealtimePeer? = null
                var realtime: TalkRealtimeAdapter? = null
                val fence =
                    launch {
                        AuthManager.dataScopeFlow.collect {
                            if (current == epoch && AuthManager.currentDataScope() != launch.scope) {
                                fail("Connection or profile changed; reopen Hermes Talk")
                            }
                        }
                    }
                try {
                    val gateway = TalkGatewayApi(launch.scope)
                    api = gateway
                    TalkProtocol.validateStatus(gateway.get("/status"))
                    val session = gateway.post("/session", JsonObject(emptyMap()))
                    TalkProtocol.validateSession(session)
                    val media =
                        NativeRealtimePeer(
                            appContext,
                            onEvent = { raw ->
                                if (current == epoch &&
                                    !queue
                                        .trySend {
                                            adapter?.handle(Json.parseToJsonElement(raw) as JsonObject)
                                        }.isSuccess
                                ) {
                                    lifetime.launch {
                                        if (current == epoch) fail("Voice event queue overflow; reconnect Hermes Talk")
                                    }
                                }
                            },
                            onState = { value ->
                                lifetime.launch {
                                    if (current == epoch && call?.isActive == true) {
                                        if (value.startsWith("error:")) {
                                            fail("Voice media disconnected; reconnect Hermes Talk")
                                        } else {
                                            mutableState.update {
                                                it.copy(phase = if (value == "connected") "listening" else value)
                                            }
                                        }
                                    }
                                }
                            },
                            onLevel = { level ->
                                if (current == epoch && call?.isActive == true) {
                                    mutableState.update { it.copy(level = if (it.muted) 0f else level) }
                                }
                            },
                        )
                    voice = media
                    peer = media
                    realtime =
                        TalkRealtimeAdapter(
                            post = gateway::post,
                            send = { event -> current == epoch && media.send(event.toString()) },
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
                            onError = { message -> mutableState.update { it.copy(error = message) } },
                        )
                    adapter = realtime
                    withTimeout(35_000) {
                        media.connect { sdp ->
                            negotiateRealtime(session.text("offerUrl"), session.text("clientSecret"), sdp)
                        }
                    }
                    val refresh =
                        launch {
                            while (true) {
                                queue.send { refreshRuns() }
                                delay(5_000)
                            }
                        }
                    try {
                        // All adapter entries, including suspended tool requests, share this actor.
                        for (command in queue) {
                            gateway.checkScope()
                            command()
                        }
                    } finally {
                        refresh.cancel()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current == epoch) {
                        mutableState.update { it.copy(phase = "error", error = safeMessage(e), level = 0f) }
                    }
                } finally {
                    fence.cancel()
                    queue.close()
                    voice?.close()
                    // Global calls have no server binding to close. Never cancel durable work here.
                    withContext(NonCancellable) { realtime?.close() }
                    if (commands === queue) {
                        peer = null
                        adapter = null
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
        ++epoch
        peer?.close()
        call?.cancel()
        mutableState.update { it.copy(phase = "ended", level = 0f) }
    }

    fun dispose() {
        disposed = true
        end()
        lifetime.cancel()
    }

    /** Called only after the dialog confirms this explicit work action. */
    fun stopWork(job: JsonObject) =
        enqueue {
            refreshRuns()
            val current = currentRun(job)
            if (current.text("status") != "running") {
                throw TalkSetupException("This work is no longer running")
            }
            val receipt =
                checkNotNull(api).post(
                    "/tool",
                    buildJsonObject {
                        put("name", "stop_work")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("target", current.text("runId"))
                                put("reason", "User confirmed Stop Work in Hermes Talk.")
                            },
                        )
                    },
                )
            mutableState.update { it.copy(receipt = receipt.text("output")) }
            refreshRuns()
        }

    fun showResult(job: JsonObject) =
        enqueue {
            refreshRuns()
            val current = currentRun(job)
            mutableState.update { it.copy(receipt = current.text("output")) }
        }

    private fun currentRun(job: JsonObject): JsonObject {
        val id = job.text("runId")
        return state.value.jobs.singleOrNull { id.isNotBlank() && it.text("runId") == id }
            ?: throw TalkSetupException("This work is no longer available on the server")
    }

    private suspend fun refreshRuns() {
        val runs = checkNotNull(api).get("/runs").rows("runs")
        mutableState.update { it.copy(jobs = runs) }
        adapter?.updateRuns(runs)
    }

    private fun enqueue(command: suspend () -> Unit) {
        val accepted =
            commands
                ?.trySend {
                    try {
                        command()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutableState.update { it.copy(error = safeMessage(e)) }
                    }
                }?.isSuccess == true
        if (!accepted) {
            mutableState.update { it.copy(error = "Voice is not connected; reconnect Hermes Talk") }
        }
    }

    private fun fail(message: String) {
        end()
        mutableState.update { it.copy(phase = "error", error = message) }
    }

    private fun safeMessage(error: Exception): String =
        when (error) {
            is TalkSetupException -> error.message ?: "Voice setup failed"
            is java.io.IOException -> error.message ?: "Voice connection failed"
            else -> "Voice authorization or protocol validation failed; check Talk configuration and reconnect"
        }
}
