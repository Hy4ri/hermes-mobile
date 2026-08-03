package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Connection state of the kanban live-events stream. */
enum class KanbanLiveStatus {
    /** Stream open and receiving events. */
    CONNECTED,

    /** Stream closed; reconnecting with backoff (or waiting for a board switch). */
    DISCONNECTED,

    /** Stream rejected with 1008 / credential refresh failed; not retrying. */
    AUTH_FAILED,
}

/** One row of the backend's ``task_events`` table (``/api/plugins/kanban/events``). */
@Serializable
data class KanbanEvent(
    val id: Long,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val kind: String,
    /** Partial update (e.g. ``{"status": "done"}``) — never applied directly. */
    val payload: JsonObject? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

/** Server frame: ``{"events": [...], "cursor": N}``. */
@Serializable
data class KanbanEventsEnvelope(
    val events: List<KanbanEvent> = emptyList(),
    val cursor: Long? = null,
)

/**
 * Parse a kanban events WS frame. Returns null when the frame is not a valid
 * envelope (heartbeats, partial writes, unrelated payloads) so the caller can
 * ignore it safely.
 */
internal fun parseKanbanEventsFrame(json: String): KanbanEventsEnvelope? =
    try {
        OkHttpProvider.json.decodeFromString<KanbanEventsEnvelope>(json)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Scoped WebSocket tail of the kanban plugin's live-events stream
 * (``/api/plugins/kanban/events`` — backend: plugin_api.py ``stream_events``).
 *
 * The backend pins the board at the WS handshake (``board`` query param) and
 * replays events past the ``since`` cursor, so switching boards means opening a
 * new connection with a fresh cursor. Event payloads are partial updates, so
 * consumers are expected to re-fetch the board from REST on any event batch —
 * the desktop dashboard uses the same debounced-reload pattern.
 */
class KanbanEventsClient {
    // Deferred: building the OkHttp client touches the shared CookieJar
    // (Android context), which must not happen at construction time — the
    // client is only built when a stream is actually opened.
    private val httpClient by lazy { OkHttpProvider.websocket }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var generation = 0
    private var closed = true
    private var backoffMs = INITIAL_BACKOFF_MS
    private var cursor: Long = 0

    /**
     * Open (or re-open) the events stream for [board]. A previous connection is
     * torn down first — safe to call on board switch.
     *
     * [onEvents] is invoked with each non-empty envelope; [onStatus] with every
     * state transition. Both are dispatched on [scope] (caller's main scope).
     */
    fun connect(
        scope: CoroutineScope,
        board: String,
        since: Long = 0,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        val gen = ++generation
        reconnectJob?.cancel()
        webSocket?.cancel()
        closed = false
        backoffMs = INITIAL_BACKOFF_MS
        cursor = since
        openSocket(scope, gen, board, onEvents, onStatus)
    }

    /** Close the stream and cancel any pending reconnect. */
    fun disconnect() {
        closed = true
        generation++
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.cancel()
        webSocket = null
    }

    private fun openSocket(
        scope: CoroutineScope,
        gen: Int,
        board: String,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        if (closed || gen != generation) return

        val credential = HermesWsClient.mintWsTicket()
        if (credential.isNullOrBlank()) {
            onStatus(KanbanLiveStatus.AUTH_FAILED)
            return
        }

        val rawAuthParam = AuthManager.serverStore.getLatestState().wsAuthParam
        val authParam = if (rawAuthParam.isBlank()) "token" else rawAuthParam
        val url =
            AuthManager.endpointForBuild().webSocketUrl(
                path = KANBAN_EVENTS_PATH,
                authParameter = authParam,
                credential = credential,
                extraParams = mapOf("since" to cursor.toString(), "board" to board),
            )
        val safeUrl = url.replace(Regex("(token|ticket)=[^&]+"), "$1=REDACTED")
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

        val request = Request.Builder().url(url).build()
        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: Response,
                    ) {
                        if (closed || gen != generation) return
                        backoffMs = INITIAL_BACKOFF_MS
                        scope.launch { onStatus(KanbanLiveStatus.CONNECTED) }
                    }

                    override fun onMessage(
                        ws: WebSocket,
                        text: String,
                    ) {
                        if (closed || gen != generation) return
                        val envelope = parseKanbanEventsFrame(text) ?: return
                        if (envelope.events.isEmpty()) return
                        envelope.cursor?.let { cursor = it }
                        scope.launch { onEvents(envelope) }
                    }

                    override fun onMessage(
                        ws: WebSocket,
                        bytes: ByteString,
                    ) = Unit

                    override fun onClosing(
                        ws: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        ws.close(code, reason)
                    }

                    override fun onClosed(
                        ws: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        if (closed || gen != generation) return
                        if (code == WS_1008_POLICY_VIOLATION) {
                            scope.launch { onStatus(KanbanLiveStatus.AUTH_FAILED) }
                            return
                        }
                        scheduleReconnect(scope, gen, board, onEvents, onStatus)
                    }

                    override fun onFailure(
                        ws: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (closed || gen != generation) return
                        if (BuildConfig.DEBUG) Log.d(TAG, "Stream failure: ${t.javaClass.simpleName}")
                        scheduleReconnect(scope, gen, board, onEvents, onStatus)
                    }
                },
            )
    }

    private fun scheduleReconnect(
        scope: CoroutineScope,
        gen: Int,
        board: String,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        if (closed || gen != generation) return
        scope.launch { onStatus(KanbanLiveStatus.DISCONNECTED) }
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                openSocket(scope, gen, board, onEvents, onStatus)
            }
    }

    private companion object {
        const val TAG = "KanbanEvents"
        const val KANBAN_EVENTS_PATH = "api/plugins/kanban/events"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val WS_1008_POLICY_VIOLATION = 1008
    }
}
