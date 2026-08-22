package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.SlashUsageStore
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.parseContextBreakdown
import com.m57.hermescontrol.data.model.parseUsageSnapshot
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.ActionProgressController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"
private const val MESSAGE_PAGE_SIZE = 150

/**
 * Canonical comparison key for a tool message's result payload.
 *
 * WS tool messages store the full tool.complete payload
 * (`{"tool_id":..., "name":..., "args":..., "result": {...}}`) while REST
 * transcript rows store just the result object (`{"output":..., "exit_code":...}`).
 * This key normalizes both sides — preferring the `result` field when present,
 * and treating int/float JSON numbers as equal — so the two representations of
 * the SAME tool call can be matched regardless of position or pagination.
 * Returns null for unparseable content (no match possible).
 */
internal fun canonicalToolResultKey(content: String): String? {
    val element =
        try {
            OkHttpProvider.json.parseToJsonElement(content)
        } catch (_: Exception) {
            return null
        }

    fun canon(e: kotlinx.serialization.json.JsonElement): String =
        when (e) {
            is kotlinx.serialization.json.JsonObject ->
                e.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${canon(it.value)}" }
            is kotlinx.serialization.json.JsonArray ->
                e.joinToString(",") { canon(it) }
            is kotlinx.serialization.json.JsonPrimitive -> {
                // Canonicalize ALL numbers through double, collapsing int/float
                // spellings of the same value (0 vs 0.0 → "i0", 0.5 → "d0.5").
                val s = e.content
                val d = s.toDoubleOrNull()
                if (d != null) {
                    if (d == d.toLong().toDouble()) "i${d.toLong()}" else "d$d"
                } else {
                    "s$s"
                }
            }
        }
    return when (element) {
        is kotlinx.serialization.json.JsonObject ->
            element["result"]?.let { canon(it) } ?: canon(element)
        else -> canon(element)
    }
}

/**
 * True when [a] and [b] are the same logical message (the WS-persisted and
 * REST-persisted copies of one row — they carry different ids, see #771).
 * Tool messages match on their normalized result payload; other roles on
 * exact content, IGNORING leading/trailing whitespace.
 *
 * Issue #842: the app seals the RAW streamed text (which can carry leading
 * blank lines the model emits before its narration), while the backend
 * persists a CLEANED copy (leading whitespace stripped). An exact-content
 * compare made the reload merge treat them as different messages and add
 * the REST copy on top — the commentary duplicated ~10s after the stream
 * ended. Trim closes the drift: the sealed live bubble is covered by the
 * REST row and the duplicate never renders.
 */
internal fun sameLogicalMessage(
    a: ChatMessage,
    b: ChatMessage,
): Boolean {
    if (a.role != b.role) return false
    if (a.role == MessageRole.TOOL) {
        // Issue #842: prefer the gateway's tool call id when both sides carry
        // it — the REST transcript stores `tool_call_id` and the live WS
        // bubble keeps it from `tool.start`. Content canonicalization cannot
        // cover MCP/web tools: the REST side stores the payload as raw
        // `<untrusted_tool_result>` text (not JSON), so it has no key at all.
        if (a.toolCallId.isNotBlank() && b.toolCallId.isNotBlank()) {
            return a.toolCallId == b.toolCallId
        }
        val ka = canonicalToolResultKey(a.content)
        val kb = canonicalToolResultKey(b.content)
        return ka != null && ka == kb
    }
    val ta = a.content.trim()
    val tb = b.content.trim()
    if (ta == tb) return true
    // Attachment dedupe: when the user sends an image/file, the backend
    // persists an ENRICHED copy of the prompt — the real caption plus
    // `@image:`/`@file:` reference lines and a `[screenshot]` marker
    // (tui_gateway _build_image_ref_message / run_agent flattening). The
    // optimistic bubble carries the raw caption only, so the exact compare
    // above fails and the REST sync renders the same message twice. Compare
    // USER rows on the caption with injection lines stripped (both sides —
    // stripping a clean string is a no-op). USER-only so assistant text that
    // legitimately mentions these tokens is never collapsed.
    if (a.role == MessageRole.USER) {
        val ca = stripAttachmentRefLines(ta)
        val cb = stripAttachmentRefLines(tb)
        if (ca == cb) return true
    }
    // Issue #842: a seal race can leave the live orphan a few trailing tokens
    // short of the streamed narration (the last delta was still in the
    // throttled buffer when tool.start sealed the message). The backend
    // persists the COMPLETE copy, so the orphan is a strict prefix of the
    // REST row. Accept prefix-covering only for substantial texts (>=40
    // chars) so a short reply can never be swallowed by a longer message
    // that merely starts with it.
    return ta.length >= 40 &&
        tb.length >= 40 &&
        (tb.startsWith(ta) || ta.startsWith(tb))
}

/**
 * Remove backend attachment-injection lines from a user message so the
 * optimistic bubble and the server-enriched REST copy compare equal.
 * Lines the gateway adds on top of the user's caption: `@image:<path>`,
 * `@file:<ref>`, and `[screenshot]` (multipart image placeholder written by
 * run_agent). Blank lines left behind are dropped too.
 */
internal fun stripAttachmentRefLines(content: String): String =
    content
        .lines()
        .map { it.trim() }
        .filterNot { line ->
            line.startsWith("@image:") ||
                line.startsWith("@file:") ||
                line == "[screenshot]"
        }
        .joinToString("\n")
        .trim()

/**
 * Room accumulates BOTH the WS-persisted copy (UUID id, rich tool payload,
 * tool name) and the REST-persisted copy (`rest-` id, result-only payload,
 * no tool name) of every message. Painting the cache verbatim renders the
 * same call twice. Drop the `rest-` copy whenever a WS copy of the same
 * logical message exists (issue #771).
 */
internal fun dedupeCachedMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val rest = messages.filter { it.id.startsWith("rest-") }
    if (rest.isEmpty()) return messages
    val nonRest = messages.filterNot { it.id.startsWith("rest-") }
    if (nonRest.isEmpty()) return messages
    val keepRest = rest.filter { restMsg -> nonRest.none { sameLogicalMessage(it, restMsg) } }
    return (nonRest + keepRest).sortedBy { it.timestamp }
}

/**
 * A transcript reload must NOT yank live WS bubbles the server has not
 * persisted yet. The gateway stores a tool row only once the tool
 * COMPLETES server-side, so a reload that lands while a tool is running
 * (app background/foreground mid-turn, reconnect re-resume, pull-refresh)
 * returns a page without the tool row — replacing the list outright made
 * the in-flight tool bubble vanish and left tool.complete with no RUNNING
 * message to update (issue #771).
 *
 * Merge instead of replace: append any current message the REST page does
 * not already cover (checked by id AND logical content via
 * [sameLogicalMessage]) and keep chronological order.
 */
internal fun mergeTranscriptWithLive(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
): List<ChatMessage> {
    val dedupedRest = restMessages.dedupeById()
    val dedupedCurrent = currentMessages.dedupeById()
    // User rows can carry live/cache-only metadata (for example whether the
    // bubble continues the active turn). Keep that richer copy when REST
    // returns the same logical row.
    val currentUsers = dedupedCurrent.filter { it.role == MessageRole.USER }.toMutableList()
    val mergedRest =
        dedupedRest.map { rest ->
            if (rest.role != MessageRole.USER) return@map rest
            val matchIndex =
                currentUsers.indexOfFirst { it.id == rest.id }.takeIf { it >= 0 }
                    ?: currentUsers.indexOfFirst { sameLogicalMessage(rest, it) }
            if (matchIndex >= 0) currentUsers.removeAt(matchIndex) else rest
        }
    val restIds = dedupedRest.map { it.id }.toSet()
    val liveTail =
        dedupedCurrent.filter { old ->
            old.id !in restIds && mergedRest.none { sameLogicalMessage(it, old) }
        }
    if (liveTail.isEmpty()) return mergedRest.dedupeById()
    return (mergedRest + liveTail).dedupeById().sortedBy { it.timestamp }
}

/** Merge a REST page without matching it against already-settled transcript rows. */
internal fun mergeIncrementalTranscriptPage(
    restMessages: List<ChatMessage>,
    currentMessages: List<ChatMessage>,
    sessionId: String,
    offset: Int,
): List<ChatMessage> {
    val settledEnd =
        currentMessages.indexOfLast { message ->
            serverMessageIndex(message.id, sessionId)?.let { it < offset } == true
        }
    return (
        currentMessages.take(settledEnd + 1) +
            mergeTranscriptWithLive(restMessages, currentMessages.drop(settledEnd + 1))
    ).dedupeById()
}

private fun serverMessageIndex(
    id: String,
    sessionId: String,
): Int? = id.removePrefix("rest-$sessionId-").takeIf { it != id }?.toIntOrNull()

internal fun List<ChatMessage>.dedupeById(): List<ChatMessage> = associateBy { it.id }.values.toList()

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Hermes",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = false,
    /**
     * Real per-turn tool-call budget (agent.max_turns) from GET /api/config.
     * Null when it could not be fetched — the tool-call dividers then degrade
     * to a bare count instead of a hardcoded default.
     */
    val maxToolCallsPerTurn: Int? = null,
    /** Standalone streaming message — rendered after the main list. */
    val streamingMessage: ChatMessage? = null,
    val errorMessage: String? = null,
    // Background job completion toast (issue #527) — non-blocking snackbar
    val backgroundCompleteMessage: String? = null,
    // Attachment feedback — surfaced as a non-blocking snackbar (issue #724)
    val openError: String? = null,
    val savingAttachmentPath: String? = null,
    // Gateway file currently being downloaded for open — drives the inline
    // loading indicator on the attachment card (issue #913 follow-up).
    val openingAttachmentPath: String? = null,
    val clarifyRequest: ClarifyUi? = null,
    // Sudo / secret prompts — surfaced as dialogs (issue #524)
    val sudoPrompt: SudoPromptUi? = null,
    val secretPrompt: SecretPromptUi? = null,
    val showSessionPicker: Boolean = false,
    // /update confirm dialog (issue #862) — the command is handled client-side
    val updateConfirmOpen: Boolean = false,
    // Search state lives in ChatSearchState (searchDelegate.searchState) — a
    // snapshot-backed holder, so search updates don't recompose the whole UI.
    // Cached settings
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    // Commands catalog
    val commandCatalog: CommandCatalog = CommandCatalog(),
    // Per-command usage counts for the slash-autocomplete ranking (issue
    // #865). Empty until the local store loads; commands without recorded
    // usage keep their catalog order.
    val slashUsageCounts: Map<String, Int> = emptyMap(),
    // Transient nav request from /resume and /history (issue #864): the
    // screen consumes it by navigating to the history tab, then clears it.
    val openHistoryRequested: Boolean = false,
    // In-session model picker (issue #589) — surfaced when the user types /model
    // (or taps the top-bar model chip). Mirror of the global model screen's
    // picker, but the selection hot-swaps the CURRENT session via the slash path.
    val showModelPicker: Boolean = false,
    val modelPickerProviders: List<ModelProvider> = emptyList(),
    val modelPickerPinned: List<PinnedModel> = emptyList(),
    val modelPickerLoading: Boolean = false,
    // Current session's active model label (provider/model), shown in the chip
    val currentSessionModel: String? = null,
    // Per-model reasoning capabilities for the current session's model
    // (issue #946). Null when unknown — UI offers full scale.
    val currentModelCapabilities: ModelCapabilities? = null,
    // Reasoning effort level for the current session
    val reasoningLevel: String? = null,
    val terminalBackend: String? = null,
    // Context-window meter (issue #756): tokens currently used by the session
    // prompt (numerator) and the active model's full context window (denominator).
    // Both null until the first successful fetch.
    val usedContextTokens: Long? = null,
    val fullContextTokens: Long? = null,
    // Detailed token breakdown for the context meter's detail sheet (null until
    // the first successful session-detail fetch).
    val contextBreakdown: ContextBreakdown? = null,
    // How many times the current session has been context-compressed (null
    // until the first successful session.usage fetch) — drives the
    // "compressed ×N" badge on the context chip.
    val compressionCount: Int? = null,
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
    // Reaction animation — set when a reaction WS event arrives, auto-clears
    val reactionKind: String? = null,
    /** Monotonic trigger ID so consecutive same-kind reactions re-animate. */
    val reactionTriggerId: Long = 0L,
    /** Subagent delegation indicators (issue #538) — transient UI state. */
    val subagentIndicators: List<SubagentIndicator> = emptyList(),
    /** Agent todo / plan items (issue #736). */
    val todos: List<TodoItem> = emptyList(),
    // Session resume recovery (desktop parity: bounded auto-retry + error UI)
    val resumeError: String? = null,
    val isResumeRetrying: Boolean = false,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED
}

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
    val parentSessionId: String? = null,
    val depth: Int = 0,
)

