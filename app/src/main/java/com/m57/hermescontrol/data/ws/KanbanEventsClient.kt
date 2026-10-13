package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Credential (ticket) refresh failed; not retrying. */
    AUTH_FAILED,

    /**
     * The backend accepted the socket, then closed it with 1008. That is its answer for a board that is
     * archived or deleted, so it says nothing about the credentials. Not retrying; the owner should
     * reconcile board availability over REST.
     */
    BOARD_UNAVAILABLE,
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object StringOrLongSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringOrLong", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonPrimitive) {
            return element.content
        }
        return null
    }
}

/** One row of the backend's ``task_events`` table (``/api/plugins/kanban/events``). */
@Serializable
data class KanbanEvent(
    val id: Long,
    @SerialName("task_id") val taskId: String? = null,
    @Serializable(with = StringOrLongSerializer::class)
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

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var generation = 0
    private var socketAttempt = 0
    private var closed = true
    private var backoffMs = INITIAL_BACKOFF_MS
    private var cursor: Long? = null
    private var streamEndpoint: ServerEndpoint? = null
    private var streamDataScope: DataScope? = null
    private var streamBoard: String? = null

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
        since: Long? = null,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        val capturedEndpoint = runCatching { AuthManager.endpointForBuild() }.getOrNull()
        val capturedScope = AuthManager.currentDataScope()

        val oldSocket: WebSocket?
        val oldJob: Job?
        val gen: Int

        synchronized(lock) {
            gen = ++generation
            socketAttempt = 0
            oldJob = reconnectJob
            reconnectJob = null
            oldSocket = webSocket
            webSocket = null
            closed = false
            backoffMs = INITIAL_BACKOFF_MS
            streamEndpoint = capturedEndpoint
            streamDataScope = capturedScope
            streamBoard = board
            cursor = since
        }

        oldJob?.cancel()
        oldSocket?.cancel()

        openSocket(scope, gen, board, onEvents, onStatus)
    }

    /** Close the stream and cancel any pending reconnect. */
    fun disconnect() {
        val oldSocket: WebSocket?
        val oldJob: Job?

        synchronized(lock) {
            closed = true
            generation++
            socketAttempt = 0
            oldJob = reconnectJob
            reconnectJob = null
            oldSocket = webSocket
            webSocket = null
            streamBoard = null
            cursor = null
            streamEndpoint = null
            streamDataScope = null
        }

        oldJob?.cancel()
        oldSocket?.cancel()
    }

    private fun isCurrentLocked(
        gen: Int,
        board: String,
    ): Boolean {
        if (closed || gen != generation || board != streamBoard) return false
        val currentEndpoint = runCatching { AuthManager.endpointForBuild() }.getOrNull()
        if (currentEndpoint != streamEndpoint) return false
        val currentScope = AuthManager.currentDataScope()
        if (currentScope != streamDataScope) return false
        return true
    }

    private fun isCurrent(
        gen: Int,
        board: String,
    ): Boolean {
        synchronized(lock) {
            return isCurrentLocked(gen, board)
        }
    }

    private fun isAttemptCurrent(
        gen: Int,
        attempt: Int,
        board: String,
        socket: WebSocket? = null,
    ): Boolean {
        synchronized(lock) {
            if (!isCurrentLocked(gen, board)) return false
            if (attempt != socketAttempt) return false
            if (socket != null && webSocket !== socket) return false
            return true
        }
    }

    private fun openSocket(
        scope: CoroutineScope,
        gen: Int,
        board: String,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        val endpoint: ServerEndpoint
        val queryCursor: Long?
        val attempt: Int

        synchronized(lock) {
            if (!isCurrentLocked(gen, board)) return
            endpoint = streamEndpoint ?: return
            queryCursor = cursor
            attempt = ++socketAttempt
        }

        scope.launch(Dispatchers.IO) {
            val credential = HermesWsClient.mintWsTicket()
            synchronized(lock) {
                if (!isCurrentLocked(gen, board) || attempt != socketAttempt) return@launch
            }

            if (credential.isNullOrBlank()) {
                scope.launch {
                    if (isAttemptCurrent(gen, attempt, board)) {
                        onStatus(KanbanLiveStatus.AUTH_FAILED)
                    }
                }
                return@launch
            }

            val rawAuthParam = AuthManager.serverStore.getLatestState().wsAuthParam
            val authParam = if (rawAuthParam.isBlank()) "token" else rawAuthParam
            val extraParams =
                buildMap {
                    put("board", board)
                    queryCursor?.let { put("since", it.toString()) }
                }
            val url =
                endpoint.webSocketUrl(
                    path = KANBAN_EVENTS_PATH,
                    authParameter = authParam,
                    credential = credential,
                    extraParams = extraParams,
                )
            val safeUrl = url.replace(Regex("(token|ticket)=[^&]+"), "$1=REDACTED")
            if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

            val request = Request.Builder().url(url).build()
            val socket =
                httpClient.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            val notify =
                                synchronized(lock) {
                                    if (!isCurrentLocked(gen, board) ||
                                        attempt != socketAttempt ||
                                        this@KanbanEventsClient.webSocket !== webSocket
                                    ) {
                                        return@synchronized false
                                    }
                                    backoffMs = INITIAL_BACKOFF_MS
                                    true
                                }
                            if (notify) {
                                scope.launch {
                                    if (isAttemptCurrent(gen, attempt, board, webSocket)) {
                                        onStatus(KanbanLiveStatus.CONNECTED)
                                    }
                                }
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val envelope = parseKanbanEventsFrame(text) ?: return
                            val shouldDispatch =
                                synchronized(lock) {
                                    if (!isCurrentLocked(gen, board) ||
                                        attempt != socketAttempt ||
                                        this@KanbanEventsClient.webSocket !== webSocket
                                    ) {
                                        return@synchronized false
                                    }
                                    envelope.cursor?.let { newCursor ->
                                        cursor = maxOf(cursor ?: 0L, newCursor)
                                    }
                                    envelope.events.isNotEmpty()
                                }
                            if (shouldDispatch) {
                                scope.launch {
                                    if (isAttemptCurrent(gen, attempt, board, webSocket)) {
                                        onEvents(envelope)
                                    }
                                }
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) = Unit

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            val isUnavailable: Boolean
                            val shouldReconnect: Boolean
                            synchronized(lock) {
                                if (!isCurrentLocked(gen, board) ||
                                    attempt != socketAttempt ||
                                    this@KanbanEventsClient.webSocket !== webSocket
                                ) {
                                    return
                                }
                                if (this@KanbanEventsClient.webSocket === webSocket) {
                                    this@KanbanEventsClient.webSocket = null
                                }
                                if (code == WS_1008_POLICY_VIOLATION) {
                                    isUnavailable = true
                                    shouldReconnect = false
                                } else {
                                    isUnavailable = false
                                    shouldReconnect = true
                                }
                            }
                            if (isUnavailable) {
                                scope.launch {
                                    if (isCurrent(gen, board)) {
                                        onStatus(KanbanLiveStatus.BOARD_UNAVAILABLE)
                                    }
                                }
                                return
                            }
                            if (shouldReconnect) {
                                scheduleReconnect(scope, gen, board, onEvents, onStatus)
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val shouldReconnect: Boolean
                            synchronized(lock) {
                                if (!isCurrentLocked(gen, board) ||
                                    attempt != socketAttempt ||
                                    this@KanbanEventsClient.webSocket !== webSocket
                                ) {
                                    return
                                }
                                if (this@KanbanEventsClient.webSocket === webSocket) {
                                    this@KanbanEventsClient.webSocket = null
                                }
                                shouldReconnect = true
                            }
                            if (BuildConfig.DEBUG) Log.d(TAG, "Stream failure: ${t.javaClass.simpleName}")
                            if (shouldReconnect) {
                                scheduleReconnect(scope, gen, board, onEvents, onStatus)
                            }
                        }
                    },
                )

            val oldToCancel: WebSocket?
            synchronized(lock) {
                if (isCurrentLocked(gen, board) && attempt == socketAttempt) {
                    oldToCancel = webSocket
                    webSocket = socket
                } else {
                    oldToCancel = socket
                }
            }
            oldToCancel?.cancel()
        }
    }

    private fun scheduleReconnect(
        scope: CoroutineScope,
        gen: Int,
        board: String,
        onEvents: (KanbanEventsEnvelope) -> Unit,
        onStatus: (KanbanLiveStatus) -> Unit,
    ) {
        val delayTime: Long
        val oldJob: Job?

        synchronized(lock) {
            if (!isCurrentLocked(gen, board)) return
            delayTime = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            oldJob = reconnectJob
            reconnectJob = null
        }

        oldJob?.cancel()

        scope.launch {
            if (isCurrent(gen, board)) {
                onStatus(KanbanLiveStatus.DISCONNECTED)
            }
        }

        val job =
            scope.launch {
                delay(delayTime)
                openSocket(scope, gen, board, onEvents, onStatus)
            }

        var cancelNewJob = false
        synchronized(lock) {
            if (isCurrentLocked(gen, board)) {
                reconnectJob = job
            } else {
                cancelNewJob = true
            }
        }
        if (cancelNewJob) {
            job.cancel()
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