data class ClarifyUi(
    val text: String,
    val options: List<String>,
    val clarifyId: String? = null,
)

/**
 * String sent to the agent when a clarify prompt is dismissed (the Dismiss
 * button). This is a *reject* — "I'm not answering this question" — NOT an
 * instruction to proceed. Deliberately NOT the CLI's interrupt sentinel
 * ("...Use your best judgement to proceed."): a mobile Dismiss is a
 * skip-the-question gesture, not an interrupt of the whole turn. The agent is
 * unblocked but told no answer was given, so it re-asks or backs off rather
 * than charging ahead.
 */
private const val CLARIFY_DISMISS_RESPONSE = "The user cancelled — no answer provided."

/** Transient — not persisted. Holds a pending sudo.password request. */
data class SudoPromptUi(
    val requestId: String?,
    val sessionId: String?,
)

/** Transient — not persisted. Holds a pending secret (token/password) request. */
data class SecretPromptUi(
    val requestId: String?,
    val sessionId: String?,
)

/**
 * Token breakdown backing the context meter's detail sheet. All values are
 * cumulative lifetime token counts sourced from `GET /api/sessions/{id}`
 * (`input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens`,
 * `reasoning_tokens`, `message_count`) — verified present on the live
 * gateway's `sessions` table. Informational accounting only; the meter's
 * live used/full values come from the `session.context_breakdown` RPC
 * (issue #756).
 */
data class ContextBreakdown(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val messageCount: Int,
)

class ChatViewModel(
    application: Application,
    private val startCleanup: Boolean,
    repo: ChatPersistenceRepository =
        ChatPersistenceRepository(
            HermesDatabase.get(application).chatMessageDao(),
        ),
    slashUsageStore: SlashUsageStore = SlashUsageStore(application.applicationContext),
    searchDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())

    private val _streamingState = MutableStateFlow(StreamingState())

    /** Maps an in-flight RPC id to its method for UI error labeling. */
    private val idToMethod = ConcurrentHashMap<String, String>()

    private data class SessionRequest(
        val generation: Long,
        val resumeSequence: Long = 0L,
        val sessionId: String? = null,
    )

    private val sessionRequestById = ConcurrentHashMap<String, SessionRequest>()
    private var sessionGeneration = 0L
    private var resumeRequestSequence = 0L
    private var activeResumeRequestSequence = 0L
    private var hydrationRequestSequence = 0L
    private var activeHydrationRequestSequence = 0L
    private var resumedGeneration = -1L
    private var hydratedGeneration = -1L

    /** Runtime TUI session returned by session.resume; Desktop storage keeps the original ID. */
    private var runtimeSessionId: String? = null

    /**
     * Whether the gateway has confirmed a persisted DB row for the current
     * session. `session.create` does NOT persist a row until the first prompt
     * (the gateway creates it lazily), so a freshly created session that has
     * never been prompted CANNOT be resumed — `session.resume` on its storage
     * key returns 4007 "session not found" and the REST transcript 404s.
     * The flag is cleared on create/switch and set once the row is confirmed
     * (REST 200, resume success, or a MessageStart = the server accepted a
     * prompt). Reconnects skip the doomed resume while it is false (issue:
     * 4007 "failed to load session" popup after tab switches).
     */
    private var sessionHasServerPresence = false

    /** Dedupe guard for [recoverGoneSession] (WS reject + REST 404 land together). */
    private var sessionGoneRecoveryInFlight = false

    /** Show the "session gone" notice once the recovery create lands (create wipes messages). */
    private var pendingGoneSessionNotice = false
    private var loadedMessageOffset = 0

    /**
     * True once the backend honored `order=latest` on the initial page (the
     * pagination echo came back). Offsets then count BACK from the newest
     * message and older pages INCREASE the offset; a full page means more
     * older messages exist. False on legacy backends (no `order` param) —
     * offsets stay absolute and decrease toward 0 (issue #859).
     */
    private var latestPaging = false
    private var isSyncingMessages = false

    // ── Session resume recovery (desktop parity) ────────────────────────
    // Bounded auto-retry with exponential backoff, mirroring the desktop's
    // use-route-resume: a failed session.resume retries 1s→2s→4s→8s up to
    // MAX_RESUME_RETRIES, then surfaces an explicit error + manual Retry
    // instead of latching the spinner forever.
    private var resumeRetrySessionId: String? = null
    private var resumeRetryAttempt = 0
    private var resumeRetryJob: Job? = null
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** Tracks the auto-clear coroutine for reaction animations. */
    private var reactionClearJob: Job? = null

    private val wsClient = HermesWsClient

    // ── Session persistence ──────────────────────────────────────────────
    private val repo: ChatPersistenceRepository = repo
    private val slashUsageStore: SlashUsageStore = slashUsageStore
    private val slashDispatcher = SlashCommandDispatcher()

    /**
     * Progress popup for `/update` from chat (issue #862). The backend `/update`
     * handler is interactive + session-exiting and can never answer the slash
     * worker (45s timeout), so the command is intercepted client-side and
     * routed through the same REST action + shared popup as the System screen.
     */
    val actionProgress = ActionProgressController(scope = viewModelScope)
    private val searchDelegate =
        ChatSearchDelegate(
            scope = viewModelScope,
            uiState = _uiState,
            dispatcher = searchDispatcher,
        )

    /** Snapshot-backed in-chat search state (see [ChatSearchDelegate]). */
    val searchState: ChatSearchState
        get() = searchDelegate.searchState
    private val attachmentsDelegate = ChatAttachmentsDelegate(uiState = _uiState)

    private val mediaDelegate =
        ChatMediaDelegate(
            uiState = _uiState,
            getApplication = { getApplication() },
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
        )

    /**
     * Model options cached from GET /api/model/options so the in-session model
     * picker (issue #589) opens instantly when the user types /model or taps the
     * top-bar chip. Preloaded at GatewayReady; refreshed on open if empty.
     */
    private var cachedModelOptions: List<ModelProvider> = emptyList()

    private val streamingController =
        ChatStreamingController(
            scope = viewModelScope,
            uiState = _uiState,
            streamingState = _streamingState,
            isCurrentSession = { sessionId -> isCurrentSession(sessionId) },
            isTestEnvironment = { isTestEnvironment() },
        )

    // ── Public state ─────────────────────────────────────────────────────

    /**
     * Combined UI state: merges internal state with the WS connection status
     * flow so there is a single source of truth for connection state.
     */
    val uiState: StateFlow<ChatUiState> =
        combine(
            _uiState,
            wsClient.connectionStatus,
        ) { state, connStatus ->
            state.copy(connectionStatus = connStatus)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _uiState.value,
        )

    /**
     * Session ID to resume when the WebSocket connects. Set synchronously by
     * [ChatScreen] via `SideEffect` during composition — before any WS event
     * can be processed. This prevents the race where [GatewayReady] fires
     * before ChatScreen's `LaunchedEffect` can call [switchSession], causing
     * [createNewSession] to create an empty chat that overwrites the
     * notification session (issue #240).
     */
    var initialSessionId: String? = null

    init {
        refreshSettings()
        refreshMaxToolCallsPerTurn()

        connectWebSocket(setLoading = false)
        viewModelScope.launch {
            wsClient.events.collect { event ->
                try {
                    handleWsEvent(event)
                } catch (e: Exception) {
                    android.util.Log.e("ChatVM", "Uncaught in event loop", e)
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
        // B7 (Jun 30 2026, kanban t_connection_loading): clear loading state on connection failure or status change
        viewModelScope.launch {
            wsClient.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED ||
                    status == ConnectionStatus.RECONNECTING ||
                    status == ConnectionStatus.NO_NETWORK ||
                    status == ConnectionStatus.AUTH_EXPIRED
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                    // The runtime session id is only valid while the socket
                    // owns it — a dropped connection may mean the gateway
                    // closed/pruned the session (or restarted, wiping the
                    // runtime registry). Clear it so session-scoped RPCs can't
                    // fire with a stale id and 4001 "session not found";
                    // handleGatewayReady rebinds it on the re-resume.
                    runtimeSessionId = null
                    // Fail any in-flight awaited RPCs so callers don't hang
                    // across the disconnect (delegated to HermesWsClient, issue #526).
                    wsClient.rejectAllPending()
                }
            }
        }
        // Desktop parity (requestFreshSession): when the profile switch
        // coordinator fires, wipe the open conversation. The re-dialed socket
        // then delivers gateway.ready → handleGatewayReady loads the new
        // profile's session list and auto-creates a FRESH session, so the
        // previous profile's context never leaks into the new profile's chat.
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched
                .collect { _ ->
                    resetSessionState(sessionId = null, title = "Hermes", isLoading = true)
                }
        }
        // Same wipe when the CONNECTION profile changes (different server):
        // without it, gateway.ready on the re-dialed socket tries to resume
        // the OLD server's session on the NEW server and fails (split-brain
        // after connection-profile switch, reproduced live 2026-08-12).
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched
                .collect { _ ->
                    resetSessionState(sessionId = null, title = "Hermes", isLoading = true)
                }
        }
        // Slash-command usage ranking (issue #865): mirror the local usage
        // counts into state so the autocomplete can surface most-used
        // commands first. Best-effort — the store never throws.
        viewModelScope.launch {
            slashUsageStore.counts().collect { counts ->
                _uiState.update { it.copy(slashUsageCounts = counts) }
            }
        }
        if (wsClient.connectionStatus.value == ConnectionStatus.CONNECTED) {
            handleGatewayReady()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket(setLoading: Boolean = false) {
        // In loopback (token) mode the session token is the WS credential and
        // must be present before connecting. In gated (ticket) mode the ticket
        // is minted fresh by HermesWsClient.refreshWsTicketIfNeeded() from the
        // persisted session cookie, so getToken() is expected to be empty here
        // and must NOT block the connect (issue #640: chat showed "reconnect"
        // immediately after basic-auth login because this guard returned early).
        val isGated =
            runCatching { AuthManager.serverStore.getLatestState().wsAuthParam == "ticket" }
                .getOrNull() ?: false
        if (!isGated) {
            val token = AuthManager.getToken() ?: return
            if (token.isBlank()) return
        }

        // Don't disturb an already-working (or already-recovering) connection.
        // HermesWsClient is a global singleton shared by every tab; the chat tab
        // is recreated on every open, so calling connect() here must be a no-op
        // unless the singleton is in a terminal state. Re-entering connect() while
        // it is CONNECTING/RECONNECTING races the in-flight socket and can leave
        // the status stuck on RECONNECTING (see HermesWsClient.connect).
        val status = wsClient.connectionStatus.value
        if (status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.RECONNECTING ||
            status == ConnectionStatus.AUTH_EXPIRED
        ) {
            return
        }

        if (setLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }

        viewModelScope.launch(ioDispatcher) {
            wsClient.connect()
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): safety timeout to clear spinner if connection hangs
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                if (_uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ── WS Event Handling ────────────────────────────────────────────────

    private fun handleGatewayReady() {
        // A (re)connect is a fresh start: clear any stale resume error and
        // cancel a pending retry — the re-resume below rebinds the session
        // on the new socket (desktop parity: gatewayBecameOpen re-resumes
        // even when the route looks already active).
        _uiState.update { it.copy(isLoading = false, resumeError = null, isResumeRetrying = false) }
        cancelResumeRetry()
        addSystemMessage("Connected to Hermes")
        loadSessions()
        fetchCommandCatalog()
        preloadModelOptions()
        val currentId = _uiState.value.currentSessionId
        if (currentId != null) {
            if (sessionHasServerPresence) {
                resumeSession(currentId, sessionGeneration)
            }
            // No server-side row yet (session created but never prompted):
            // resuming would 4007 and the REST transcript would 404. Keep the
            // in-memory chat as-is — the first prompt persists the row, and a
            // later reconnect resumes it normally.
        } else {
            val initial = initialSessionId
            if (!initial.isNullOrBlank()) {
                initialSessionId = null
                switchSession(initial)
            } else {
                createNewSession(setLoading = false)
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        // RpcError is reduced before ViewModel request handling. Drop stale
        // session errors here so the shared reducer cannot clear loading or
        // surface an error for a newly selected session.
        if (event is WsEvent.RpcError && isStaleSessionRequest(event.id)) {
            forgetRequest(event.id)
            return
        }

        // Flush any throttled reasoning before a state transition so the
        // finalized/orphan message carries the latest reasoning text.
        when (event) {
            is WsEvent.MessageStart,
            is WsEvent.MessageComplete,
            is WsEvent.MessageDone,
            is WsEvent.ToolStart,
            -> {
                streamingController.flushPendingReasoning()
                // Issue #842: the token buffer can hold deltas that landed
                // <33ms before the transition. The reducer seals the
                // streaming message into the orphan at tool.start — flush
                // first so the seal carries the COMPLETE narration (a
                // truncated seal fails the later REST dedupe and ghosts).
                streamingController.flushPendingTokens()
            }

            else -> Unit
        }

        // First, let the reducer compute the new state and any effects
        val result =
            ChatWsEventReducer.reduce(
                _uiState.value,
                _streamingState.value,
                event,
                runtimeSessionId ?: _uiState.value.currentSessionId,
            )

        // Apply the new state
        _uiState.update { result.state }
        _streamingState.update { result.streamingState }

        // Process side-effects from the reducer
        for (effect in result.effects) {
            when (effect) {
                is ReducerEffect.PersistMessage -> {
                    viewModelScope.launch(ioDispatcher) {
                        repo.persistMessage(effect.message, effect.sessionId)
                    }
                }

                is ReducerEffect.CreateNewSession -> {
                    createNewSession()
                }

                is ReducerEffect.LoadSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshContextUsage -> {
                    // Streaming finished — refresh the context meter now rather
                    // than waiting up to 5s for the next session-sync poll.
                    viewModelScope.launch { fetchContextUsage() }
                }

                is ReducerEffect.AttachHostMedia -> {
                    // Issue #724: turn host-path MEDIA: directives into real
                    // attachments (images inline, every other file tappable)
                    // via the gateway /api/files/download endpoint. Works on a
                    // remote phone too.
                    viewModelScope.launch(ioDispatcher) {
                        mediaDelegate.attachHostMedia(effect.sessionId, effect.messageId)
                    }
                }
            }
        }

        // Handle complex events that need ViewModel-specific context
        when (event) {
            is WsEvent.GatewayReady -> {
                handleGatewayReady()
            }

            is WsEvent.SessionInfo -> {
                // Session info pushed by backend when config changes
                // (model switch, reasoning level, etc.)
                val info = event.data
                if (info != null) {
                    val model = info["model"] as? String
                    val provider = info["provider"] as? String
                    val reasoningEffort = info["reasoning_effort"] as? String
                    val terminalBackend = info["terminal_backend"] as? String
                    val newModelLabel =
                        if (model != null && provider != null) {
                            "$provider/$model"
                        } else {
                            model
                        }
                    // Issue #817: on a REAL model swap the meter's denominator
                    // still belongs to the old model until the next fetch.
                    // Blank it so the chip hides instead of flashing a stale
                    // window under the new label, and re-fire the fetch so the
                    // new window lands immediately (no 30s poll wait). Only a
                    // change from a known label counts — the first SessionInfo
                    // of a session just sets the label.
                    val previousLabel = _uiState.value.currentSessionModel
                    val modelSwapped =
                        previousLabel != null && newModelLabel != null && newModelLabel != previousLabel
                    _uiState.update { state ->
                        state.copy(
                            currentSessionModel = newModelLabel ?: state.currentSessionModel,
                            reasoningLevel =
                                if (reasoningEffort.isNullOrEmpty()) {
                                    null
                                } else {
                                    reasoningEffort
                                },
                            terminalBackend = terminalBackend ?: state.terminalBackend,
                            fullContextTokens = if (modelSwapped) null else state.fullContextTokens,
                        )
                    }
                    syncCurrentModelCapabilities()
                    if (modelSwapped) {
                        // Issue #817: after a swap the REST model/info window is
                        // PROFILE-scoped and may describe the old model (e.g. a
                        // session-scoped swap) — the meter must not fall back to
                        // it. Wait for the RPC's live context_max instead; the
                        // chip stays hidden until the real window lands.
                        viewModelScope.launch { fetchContextUsage(skipRestFallback = true) }
                    }
                }
            }

            is WsEvent.MessageToken -> {
                streamingController.handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                streamingController.handleThinkingDelta(event)
            }

            is WsEvent.ReasoningDelta -> {
                streamingController.handleReasoningDelta(event)
            }

            is WsEvent.MessageStart -> {
                // The server accepted a prompt for this session — its DB row
                // now exists (created lazily at prompt.submit), so a reconnect
                // resume will succeed.
                sessionHasServerPresence = true
                streamingController.beginStreamingMessage()
            }

            is WsEvent.MessageComplete -> {
                // Buffers cleared before reduce; ViewModel resets them after
                streamingController.resetStreaming()
            }

            is WsEvent.MessageDone -> {
                streamingController.resetStreaming()
            }

            is WsEvent.ToolStart -> {
                // Issue #771: the reducer keeps the streaming message (and its
                // reasoning) alive across the tool call so the finalized answer
                // retains the thinking card. Only the token buffers are cleared
                // here — resetStreaming() would wipe streamingMessage +
                // reasoningText and re-introduce the mid-turn reasoning vanish.
                streamingController.clearStreamingBuffers()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.SessionUpdated -> {
                loadSessions()
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
            }

            is WsEvent.ApprovalRequest -> {
                handleApprovalRequest(event)
            }

            is WsEvent.SudoRequest -> {
                handleSudoRequest(event)
            }

            is WsEvent.SecretRequest -> {
                handleSecretRequest(event)
            }

            is WsEvent.GatewayError -> {
                // Reducer already set errorMessage; no extra VM work needed.
            }

            is WsEvent.BackgroundComplete -> {
                // Reducer already set backgroundCompleteMessage; the UI observes
                // it via a LaunchedEffect and triggers the snackbar.
            }

            is WsEvent.ReactionEvent -> {
                // Cancel any previous auto-clear to avoid race (agy finding #1)
                reactionClearJob?.cancel()
                _uiState.update {
                    it.copy(
                        reactionKind = event.kind,
                        reactionTriggerId = it.reactionTriggerId + 1L,
                    )
                }
                // Auto-clear after the animation duration
                reactionClearJob =
                    viewModelScope.launch {
                        delay(2_000L)
                        _uiState.update { it.copy(reactionKind = null) }
                    }
            }

            else -> { /* reducer handles these */ }
        }
    }

    // ── Message streaming ────────────────────────────────────────────────

    /**
     * Checks if an incoming WS event belongs to the currently active
     * session. Returns true if the event should be processed.
     */
    private fun isCurrentSession(eventSessionId: String?): Boolean {
        // If the event has no session ID, process it (legacy compatibility)
        if (eventSessionId == null) return true
        return eventSessionId == runtimeSessionId || eventSessionId == _uiState.value.currentSessionId
    }

    // ── RPC response handling ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val method = idToMethod.remove(id) ?: return
        val request = sessionRequestById.remove(id)
        if (request != null && isStaleSessionRequest(request)) return
        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val runtimeId = resultMap["session_id"] as? String ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                runtimeSessionId = runtimeId
                // The gateway persists the row lazily on the first prompt —
                // do not resume this key until presence is confirmed.
                sessionHasServerPresence = false
                sessionGoneRecoveryInFlight = false
                _uiState.update {
                    it.copy(
                        currentSessionId = storageId,
                        isLoading = false,
                        messages = emptyList(),
                        chatTitle = "Hermes",
                        usedContextTokens = null,
                        fullContextTokens = null,
                        contextBreakdown = null,
                        compressionCount = null,
                    )
                }
                // A gone-session recovery just landed — announce it now that
                // the message list has been reset by the create.
                if (pendingGoneSessionNotice) {
                    pendingGoneSessionNotice = false
                    addSystemMessage("Previous session is no longer available on the server — starting a new chat")
                }
                // Mirror the active session id app-wide so session-scoped
                // drawer screens (e.g. Processes, issue #532) can issue
                // session-scoped RPCs. See ActiveSessionHolder.
                ActiveSessionHolder.set(runtimeId, storageId)
                _streamingState.update { StreamingState() }
                addSystemMessage("Session created", persist = true)
                loadSessions()
                fetchContextUsage()
            }

            WsMethods.SESSION_BRANCH -> {
                val resultMap = result as? Map<String, Any?> ?: return
                // The result carries BOTH ids: `session_id` is the runtime
                // registry id, `stored_session_id` is the DB key. currentSessionId
                // must stay the storage key — storing the runtime id here made
                // every later resume 4007 "session not found" (the DB lookup
                // misses) and the REST transcript 404.
                val runtimeId = resultMap["session_id"] as? String ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                val generation =
                    resetSessionState(
                        sessionId = storageId,
                        title = (resultMap["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Hermes",
                        isLoading = false,
                    )
                runtimeSessionId = runtimeId
                ActiveSessionHolder.set(runtimeId, storageId)
                sessionHasServerPresence = false
                sessionGoneRecoveryInFlight = false
                addSystemMessage("Session branched", persist = true)
                loadSessionMessages(storageId, generation)
                loadSessions()
                fetchContextUsage()
            }

            WsMethods.SESSION_LIST -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionsList = resultMap["sessions"] as? List<Map<String, Any?>> ?: return
                val sessions =
                    sessionsList.map { s ->
                        SessionUi(
                            id = s["id"] as? String ?: "",
                            title = s["title"] as? String ?: "Untitled",
                            messageCount = (s["message_count"] as? Double)?.toInt() ?: 0,
                        )
                    }
                _uiState.update { state ->
                    val newTitle = sessions.find { s -> s.id == state.currentSessionId }?.title
                    state.copy(
                        sessions = sessions,
                        chatTitle = newTitle ?: state.chatTitle,
                    )
                }
            }

            WsMethods.SESSION_RESUME -> {
                val resultMap = result as? Map<String, Any?>
                runtimeSessionId = resultMap?.get("session_id") as? String
                // Resume succeeded — the gateway confirmed the DB row.
                sessionHasServerPresence = true
                val sessionId =
                    request?.sessionId
                        ?: (resultMap?.get("resumed") as? String)
                        ?: _uiState.value.currentSessionId

                // Parse session info from backend — model, provider, reasoning_effort
                val infoMap = resultMap?.get("info") as? Map<String, Any?>
                val model = infoMap?.get("model") as? String
                val provider = infoMap?.get("provider") as? String
                val reasoningEffort = infoMap?.get("reasoning_effort") as? String
                val terminalBackend = infoMap?.get("terminal_backend") as? String

                // B8 (Jun 20 2026, kanban t_session_resume): do NOT reload
                // cached messages here — switchSession() already did so before
                // the WS round-trip. Calling loadCachedMessages() here would
                // overwrite any message the user sent between switchSession() and
                // the server ack, making the chat appear to go blank.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        currentSessionId = sessionId,
                        currentSessionModel =
                            if (model != null && provider != null) {
                                "$provider/$model"
                            } else {
                                model ?: it.currentSessionModel
                            },
                        reasoningLevel =
                            if (reasoningEffort.isNullOrEmpty()) {
                                null
                            } else {
                                reasoningEffort
                            },
                        terminalBackend = terminalBackend ?: it.terminalBackend,
                    )
                }
                syncCurrentModelCapabilities()
                // Mirror the active runtime session id app-wide (issue #532).
                ActiveSessionHolder.set(runtimeSessionId ?: sessionId, sessionId)
                addSystemMessage("Session resumed")
                fetchContextUsage()
                val generation = request?.generation ?: sessionGeneration
                resumedGeneration = generation
                finishResumeWhenHydrated(generation)
            }

            WsMethods.SESSION_INTERRUPT -> {
                // Issue #842 follow-up: seal whatever the agent streamed so far
                // (interim commentary + partial answer) BEFORE clearing the
                // streaming state. The old tool.start orphan seal used to leave
                // pre-tool text behind on interrupt; with that seal gone, the
                // partial would otherwise vanish entirely.
                sealStreamingMessageIfAny()
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
                addSystemMessage("Session interrupted")
            }

            WsMethods.COMMANDS_CATALOG -> {
                val map = result as? Map<*, *> ?: return
                val catalog = parseCommandCatalog(map)
                if (catalog != null) {
                    _uiState.update { it.copy(commandCatalog = catalog) }
                }
            }

            WsMethods.COMMAND_DISPATCH -> {
                handleDispatchResult(result)
            }

            WsMethods.APPROVAL_RESPOND -> {
                val map = result as? Map<*, *>
                val resolved = (map?.get("resolved") as? Number)?.toInt() ?: 0
                if (resolved > 0) {
                    addSystemMessage("Approval submitted")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDispatchResult(result: Any?) {
        val map = result as? Map<*, *> ?: return
        val type = map["type"] as? String ?: return
        when (type) {
            "send" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "exec" -> {
                val output = map["output"] as? String ?: map["message"] as? String ?: ""
                addAssistantMessage(output)
            }

            "skill" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "plugin" -> {
                val output = map["output"] as? String ?: ""
                addAssistantMessage(output)
            }

            "alias" -> {
                val target = map["target"] as? String ?: return
                handleSlashCommand(target)
            }

            else -> {
                val output = map["output"] as? String ?: map.toString()
                addAssistantMessage(output)
            }
        }
    }

    private fun handleRpcError(
        id: String,
        error: Any?,
    ) {
        val method = idToMethod.remove(id) ?: return
        val request = sessionRequestById.remove(id)
        if (request != null && isStaleSessionRequest(request)) return
        val errorMsg =
            when (error) {
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                else -> error.toString()
            }

        // Session resume failures go through the bounded retry (desktop
        // parity) instead of a one-shot snackbar — the session may be
        // mid-flush on the gateway or the WS may have just rebound, and a
        // brief backoff usually clears it. Persistent failure ends in the
        // explicit error + Retry state.
        if (method == WsMethods.SESSION_RESUME) {
            val sessionId = request?.sessionId ?: _uiState.value.currentSessionId
            if (sessionId != null) {
                val generation = request?.generation ?: sessionGeneration
                if (resumedGeneration == generation) resumedGeneration = -1L
                handleResumeFailure(sessionId, generation, errorMsg)
            }
            return
        }

        // Surface error in UI (these are server-pushed RpcError for
        // fire-and-forget RPCs — awaited RPCs handle their own failure
        // via the HermesWsClient.request() deferred).
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error ($method): $errorMsg",
            )
        }
    }

    // ── Send message ─────────────────────────────────────────────────────

    /**
     * Send a user prompt, uploading any pending attachments to the backend
     * first via their dedicated RPC methods.
     *
     * Flow:
     * 1. Snapshot pending attachments (then clear them from UI)
     * 2. Add user message to UI + DB immediately (optimistic UX)
     * 3. For each image → await `image.attach_bytes` (requires session_id)
     * 4. For each file → await `file.attach` (requires session_id), collect @file: refs
     * 5. Send `prompt.submit` with text + @file: refs — images auto-picked up by backend
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && _uiState.value.pendingAttachments.isEmpty()) return
        val storageSessionId = _uiState.value.currentSessionId ?: return
        val agentSessionId = runtimeSessionId ?: return

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            // Issue #589: a bare "/model" (no argument) opens the picker instead
            // of requiring the user to hand-type the provider/model.
            if (isModelPickerCommand(trimmed)) {
                openModelPicker()
                return
            }
            handleSlashCommand(trimmed)
            return
        }

        // Snapshot + clear attachments so the input bar empties immediately
        val attachments = _uiState.value.pendingAttachments.toList()
        clearAttachments()

        val wasStreaming = _uiState.value.isAgentTyping

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
                attachments = if (attachments.isNotEmpty()) attachments else null,
            )

        // Update UI immediately
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        // Persist under the original Desktop session ID.
        viewModelScope.launch(ioDispatcher) {
            repo.persistMessage(userMessage, storageSessionId)
        }

        // Upload attachments then submit prompt
        viewModelScope.launch(ioDispatcher) {
            val fileRefs = mutableListOf<String>()

            for (attachment in attachments) {
                val b64 = readContentUriBase64(attachment.uri)
                if (b64 == null) {
                    Log.w(TAG, "Skipping unreadable attachment: ${attachment.name}")
                    continue
                }

                try {
                    if (attachment.isImage) {
                        // Await so the backend stages the image into
                        // session["attached_images"] BEFORE prompt.submit runs
                        // (a fire-and-forget send raced prompt.submit and the
                        // image was dropped). Requires session_id or the gateway
                        // 4001s "session not found" (desktop passes it too).
                        val result =
                            sendRpcAndAwait(
                                method = WsMethods.IMAGE_ATTACH_BYTES,
                                params =
                                    mapOf(
                                        "session_id" to agentSessionId,
                                        "content_base64" to "data:${attachment.mimeType};base64,$b64",
                                        "filename" to attachment.name,
                                        "ext" to attachment.fileExtension,
                                    ),
                            )
                        if (result != null) {
                            @Suppress("UNCHECKED_CAST")
                            val ok = (result as? Map<String, Any?>)?.get("attached") as? Boolean
                            if (ok != true) {
                                Log.w(TAG, "Image attach for ${attachment.name} returned non-ok: $result")
                            }
                        }
                    } else {
                        // Await the @file: ref text so we can embed it in the prompt.
                        // file.attach also requires session_id or the gateway 4001s
                        // "session not found" (same resolver as image.attach_bytes).
                        sendRpcAndAwait(
                            method = WsMethods.FILE_ATTACH,
                            params =
                                mapOf(
                                    "session_id" to agentSessionId,
                                    "data_url" to "data:${attachment.mimeType};base64,$b64",
                                    "name" to attachment.name,
                                ),
                        )?.let { result ->
                            @Suppress("UNCHECKED_CAST")
                            val refText =
                                (result as? Map<String, Any?>)?.get("ref_text") as? String
                            if (!refText.isNullOrBlank()) fileRefs.add(refText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload attachment ${attachment.name}", e)
                    _uiState.update {
                        it.copy(errorMessage = "Upload failed: ${attachment.name}")
                    }
                }
            }

            // Build prompt text — prepend @file: refs for non-image files
            val fullText =
                if (fileRefs.isEmpty()) {
                    text
                } else {
                    fileRefs.joinToString("\n") +
                        if (text.isNotBlank()) "\n\n$text" else ""
                }

            // While a turn is actively streaming and this is a plain text prompt
            // (no attachments — session.redirect carries text only), steer the
            // in-flight turn via session.redirect instead of queueing a fresh
            // prompt.submit. The backend rewrites the live turn when it can, or
            // queues the correction as the next turn otherwise (issue #710).
            ActiveSessionHolder.set(agentSessionId, storageSessionId)
            if (wasStreaming && attachments.isEmpty()) {
                wsClient.sendRedirect(
                    agentSessionId,
                    fullText,
                    onSent = { id -> trackRequest(id, WsMethods.SESSION_REDIRECT) },
                )
            } else {
                wsClient.sendMessage(
                    agentSessionId,
                    fullText,
                    onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
                )
            }
        }
    }

    /** Read and encode a `content://` or `file://` URI to Base64 via ContentResolver, avoiding large allocations. */
    private suspend fun readContentUriBase64(uriString: String): String? =
        try {
            val context = getApplication<Application>()
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val baos = ByteArrayOutputStream()
                val b64os = Base64OutputStream(baos, Base64.NO_WRAP)
                val buffer = ByteArray(1024 * 128) // 128KB chunk
                var bytesRead: Int

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    b64os.write(buffer, 0, bytesRead)
                    yield() // Prevent blocking the thread during large reads
                }
                b64os.close()
                baos.toString("UTF-8")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to read and encode attachment: ${e.message}", e)
            null
        }

    /**
     * Send a JSON-RPC call and suspend until the response arrives, delegating
     * the deferred + 120s timeout to [HermesWsClient.request] (issue #526).
     * Throws [HermesWsClient.HermesRpcException] on RPC error, or
     * [kotlinx.coroutines.TimeoutCancellationException] if the server never
     * answers within the timeout.
     */
    private suspend fun sendRpcAndAwait(
        method: String,
        params: Map<String, Any>,
    ): Any? = HermesWsClient.request(method, params).await()

    // ── Attachment management ─────────────────────────────────────────────

    /**
     * Add a picked file as a pending attachment.
     * [uri] should be a content:// URI string; the ViewModel will read
     * the content and encode it for sending.
     */
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) = attachmentsDelegate.addAttachment(uri, name, mimeType, size)

    fun removeAttachment(index: Int) = attachmentsDelegate.removeAttachment(index)

    fun openAttachment(attachment: Attachment) = mediaDelegate.openAttachment(attachment)

    fun saveAttachment(
        attachment: Attachment,
        destination: android.net.Uri,
    ) = mediaDelegate.saveAttachment(attachment, destination)

    fun clearOpenError() {
        _uiState.update { it.copy(openError = null) }
    }

    fun gatewayPathFor(attachment: Attachment): String = mediaDelegate.gatewayPathFor(attachment)

    fun clearAttachments() = attachmentsDelegate.clearAttachments()

    private fun handleSlashCommand(command: String) {
        // Classify FIRST (pure logic) — /queue's optimistic bubble must show
        // the queued TEXT (prefix stripped, see QueuePrompt.displayContent) so
        // it matches the server echo and the transcript sync dedupes instead
        // of rendering a duplicate below its answer.
        val result = slashDispatcher.dispatch(command)
        val displayContent =
            if (result is SlashResult.QueuePrompt) result.displayContent else command
        val userMsg = ChatMessage(role = MessageRole.USER, content = displayContent)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + userMsg) }

        // Persist — OUTSIDE update{}
        if (sessionId != null) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(userMsg, sessionId)
            }
        }

        // Block desktop/CLI-only + TUI-only commands that don't function on
        // mobile (issue #576, deliverable #3). These are also hidden from the
        // suggestion menu, but a user can still type one — intercept it here
        // (before any RPC fires) with a clear message instead of a doomed call.
        if (CommandBlocklist.contains(command)) {
            addAssistantMessage(
                "${command.split(" ", limit = 2)[0]} is not supported on mobile",
            )
            return
        }

        // Track per-command usage for the slash-autocomplete ranking (issue
        // #865). Counted here, AFTER the blocklist guard, so commands that
        // can never dispatch don't climb the ranking. Best-effort — a store
        // failure must never block the dispatch itself.
        val commandName = command.split(" ", limit = 2)[0].lowercase()
        viewModelScope.launch {
            slashUsageStore.recordUse(commandName)
        }

        when (result) {
            is SlashResult.Interrupt -> {
                interruptSession()
            }

            is SlashResult.NewSession -> {
                createNewSession()
            }

            is SlashResult.SessionBranch -> {
                branchSession(command)
            }

            is SlashResult.ModelSwitch -> {
                handleModelSwitch(command)
            }

            is SlashResult.Update -> {
                openUpdateConfirm()
            }

            is SlashResult.OpenHistory -> {
                // Client-side: open the session history tab so the user can
                // pick a past session to resume (issue #864) — no gateway
                // round-trip (the backend slash worker can't answer /resume).
                _uiState.update { it.copy(openHistoryRequested = true) }
            }

            is SlashResult.QueuePrompt -> {
                handleQueueCommand(command)
            }

            is SlashResult.RpcDispatch -> {
                dispatchViaRpc(command)
            }
        }
    }

    /**
     * Queue a prompt to run after the current turn (backend contract
     * `prompt.submit` `queued=true` — hermes-agent methods_prompt.py:147,
     * _handle_busy_submit). The gateway then enqueues it as the next turn
     * and NEVER redirects/interrupts the live turn, regardless of
     * `display.busy_input_mode`. Intercepted client-side because the
     * `command.dispatch` `queue` shim only echoes the text back as a plain
     * submit, which loses the queued flag and hijacks the live turn.
     */
    private fun handleQueueCommand(command: String) {
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        if (arg.isBlank()) {
            addAssistantMessage("usage: /queue <prompt>")
            return
        }
        submitPrompt(arg, queued = true)
    }

    // ── Update from chat (issue #862) ────────────────────────────────────
    // `/update` can't travel via the slash worker (the backend handler is
    // interactive + session-exiting → guaranteed 45s timeout). Intercept it
    // client-side: confirm, then trigger the same REST action the System
    // screen uses and track it in the shared ActionProgressDialog.

    fun openUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = true) }
    }

    fun closeUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = false) }
    }

    /**
     * Run the backend update: `POST /api/hermes/update` returns immediately
     * (`{ok, name}`) while `hermes update` runs in the background, so
     * [actionProgress] polls its status log until it exits and the popup shows
     * the live tail + final state.
     */
    fun applyUpdate() {
        actionProgress.open()
        viewModelScope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.updateHermes() }
            when (result) {
                is NetworkResult.Success -> {
                    val name = result.data.name
                    if (name != null) {
                        actionProgress.markStarted(name)
                    } else {
                        actionProgress.fail(
                            "Update started but the backend did not report an action name",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    actionProgress.fail("Failed to start update: ${result.error.message}")
                }
            }
        }
    }

    /**
     * Fork the active conversation via the session.branch WS RPC (issue #533).
     * The backend already supports session.branch; the mobile previously had
     * no client surface, so `/fork` fell through to command.dispatch and 4018'd.
     * The optional arg becomes the new branch's title.
     */
    private fun branchSession(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        val params = mutableMapOf<String, Any>("session_id" to sessionId)
        if (arg.isNotBlank()) params["name"] = arg
        val generation = sessionGeneration
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_BRANCH,
                params,
                onSent = { id -> trackSessionRequest(id, WsMethods.SESSION_BRANCH, generation) },
            )
        }
    }

    private fun dispatchViaRpc(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val parts = command.split(" ", limit = 2)
        val name = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        viewModelScope.launch(ioDispatcher) {
            try {
                // Primary path: command.dispatch handles quick/plugin/bundle/
                // skill commands + a few hardcoded ones. It returns a hard 4018
                // "not a ... command" for everything that lives only in the TUI
                // slash worker (the 29 commands that 4018'd on mobile — issue
                // #576). For those we fall back to slash.exec, which runs the
                // full COMMAND_REGISTRY through the worker.
                val result =
                    wsClient
                        .request(
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to name, "arg" to arg, "session_id" to sessionId),
                        ).await()
                handleDispatchResult(result)
            } catch (e: HermesWsClient.HermesRpcException) {
                val msg = e.message.orEmpty()
                // Registry miss on command.dispatch: the backend emits exactly
                // "not a quick/plugin/bundle/skill command: <name>" (tui_gateway
                // server.py L12408). Match that precise phrase so unrelated
                // errors can't accidentally trigger the slash.exec fallback.
                if (msg.contains("not a quick/plugin/bundle/skill command")) {
                    // Registry miss on command.dispatch -> retry via slash.exec,
                    // which routes the full CLI command set through the worker.
                    try {
                        val result =
                            wsClient
                                .request(
                                    WsMethods.SLASH_EXEC,
                                    mapOf(
                                        "command" to "/$name${if (arg.isNotEmpty()) " $arg" else ""}",
                                        "session_id" to sessionId,
                                    ),
                                ).await()
                        val output = (result as? Map<*, *>)?.get("output") as? String
                        if (!output.isNullOrBlank()) addAssistantMessage(output)
                    } catch (e2: HermesWsClient.HermesRpcException) {
                        addAssistantMessage("/$name: ${e2.message}")
                    }
                } else {
                    // Legit error from command.dispatch (busy, no history, etc.)
                    addAssistantMessage("/$name: ${e.message}")
                }
            }
        }
    }

    /**
     * Hot-swap the current session's model via the backend's model-switch
     * mechanism (issue #589).
     *
     * The TUI gateway's `prompt.submit` does NOT parse slash commands (it would
     * make the LLM treat "/model ..." as a chat message), and `command.dispatch`
     * only knows quick/plugin/bundle/skill commands (4018s on /model). The
     * correct RPC is `config.set` with `key="model"` — the gateway (server.py
     * `config.set`, L10253) routes `key=="model"` straight to `_apply_model_switch`
     * using the same `_sessions.get(session_id)` lookup that the working
     * `command.dispatch` uses.
     *
     * IMPORTANT: `config.set` key=model passes the value DIRECTLY to
     * `parse_model_flags` (it does NOT strip a leading "/model" like slash.exec /
     * prompt.submit do). So we strip the "/model" prefix here and send the bare
     * spec `parse_model_flags` understands:
     *   `<model> --provider <slug> --session`
     * (matching the TUI client's `modelValueForConfigSet`).
     */
    private fun handleModelSwitch(command: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        // Strip a leading "/model" (and any following whitespace) — config.set
        // key=model expects the bare spec, not a slash command. Match the
        // dispatcher's case-insensitive "/model" detection so a typed "/MODEL"
        // (or any casing) doesn't forward the literal slash prefix to the
        // backend, where parse_model_flags wouldn't recognize it.
        val spec =
            if (command.startsWith("/model", ignoreCase = true)) {
                command.substring(6).trim()
            } else {
                command.trim()
            }
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                mapOf("key" to "model", "value" to spec, "session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.CONFIG_SET) },
            )
        }
    }

    /**
     * Submits [text] as a prompt to the current session via WS, without
     * adding a duplicate user message. Used by [handleDispatchResult] when
     * a slash command resolves to a normal user prompt (e.g. `/init` → "Scan this repo").
     */
    private fun submitPrompt(
        text: String,
        queued: Boolean = false,
    ) {
        if (text.isBlank()) return
        val sessionId = runtimeSessionId ?: return
        _uiState.update { it.copy(isAgentTyping = true) }
        viewModelScope.launch(ioDispatcher) {
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
                queued = queued,
            )
        }
    }

    private fun addAssistantMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = text)
        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        val sessionId = _uiState.value.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(msg, sessionId)
            }
        }
    }

    // ── Session management ───────────────────────────────────────────────

    fun interruptSession() {
        val sessionId = runtimeSessionId ?: return
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_INTERRUPT) },
            )
        }
    }

    fun createNewSession(setLoading: Boolean = true) {
        // A fresh create has no persisted row until the first prompt.
        sessionHasServerPresence = false
        val generation = resetSessionState(sessionId = null, title = "Hermes", isLoading = setLoading)
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_CREATE,
                params = mapOf("source" to "desktop"),
                onSent = { id -> trackSessionRequest(id, WsMethods.SESSION_CREATE, generation) },
            )
        }
        // B7 safety timeout: clear loading state if RPC response never arrives
        if (setLoading && !isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                // Only clear if no newer session creation has started — prevents a
                // stale timeout from wiping the loading flag of a subsequent request.
                if (generation == sessionGeneration && _uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_LIST,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_LIST) },
            )
        }
    }

    private fun fetchCommandCatalog() {
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.COMMANDS_CATALOG,
                onSent = { id -> trackRequest(id, WsMethods.COMMANDS_CATALOG) },
            )
        }
    }

    fun refreshCurrentSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        // No server-side copy yet (created but never prompted): the REST
        // transcript 404s and would burn the resume retry budget for nothing.
        if (!sessionHasServerPresence) return
        loadSessionMessages(sessionId, sessionGeneration)
    }

    fun refreshSettings() {
        _uiState.update { state ->
            state.copy(
                typingEffectEnabled = AuthManager.isTypingEffectEnabled(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
            )
        }
    }

    /**
     * Fetch the real per-turn tool-call budget (`agent.max_turns` — falling back
     * to the legacy top-level `max_turns`) from GET /api/config so the chat's
     * tool-call dividers can render `count/max` against the actual backend
     * limit. Never hardcoded. Null on failure — the divider degrades to a
     * bare count.
     */
    private fun refreshMaxToolCallsPerTurn() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val response = ApiClient.hermesApi.getConfig()
                if (!response.isSuccessful) return@launch
                val config = response.body() ?: return@launch
                val agent = config["agent"] as? JsonObject
                val max =
                    (agent?.get("max_turns")?.jsonPrimitive?.intOrNull)
                        ?: config["max_turns"]?.jsonPrimitive?.intOrNull
                if (max != null && max > 0) {
                    _uiState.update { it.copy(maxToolCallsPerTurn = max) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch max tool calls per turn", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCommandCatalog(map: Map<*, *>): CommandCatalog? =
        try {
            val jsonElement = map.toJsonElement()
            OkHttpProvider.json.decodeFromJsonElement<CommandCatalog>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command catalog", e)
            null
        }

    // ── In-session model picker (issue #589) ─────────────────────────────

    /**
     * Whether [command] should open the in-session model picker instead of being
     * dispatched as a normal slash command. True for a bare `/model` (with no
     * trailing model argument) — the picker supplies the argument interactively.
     * A fully-typed `/model provider/model` is forwarded straight to the backend.
     */
    private fun isModelPickerCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/", ignoreCase = true)) return false
        val body = trimmed.removePrefix("/").trimStart()
        // Must be exactly "model" with no argument (or just whitespace).
        return body.equals("model", ignoreCase = true) ||
            (
                body.startsWith("model ", ignoreCase = true) &&
                    body.substringAfter("model").trim().isEmpty()
            )
    }

    /** Preload model options so the picker opens instantly (no spinner on /model). */
    private fun preloadModelOptions() {
        viewModelScope.launch(ioDispatcher) {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getModelOptions(refresh = false)
                }
            if (result is NetworkResult.Success) {
                cachedModelOptions = result.data.providers.orEmpty()
                _uiState.update { it.copy(modelPickerPinned = AuthManager.getPinnedModels()) }
                syncCurrentModelCapabilities()
            }
        }
    }

    /**
     * Open the in-session model picker. Uses the preloaded options if available
     * (instant open); otherwise shows a loading state and fetches them. The
     * `/model` slash command is the supported session hot-swap mechanism per the
     * backend contract (issue #589).
     */
    fun openModelPicker() {
        val hasCached = cachedModelOptions.isNotEmpty()
        _uiState.update {
            it.copy(
                showModelPicker = true,
                modelPickerProviders = if (hasCached) cachedModelOptions else emptyList(),
                modelPickerPinned = AuthManager.getPinnedModels(),
                modelPickerLoading = !hasCached,
            )
        }
        if (!hasCached) {
            refreshModelOptions()
        }
    }

    /** Re-fetch options (pull-to-refresh style) when the picker is already open. */
    fun refreshModelOptions() {
        _uiState.update { it.copy(modelPickerLoading = true) }
        viewModelScope.launch(ioDispatcher) {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getModelOptions(refresh = true)
                }
            when (result) {
                is NetworkResult.Success -> {
                    cachedModelOptions = result.data.providers.orEmpty()
                    _uiState.update {
                        it.copy(
                            modelPickerProviders = cachedModelOptions,
                            modelPickerLoading = false,
                        )
                    }
                    syncCurrentModelCapabilities()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            modelPickerLoading = false,
                            errorMessage = "Failed to load models: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun closeModelPicker() {
        _uiState.update { it.copy(showModelPicker = false, modelPickerLoading = false) }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = AuthManager.getPinnedModels().toMutableList()
        val target = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(target)) {
            currentPinned.remove(target)
        } else {
            currentPinned.add(target)
        }
        AuthManager.savePinnedModels(currentPinned)
        _uiState.update { it.copy(modelPickerPinned = currentPinned) }
    }

    /**
     * Hot-swap the CURRENT session's model via the /model slash command.
     *
     * Builds the backend-valid command form `/model <model> --provider <slug>
     * --session`. The `--session` flag keeps the switch scoped to this chat
     * only (it writes a per-session override and never touches the global
     * model config), per the backend model-switch contract.
     */
    fun sendSlashModel(
        provider: String,
        model: String,
    ) {
        _uiState.update {
            it.copy(
                showModelPicker = false,
                modelPickerLoading = false,
                // Optimistic: reflect the chosen model in the top-bar chip until
                // the next session sync confirms the backend hot-swap.
                currentSessionModel = "$provider/$model",
            )
        }
        syncCurrentModelCapabilities()
        // Model switch changes the context-window denominator — refetch it.
        fetchContextUsage()
        handleSlashCommand("/model $model --provider $provider --session")
    }

    /**
     * Set the reasoning effort level for the current session.
     *
     * Updates the UI optimistically and sends a `config.set` RPC to the
     * backend. The level applies per-session via the runtime session ID.
     * If [level] is null it resets to the model's default.
     *
     * @param level One of "low", "medium", "high", or null for default.
     */
    fun setReasoningLevel(level: String?) {
        _uiState.update { it.copy(reasoningLevel = level) }
        val sessionId = runtimeSessionId ?: return
        if (level == null) return // null = model default, no need to send WS
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                mapOf(
                    "key" to "reasoning",
                    "value" to level,
                    "session_id" to sessionId,
                ),
                onSent = { id -> trackRequest(id, WsMethods.CONFIG_SET) },
            )
        }
    }

    fun getModelCapabilities(
        providerSlug: String,
        modelName: String,
    ): ModelCapabilities? = cachedModelOptions.find { it.slug == providerSlug }?.capabilities?.get(modelName)

    fun getCurrentModelCapabilities(): ModelCapabilities? {
        val label = _uiState.value.currentSessionModel ?: return null
        val idx = label.indexOf('/')
        if (idx <= 0) return null
        val provider = label.substring(0, idx)
        val model = label.substring(idx + 1)
        return getModelCapabilities(provider, model)
    }

    private fun syncCurrentModelCapabilities() {
        val label = _uiState.value.currentSessionModel
        val caps =
            if (label == null) {
                null
            } else {
                val idx = label.indexOf('/')
                if (idx <= 0) {
                    null
                } else {
                    val provider = label.substring(0, idx)
                    val model = label.substring(idx + 1)
                    cachedModelOptions.find { it.slug == provider }?.capabilities?.get(model)
                }
            }
        _uiState.update { state ->
            if (state.currentModelCapabilities != caps) state.copy(currentModelCapabilities = caps) else state
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return

        // The id came from the gateway's own session list / picker — its row
        // is expected to exist, so resume it optimistically on reconnect even
        // before the REST page confirms (a transient 500 must not strand the
        // user on an un-resumable session). Only VM-created (never prompted)
        // sessions stay unconfirmed: see createNewSession.
        sessionHasServerPresence = true
        sessionGoneRecoveryInFlight = false
        pendingGoneSessionNotice = false
        val title = _uiState.value.sessions.find { it.id == sessionId }?.title ?: "Hermes"
        val generation = resetSessionState(sessionId, title, isLoading = true)
        viewModelScope.launch {
            // Warm-cache fast-path (desktop parity): paint the cached Room
            // transcript immediately so the screen never sits blank, then load
            // the fresh server transcript in parallel. If the cache is empty
            // the spinner stays up until the server page lands.
            loadCachedMessages(sessionId, generation)
            // Resume the selected desktop session and hydrate its transcript.
            resumeSession(sessionId, generation)
            loadSessions()
        }
    }

    private fun loadCachedMessages(
        sessionId: String,
        generation: Long,
    ): Job =
        viewModelScope.launch(ioDispatcher) {
            val cachedMessages = dedupeCachedMessages(repo.loadMessages(sessionId))
            _uiState.update { state ->
                // Only paint if still showing this session AND no fresher server
                // page has landed yet (a fast REST fetch must not be clobbered
                // by a stale cache read that loses the race).
                if (isCurrentSessionRequest(sessionId, generation) &&
                    state.messages.isEmpty() &&
                    cachedMessages.isNotEmpty()
                ) {
                    state.copy(
                        messages = cachedMessages,
                        isLoading = false,
                        todos = hydrateTodosFromMessages(cachedMessages),
                    )
                } else {
                    state
                }
            }
        }

    private fun loadSessionMessages(
        sessionId: String,
        generation: Long,
    ) {
        val requestSequence = ++hydrationRequestSequence
        activeHydrationRequestSequence = requestSequence
        viewModelScope.launch {
            // Initial page: ask for the NEWEST page directly (order=latest —
            // offset is measured back from the newest message, page returned
            // chronologically; verified in hermes_state.py get_messages).
            // No count-based anchor, so a stale session-list message_count can
            // no longer land the page at the wrong position (issue #859).
            // Legacy backends without the `order` param ignore it and echo no
            // `pagination` — detect that and fall back to the count-based
            // anchor so nothing regresses.
            val latestResult = fetchMessagePage(sessionId, 0, MESSAGE_PAGE_SIZE, order = "latest")
            if (!isCurrentHydration(sessionId, generation, requestSequence)) return@launch
            val (result, requestedOffset) =
                if (latestResult is NetworkResult.Success && latestResult.data.pagination?.order == "latest") {
                    latestPaging = true
                    latestResult to 0
                } else if (latestResult is NetworkResult.Success) {
                    latestPaging = false
                    val messageCount = fetchServerMessageCount(sessionId, generation, requestSequence)
                    if (!isCurrentHydration(sessionId, generation, requestSequence)) return@launch
                    val offset = (messageCount - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
                    fetchMessagePage(sessionId, offset, MESSAGE_PAGE_SIZE) to offset
                } else {
                    latestPaging = false
                    latestResult to 0
                }
            if (!isCurrentHydration(sessionId, generation, requestSequence)) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    // REST 200 — the gateway has the row for this session.
                    sessionHasServerPresence = true
                    val serverOffset = result.data.pagination?.offset ?: result.data.offset ?: requestedOffset
                    val chatMessages =
                        mapServerMessages(
                            sessionId,
                            result.data.messages.orEmpty(),
                            serverOffset,
                            latestPaging,
                            _uiState.value.messages,
                        )
                    loadedMessageOffset = serverOffset
                    withContext(ioDispatcher) {
                        repo.persistMessages(chatMessages, sessionId)
                    }
                    if (!isCurrentHydration(sessionId, generation, requestSequence)) return@launch
                    _uiState.update { state ->
                        if (!isCurrentHydration(sessionId, generation, requestSequence)) return@update state
                        // Merge, don't replace: a reload mid-turn must not
                        // drop live WS bubbles (running tool call, streaming
                        // answer) the server hasn't persisted yet. Issue #771.
                        val merged = mergeTranscriptWithLive(chatMessages, state.messages)
                        val hasOlder =
                            if (latestPaging) {
                                // Newest-anchored: older messages exist iff the
                                // page came back FULL (a short page means we hit
                                // the oldest boundary).
                                val returned = result.data.pagination?.returned ?: chatMessages.size
                                returned >= MESSAGE_PAGE_SIZE && chatMessages.isNotEmpty()
                            } else {
                                serverOffset > 0 && chatMessages.isNotEmpty()
                            }
                        state.copy(
                            messages = merged,
                            todos = hydrateTodosFromMessages(merged),
                            isLoading = false,
                            hasOlderMessages = hasOlder,
                            isLoadingOlder = false,
                        )
                    }
                    hydratedGeneration = generation
                    finishResumeWhenHydrated(generation)
                }

                is NetworkResult.Failure -> {
                    if (hydratedGeneration == generation) hydratedGeneration = -1L
                    _uiState.update {
                        if (!isCurrentHydration(sessionId, generation, requestSequence)) return@update it
                        it.copy(
                            isLoading = false,
                            isLoadingOlder = false,
                        )
                    }
                    // Route the transcript failure through the bounded resume
                    // retry (desktop parity) instead of a one-shot snackbar —
                    // a transient backend/network blip recovers on its own,
                    // and a persistent failure ends in an explicit Retry.
                    handleResumeFailure(
                        sessionId,
                        generation,
                        "Failed to load messages: ${result.error.message}",
                    )
                }
            }
        }
    }

    // ── Session resume recovery (desktop parity) ─────────────────────────

    /**
     * Persists the in-flight streaming message as-is (isStreaming=false) so an
     * interrupted turn keeps the text the user already saw on screen. No-op
     * when there is no streaming content/reasoning to save. (Issue #842
     * follow-up: replaces the old tool.start orphan seal — the streaming
     * message now survives tool calls, so interrupts are the only path that
     * would otherwise drop the partial text.)
     */
    private fun sealStreamingMessageIfAny() {
        val streaming = _streamingState.value.streamingMessage ?: return
        if (streaming.content.isBlank() && streaming.reasoningText.isBlank()) return
        val finalized = streaming.copy(isStreaming = false)
        _uiState.update { it.copy(messages = (it.messages + finalized).dedupeById()) }
        val sid = _uiState.value.currentSessionId
        if (sid != null) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(finalized, sid)
            }
        }
    }

    private fun resetSessionState(
        sessionId: String?,
        title: String,
        isLoading: Boolean,
    ): Long {
        val generation = ++sessionGeneration
        cancelResumeRetry()
        resumedGeneration = -1L
        hydratedGeneration = -1L
        runtimeSessionId = null
        ActiveSessionHolder.clear()
        loadedMessageOffset = 0
        latestPaging = false
        isSyncingMessages = false
        streamingController.resetStreaming()
        _streamingState.value = StreamingState()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                currentSessionId = sessionId,
                chatTitle = title,
                isAgentTyping = false,
                isThinking = false,
                thinkingText = "",
                isLoading = isLoading,
                isLoadingOlder = false,
                hasOlderMessages = false,
                streamingMessage = null,
                errorMessage = null,
                openError = null,
                clarifyRequest = null,
                sudoPrompt = null,
                secretPrompt = null,
                showSessionPicker = false,
                showModelPicker = false,
                modelPickerLoading = false,
                currentSessionModel = null,
                currentModelCapabilities = null,
                reasoningLevel = null,
                terminalBackend = null,
                usedContextTokens = null,
                fullContextTokens = null,
                contextBreakdown = null,
                compressionCount = null,
                pendingAttachments = emptyList(),
                reactionKind = null,
                subagentIndicators = emptyList(),
                todos = emptyList(),
                resumeError = null,
                isResumeRetrying = false,
            )
        }
        return generation
    }

    private fun resumeSession(
        sessionId: String,
        generation: Long,
    ) {
        val requestSequence = ++resumeRequestSequence
        activeResumeRequestSequence = requestSequence
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_RESUME,
                mapOf("session_id" to sessionId, "omit_messages" to true),
                onSent = { id ->
                    trackSessionRequest(
                        id = id,
                        method = WsMethods.SESSION_RESUME,
                        generation = generation,
                        resumeSequence = requestSequence,
                        sessionId = sessionId,
                    )
                },
            )
        }
        loadSessionMessages(sessionId, generation)
    }

    private fun cancelResumeRetry() {
        resumeRetryJob?.cancel()
        resumeRetryJob = null
        resumeRetrySessionId = null
        resumeRetryAttempt = 0
    }

    private fun finishResumeWhenHydrated(generation: Long) {
        if (generation != sessionGeneration ||
            resumedGeneration != generation ||
            hydratedGeneration != generation
        ) {
            return
        }
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = false,
                resumeError = null,
                errorMessage = null,
            )
        }
    }

    private fun resumeRetryDelayMs(attempt: Int): Long =
        minOf(RESUME_RETRY_MAX_MS, RESUME_RETRY_BASE_MS * (1L shl attempt))

    /**
     * Bounded auto-retry for a failed session resume (mirrors the desktop's
     * use-route-resume). A failed resume — gateway RPC reject or REST
     * transcript failure — retries with exponential backoff (1s→2s→4s→8s),
     * capped at [MAX_RESUME_RETRIES]. After exhaustion the UI gets an
     * explicit error + manual Retry ([retryResumeSession]) instead of an
     * infinite spinner.
     *
     * Failures that PROVE the session is gone server-side — the resume RPC's
     * 4007 "session not found" (DB miss) and the REST transcript's 404 — are
     * terminal: retrying can never succeed, so they recover immediately with
     * a fresh chat ([recoverGoneSession]) instead of burning the budget.
     */

    private fun isDefinitiveSessionGone(message: String): Boolean =
        message.contains("session not found", ignoreCase = true) ||
            message.contains("404", ignoreCase = true)

    /**
     * The gateway definitively has no row for this session. Recover by
     * starting a fresh chat instead of dead-ending on a Retry button that
     * re-sends the same doomed key (the pre-fix behavior: 4007 popup whose
     * Retry never fixed anything). Dedupe: the WS reject and the REST 404 for
     * the same resume land close together — the first recovery switches
     * currentSessionId (on the create result), so a second call no-ops on the
     * sessionId guard; [sessionGoneRecoveryInFlight] closes the window before
     * that result lands.
     */
    private fun recoverGoneSession(sessionId: String) {
        if (_uiState.value.currentSessionId != sessionId) return
        if (sessionGoneRecoveryInFlight) return
        sessionGoneRecoveryInFlight = true
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = false,
                resumeError = null,
            )
        }
        // createNewSession() clears messages immediately — queue the notice
        // until its result lands so the user actually sees it.
        pendingGoneSessionNotice = true
        createNewSession(setLoading = false)
    }

    private fun handleResumeFailure(
        sessionId: String,
        generation: Long,
        errorMessage: String,
    ) {
        // Only handle if still on this session.
        if (!isCurrentSessionRequest(sessionId, generation)) return
        _uiState.update { it.copy(errorMessage = null) }

        // New session → reset the counter for a fresh backoff cycle.
        if (resumeRetrySessionId != sessionId) {
            resumeRetrySessionId = sessionId
            resumeRetryAttempt = 0
        }

        // A definitive "session not found" (4007 RPC / 404 REST) is permanent:
        // no backoff will fix it — recover with a fresh chat right away.
        if (isDefinitiveSessionGone(errorMessage)) {
            recoverGoneSession(sessionId)
            return
        }

        if (resumeRetryAttempt >= MAX_RESUME_RETRIES) {
            // Exhausted — surface the error + manual Retry affordance.
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isResumeRetrying = false,
                    resumeError = errorMessage,
                    errorMessage = null,
                )
            }
            return
        }

        // A WS RPC reject and the REST transcript failure for the same resume
        // land close together — treat them as ONE failure: if a retry is
        // already armed for this session, don't double-count or re-schedule.
        if (resumeRetrySessionId == sessionId && resumeRetryJob?.isActive == true) {
            return
        }

        val delayMs = resumeRetryDelayMs(resumeRetryAttempt)
        resumeRetryAttempt++

        _uiState.update {
            it.copy(
                isLoading = false,
                isResumeRetrying = true,
                resumeError = null,
                errorMessage = null,
            )
        }

        resumeRetryJob?.cancel()
        resumeRetryJob =
            viewModelScope.launch {
                delay(delayMs)
                // Re-check liveness at fire time: the user may have switched
                // sessions or the gateway may have reconnected meanwhile.
                if (!isCurrentSessionRequest(sessionId, generation)) return@launch

                _uiState.update { it.copy(isResumeRetrying = false) }
                if (_uiState.value.messages.isEmpty()) {
                    _uiState.update { it.copy(isLoading = true) }
                }
                // Retry the full resume: rebind the runtime via WS + refresh
                // the transcript via REST. Both are idempotent.
                resumeSession(sessionId, generation)
            }
    }

    /**
     * Manual retry after the bounded auto-retry exhausted. Clears the
     * exhausted latch and starts a fresh backoff cycle (mirrors the desktop's
     * resumeSession: reconnect / reselect / Retry all reset the counter).
     */
    fun retryResumeSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val generation = sessionGeneration
        cancelResumeRetry()
        _uiState.update {
            it.copy(
                resumeError = null,
                isResumeRetrying = false,
                isLoading = true,
            )
        }
        resumeSession(sessionId, generation)
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val generation = sessionGeneration
        if (!state.hasOlderMessages || state.isLoadingOlder) return
        if (!latestPaging && loadedMessageOffset <= 0) return
        val oldOffset = loadedMessageOffset
        // latest: offsets count BACK from the newest message, so older pages go
        // UP; legacy: absolute offsets go DOWN toward 0 (issue #859).
        val newOffset =
            if (latestPaging) {
                oldOffset + MESSAGE_PAGE_SIZE
            } else {
                (oldOffset - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
            }
        // Legacy: the final page can be short; requesting a full page at a
        // clamped offset OVERLAPS already-loaded rows (duplicate stable keys
        // crash LazyColumn), so size the request to the real gap. Latest:
        // pages are disjoint from-end ranges, so a full page never overlaps.
        val limit = if (latestPaging) MESSAGE_PAGE_SIZE else oldOffset - newOffset
        _uiState.update { it.copy(isLoadingOlder = true) }
        viewModelScope.launch {
            val result =
                fetchMessagePage(
                    sessionId,
                    newOffset,
                    limit,
                    order = if (latestPaging) "latest" else null,
                )
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSessionRequest(sessionId, generation)) return@launch
                    val returnedOffset = result.data.pagination?.offset ?: result.data.offset ?: newOffset
                    val older =
                        mapServerMessages(
                            sessionId,
                            result.data.messages.orEmpty(),
                            returnedOffset,
                            latestPaging,
                            _uiState.value.messages,
                        )
                    loadedMessageOffset = returnedOffset
                    withContext(ioDispatcher) { repo.persistMessages(older, sessionId) }
                    _uiState.update { current ->
                        if (!isCurrentSessionRequest(sessionId, generation)) return@update current
                        val hasOlder =
                            if (latestPaging) {
                                // Full page = more older messages behind it; a
                                // short (or empty) page is the oldest boundary.
                                val returned = result.data.pagination?.returned ?: older.size
                                returned >= limit && older.isNotEmpty()
                            } else {
                                returnedOffset < oldOffset && older.isNotEmpty() && returnedOffset > 0
                            }
                        current.copy(
                            messages = (older + current.messages).distinctBy { it.id },
                            isLoadingOlder = false,
                            hasOlderMessages = hasOlder,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        if (isCurrentSessionRequest(sessionId, generation)) {
                            it.copy(isLoadingOlder = false)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    fun syncCurrentSession() {
        // Issue #840: a session created but never prompted has no server row
        // yet — the REST transcript 404s and the resume-retry machinery spins
        // forever (visible in logcat as repeated /messages 404s). MessageStart
        // flips the flag once the first prompt persists the row.
        if (!sessionHasServerPresence) return
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val generation = sessionGeneration
        if (isSyncingMessages || state.isLoading || state.isLoadingOlder || state.isAgentTyping ||
            _streamingState.value.streamingMessage != null
        ) {
            return
        }
        val nextOffset =
            if (latestPaging) {
                // Newest-anchored paging can't compute an absolute
                // "after my last row" offset (from-end offsets shift as the
                // transcript grows), so refetch the newest page; the logical
                // merge below keeps existing copies and only adds new rows
                // (issue #859).
                0
            } else {
                state.messages
                    .mapNotNull { serverMessageIndex(it.id, sessionId) }
                    .maxOrNull()
                    ?.plus(1)
                    ?: loadedMessageOffset
            }
        isSyncingMessages = true
        viewModelScope.launch {
            try {
                val result =
                    fetchMessagePage(
                        sessionId,
                        nextOffset,
                        MESSAGE_PAGE_SIZE,
                        order = if (latestPaging) "latest" else null,
                    )
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSessionRequest(sessionId, generation)) return@launch
                        val incoming =
                            mapServerMessages(
                                sessionId,
                                result.data.messages.orEmpty(),
                                nextOffset,
                                latestPaging,
                                _uiState.value.messages,
                            )
                        if (incoming.isEmpty()) return@launch
                        withContext(ioDispatcher) { repo.persistMessages(incoming, sessionId) }
                        _uiState.update { current ->
                            if (!isCurrentSessionRequest(sessionId, generation)) return@update current
                            // Issue #771: the sync merge was dropping the
                            // newest tool bubble — the incoming REST page
                            // didn't include it yet (server persists tool rows
                            // at completion, but the sync offset may predate
                            // that), and the fragile toolName/content match
                            // consumed the WRONG incoming tool for an existing
                            // one, leaving the newest WS tool with no match
                            // → dropped. Use sameLogicalMessage (canonical
                            // result-key match) and always preserve any WS
                            // message that has no REST counterpart.
                            val unmatchedIncoming: MutableList<ChatMessage?> = incoming.toMutableList()
                            val incomingById = HashMap<String, Int>(unmatchedIncoming.size)
                            for (i in unmatchedIncoming.indices) {
                                val id = unmatchedIncoming[i]?.id
                                if (id != null && !incomingById.containsKey(id)) {
                                    incomingById[id] = i
                                }
                            }

                            val mergedList = mutableListOf<ChatMessage>()

                            for (existing in current.messages) {
                                val existingServerIndex = serverMessageIndex(existing.id, sessionId)
                                if (existingServerIndex != null) {
                                    val matchIdx = incomingById[existing.id]
                                    if (matchIdx != null && unmatchedIncoming[matchIdx] != null) {
                                        mergedList.add(unmatchedIncoming[matchIdx]!!)
                                        unmatchedIncoming[matchIdx] = null
                                    } else if (latestPaging) {
                                        // Newest-anchored paging re-keys rows by
                                        // from-end position, so a transcript that
                                        // grew since the last fetch shifted ids —
                                        // match by logical content and keep the
                                        // existing copy (issue #859).
                                        val logicalIdx =
                                            unmatchedIncoming.indexOfFirst { inc ->
                                                inc != null && sameLogicalMessage(inc, existing)
                                            }
                                        if (logicalIdx >= 0) {
                                            mergedList.add(existing)
                                            unmatchedIncoming[logicalIdx] = null
                                        } else {
                                            mergedList.add(existing)
                                        }
                                    } else {
                                        mergedList.add(existing)
                                    }
                                } else {
                                    // WS message (UUID id, no server index):
                                    // match by canonical content, not fragile
                                    // toolName/content equality.
                                    val matchIdx =
                                        unmatchedIncoming.indexOfFirst { inc ->
                                            inc != null && sameLogicalMessage(inc, existing)
                                        }
                                    if (matchIdx >= 0) {
                                        // Prefer the WS copy (richer payload,
                                        // real tool name) when available.
                                        mergedList.add(existing)
                                        unmatchedIncoming[matchIdx] = null
                                    } else {
                                        // No REST counterpart (server hasn't
                                        // persisted yet) — KEEP the WS message.
                                        mergedList.add(existing)
                                    }
                                }
                            }

                            for (inc in unmatchedIncoming) {
                                if (inc != null) {
                                    mergedList.add(inc)
                                }
                            }
                            val merged = mergedList.distinctBy { it.id }
                            if (sameMessages(current.messages, merged)) {
                                current
                            } else {
                                current.copy(messages = merged)
                            }
                        }
                    }

                    is NetworkResult.Failure -> {}
                }
            } finally {
                if (generation == sessionGeneration) isSyncingMessages = false
            }
        }
    }

    /**
     * Refresh the context meter: used / full tokens for the current session.
     *
     * The numerator comes from the `session.context_breakdown` WS RPC — the
     * same RPC the Hermes desktop app's status-bar meter uses. It reports the
     * live agent's actual prompt occupancy (compressor `last_prompt_tokens`,
     * falling back to an estimate of the live system prompt + tools +
     * history), so it DROPS after context compression. The previous numerator,
     * `GET /api/sessions/{id}` `input_tokens`, is a cumulative lifetime
     * counter that never resets on compression (issue #756).
     *
     * The denominator comes from the RPC's `context_max` (the compressor's
     * real context window) when present, else `GET /api/model/info`
     * `effective_context_length`. The REST session-detail call is kept only to
     * feed the detail sheet's cumulative token accounting.
     *
     * Both calls are independent and best-effort: a failure on one must not
     * wipe the other's already-shown value, and neither blocks the chat. The
     * two fetches are launched separately so a slow/erroring one can't starve
     * the other. Polled from [syncCurrentSession] via the 30s loop and re-fired
     * on model switch (the denominator changes).
     */
    fun fetchContextUsage(skipRestFallback: Boolean = false) {
        val sessionId = _uiState.value.currentSessionId ?: return
        val profile = AuthManager.activeProfileId.value
        viewModelScope.launch(ioDispatcher) {
            // Denominator fallback: full context window (cheap, public, rarely
            // changes). The RPC's context_max below overrides it when present.
            // Kept as a local (not a state write) so both sources resolve
            // before ONE atomic update below (issue #817 — two independent
            // writes let a stale pre-swap value override a fresh one mid-swap).
            val fullResult =
                safeApiCall { ApiClient.hermesApi.getModelInfo() }
            val restFull =
                if (fullResult is NetworkResult.Success) {
                    fullResult.data.effective_context_length
                        ?: fullResult.data.auto_context_length
                        ?: fullResult.data.config_context_length
                } else {
                    null
                }
            // Numerator: live context occupancy from the gateway's live agent,
            // via the same RPC the desktop meter uses. `context_used` is the
            // real current prompt size (drops after compression); `context_max`
            // is the compressor's actual window. Any failure keeps the last
            // known values — never blank the meter over a transient RPC error.
            //
            // These RPCs resolve the session against the gateway's LIVE runtime
            // registry (_sess_nowait) — the storage session id 4001s "session
            // not found" until session.resume has registered it. ChatScreen's
            // sync effect fires this immediately on session switch, before the
            // resume result lands, so skip the RPCs until resume confirms the
            // runtime id. The REST parts below stay live (they key on the
            // storage id).
            var rpcUsed: Long? = null
            var rpcMax: Long? = null
            val rpcSessionId = runtimeSessionId
            if (rpcSessionId != null) {
                try {
                    val result =
                        sendRpcAndAwait(
                            WsMethods.SESSION_CONTEXT_BREAKDOWN,
                            mapOf("session_id" to rpcSessionId),
                        )
                    val ctx = parseContextBreakdown(result)
                    if (ctx != null) {
                        rpcUsed = ctx.contextUsed?.takeIf { it > 0L }
                        rpcMax = ctx.contextMax?.takeIf { it > 0L }
                    }
                } catch (_: Exception) {
                    // Best-effort: RPC error/timeout/disconnect — keep last values.
                }
                // Compression count: how many times this session has been compacted
                // (session.usage → compressions). Feeds the "compressed ×N" badge —
                // the same usage snapshot the desktop status bar reads.
                try {
                    val usage =
                        sendRpcAndAwait(
                            WsMethods.SESSION_USAGE,
                            mapOf("session_id" to rpcSessionId),
                        )
                    val snapshot = parseUsageSnapshot(usage)
                    if (snapshot != null && snapshot.compressions != null) {
                        _uiState.update { it.copy(compressionCount = snapshot.compressions) }
                    }
                } catch (_: Exception) {
                    // Best-effort: keep the last known badge value.
                }
            }
            // Issue #817: single atomic denominator write. The RPC's live
            // context_max wins, REST model/info is the fallback, and a stale
            // value from a pre-swap fetch can never overwrite a fresh one —
            // the meter always shows ONE coherent window.
            _uiState.update { current ->
                val fallbackFull =
                    if (skipRestFallback) current.fullContextTokens else restFull ?: current.fullContextTokens
                current.copy(
                    usedContextTokens = rpcUsed ?: current.usedContextTokens,
                    fullContextTokens = rpcMax ?: fallbackFull,
                )
            }
            // Detail-sheet accounting (cumulative REST counters, informational).
            val usedResult =
                safeApiCall { ApiClient.hermesApi.getSessionDetail(sessionId, profile) }
            if (usedResult is NetworkResult.Success) {
                val d = usedResult.data
                val used = d.input_tokens
                if (used != null) {
                    _uiState.update {
                        it.copy(
                            contextBreakdown =
                                ContextBreakdown(
                                    inputTokens = used,
                                    outputTokens = d.output_tokens ?: 0L,
                                    cacheReadTokens = d.cache_read_tokens ?: 0L,
                                    cacheWriteTokens = d.cache_write_tokens ?: 0L,
                                    reasoningTokens = d.reasoning_tokens ?: 0L,
                                    messageCount = d.message_count ?: 0,
                                ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchServerMessageCount(
        sessionId: String,
        generation: Long,
        requestSequence: Long,
    ): Int {
        val known =
            _uiState.value.sessions
                .find { it.id == sessionId }
                ?.messageCount
        if (known != null) return known
        val result =
            withContext(ioDispatcher) {
                // Backend caps limit at 100 (sessions.py Query le=100) — 500
                // 422'd (seen in device logcat after a branch). Sessions are
                // ordered "recent", so the target is always in the top page.
                safeApiCall { ApiClient.hermesApi.getSessions(limit = 100, offset = 0, order = "recent") }
            }
        if (result is NetworkResult.Success) {
            val sessions = result.data.sessions.orEmpty()
            val count = sessions.find { it.id == sessionId }?.message_count
            if (count != null) {
                _uiState.update { current ->
                    if (isCurrentHydration(sessionId, generation, requestSequence)) {
                        current.copy(
                            sessions =
                                current.sessions.map {
                                    if (it.id == sessionId) {
                                        it.copy(messageCount = count)
                                    } else {
                                        it
                                    }
                                },
                        )
                    } else {
                        current
                    }
                }
                return count
            }
        }
        return known
            ?: if (isCurrentHydration(sessionId, generation, requestSequence)) {
                _uiState.value.messages.size
            } else {
                0
            }
    }

    private suspend fun fetchMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String? = null,
    ) = withContext(ioDispatcher) {
        safeApiCall {
            ApiClient.hermesApi.getSessionMessages(
                sessionId = sessionId,
                limit = limit,
                offset = offset,
                includeCompacted = true,
                order = order,
            )
        }
    }

    // ── UI actions ───────────────────────────────────────────────────────

    /**
     * Dismiss the active clarify prompt and reject it (tell the agent no answer
     * was given).
     *
     * The backend's clarify tool blocks the agent thread waiting for a response
     * (CLI timeout is 120s). A silent dismiss would leave the agent hanging
     * until that timeout, so we send a cancel sentinel
     * ([CLARIFY_DISMISS_RESPONSE]) over `clarify.respond` to unblock it.
     *
     * This is a *reject*, not an instruction to proceed — the agent is told no
     * answer was provided and should re-ask or back off, NOT charge ahead.
     *
     * Unlike [respondToClarify] we do NOT append a user chat bubble: a dismiss
     * is not something the user typed, so faking a USER message would be
     * dishonest. We instead surface a short SYSTEM note so the dismissal is
     * visible in the transcript.
     */
    fun dismissClarify() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val clarifyId = _uiState.value.clarifyRequest?.clarifyId
        _uiState.update { it.copy(clarifyRequest = null) }

        addSystemMessage("Clarify dismissed — no answer sent", persist = true)

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "response" to CLARIFY_DISMISS_RESPONSE,
                    "answer" to CLARIFY_DISMISS_RESPONSE,
                )
            if (clarifyId != null) {
                params["clarify_id"] = clarifyId
                params["request_id"] = clarifyId
            }
            wsClient.send(
                method = WsMethods.CLARIFY_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
            )
        }
    }

    fun respondToClarify(option: String) {
        val sessionId = _uiState.value.currentSessionId ?: return
        val clarifyId = _uiState.value.clarifyRequest?.clarifyId
        _uiState.update { it.copy(clarifyRequest = null) }

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = option,
            )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        viewModelScope.launch(ioDispatcher) {
            repo.persistMessage(userMessage, sessionId)
        }

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "response" to option,
                    "answer" to option,
                )
            if (clarifyId != null) {
                params["clarify_id"] = clarifyId
                params["request_id"] = clarifyId
            }
            wsClient.send(
                method = WsMethods.CLARIFY_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearBackgroundComplete() {
        _uiState.update { it.copy(backgroundCompleteMessage = null) }
    }

    /** Consume the /resume · /history navigation request (issue #864). */
    fun consumeOpenHistoryRequest() {
        _uiState.update { it.copy(openHistoryRequested = false) }
    }

    // ── Approval flow ───────────────────────────────────────────────────

    private fun handleApprovalRequest(event: WsEvent.ApprovalRequest) {
        val description = event.description ?: event.command ?: "Unknown command"
        val content = "**Approval Required**\n$description"
        val msg =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = content,
                approvalInfo =
                    ApprovalInfo(
                        command = event.command,
                        description = event.description,
                        patternKeys = event.patternKeys,
                    ),
            )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            )
        }
    }

    fun respondToApproval(action: String) {
        val state = _uiState.value
        val approvalMsg = state.messages.lastOrNull { it.approvalInfo != null } ?: return
        val sessionId = state.currentSessionId ?: return

        // Clear buttons immediately
        _uiState.update { s ->
            s.copy(
                messages =
                    s.messages.map {
                        if (it.id == approvalMsg.id) {
                            it.copy(approvalInfo = null)
                        } else {
                            it
                        }
                    },
            )
        }

        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                method = WsMethods.APPROVAL_RESPOND,
                params =
                    mapOf(
                        "session_id" to sessionId,
                        "choice" to action,
                        "all" to false,
                    ),
                onSent = { id -> trackRequest(id, WsMethods.APPROVAL_RESPOND) },
            )
        }
    }

    // ── Sudo / secret prompt flow (issue #524) ──────────────────────────

    /**
     * The agent needs the user's sudo password. Previously dropped → agent
     * hung forever. Now we surface a secure dialog and reply via sudo.respond.
     */
    private fun handleSudoRequest(event: WsEvent.SudoRequest) {
        _uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(event.requestId, event.sessionId),
                isAgentTyping = false,
            )
        }
    }

    /**
     * The agent needs a secret value (token/password). Previously dropped →
     * agent hung forever. Now we surface a secure dialog and reply via
     * secret.respond.
     */
    private fun handleSecretRequest(event: WsEvent.SecretRequest) {
        _uiState.update {
            it.copy(
                secretPrompt = SecretPromptUi(event.requestId, event.sessionId),
                isAgentTyping = false,
            )
        }
    }

    fun dismissSudo() {
        _uiState.update { it.copy(sudoPrompt = null) }
    }

    fun dismissSecret() {
        _uiState.update { it.copy(secretPrompt = null) }
    }

    /**
     * Send the user's sudo password back to the gateway. Mirrors
     * respondToApproval: clear the prompt immediately, then fire the RPC.
     */
    fun respondToSudo(password: String) {
        val prompt = _uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId ?: return
        if (password.isBlank()) return

        _uiState.update { it.copy(sudoPrompt = null) }

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to password,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SUDO_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SUDO_RESPOND) },
            )
        }
    }

    /**
     * Send the user's secret value back to the gateway. Mirrors respondToSudo.
     */
    fun respondToSecret(value: String) {
        val prompt = _uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: _uiState.value.currentSessionId ?: return
        if (value.isBlank()) return

        _uiState.update { it.copy(secretPrompt = null) }

        viewModelScope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to value,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SECRET_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SECRET_RESPOND) },
            )
        }
    }

    fun reconnect() {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.rejectAllPending()
            wsClient.disconnect()
        }
        viewModelScope.launch {
            delay(500)
            connectWebSocket(setLoading = true)
        }
    }

    fun relogin(
        username: String,
        password: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val endpoint = AuthManager.endpointForBuild()
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody =
                JSONObject()
                    .put("provider", "basic")
                    .put("username", username)
                    .put("password", password)
                    .put("next", "")
                    .toString()

            try {
                val loginClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.probe
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val loginReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("auth/password-login").toString())
                        .header("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()
                loginClient.newCall(loginReq).execute().use { loginResp ->
                    if (!loginResp.isSuccessful) {
                        val msg =
                            when (loginResp.code) {
                                401 -> "Invalid username or password (401)"
                                403 -> "Forbidden (403)"
                                else -> "HTTP error code: ${loginResp.code}"
                            }
                        withContext(Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }

                val ticketClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.base
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val ticketReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("api/auth/ws-ticket").toString())
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()
                ticketClient.newCall(ticketReq).execute().use { ticketResp ->
                    if (!ticketResp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Failed to mint WS ticket: HTTP ${ticketResp.code}")
                        }
                        return@launch
                    }

                    val body = ticketResp.body.string()
                    val ticket = JSONObject(body).optString("ticket").takeIf { it.isNotBlank() }

                    if (ticket.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Invalid ticket returned from server")
                        }
                        return@launch
                    }

                    AuthManager.setWsAuthParam("ticket")
                    AuthManager.setToken(ticket)

                    withContext(Dispatchers.Main) {
                        onResult(true, null)
                        reconnect()
                    }
                }
            } catch (e: java.io.IOException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            } catch (e: org.json.JSONException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun addSystemMessage(
        text: String,
        persist: Boolean = false,
    ) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        if (persist && sessionId != null) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(msg, sessionId)
            }
        }
    }

    // ── Pending request tracking ─────────────────────────────────────────

    private fun trackRequest(
        id: String,
        method: String,
    ) {
        idToMethod[id] = method
    }

    private fun trackSessionRequest(
        id: String,
        method: String,
        generation: Long,
        resumeSequence: Long = 0L,
        sessionId: String? = null,
    ) {
        sessionRequestById[id] = SessionRequest(generation, resumeSequence, sessionId)
        trackRequest(id, method)
    }

    private fun isCurrentSessionRequest(
        sessionId: String,
        generation: Long,
    ): Boolean = generation == sessionGeneration && sessionId == _uiState.value.currentSessionId

    private fun isCurrentHydration(
        sessionId: String,
        generation: Long,
        requestSequence: Long,
    ): Boolean = requestSequence == activeHydrationRequestSequence && isCurrentSessionRequest(sessionId, generation)

    private fun isStaleSessionRequest(id: String): Boolean =
        sessionRequestById[id]?.let(::isStaleSessionRequest) == true

    private fun isStaleSessionRequest(request: SessionRequest): Boolean =
        request.generation != sessionGeneration ||
            (request.sessionId != null && request.sessionId != _uiState.value.currentSessionId) ||
            (request.resumeSequence != 0L && request.resumeSequence != activeResumeRequestSequence)

    private fun forgetRequest(id: String) {
        idToMethod.remove(id)
        sessionRequestById.remove(id)
    }

    // ── Search ────────────────────────────────────────────────────────────
    // Compatibility façade: stable public API around ChatSearchDelegate.
    // These thin delegates keep ChatViewModel's public surface intact while
    // the search logic now lives in the delegate. Safe to remove once all
    // callers migrate directly to the delegate.

    fun toggleSearch() = searchDelegate.toggleSearch()

    fun setSearchQuery(query: String) = searchDelegate.setSearchQuery(query)

    fun navigateSearchMatch(direction: Int) = searchDelegate.navigateSearchMatch(direction)

    fun clearSearch() = searchDelegate.clearSearch()

    private var isTestEnv: Boolean? = null

    private fun isTestEnvironment(): Boolean {
        if (isTestEnv == null) {
            isTestEnv =
                try {
                    Class.forName("org.junit.Test")
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
        }
        return isTestEnv == true
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // PERF-16: Don't disconnect the global HermesWsClient singleton when
        // leaving the Chat screen — it's used by background notification reply.
    }

    companion object {
        /** Max auto-retry attempts for a failed session.resume (desktop parity). */
        const val MAX_RESUME_RETRIES = 4

        /** Base backoff for resume retries — doubles per attempt, capped at 8s. */
        const val RESUME_RETRY_BASE_MS = 1_000L

        /** Upper bound for the resume retry backoff delay. */
        const val RESUME_RETRY_MAX_MS = 8_000L
    }
}
