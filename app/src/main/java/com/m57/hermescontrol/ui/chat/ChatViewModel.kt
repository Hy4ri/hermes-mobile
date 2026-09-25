package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.SlashUsageStore
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.SessionTimelineEntry
import com.m57.hermescontrol.data.model.UsageSnapshotResponse
import com.m57.hermescontrol.data.model.parseContextBreakdown
import com.m57.hermescontrol.data.model.parseUsageSnapshot
import com.m57.hermescontrol.data.model.reasoningSupport
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.repository.VoiceNoteRepository
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionOperationParser
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toAny
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.notification.captureTurnBoundary
import com.m57.hermescontrol.notification.correlationScopeId
import com.m57.hermescontrol.ui.common.ActionProgressController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"
private const val MESSAGE_PAGE_SIZE = 150
private const val TIMELINE_PAGE_SIZE = 500
private const val HISTORY_WINDOW_SIZE = 120
private const val VOICE_NOTE_OFFLINE_MESSAGE = "Voice note not sent — not connected"
private const val VOICE_NOTE_EMPTY_MESSAGE = "No speech detected in the voice note"
private const val VOICE_NOTE_FAILED_MESSAGE = "Voice note transcription failed"
private const val VOICE_NOTE_UNSENT_MESSAGE =
    "Voice note not sent — transcript kept in the input field"

private val REASONING_EFFORT_LEVELS =
    setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

private data class ReasoningSlashArgs(
    val value: String,
    val scopeName: String?,
)

/** Mirrors Desktop's reasoningSlashParams(): flags may appear in any order. */
private fun parseReasoningSlashArgs(arg: String): ReasoningSlashArgs? {
    var scopeName: String? = null
    val values = mutableListOf<String>()

    arg
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .forEach { part ->
            when (part.lowercase()) {
                "--global", "-g", "global" -> scopeName = "global"
                "--session", "-s", "session" -> scopeName = "session"
                else -> values += part
            }
        }

    if (values.isEmpty()) return null
    return ReasoningSlashArgs(values.joinToString(" "), scopeName)
}

private fun rpcResultMap(result: Any?): Map<*, *>? =
    when (result) {
        is JsonElement -> result.toAny() as? Map<*, *>
        is Map<*, *> -> result
        else -> null
    }

private data class PreparedAttachment(
    val attachment: Attachment,
    val encodedFile: File,
)

private class QueuedAttachmentTooLargeException(
    val attachment: Attachment,
) : Exception("Attachment too large: ${attachment.name}")

private sealed interface PrepareAttachmentResult {
    data class Success(
        val prepared: PreparedAttachment,
    ) : PrepareAttachmentResult

    data object TooLarge : PrepareAttachmentResult

    data object Unreadable : PrepareAttachmentResult
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Hermes",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    val isMainTurnBusy: Boolean = false,
    val isSending: Boolean = false,
    val busySendMode: BusySendMode = BusySendMode.CORRECT,
    val pendingSends: List<PendingSend> = emptyList(),
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    /** True while a recorded voice note uploads for server-side transcription. */
    val isTranscribingVoiceNote: Boolean = false,
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
    /** Persistent until dismissed/new turn; distinct from one-shot RPC/network snackbars. */
    val replyFailure: ReplyFailure? = null,
    /** Identity of the single non-durable failed assistant projection for this session lifecycle. */
    val replyFailureProjection: ReplyFailureProjection? = null,
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
    // Credential vault prompts — interactive prompt cards (issue #1090)
    val vaultUnlockPrompt: VaultUnlockPromptUi? = null,
    val vaultSaveLoginPrompt: VaultSaveLoginPromptUi? = null,
    val vaultCodePrompt: VaultCodePromptUi? = null,
    val showSessionPicker: Boolean = false,
    // /update confirm dialog (issue #862) — the command is handled client-side
    val updateConfirmOpen: Boolean = false,
    // Search state lives in ChatSearchState (searchDelegate.searchState) — a
    // snapshot-backed holder, so search updates don't recompose the whole UI.
    // Cached settings
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    val messageStatsEnabled: Boolean = false,
    val showUserMessageTokens: Boolean = true,
    val showAssistantMessageTokens: Boolean = true,
    val showTokensPerSecond: Boolean = true,
    val showModelProvider: Boolean = false,
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
    val modelSwitchConfirmMessage: String? = null,
    // Current session's active model label (provider/model), shown in the chip
    val currentSessionModel: String? = null,
    // Per-model reasoning capabilities for the current session's model
    // (issue #946). Null when unknown — UI offers full scale.
    val currentModelCapabilities: ModelCapabilities? = null,
    // Reasoning effort level for the current session
    val reasoningLevel: String? = null,
    // Gateway-reported effective effort on the active route (clamped level, e.g. "max" when requested was "ultra")
    val reasoningWireLevel: String? = null,
    // In-flight reasoning effort currently applying via config.set
    val pendingReasoningLevel: String? = null,
    // Fast mode / Priority processing state for the current session
    val fastMode: Boolean = false,
    val isFastModeChanging: Boolean = false,
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
    /** Rolling output tokens/sec over the last ~10 calls. */
    val latestTps: Double? = null,
    /** Latest cumulative backend usage snapshot for the current session. */
    val sessionUsage: UsageSnapshotResponse? = null,
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
    /** One-shot composer recovery after an attachment is rejected before send. */
    val composerTextToRestore: String? = null,
    // Reaction animation — set when a reaction WS event arrives, auto-clears
    val reactionKind: String? = null,
    /** Monotonic trigger ID so consecutive same-kind reactions re-animate. */
    val reactionTriggerId: Long = 0L,
    /** Side-question state for /btw (issue #1015). */
    val btwState: BtwUiState? = null,
    /** Subagent delegation indicators (issue #538) — transient UI state. */
    val subagentIndicators: List<SubagentIndicator> = emptyList(),
    /** Currently inspected subagent ID for live transcript tail (issue #1089). */
    val inspectingSubagentId: String? = null,
    /** Transient live transcript tail state for the inspected subagent (issue #1089). */
    val subagentTranscript: SubagentTranscriptUiState? = null,
    /** Agent todo / plan items (issue #736). */
    val todos: List<TodoItem> = emptyList(),
    // Session resume recovery (desktop parity: bounded auto-retry + error UI)
    val isSessionReady: Boolean = false,
    val resumeError: String? = null,
    val isResumeRetrying: Boolean = false,
    /** Text staged to prefill the composer (e.g. from /undo). */
    val pendingPrefillText: String? = null,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED

    /**
     * True only while a generation can actually be interrupted: typing can
     * start during session preparation, before any runtime session exists,
     * and `session.interrupt` has nothing to stop then. The composer derives
     * its Stop affordance from this instead of [isAgentTyping] alone
     * (review, PR #1250).
     */
    val canInterrupt: Boolean get() = isAgentTyping && isSessionReady
}

data class ChatTimelineState(
    val isOpen: Boolean = false,
    val entries: List<SessionTimelineEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: Int? = null,
    val errorMessage: String? = null,
    val jumpingRowId: Int? = null,
    val windowErrorMessage: String? = null,
    val historyMessages: List<ChatMessage>? = null,
    val historyAnchorRowId: Int? = null,
    val historyHasOlder: Boolean = false,
    val historyHasNewer: Boolean = false,
) {
    val isHistorical: Boolean get() = historyMessages != null
}

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
    val parentSessionId: String? = null,
    val depth: Int = 0,
)

data class ClarifyQuestionUi(
    val qid: String = "q0",
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

data class ClarifyUi(
    val text: String,
    val options: List<String> = emptyList(),
    val clarifyId: String? = null,
    val questionId: String? = null,
    val multiSelect: Boolean = false,
    val questions: List<ClarifyQuestionUi> = emptyList(),
    val serverRequestId: String? = null,
    val lockedAnswers: Map<String, String> = emptyMap(),
) {
    /**
     * Normalized list of questions to display. Guarantees at least one question
     * entry even for legacy single-question clarify events.
     */
    val resolvedQuestions: List<ClarifyQuestionUi>
        get() =
            if (questions.isNotEmpty()) {
                questions
            } else {
                listOf(
                    ClarifyQuestionUi(
                        qid = questionId ?: "q0",
                        question = text,
                        choices = options,
                        multiSelect = multiSelect,
                    ),
                )
            }
}

/**
 * State for the context-aware side-question bottom sheet (issue #1015, `/btw`).
 */
data class BtwUiState(
    val taskId: String? = null,
    val question: String = "",
    val answer: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
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
    val serverRequestId: String? = null,
)

/** Transient — not persisted. Holds a pending secret (token/password) request. */
data class SecretPromptUi(
    val requestId: String?,
    val sessionId: String?,
    val envVar: String? = null,
    val prompt: String? = null,
    val serverRequestId: String? = null,
)

/** Transient — not persisted. Holds a pending vault unlock request (issue #1090). */
data class VaultUnlockPromptUi(
    val requestId: String?,
    val sessionId: String?,
    val backend: String? = null,
    val displayName: String? = null,
    val serverRequestId: String? = null,
)

/** Transient — not persisted. Holds a pending vault save login request (issue #1090). */
data class VaultSaveLoginPromptUi(
    val requestId: String?,
    val sessionId: String?,
    val origin: String? = null,
    val site: String? = null,
    val serverRequestId: String? = null,
)

/** Transient — not persisted. Holds a pending vault 2FA/MFA code request (issue #1090). */
data class VaultCodePromptUi(
    val requestId: String?,
    val sessionId: String?,
    val site: String? = null,
    val hint: String? = null,
    val serverRequestId: String? = null,
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
        ChatPersistenceRepository {
            HermesDatabase.get(application).chatMessageDao()
        },
    slashUsageStore: SlashUsageStore = SlashUsageStore(application.applicationContext),
    searchDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val historyDispatcher: kotlinx.coroutines.CoroutineDispatcher = searchDispatcher,
    private val sendStore: ChatSendStore = ChatSendStore(application),
    private val voiceNoteRepository: VoiceNoteRepository = VoiceNoteRepository(),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    private val connectionOperationDelegate =
        ChatConnectionOperationDelegate { method, params ->
            HermesWsClient.request(method, params).await()
        }

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())
    val connectionOperationState: StateFlow<ConnectionOperationUiState> = connectionOperationDelegate.state

    private val _streamingState = MutableStateFlow(StreamingState())

    /** Maps an in-flight RPC id to its method for UI error labeling. */
    private val idToMethod = ConcurrentHashMap<String, String>()

    private data class SessionRequest(
        val generation: Long,
        val resumeSequence: Long = 0L,
        val sessionId: String? = null,
        val connectionCheckpoint: ConnectionResumeCheckpoint? = null,
    )

    private data class SendOwner(
        val scope: String,
        val storageSessionId: String,
        val agentSessionId: String,
        val generation: Long,
    )

    private data class HardInterruptRequest(
        val messageId: String,
        val mainTurnEpoch: Long,
        val owner: SendOwner,
    )

    private data class PendingSendReservation(
        val pending: PendingSend,
        val userMessage: ChatMessage,
        val wasStreaming: Boolean,
        val storageSessionId: String,
        val agentSessionId: String,
        val scope: String,
        val generation: Long,
        val mainTurnEpoch: Long,
        val originalPending: PendingSend? = null,
    )

    private val sessionRequestById = ConcurrentHashMap<String, SessionRequest>()
    private val outgoingRequestById = ConcurrentHashMap<String, String>()
    private val hardInterruptRequestById = ConcurrentHashMap<String, HardInterruptRequest>()
    private val queuedStagingIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingSendReservationLock = Any()
    private val pendingSendReservations = mutableListOf<PendingSendReservation>()
    private var pendingSendReservationJob: Job? = null
    private var queueDrainJob: Job? = null
    private var mainTurnBusy = false
        set(busy) {
            field = busy
            _uiState.update { state ->
                if (state.isMainTurnBusy == busy) state else state.copy(isMainTurnBusy = busy)
            }
        }

    private var lastMainCompletionAt = 0L
    private var lastSubmissionKey: String? = null
    private var lastSubmissionAt = 0L
    private var sessionGeneration = 0L
    private var mainTurnEpoch = 0L
    private var lastPendingSendCreatedAt = 0L

    private var resumeRequestSequence = 0L
    private var activeResumeRequestSequence = 0L
    private var hydrationRequestSequence = 0L
    private var activeHydrationRequestSequence = 0L
    private var resumedGeneration = -1L
    private var hydratedGeneration = -1L

    /** Runtime TUI session returned by session.resume; Desktop storage keeps the original ID. */
    private var runtimeSessionId: String? = null

    /** Issue #969: Prompt staged before session.create resolves. */
    private data class PendingPrompt(
        val text: String,
        val attachments: List<Attachment>,
        val wasStreaming: Boolean,
        val userMessage: ChatMessage,
        val createdAt: Long,
        val mainTurnEpoch: Long,
    )

    private var pendingInitialPrompt: PendingPrompt? = null

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
    private var cacheCursor: ChatPersistenceRepository.Cursor? = null
    private var cacheHasOlder = false
    private var cacheLoaded = false
    private var serverHasOlder = false
    private var cacheJob: Job? = null
    private var hydrationJob: Job? = null
    private var olderJob: Job? = null
    private var syncJob: Job? = null
    private val historyFetchMutex = Mutex()
    private var timelineJob: Job? = null
    private var historyWindowJob: Job? = null
    private var timelineRequestSequence = 0L
    private var activeTimelineRequestSequence = 0L
    private var historyWindowRequestSequence = 0L
    private var activeHistoryWindowRequestSequence = 0L
    private val _timelineState = MutableStateFlow(ChatTimelineState())

    // ── Session resume recovery (desktop parity) ────────────────────────
    // Bounded auto-retry with exponential backoff, mirroring the desktop's
    // use-route-resume: a failed session.resume retries 1s→2s→4s→8s up to
    // MAX_RESUME_RETRIES, then surfaces an explicit error + manual Retry
    // instead of latching the spinner forever.
    private var resumeRetrySessionId: String? = null
    private var resumeRetryAttempt = 0
    private var resumeRetryJob: Job? = null
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()
    val timelineState: StateFlow<ChatTimelineState> = _timelineState.asStateFlow()

    /** Tracks the auto-clear coroutine for reaction animations. */
    private var reactionClearJob: Job? = null

    /** Last confirmed model label pushed by SessionInfo or SessionResume from backend (issue #1103). */
    private var lastConfirmedSessionModel: String? = null

    /** Model generation counter to prevent stale context writes across switches (issue #1103). */
    private var modelGeneration: Long = 0L

    /** Request sequence counter for fetchContextUsage calls. */
    private var contextFetchSequence: Long = 0L

    /** Tracks the latest context-usage poll or refetch job. */
    private var contextUsageJob: Job? = null

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

    private val reloginAuthenticator =
        com.m57.hermescontrol.data.remote.ChatReloginAuthenticator(
            ioDispatcher = ioDispatcher,
            mainDispatcher = Dispatchers.Main,
        )

    private val modelSwitchDelegate =
        ChatModelSwitchDelegate(
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            uiState = _uiState,
            runtimeSessionId = { runtimeSessionId },
            wsSend = { method, params, onSent -> wsClient.send(method, params, onSent) },
            trackRequest = { id, method -> trackRequest(id, method) },
            addAssistantMessage = { text -> addAssistantMessage(text) },
            handleSlashCommand = { cmd -> handleSlashCommand(cmd) },
            fetchContextUsage = { fetchContextUsage() },
            onModelSwitchInitiated = { onModelSwitchInitiated() },
            wsRequest = { method, params -> wsClient.request(method, params).await() },
        ).apply {
            attachScopeObserver(viewModelScope)
        }

    private val credentialPromptsDelegate =
        ChatCredentialPromptsDelegate(
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            uiState = _uiState,
            wsSend = { method, params, onSent -> wsClient.send(method, params, onSent) },
            trackRequest = { id, method -> trackRequest(id, method) },
            respondToServerRequest = { id, result -> wsClient.respondToServerRequest(id, result) },
        )

    private val approvalsDelegate =
        ChatApprovalsDelegate(
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            uiState = _uiState,
            runtimeSessionId = { runtimeSessionId },
            wsSend = { method, params, onSent -> wsClient.send(method, params, onSent) },
            trackRequest = { id, method -> trackRequest(id, method) },
            addSystemMessage = { text -> addSystemMessage(text) },
            respondToServerRequest = { id, result -> wsClient.respondToServerRequest(id, result) },
        )

    private val clarifyDelegate =
        ChatClarifyDelegate(
            uiState = _uiState,
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            persistMessage = { msg, sid -> repo.persistMessage(msg, sid) },
            wsClient = wsClient,
            trackRequest = { id, method -> trackRequest(id, method) },
            respondToServerRequest = { id, result -> wsClient.respondToServerRequest(id, result) },
        )

    private val subagentsDelegate =
        ChatSubagentsDelegate(
            uiState = _uiState,
            scope = viewModelScope,
            ioDispatcher = ioDispatcher,
            runtimeSessionId = { runtimeSessionId ?: _uiState.value.currentSessionId },
        )

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
                    mainTurnBusy = false
                    // A gateway acceptance is not a durable REST acknowledgement. Recover even
                    // receipts whose RPC correlation was already removed, without resending them.
                    val acceptedIds =
                        sendStore
                            .all()
                            .filter {
                                it.scope == sendScope() && it.state == PendingSendState.ACCEPTED
                            }.map { it.id }
                    (outgoingRequestById.values + hardInterruptRequestById.values.map { it.messageId } + acceptedIds)
                        .distinct()
                        .forEach { messageId ->
                            if (sendStore.all().any {
                                    it.id == messageId &&
                                        (it.state == PendingSendState.SENDING || it.state == PendingSendState.ACCEPTED)
                                }
                            ) {
                                markPendingSend(messageId, PendingSendState.UNKNOWN)
                                removeUnconfirmedBubble(messageId)
                            }
                        }
                    _uiState.update { it.copy(isLoading = false) }
                    subagentsDelegate.closeSubagentTranscript()
                    // The runtime session id is only valid while the socket
                    // owns it — a dropped connection may mean the gateway
                    // closed/pruned the session (or restarted, wiping the
                    // runtime registry). Clear it so session-scoped RPCs can't
                    // fire with a stale id and 4001 "session not found";
                    // handleGatewayReady rebinds it on the re-resume.
                    runtimeSessionId = null
                    resumedGeneration = -1L
                    hydratedGeneration = -1L
                    activeResumeRequestSequence = ++resumeRequestSequence
                    activeHydrationRequestSequence = ++hydrationRequestSequence
                    // Preserve metadata so late results and errors are rejected as stale.
                    sessionGeneration++
                    queueDrainJob?.cancel()
                    queueDrainJob = null
                    // #129: invalidate and cancel paged work together, including its busy flags.
                    hydrationJob?.cancel()
                    olderJob?.cancel()
                    syncJob?.cancel()
                    cacheJob?.cancel()
                    isSyncingMessages = false
                    resetTimelineState()
                    cancelResumeRetry()
                    _uiState.update {
                        it.copy(isSessionReady = false, isLoadingOlder = false, isResumeRetrying = false)
                    }
                    _uiState.value.currentSessionId?.let { sessionId ->
                        if (!cacheLoaded) loadCachedMessages(sessionId, sessionGeneration)
                    }
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
                    pendingGoneSessionNotice = false
                    sessionHasServerPresence = false
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
                    pendingGoneSessionNotice = false
                    sessionHasServerPresence = false
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
        modelSwitchDelegate.preloadModelOptions()
        val currentId = _uiState.value.currentSessionId
        if (currentId != null) {
            if (sessionHasServerPresence) {
                resumeSession(currentId, sessionGeneration)
            } else {
                // Issue #969: No server-side row yet — the gateway dropped the
                // ephemeral unpersisted session when the old socket closed.
                // Re-create the session on the new socket so runtimeSessionId is
                // refreshed and ready for prompts.
                createNewSession(setLoading = false)
            }
        } else {
            val initial = initialSessionId
            if (!initial.isNullOrBlank()) {
                initialSessionId = null
                switchSession(initial)
            } else if (AuthManager.isRestoreLastSession()) {
                val restoredId = AuthManager.getLastOpenedSessionId()
                if (!restoredId.isNullOrBlank()) {
                    switchSession(restoredId)
                } else {
                    createNewSession(setLoading = false)
                }
            } else {
                createNewSession(setLoading = false)
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        // Filter explicitly scoped terminal/start events before buffer resets and reduction.
        // Legacy unscoped failures are ambiguous across sessions, so fail closed: gateways
        // must include session_id for failed replies to be surfaced by this client.
        val turnSessionId =
            when (event) {
                is WsEvent.MessageStart -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                else -> null
            }
        if (turnSessionId != null && !isCurrentSession(turnSessionId)) return
        if (event is WsEvent.MessageComplete && event.rawPayload?.get("status") == "error" &&
            event.sessionId == null
        ) {
            return
        }

        // RpcError is reduced before ViewModel request handling. Drop stale
        // session errors here so the shared reducer cannot clear loading or
        // surface an error for a newly selected session.
        if (event is WsEvent.RpcError && isStaleSessionRequest(event.id)) {
            outgoingRequestById.remove(event.id)?.let { markPendingSend(it, PendingSendState.UNKNOWN) }
            hardInterruptRequestById.remove(event.id)?.let { markPendingSend(it.messageId, PendingSendState.UNKNOWN) }
            forgetRequest(event.id)
            return
        }

        if (event is WsEvent.ConnectionRequest) {
            if (!isCurrentSession(event.snapshot.sessionId)) return
            connectionOperationDelegate.acceptRequest(event.snapshot)
        } else if (event is WsEvent.ConnectionUpdate) {
            if (!isCurrentSession(event.snapshot.sessionId)) return
            connectionOperationDelegate.acceptUpdate(event.snapshot)
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

            else -> {}
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
        dispatchReducerEffects(result.effects)

        // Handle complex events that need ViewModel-specific context
        when (event) {
            is WsEvent.GatewayReady -> {
                handleGatewayReady()
            }

            is WsEvent.SessionInfo -> {
                val current = runtimeSessionId ?: _uiState.value.currentSessionId
                // Ignore session.info events clearly tagged for a different session
                if (event.sessionId == null || current == null || event.sessionId == current) {
                    handleSessionInfo(event.data)
                }
            }

            is WsEvent.MessageToken -> {
                if (isCurrentSession(event.sessionId)) mainTurnBusy = true
                streamingController.handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                if (isCurrentSession(event.sessionId)) mainTurnBusy = true
                streamingController.handleThinkingDelta(event)
            }

            is WsEvent.ReasoningDelta -> {
                if (isCurrentSession(event.sessionId)) mainTurnBusy = true
                streamingController.handleReasoningDelta(event)
            }

            is WsEvent.MessageStart -> {
                if (isCurrentSession(event.sessionId)) {
                    mainTurnEpoch++
                    mainTurnBusy = true
                }
                // The server accepted a prompt for this session — its DB row
                // now exists (created lazily at prompt.submit), so a reconnect
                // resume will succeed.
                sessionHasServerPresence = true
                streamingController.beginStreamingMessage()
            }

            is WsEvent.MessageComplete -> {
                if (isCurrentSession(event.sessionId)) {
                    mainTurnBusy = false
                    lastMainCompletionAt = System.currentTimeMillis()
                    refreshSendReceipts()
                }
                // Buffers cleared before reduce; ViewModel resets them after
                streamingController.resetStreaming()
            }

            is WsEvent.MessageDone -> {
                if (isCurrentSession(event.sessionId)) {
                    mainTurnBusy = false
                    lastMainCompletionAt = System.currentTimeMillis()
                    refreshSendReceipts()
                }
                streamingController.resetStreaming()
            }

            is WsEvent.ToolStart -> {
                if (isCurrentSession(event.sessionId)) mainTurnBusy = true
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

            is WsEvent.TranscriptResyncRequired -> {
                val current = runtimeSessionId ?: _uiState.value.currentSessionId
                if (current != null && (current == event.sessionId || event.sessionId.isEmpty())) {
                    val storageId = ActiveSessionHolder.resolveStoredSessionId(current) ?: current
                    loadSessionMessages(storageId, sessionGeneration)
                }
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

            is WsEvent.ServerRequest -> {
                handleServerRequest(event)
            }

            is WsEvent.ServerRequestCancelled -> {
                handleServerRequestCancelled(event)
            }

            is WsEvent.ApprovalRequest -> {
                approvalsDelegate.handleApprovalRequest(event)
            }

            is WsEvent.SudoRequest -> {
                credentialPromptsDelegate.handleSudoRequest(event)
            }

            is WsEvent.SudoExpire -> {
                credentialPromptsDelegate.handleSudoExpire(event)
            }

            is WsEvent.SecretRequest -> {
                credentialPromptsDelegate.handleSecretRequest(event)
            }

            is WsEvent.SecretExpire -> {
                credentialPromptsDelegate.handleSecretExpire(event)
            }

            is WsEvent.VaultUnlockRequest -> {
                credentialPromptsDelegate.handleVaultUnlockRequest(event)
            }

            is WsEvent.VaultUnlockExpire -> {
                credentialPromptsDelegate.handleVaultUnlockExpire(event)
            }

            is WsEvent.VaultSaveLoginRequest -> {
                credentialPromptsDelegate.handleVaultSaveLoginRequest(event)
            }

            is WsEvent.VaultSaveLoginExpire -> {
                credentialPromptsDelegate.handleVaultSaveLoginExpire(event)
            }

            is WsEvent.VaultCodeRequest -> {
                credentialPromptsDelegate.handleVaultCodeRequest(event)
            }

            is WsEvent.VaultCodeExpire -> {
                credentialPromptsDelegate.handleVaultCodeExpire(event)
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

    /** Route the generic transport event into the existing prompt delegates. */
    private fun handleServerRequest(request: WsEvent.ServerRequest) {
        val params = request.params
        val sessionId = params["session_id"] as? String
        when (request.method) {
            "clarify" -> {
                @Suppress("UNCHECKED_CAST")
                val rawQuestions = params["questions"] as? List<*>

                @Suppress("UNCHECKED_CAST")
                val lockedAnswers =
                    (params["answers"] as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) ->
                            if (key is String && value is String) key to value else null
                        }?.toMap()
                        .orEmpty()
                val questions =
                    rawQuestions.orEmpty().mapIndexedNotNull { index, item ->
                        val map = item as? Map<*, *> ?: return@mapIndexedNotNull null
                        val question = map["question"] as? String ?: return@mapIndexedNotNull null

                        @Suppress("UNCHECKED_CAST")
                        val choices = (map["choices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        WsEvent.ClarifyQuestion(
                            qid = map["qid"] as? String ?: "q$index",
                            question = question,
                            choices = choices,
                            multiSelect = map["multi_select"] as? Boolean ?: false,
                        )
                    }
                val first = questions.firstOrNull()
                handleWsEvent(
                    WsEvent.ClarifyRequest(
                        text = first?.question ?: params["question"] as? String,
                        options = first?.choices ?: (params["choices"] as? List<*>)?.filterIsInstance<String>(),
                        clarifyId = params["clarify_id"] as? String ?: params["request_id"] as? String,
                        sessionId = sessionId,
                        questionId = first?.qid,
                        multiSelect = first?.multiSelect ?: (params["multi_select"] as? Boolean ?: false),
                        questions = questions,
                        serverRequestId = request.id,
                        lockedAnswers = lockedAnswers,
                    ),
                )
            }

            "approval" -> {
                @Suppress("UNCHECKED_CAST")
                val choices = (params["choices"] as? List<*>)?.filterIsInstance<String>()
                handleWsEvent(
                    WsEvent.ApprovalRequest(
                        command = params["command"] as? String,
                        description = params["description"] as? String,
                        patternKeys = (params["pattern_keys"] as? List<*>)?.filterIsInstance<String>(),
                        sessionId = sessionId,
                        requestId = params["request_id"] as? String,
                        serverRequestId = request.id,
                        choices = choices,
                        allowPermanent = params["allow_permanent"] as? Boolean,
                        smartDenied = params["smart_denied"] as? Boolean,
                    ),
                )
            }

            "sudo" -> {
                handleWsEvent(
                    WsEvent.SudoRequest(
                        requestId = params["request_id"] as? String,
                        sessionId = sessionId,
                        serverRequestId = request.id,
                    ),
                )
            }

            "secret" -> {
                handleWsEvent(
                    WsEvent.SecretRequest(
                        requestId = params["request_id"] as? String,
                        sessionId = sessionId,
                        envVar = params["env_var"] as? String,
                        prompt = params["prompt"] as? String,
                        serverRequestId = request.id,
                    ),
                )
            }

            "vault.code" -> {
                handleWsEvent(
                    WsEvent.VaultCodeRequest(
                        requestId = params["request_id"] as? String,
                        sessionId = sessionId,
                        site = params["site"] as? String,
                        hint = params["hint"] as? String,
                        serverRequestId = request.id,
                    ),
                )
            }

            "vault.save_login" -> {
                handleWsEvent(
                    WsEvent.VaultSaveLoginRequest(
                        requestId = params["request_id"] as? String,
                        sessionId = sessionId,
                        origin = params["origin"] as? String,
                        site = params["site"] as? String,
                        serverRequestId = request.id,
                    ),
                )
            }

            "vault.unlock_prompt" -> {
                handleWsEvent(
                    WsEvent.VaultUnlockRequest(
                        requestId = params["request_id"] as? String,
                        sessionId = sessionId,
                        backend = params["backend"] as? String,
                        displayName = params["display_name"] as? String,
                        serverRequestId = request.id,
                    ),
                )
            }

            else -> {
                wsClient.respondToServerRequestError(
                    request.id,
                    -32601,
                    "no handler for server request: ${request.method}",
                )
            }
        }
    }

    private fun handleServerRequestCancelled(event: WsEvent.ServerRequestCancelled) {
        when (event.method) {
            "clarify" -> {
                if (_uiState.value.clarifyRequest?.serverRequestId == event.id) {
                    _uiState.update { it.copy(clarifyRequest = null) }
                    streamingController.resetStreaming()
                }
            }

            "approval" -> {
                approvalsDelegate.cancelServerRequest(event.id)
            }

            "sudo" -> {
                credentialPromptsDelegate.handleSudoExpire(
                    WsEvent.SudoExpire(null, event.sessionId, serverRequestId = event.id),
                )
            }

            "secret" -> {
                credentialPromptsDelegate.handleSecretExpire(
                    WsEvent.SecretExpire(null, event.sessionId, serverRequestId = event.id),
                )
            }

            "vault.code" -> {
                credentialPromptsDelegate.handleVaultCodeExpire(
                    WsEvent.VaultCodeExpire(null, event.sessionId, serverRequestId = event.id),
                )
            }

            "vault.save_login" -> {
                credentialPromptsDelegate.handleVaultSaveLoginExpire(
                    WsEvent.VaultSaveLoginExpire(null, event.sessionId, serverRequestId = event.id),
                )
            }

            "vault.unlock_prompt" -> {
                credentialPromptsDelegate.handleVaultUnlockExpire(
                    WsEvent.VaultUnlockExpire(null, event.sessionId, serverRequestId = event.id),
                )
            }
        }
    }

    // ── Message streaming ────────────────────────────────────────────────

    fun dismissReplyFailure(id: String) {
        _uiState.update { state ->
            if (state.replyFailure?.id == id) state.copy(replyFailure = null) else state
        }
    }

    /** Checks if an incoming WS event belongs to the currently active session. */
    private fun isCurrentSession(eventSessionId: String?): Boolean {
        // If the event has no session ID, process it (legacy compatibility)
        if (eventSessionId == null) return true
        return eventSessionId == runtimeSessionId || eventSessionId == _uiState.value.currentSessionId
    }

    private fun dispatchReducerEffects(effects: List<ReducerEffect>) {
        for (effect in effects) {
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
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSessionInfo(info: Map<String, Any?>?) {
        // session.resume/session.info carries the open operation so a mobile client
        // that missed connection.request can reconstruct the backend-authoritative card.
        val pendingConnection = info?.get("pending_connection") as? Map<String, Any?>
        if (pendingConnection != null) {
            ConnectionOperationParser
                .parse(
                    pendingConnection,
                    runtimeSessionId ?: _uiState.value.currentSessionId,
                )?.let(connectionOperationDelegate::acceptRequest)
        }
        // Session info pushed by backend when config changes
        // (model switch, reasoning level, etc.)
        if (info != null) {
            (info["running"] as? Boolean)?.let { mainTurnBusy = it }
            val model = info["model"] as? String
            val provider = info["provider"] as? String
            val reasoningEffort = info["reasoning_effort"] as? String
            val reasoningEffortWire = info["reasoning_effort_wire"] as? String
            val terminalBackend = info["terminal_backend"] as? String
            val serviceTier = (info["service_tier"] as? String)?.trim()?.lowercase()
            val fastFlag =
                (info["fast"] as? Boolean)
                    ?: (if (serviceTier != null) serviceTier == "priority" else null)
            val newModelLabel =
                if (model != null && provider != null) {
                    "$provider/$model"
                } else {
                    model
                }
            // Issue #817 & #1103: on a REAL model swap the meter's denominator
            // still belongs to the old model until the next fetch.
            // Check against both lastConfirmedSessionModel and optimisticPreviousModel
            // so optimistic model updates in sendSlashModel don't defeat swap detection.
            val optimisticPrevious = modelSwitchDelegate.consumeOptimisticPreviousModel()
            val previousModel =
                lastConfirmedSessionModel
                    ?: optimisticPrevious
                    ?: _uiState.value.currentSessionModel
            val modelSwapped =
                previousModel != null &&
                    newModelLabel != null &&
                    !newModelLabel.equals(previousModel, ignoreCase = true)
            val initialHydration = previousModel == null && newModelLabel != null
            val meterEmpty = _uiState.value.fullContextTokens == null
            lastConfirmedSessionModel = newModelLabel ?: lastConfirmedSessionModel
            if (newModelLabel != null) {
                modelSwitchDelegate.onModelConfirmed(newModelLabel)
            }
            _uiState.update { state ->
                val newEffort =
                    if (reasoningEffort != null) {
                        if (reasoningEffort.isEmpty()) null else reasoningEffort
                    } else {
                        state.reasoningLevel
                    }
                val newWire =
                    if (reasoningEffortWire != null) {
                        if (reasoningEffortWire.isEmpty()) null else reasoningEffortWire
                    } else if (newEffort != state.reasoningLevel || modelSwapped) {
                        null
                    } else {
                        state.reasoningWireLevel
                    }
                state.copy(
                    currentSessionModel = newModelLabel ?: state.currentSessionModel,
                    reasoningLevel = newEffort,
                    reasoningWireLevel = newWire,
                    fastMode = fastFlag ?: state.fastMode,
                    isFastModeChanging = if (fastFlag != null) false else state.isFastModeChanging,
                    terminalBackend = terminalBackend ?: state.terminalBackend,
                    fullContextTokens = if (modelSwapped) null else state.fullContextTokens,
                )
            }
            modelSwitchDelegate.syncCurrentModelCapabilities()
            if (modelSwapped) {
                modelGeneration++
                contextUsageJob?.cancel()
                contextUsageJob = null
                // Issue #817 & #1103: after a swap the REST model/info window is
                // PROFILE-scoped and may describe the old model (e.g. a
                // session-scoped swap) — the meter must not fall back to
                // it. Wait for the RPC's live context_max instead; the
                // chip stays hidden until the real window lands.
                viewModelScope.launch { fetchContextUsage(skipRestFallback = true) }
            } else if ((initialHydration || meterEmpty) && newModelLabel != null &&
                contextUsageJob?.isActive != true
            ) {
                viewModelScope.launch { fetchContextUsage() }
            }
            // Session.info can carry `pending_approval` (reconnect
            // reconciliation) — surface it unless already on screen.
            val pendingApproval = info["pending_approval"] as? Map<*, *>
            if (pendingApproval != null) {
                approvalsDelegate.maybeSurfacePendingApproval(
                    pendingApproval,
                    runtimeSessionId ?: _uiState.value.currentSessionId,
                )
            }
            // Session.info can carry "pending_clarify" the same way — restore
            // the clarify bubble so a question asked while detached stays
            // answerable after leaving and re-entering the chat.
            val pendingClarify = info["pending_clarify"] as? Map<*, *>
            if (pendingClarify != null) {
                surfacePendingClarify(
                    pendingClarify,
                    runtimeSessionId ?: _uiState.value.currentSessionId,
                )
            }
        }
    }

    /**
     * Restore a pending clarify from the "pending_clarify" replay payload that
     * "session.resume" / "session.info" carry while a clarify blocks the turn
     * server-side (gateway _live_session_payload). Without this, leaving and
     * re-entering a chat shows the "waiting for input" status with no way to
     * answer: the live "clarify.request" event only reached whatever client
     * was attached when the agent asked.
     *
     * The payload has the same shape as a live "clarify.request" event (batch
     * "questions" or legacy "question"/"choices", plus the "request_id" the
     * answer must reference), so reuse [EventParser] and feed the typed event
     * through [handleWsEvent] — the exact path a live question takes. Batch
     * replays may also carry locked per-question "answers".
     */
    private fun surfacePendingClarify(
        payload: Map<*, *>,
        sessionId: String?,
    ) {
        val clarifyId = (payload["clarify_id"] ?: payload["request_id"]) as? String
        if (clarifyId != null && _uiState.value.clarifyRequest?.clarifyId == clarifyId) {
            // Already on screen — avoid clobbering in-progress answer state.
            return
        }
        val clarifyPayload: Map<String, Any?> = payload.entries.associate { (k, v) -> k.toString() to v }
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "clarify.request",
                    "session_id" to sessionId,
                    "payload" to clarifyPayload,
                ),
            ) as? WsEvent.ClarifyRequest ?: return
        if (event.questions.isEmpty() && event.text.isNullOrBlank() && event.options.isNullOrEmpty()) {
            return
        }
        // "answers" is replay-only (locked batch answers); mirror it into the
        // event so restored questions render their answered state.
        val lockedAnswers =
            (clarifyPayload["answers"] as? Map<*, *>)
                ?.mapNotNull { (qid, answer) ->
                    val id = qid as? String ?: return@mapNotNull null
                    val text = answer as? String ?: return@mapNotNull null
                    id to text
                }?.toMap()
                ?: emptyMap()
        handleWsEvent(event.copy(lockedAnswers = lockedAnswers))
    }

    // ── RPC response handling ────────────────────────────────────────────

    private fun settleOutgoingResult(
        requestId: String,
        method: String,
        result: Any?,
    ) {
        val messageId = outgoingRequestById.remove(requestId) ?: return
        val status = rpcResultMap(result)?.get("status") as? String
        val accepted =
            when (method) {
                WsMethods.SESSION_REDIRECT -> status == "redirected" || status == "queued"
                WsMethods.SESSION_STEER -> status == "queued" || status == "steered"
                else -> status == "streaming" || status == "queued"
            }
        when {
            accepted -> {
                markPendingSend(messageId, PendingSendState.ACCEPTED)
                if (sendStore.all().any { it.id == messageId && lastMainCompletionAt >= it.createdAt }) {
                    refreshSendReceipts()
                }
            }

            status == "rejected" && method != WsMethods.PROMPT_SUBMIT -> {
                removeUnconfirmedBubble(messageId)
                sendStore.update(messageId) { it.copy(mode = BusySendMode.QUEUE, state = PendingSendState.QUEUED) }
                publishPendingSends()
                drainPendingQueue()
            }

            status == "rejected" -> {
                markPendingSend(messageId, PendingSendState.REJECTED)
                removeUnconfirmedBubble(messageId)
            }

            else -> {
                markPendingSend(messageId, PendingSendState.UNKNOWN)
                removeUnconfirmedBubble(messageId)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val method = idToMethod.remove(id) ?: return
        val request = sessionRequestById.remove(id)
        if (request != null && isStaleSessionRequest(request)) {
            outgoingRequestById.remove(id)?.let { markPendingSend(it, PendingSendState.UNKNOWN) }
            hardInterruptRequestById.remove(id)?.let { markPendingSend(it.messageId, PendingSendState.UNKNOWN) }
            return
        }
        if (method == WsMethods.PROMPT_SUBMIT || method == WsMethods.SESSION_REDIRECT ||
            method == WsMethods.SESSION_STEER
        ) {
            settleOutgoingResult(id, method, result)
        }
        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?>
                val runtimeId = (resultMap?.get("session_id") as? String)?.takeIf { it.isNotBlank() }
                if (runtimeId == null) {
                    _uiState.update {
                        it.copy(isLoading = false, resumeError = "Invalid session creation response")
                    }
                    return
                }
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                runtimeSessionId = runtimeId
                connectionOperationDelegate.bindSession(runtimeId)
                // The gateway persists the row lazily on the first prompt —
                // do not resume this key until presence is confirmed.
                sessionHasServerPresence = false
                sessionGoneRecoveryInFlight = false
                _uiState.update {
                    it.copy(
                        currentSessionId = storageId,
                        isSessionReady = true,
                        isLoading = false,
                        messages = if (pendingInitialPrompt != null) it.messages else emptyList(),
                        chatTitle = "Hermes",
                        usedContextTokens = null,
                        fullContextTokens = null,
                        contextBreakdown = null,
                        compressionCount = null,
                        sessionUsage = null,
                    )
                }
                publishPendingSends()
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

                // Issue #969: Drain prompt queued while session creation was in-flight.
                val pending = pendingInitialPrompt
                pendingInitialPrompt = null
                if (pending != null) {
                    enqueuePendingSendReservation(
                        PendingSendReservation(
                            pending =
                                PendingSend(
                                    id = pending.userMessage.id,
                                    scope = sendScope(),
                                    sessionId = storageId,
                                    text = pending.text,
                                    attachments = pending.attachments,
                                    mode = BusySendMode.CORRECT,
                                    state = PendingSendState.SENDING,
                                    createdAt = pending.createdAt,
                                ),
                            userMessage = pending.userMessage,
                            wasStreaming = pending.wasStreaming,
                            storageSessionId = storageId,
                            agentSessionId = runtimeId,
                            scope = sendScope(),
                            generation = sessionGeneration,
                            mainTurnEpoch = pending.mainTurnEpoch,
                        ),
                    )
                }
            }

            WsMethods.SESSION_BRANCH -> {
                val resultMap = result as? Map<String, Any?> ?: return
                // The result carries BOTH ids: `session_id` is the runtime
                // registry id, `stored_session_id` is the DB key. currentSessionId
                // must stay the storage key — storing the runtime id here made
                // every later resume 4007 "session not found" (the DB lookup
                // misses) and the REST transcript 404.
                val runtimeId = (resultMap["session_id"] as? String)?.takeIf { it.isNotBlank() } ?: return
                val storageId = resultMap["stored_session_id"] as? String ?: runtimeId
                val generation =
                    resetSessionState(
                        sessionId = storageId,
                        title = (resultMap["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Hermes",
                        isLoading = false,
                    )
                runtimeSessionId = runtimeId
                connectionOperationDelegate.bindSession(runtimeId)
                resumedGeneration = generation
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
                // A resume response may carry retained terminal state. Correlate the
                // exact request and stored session before binding the runtime id or
                // projecting any failure; a late response must not touch the new chat.
                val resumeRequest = request ?: return
                val sessionId = resumeRequest.sessionId ?: return
                if (sessionId != _uiState.value.currentSessionId) return
                val resumedStoredId = (resultMap?.get("resumed") as? String)?.takeIf { it.isNotBlank() }
                if (resumedStoredId != null && resumedStoredId != sessionId) return
                val runtimeId = (resultMap?.get("session_id") as? String)?.takeIf { it.isNotBlank() }
                if (runtimeId == null) {
                    handleResumeFailure(sessionId, resumeRequest.generation, "Invalid session resume response")
                    return
                }
                runtimeSessionId = runtimeId
                connectionOperationDelegate.bindSession(runtimeId)
                mainTurnBusy = resultMap["running"] as? Boolean ?: false
                // Resume succeeded — the gateway confirmed the DB row.
                sessionHasServerPresence = true

                // Parse session info from backend — model, provider, reasoning_effort
                val infoMap = resultMap["info"] as? Map<String, Any?>
                val model = infoMap?.get("model") as? String
                val provider = infoMap?.get("provider") as? String
                val reasoningEffort = infoMap?.get("reasoning_effort") as? String
                val reasoningEffortWire = infoMap?.get("reasoning_effort_wire") as? String
                val terminalBackend = infoMap?.get("terminal_backend") as? String
                val serviceTier = (infoMap?.get("service_tier") as? String)?.trim()?.lowercase()
                val fastFlag =
                    (infoMap?.get("fast") as? Boolean)
                        ?: (if (serviceTier != null) serviceTier == "priority" else null)

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
                        reasoningWireLevel =
                            if (reasoningEffortWire.isNullOrEmpty()) {
                                null
                            } else {
                                reasoningEffortWire
                            },
                        fastMode = fastFlag ?: false,
                        isFastModeChanging = false,
                        terminalBackend = terminalBackend ?: it.terminalBackend,
                    )
                }
                val resumedModelLabel =
                    if (model != null && provider != null) {
                        "$provider/$model"
                    } else {
                        model
                    }
                if (resumedModelLabel != null) {
                    if (lastConfirmedSessionModel != null &&
                        !resumedModelLabel.equals(lastConfirmedSessionModel, ignoreCase = true)
                    ) {
                        modelGeneration++
                        contextUsageJob?.cancel()
                        contextUsageJob = null
                        _uiState.update { it.copy(fullContextTokens = null) }
                    }
                    lastConfirmedSessionModel = resumedModelLabel
                }
                modelSwitchDelegate.syncCurrentModelCapabilities()
                // Mirror the active runtime session id app-wide (issue #532).
                ActiveSessionHolder.set(runtimeSessionId ?: sessionId, sessionId)
                addSystemMessage("Session resumed")
                fetchContextUsage()
                projectRetainedReplyFailure(resultMap["inflight"] as? Map<String, Any?>, runtimeId)
                val generation = resumeRequest.generation
                resumedGeneration = generation
                finishResumeWhenHydrated(generation)
                publishPendingSends()
                subagentsDelegate.hydrateSubagents(runtimeSessionId ?: sessionId)
                // Reconnect replay: resume payload can carry `pending_approval`
                // (server `_session_info_payload`); surface it, then ask for
                // the full queue in case more are parked.
                val pendingApproval = resultMap["pending_approval"] as? Map<*, *>
                if (pendingApproval != null) {
                    approvalsDelegate.maybeSurfacePendingApproval(
                        pendingApproval,
                        runtimeSessionId ?: sessionId,
                    )
                }
                // Reconnect replay: resume payload can carry "pending_clarify"
                // (server _live_session_payload) — restore the clarify bubble
                // so questions asked while the client was detached remain
                // answerable after leaving and re-entering the session.
                val pendingClarify = resultMap["pending_clarify"] as? Map<*, *>
                if (pendingClarify != null) {
                    surfacePendingClarify(
                        pendingClarify,
                        runtimeSessionId ?: sessionId,
                    )
                }
                val activeSessionId = runtimeSessionId ?: sessionId
                // Reconnect replay: the backend-owned connector operation is
                // authoritative and uses the same full snapshot as the live
                // connection.request event. Feed it through the seq guard so
                // a late resume response cannot regress a newer live update.
                val pendingConnection = resultMap["pending_connection"] as? Map<String, Any?>
                val pendingSnapshot =
                    pendingConnection?.let { ConnectionOperationParser.parse(it, activeSessionId) }
                request?.connectionCheckpoint?.let { checkpoint ->
                    connectionOperationDelegate.reconcileResume(pendingSnapshot, checkpoint)
                }
                if (activeSessionId != null) approvalsDelegate.replayPendingApproval(activeSessionId)
            }

            WsMethods.SESSION_INTERRUPT -> {
                val hardInterrupt = hardInterruptRequestById.remove(id)
                val status = rpcResultMap(result)?.get("status")
                val targetsCurrentTurn = hardInterrupt == null || hardInterrupt.mainTurnEpoch == mainTurnEpoch
                if (targetsCurrentTurn && status in setOf("interrupted", "not_interrupted")) {
                    // Never let a late result for an older same-session turn
                    // erase a newer stream or make its replacement race it.
                    sealStreamingMessageIfAny()
                    _uiState.update { it.copy(isAgentTyping = false) }
                    _streamingState.update { StreamingState() }
                    streamingController.resetStreaming()
                    if (status == "interrupted") addSystemMessage("Session interrupted")
                    mainTurnBusy = false
                }
                hardInterrupt?.let { tracked ->
                    val messageId = tracked.messageId
                    val row = sendStore.all().firstOrNull { it.id == messageId } ?: return@let
                    when {
                        !targetsCurrentTurn && status in setOf("interrupted", "not_interrupted") -> {
                            // The replacement is known unsent, but this successful interrupt
                            // result belongs to an older turn. Wait behind the newer turn.
                            sendStore.update(messageId) {
                                it.copy(state = PendingSendState.QUEUED, mode = BusySendMode.QUEUE)
                            }
                            publishPendingSends()
                        }

                        status == "interrupted" -> {
                            val message =
                                _uiState.value.messages.firstOrNull { it.id == row.id }
                                    ?: ChatMessage(
                                        id = row.id,
                                        role = MessageRole.USER,
                                        content = row.text,
                                        attachments = row.attachments,
                                    )
                            dispatchPrompt(
                                text = row.text,
                                attachments = row.attachments,
                                wasStreaming = false,
                                storageSessionId = row.sessionId,
                                agentSessionId = tracked.owner.agentSessionId,
                                userMessage = message,
                                mode = BusySendMode.INTERRUPT,
                                queued = true,
                                owner = tracked.owner,
                                pendingReceipt = row,
                                outboundAlreadyStarted = true,
                            )
                        }

                        status == "not_interrupted" -> {
                            sendStore.update(messageId) {
                                it.copy(state = PendingSendState.QUEUED, mode = BusySendMode.QUEUE)
                            }
                            publishPendingSends()
                            drainPendingQueue()
                        }

                        else -> {
                            markPendingSend(messageId, PendingSendState.UNKNOWN)
                            removeUnconfirmedBubble(messageId)
                        }
                    }
                }
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
                approvalsDelegate.handleApprovalRespondResult(result)
            }

            WsMethods.APPROVAL_PENDING -> {
                approvalsDelegate.handleApprovalPendingResult(result)
            }

            WsMethods.CONFIG_SET -> {
                modelSwitchDelegate.handleConfigSetResult(id, result)
            }
        }
    }

    /**
     * Replays a gateway-retained failed turn through the same reducer path as a
     * live terminal `message.complete(status=error)`. The assistant field is
     * the partial display projection; user/transcript fields are never exported
     * as diagnostics, and reducer effects intentionally do not persist it.
     */
    private fun projectRetainedReplyFailure(
        inflight: Map<String, Any?>?,
        runtimeId: String,
    ) {
        if (inflight?.get("status") != "error") return
        val result =
            ChatWsEventReducer.reduceRetainedReplyFailure(_uiState.value, inflight, runtimeId)
        _uiState.value = result.state
        _streamingState.value = result.streamingState
        dispatchReducerEffects(result.effects)
        streamingController.resetStreaming()
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

            "prefill" -> {
                val message = map["message"] as? String ?: ""
                val notice = map["notice"] as? String ?: ""
                handlePrefillResult(message, notice)
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
        if (request != null && isStaleSessionRequest(request)) {
            outgoingRequestById.remove(id)?.let { markPendingSend(it, PendingSendState.UNKNOWN) }
            hardInterruptRequestById.remove(id)?.let { markPendingSend(it.messageId, PendingSendState.UNKNOWN) }
            return
        }
        val errorMsg =
            when (error) {
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                else -> error.toString()
            }

        val messageId = outgoingRequestById.remove(id)
        if (messageId != null) {
            val code = (error as? JsonRpcError)?.code
            val correction = method == WsMethods.SESSION_REDIRECT || method == WsMethods.SESSION_STEER
            if (correction && code in setOf(4001, 4009, 4010)) {
                removeUnconfirmedBubble(messageId)
                sendStore.update(messageId) { it.copy(mode = BusySendMode.QUEUE, state = PendingSendState.QUEUED) }
                publishPendingSends()
                drainPendingQueue()
            } else {
                markPendingSend(
                    messageId,
                    if (code != null && code in 4000..4999) PendingSendState.REJECTED else PendingSendState.UNKNOWN,
                )
                removeUnconfirmedBubble(messageId)
            }
        }
        hardInterruptRequestById.remove(id)?.let { tracked ->
            markPendingSend(tracked.messageId, PendingSendState.UNKNOWN)
            removeUnconfirmedBubble(tracked.messageId)
        }

        if (method == WsMethods.PROMPT_SUBMIT && !mainTurnBusy) {
            // A prompt rejection can arrive before message.start, leaving the
            // UI with only the optimistic typing state. Clear the live tail so
            // a failed generation cannot leave stale dots or reasoning behind.
            sealStreamingMessageIfAny()
            _uiState.update { it.copy(isAgentTyping = mainTurnBusy) }
            _streamingState.update { StreamingState() }
            streamingController.resetStreaming()
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

        if (method == WsMethods.CONFIG_SET) {
            modelSwitchDelegate.handleConfigSetError(id, error)
        }

        if (method == WsMethods.SESSION_CREATE) {
            val pending = pendingInitialPrompt
            pendingInitialPrompt = null
            publishPendingSends()
            if (pending != null) {
                _uiState.update {
                    it.copy(
                        messages = it.messages.filterNot { message -> message.id == pending.userMessage.id },
                        pendingAttachments = pending.attachments + it.pendingAttachments,
                        composerTextToRestore = pending.text,
                        isAgentTyping = false,
                        errorMessage = "Failed to create session: $errorMsg",
                    )
                }
            }
        }

        // Surface error in UI (these are server-pushed RpcError for
        // fire-and-forget RPCs — awaited RPCs handle their own failure
        // via the HermesWsClient.request() deferred).
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error ($method): $errorMsg",
                resumeError = if (method == WsMethods.SESSION_CREATE) errorMsg else it.resumeError,
            )
        }
    }

    // ── Send message ─────────────────────────────────────────────────────

    private fun sendScope(): String =
        listOf(
            AuthManager.getBaseUrl(),
            AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
            AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
        ).joinToString("\u001f")

    private fun isCurrentSendContext(
        scope: String,
        storageSessionId: String,
        agentSessionId: String,
        generation: Long,
    ): Boolean =
        generation == sessionGeneration &&
            scope == sendScope() &&
            storageSessionId == _uiState.value.currentSessionId &&
            agentSessionId == runtimeSessionId

    private fun isCurrentSendContext(owner: SendOwner): Boolean =
        isCurrentSendContext(
            owner.scope,
            owner.storageSessionId,
            owner.agentSessionId,
            owner.generation,
        )

    private fun captureSendOwner(
        storageSessionId: String,
        agentSessionId: String,
        scope: String = sendScope(),
        generation: Long = sessionGeneration,
    ): SendOwner = SendOwner(scope, storageSessionId, agentSessionId, generation)

    /** Context invalidation and synchronous WS enqueue are serialized on Main. */
    private suspend fun <T> withCurrentSendOwner(
        owner: SendOwner,
        action: () -> T,
    ): T? =
        withContext(Dispatchers.Main.immediate) {
            if (isCurrentSendContext(owner)) action() else null
        }

    private fun restoreKnownUnsentReceipt(
        attempted: PendingSend,
        rollback: PendingSend?,
        deleteSnapshot: Boolean,
    ) {
        if (rollback != null) sendStore.put(rollback) else sendStore.remove(attempted.id)
        publishPendingSends()
        if (deleteSnapshot && rollback?.attachments.orEmpty().none { it.uri.startsWith("file:", ignoreCase = true) }) {
            viewModelScope.launch(ioDispatcher) { deleteQueuedAttachmentSnapshot(attempted.id) }
        }
    }

    /** Commit/claim and enqueue the first RPC without releasing Main ownership. */
    private suspend fun <T> commitReceiptAndEnqueue(
        owner: SendOwner,
        attempted: PendingSend,
        rollback: PendingSend?,
        deleteSnapshotOnRollback: Boolean,
        enqueue: () -> T,
    ): T? =
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentSendContext(owner)) {
                restoreKnownUnsentReceipt(attempted, rollback, deleteSnapshotOnRollback)
                return@withContext null
            }
            sendStore.put(attempted)
            publishPendingSends()
            // Store callbacks can re-enter profile selection.
            if (!isCurrentSendContext(owner)) {
                restoreKnownUnsentReceipt(attempted, rollback, deleteSnapshotOnRollback)
                return@withContext null
            }
            enqueue()
        }

    private fun deleteQueuedAttachmentSnapshot(messageId: String) {
        File(getApplication<Application>().filesDir, "chat-send/$messageId").deleteRecursively()
    }

    @Synchronized
    private fun nextPendingSendCreatedAt(): Long {
        lastPendingSendCreatedAt = maxOf(System.currentTimeMillis(), lastPendingSendCreatedAt + 1L)
        return lastPendingSendCreatedAt
    }

    private fun hasPendingSendReservation(
        scope: String,
        sessionId: String?,
    ): Boolean =
        synchronized(pendingSendReservationLock) {
            pendingSendReservations.any { it.scope == scope && it.storageSessionId == sessionId }
        }

    private fun enqueuePendingSendReservation(reservation: PendingSendReservation) {
        synchronized(pendingSendReservationLock) { pendingSendReservations += reservation }
        startPendingSendReservationDrain()
    }

    @Synchronized
    private fun startPendingSendReservationDrain() {
        if (pendingSendReservationJob?.isActive == true) return
        pendingSendReservationJob =
            viewModelScope.launch(ioDispatcher) {
                try {
                    while (true) {
                        val reservation =
                            synchronized(pendingSendReservationLock) { pendingSendReservations.firstOrNull() }
                                ?: break
                        try {
                            processPendingSendReservation(reservation)
                        } finally {
                            synchronized(pendingSendReservationLock) {
                                pendingSendReservations.removeAll { it.pending.id == reservation.pending.id }
                            }
                        }
                    }
                } finally {
                    pendingSendReservationJob = null
                    if (synchronized(pendingSendReservationLock) { pendingSendReservations.isNotEmpty() }) {
                        startPendingSendReservationDrain()
                    } else {
                        drainPendingQueue()
                    }
                }
            }
    }

    private suspend fun processPendingSendReservation(reservation: PendingSendReservation) {
        val owner =
            captureSendOwner(
                reservation.storageSessionId,
                reservation.agentSessionId,
                reservation.scope,
                reservation.generation,
            )
        try {
            var staged = stageQueuedAttachments(reservation.pending).copy(requiresAttachmentRecovery = false)
            kotlinx.coroutines.yield()
            if (withCurrentSendOwner(owner) { true } != true) {
                if (reservation.originalPending == null) deleteQueuedAttachmentSnapshot(reservation.pending.id)
                return
            }
            if (reservation.wasStreaming && staged.mode == BusySendMode.INTERRUPT &&
                reservation.mainTurnEpoch != mainTurnEpoch
            ) {
                staged = staged.copy(mode = BusySendMode.QUEUE, state = PendingSendState.QUEUED)
            }
            val stagedMessage =
                reservation.userMessage.copy(
                    attachments = staged.attachments.takeIf { it.isNotEmpty() },
                )
            if (withCurrentSendOwner(owner) {
                    _uiState.update { state ->
                        val messages =
                            if (state.messages.any { it.id == stagedMessage.id }) {
                                state.messages.map { if (it.id == stagedMessage.id) stagedMessage else it }
                            } else {
                                state.messages + stagedMessage
                            }
                        state.copy(messages = messages, isSending = true)
                    }
                    true
                } != true
            ) {
                if (reservation.originalPending == null) deleteQueuedAttachmentSnapshot(staged.id)
                return
            }

            when {
                staged.state == PendingSendState.QUEUED -> {
                    val committed =
                        withContext(Dispatchers.Main.immediate) {
                            if (!isCurrentSendContext(owner)) return@withContext false
                            sendStore.put(staged)
                            publishPendingSends()
                            if (!isCurrentSendContext(owner)) {
                                restoreKnownUnsentReceipt(
                                    staged,
                                    reservation.originalPending,
                                    deleteSnapshot = reservation.originalPending != null,
                                )
                                false
                            } else {
                                true
                            }
                        }
                    if (committed) repo.persistMessage(stagedMessage, owner.storageSessionId)
                }

                reservation.wasStreaming && staged.mode == BusySendMode.INTERRUPT -> {
                    commitReceiptAndEnqueue(
                        owner,
                        staged,
                        reservation.originalPending,
                        deleteSnapshotOnRollback = true,
                    ) {
                        wsClient.send(
                            WsMethods.SESSION_INTERRUPT,
                            mapOf("session_id" to owner.agentSessionId),
                            onSent = { id -> trackHardInterrupt(id, staged.id, owner, reservation.mainTurnEpoch) },
                        )
                    }
                }

                else -> {
                    dispatchPrompt(
                        text = staged.text,
                        attachments = staged.attachments,
                        wasStreaming = reservation.wasStreaming,
                        storageSessionId = owner.storageSessionId,
                        agentSessionId = owner.agentSessionId,
                        userMessage = stagedMessage,
                        mode = if (reservation.wasStreaming) staged.mode else BusySendMode.CORRECT,
                        owner = owner,
                        pendingReceipt = staged,
                        rollbackReceipt = reservation.originalPending,
                        deleteSnapshotOnRollback = true,
                    ).join()
                }
            }
        } catch (e: Exception) {
            if (reservation.originalPending != null) {
                sendStore.put(reservation.originalPending)
            } else {
                sendStore.remove(reservation.pending.id)
                deleteQueuedAttachmentSnapshot(reservation.pending.id)
            }
            publishPendingSends()
            if (e is CancellationException) throw e
            if (withCurrentSendOwner(owner) { true } == true) {
                if (reservation.originalPending == null) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.filterNot { it.id == reservation.pending.id },
                            pendingAttachments = reservation.pending.attachments + state.pendingAttachments,
                            composerTextToRestore = reservation.pending.text,
                            errorMessage =
                                if (e is QueuedAttachmentTooLargeException) {
                                    attachmentTooLargeMessage(e.attachment)
                                } else {
                                    "Could not retain attachment"
                                },
                        )
                    }
                } else if (e is QueuedAttachmentTooLargeException) {
                    _uiState.update { it.copy(errorMessage = attachmentTooLargeMessage(e.attachment)) }
                }
                publishPendingSends()
            }
        }
    }

    private fun publishPendingSends() {
        val sessionId = _uiState.value.currentSessionId
        val scope = sendScope()
        val current = sendStore.all().filter { it.scope == scope && it.sessionId == sessionId }
        _uiState.update {
            it.copy(
                pendingSends = current,
                isSending =
                    pendingInitialPrompt != null || current.any { row -> row.state == PendingSendState.SENDING },
            )
        }
    }

    private fun trackOutgoingRequest(
        requestId: String,
        messageId: String,
        method: String,
        generation: Long,
        sessionId: String,
    ) {
        outgoingRequestById[requestId] = messageId
        trackSessionRequest(requestId, method, generation, sessionId = sessionId)
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(30_000L)
                expireOutgoingRequest(requestId)
            }
        }
    }

    internal fun expireOutgoingRequest(requestId: String) {
        val messageId =
            outgoingRequestById[requestId]
                ?: hardInterruptRequestById[requestId]?.messageId
                ?: return
        if (sendStore.all().any { it.id == messageId && it.state == PendingSendState.SENDING }) {
            markPendingSend(messageId, PendingSendState.UNKNOWN)
            removeUnconfirmedBubble(messageId)
        }
    }

    private fun trackHardInterrupt(
        requestId: String,
        messageId: String,
        owner: SendOwner,
        interruptedTurnEpoch: Long,
    ) {
        hardInterruptRequestById[requestId] = HardInterruptRequest(messageId, interruptedTurnEpoch, owner)
        trackSessionRequest(
            requestId,
            WsMethods.SESSION_INTERRUPT,
            owner.generation,
            sessionId = owner.storageSessionId,
        )
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(30_000L)
                expireOutgoingRequest(requestId)
            }
        }
    }

    private fun markPendingSend(
        messageId: String,
        state: PendingSendState,
    ) {
        sendStore.update(messageId) { it.copy(state = state) }
        publishPendingSends()
    }

    private fun removePendingSend(messageId: String) {
        sendStore.remove(messageId)
        viewModelScope.launch(ioDispatcher) { deleteQueuedAttachmentSnapshot(messageId) }
        publishPendingSends()
    }

    private fun removeUnconfirmedBubble(messageId: String) {
        val row = _uiState.value.messages.firstOrNull { it.id == messageId && it.canonicalRestId == null } ?: return
        _uiState.update { state -> state.copy(messages = state.messages.filterNot { it.id == row.id }) }
        viewModelScope.launch(ioDispatcher) { repo.deleteMessage(row.id) }
    }

    private fun failOutgoingBeforeSubmit(
        messageId: String,
        reason: String,
        generation: Long = sessionGeneration,
    ) {
        markPendingSend(messageId, PendingSendState.REJECTED)
        if (generation != sessionGeneration) return
        removeUnconfirmedBubble(messageId)
        _uiState.update { it.copy(errorMessage = reason, isAgentTyping = mainTurnBusy) }
    }

    /** Send one locally queued turn only after the previous receipt has reached REST history. */
    @Synchronized
    private fun drainPendingQueue() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val runtimeId = runtimeSessionId ?: return
        val scope = sendScope()
        if (mainTurnBusy || !_uiState.value.isSessionReady ||
            wsClient.connectionStatus.value != ConnectionStatus.CONNECTED
        ) {
            return
        }
        if (queueDrainJob?.isActive == true) return
        if (hasPendingSendReservation(scope, sessionId)) return
        val rows = sendStore.all().filter { it.scope == scope && it.sessionId == sessionId }
        if (rows.any {
                it.state == PendingSendState.SENDING || it.state == PendingSendState.UNKNOWN ||
                    it.state == PendingSendState.ACCEPTED
            }
        ) {
            return
        }
        val next = rows.firstOrNull { it.state == PendingSendState.QUEUED } ?: return
        if (queuedStagingIds.contains(next.id)) return
        val owner = captureSendOwner(sessionId, runtimeId, scope, sessionGeneration)
        queueDrainJob =
            viewModelScope.launch(ioDispatcher) {
                try {
                    val staged = stageQueuedAttachments(next)
                    if (withCurrentSendOwner(owner) { true } != true) return@launch
                    val message =
                        _uiState.value.messages.firstOrNull { it.id == next.id }
                            ?: ChatMessage(
                                id = next.id,
                                role = MessageRole.USER,
                                content = next.text,
                                attachments = staged.attachments,
                            )
                    dispatchPrompt(
                        text = next.text,
                        attachments = staged.attachments,
                        wasStreaming = false,
                        storageSessionId = owner.storageSessionId,
                        agentSessionId = owner.agentSessionId,
                        userMessage = message,
                        mode = BusySendMode.QUEUE,
                        queued = true,
                        owner = owner,
                        pendingReceipt =
                            staged.copy(
                                state = PendingSendState.SENDING,
                                attempts = next.attempts + 1,
                            ),
                        rollbackReceipt = next,
                    ).join()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failOutgoingBeforeSubmit(
                        next.id,
                        if (e is QueuedAttachmentTooLargeException) {
                            attachmentTooLargeMessage(e.attachment)
                        } else {
                            "Could not retain queued attachment"
                        },
                    )
                }
            }
    }

    private fun stageQueuedAttachments(row: PendingSend): PendingSend {
        if (row.attachments.isEmpty()) return row
        val context = getApplication<Application>()
        val dir = File(context.filesDir, "chat-send/${row.id}")
        if (!dir.exists() && !dir.mkdirs()) error("Could not create attachment snapshot")
        val staged =
            row.attachments.mapIndexed { index, attachment ->
                val uri = Uri.parse(attachment.uri)
                val target = File(dir, index.toString())
                if (target.exists()) {
                    attachment.copy(uri = Uri.fromFile(target).toString(), size = target.length())
                } else {
                    val partial = File(dir, "$index.part")
                    var copied = 0L
                    try {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Attachment unavailable")
                        input.use { source ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    if (copied > MAX_CHAT_ATTACHMENT_BYTES) {
                                        throw QueuedAttachmentTooLargeException(attachment)
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (!partial.renameTo(target)) error("Could not save attachment snapshot")
                        attachment.copy(uri = Uri.fromFile(target).toString(), size = copied)
                    } catch (e: Exception) {
                        partial.delete()
                        throw e
                    }
                }
            }
        return row.copy(attachments = staged)
    }

    private fun refreshSendReceipts() {
        val sessionId = _uiState.value.currentSessionId ?: return
        if (sendStore.all().any {
                it.scope == sendScope() && it.sessionId == sessionId &&
                    it.state in setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
            }
        ) {
            if (sessionHasServerPresence) syncCurrentSession() else loadSessionMessages(sessionId, sessionGeneration)
        } else {
            drainPendingQueue()
        }
    }

    /** A manual promotion is the only path that may retry an uncertain send. */
    @Synchronized
    fun sendQueuedNow(id: String) {
        val row = sendStore.all().firstOrNull { it.id == id } ?: return
        if (row.scope != sendScope() || row.sessionId != _uiState.value.currentSessionId ||
            runtimeSessionId == null
        ) {
            return
        }
        if (row.state !in
            setOf(
                PendingSendState.QUEUED,
                PendingSendState.PARKED,
                PendingSendState.REJECTED,
                PendingSendState.UNKNOWN,
                PendingSendState.ACCEPTED,
            )
        ) {
            return
        }
        if (queueDrainJob?.isActive == true ||
            synchronized(pendingSendReservationLock) { pendingSendReservations.any { it.pending.id == id } }
        ) {
            return
        }
        val others =
            sendStore.all().any {
                it.id != id && it.scope == row.scope && it.sessionId == row.sessionId &&
                    it.state in setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
            }
        if (others) {
            _uiState.update { it.copy(errorMessage = "Wait for the current send to settle") }
            return
        }
        if (row.attachments.isNotEmpty()) {
            val runtimeId = runtimeSessionId ?: return
            val generation = sessionGeneration
            val wasStreaming = mainTurnBusy
            val message =
                _uiState.value.messages.firstOrNull { it.id == row.id }
                    ?: ChatMessage(
                        id = row.id,
                        role = MessageRole.USER,
                        content = row.text,
                        attachments = row.attachments,
                    )
            enqueuePendingSendReservation(
                PendingSendReservation(
                    pending =
                        row.copy(
                            mode = if (wasStreaming) BusySendMode.INTERRUPT else BusySendMode.QUEUE,
                            state = PendingSendState.SENDING,
                            attempts = row.attempts + 1,
                        ),
                    userMessage = message,
                    wasStreaming = wasStreaming,
                    storageSessionId = row.sessionId,
                    agentSessionId = runtimeId,
                    scope = row.scope,
                    generation = generation,
                    mainTurnEpoch = mainTurnEpoch,
                    originalPending = row,
                ),
            )
            return
        }
        sendStore.promote(id)
        publishPendingSends()
        if (mainTurnBusy) {
            val runtimeId = runtimeSessionId ?: return
            val owner = captureSendOwner(row.sessionId, runtimeId, row.scope, sessionGeneration)
            val interruptedTurnEpoch = mainTurnEpoch
            viewModelScope.launch {
                commitReceiptAndEnqueue(
                    owner,
                    row.copy(state = PendingSendState.SENDING, attempts = row.attempts + 1),
                    row,
                    deleteSnapshotOnRollback = false,
                ) {
                    wsClient.send(
                        WsMethods.SESSION_INTERRUPT,
                        mapOf("session_id" to owner.agentSessionId),
                        onSent = { requestId -> trackHardInterrupt(requestId, id, owner, interruptedTurnEpoch) },
                    )
                }
            }
        } else {
            drainPendingQueue()
        }
    }

    /**
     * Send a user prompt, uploading any pending attachments to the backend
     * first via their dedicated RPC methods.
     *
     * Flow:
     * 1. Snapshot pending attachments (then clear them from UI)
     * 2. Add user message to UI immediately (optimistic UX)
     * 3. If session creation is still in flight (currentSessionId/runtimeSessionId null),
     *    queue the prompt into [pendingInitialPrompt] to be dispatched as soon as
     *    SESSION_CREATE resolves (issue #969).
     * 4. Persist user message to DB
     * 5. For each image → await `image.attach_bytes` (requires session_id)
     * 6. For each file → await `file.attach` (requires session_id), collect @file: refs
     * 7. Send `prompt.submit` with text + @file: refs — images auto-picked up by backend
     */
    fun sendMessage(
        text: String,
        modeOverride: BusySendMode? = null,
    ): Boolean {
        if (!canSubmitMessage()) return false
        if (text.isBlank() && _uiState.value.pendingAttachments.isEmpty()) return false

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            // Issue #589: a bare "/model" (no argument) opens the picker instead
            // of requiring the user to hand-type the provider/model.
            if (modelSwitchDelegate.isModelPickerCommand(trimmed)) {
                openModelPicker()
                return true
            }
            handleSlashCommand(trimmed)
            return true
        }

        val oversizedAttachment =
            _uiState.value.pendingAttachments.firstOrNull {
                it.size > MAX_CHAT_ATTACHMENT_BYTES
            }
        if (oversizedAttachment != null) {
            _uiState.update {
                it.copy(
                    errorMessage = attachmentTooLargeMessage(oversizedAttachment),
                )
            }
            return false
        }

        val attachments = _uiState.value.pendingAttachments.toList()
        val submissionKey =
            listOf(_uiState.value.currentSessionId, text, attachments.joinToString { it.uri }).joinToString("\u001f")
        val now = System.nanoTime()
        if (submissionKey == lastSubmissionKey && now - lastSubmissionAt < 500_000_000L) return false
        val wasStreaming = mainTurnBusy
        val clickedMainTurnEpoch = mainTurnEpoch
        val hasOutstandingDelivery =
            sendStore.all().any {
                it.scope == sendScope() && it.sessionId == _uiState.value.currentSessionId &&
                    it.state in
                    setOf(
                        PendingSendState.SENDING,
                        PendingSendState.ACCEPTED,
                        PendingSendState.UNKNOWN,
                    )
            } || hasPendingSendReservation(sendScope(), _uiState.value.currentSessionId)
        val busy = wasStreaming || hasOutstandingDelivery
        val selectedMode = modeOverride ?: _uiState.value.busySendMode
        val mode =
            when {
                selectedMode == BusySendMode.INTERRUPT && !wasStreaming && hasOutstandingDelivery -> {
                    BusySendMode.QUEUE
                }

                busy && selectedMode != BusySendMode.INTERRUPT &&
                    (attachments.isNotEmpty() || !wasStreaming) -> {
                    BusySendMode.QUEUE
                }

                else -> {
                    selectedMode
                }
            }

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
                attachments = if (attachments.isNotEmpty()) attachments else null,
                tokenCount = TokenEstimator.estimate(text).takeIf { it > 0 },
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )

        val storageSessionId = _uiState.value.currentSessionId
        val agentSessionId = runtimeSessionId
        val scope = sendScope()
        val createdAt = nextPendingSendCreatedAt()
        if (storageSessionId != null && agentSessionId != null &&
            (attachments.isNotEmpty() || hasPendingSendReservation(scope, storageSessionId))
        ) {
            val generation = sessionGeneration
            val pending =
                PendingSend(
                    id = userMessage.id,
                    scope = scope,
                    sessionId = storageSessionId,
                    text = text,
                    attachments = attachments,
                    mode = mode,
                    state =
                        if (busy && mode == BusySendMode.QUEUE) {
                            PendingSendState.QUEUED
                        } else {
                            PendingSendState.SENDING
                        },
                    createdAt = createdAt,
                )
            lastSubmissionKey = submissionKey
            lastSubmissionAt = now
            clearAttachments()
            enqueuePendingSendReservation(
                PendingSendReservation(
                    pending = pending,
                    userMessage = userMessage,
                    wasStreaming = wasStreaming,
                    storageSessionId = storageSessionId,
                    agentSessionId = agentSessionId,
                    scope = scope,
                    generation = generation,
                    mainTurnEpoch = clickedMainTurnEpoch,
                ),
            )
            return true
        }
        if (storageSessionId != null && agentSessionId != null && busy && mode == BusySendMode.QUEUE) {
            try {
                sendStore.put(
                    PendingSend(
                        id = userMessage.id,
                        scope = scope,
                        sessionId = storageSessionId,
                        text = text,
                        attachments = attachments,
                        mode = mode,
                        createdAt = createdAt,
                        state =
                            if (busy && mode == BusySendMode.QUEUE) {
                                PendingSendState.QUEUED
                            } else {
                                PendingSendState.SENDING
                            },
                    ),
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not save message for delivery") }
                return false
            }
        }
        lastSubmissionKey = submissionKey
        lastSubmissionAt = now
        clearAttachments()

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isSending = true,
            )
        }
        publishPendingSends()

        if (storageSessionId == null || agentSessionId == null) {
            // Issue #969: Session creation is still in-flight. Hold the prompt
            // so it is dispatched automatically the moment SESSION_CREATE lands.
            pendingInitialPrompt =
                PendingPrompt(text, attachments, wasStreaming, userMessage, createdAt, clickedMainTurnEpoch)
            publishPendingSends()
            return true
        }

        if (busy && mode == BusySendMode.QUEUE) {
            queuedStagingIds.add(userMessage.id)
            viewModelScope.launch(ioDispatcher) {
                try {
                    stageQueuedAttachments(sendStore.all().first { it.id == userMessage.id })
                    repo.persistMessage(userMessage, storageSessionId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failOutgoingBeforeSubmit(userMessage.id, "Could not retain queued attachment")
                } finally {
                    queuedStagingIds.remove(userMessage.id)
                    drainPendingQueue()
                }
            }
            return true
        }

        if (wasStreaming && mode == BusySendMode.INTERRUPT) {
            val owner = captureSendOwner(storageSessionId, agentSessionId, scope, sessionGeneration)
            val interruptedTurnEpoch = mainTurnEpoch
            val receipt =
                PendingSend(
                    id = userMessage.id,
                    scope = scope,
                    sessionId = storageSessionId,
                    text = text,
                    attachments = attachments,
                    mode = mode,
                    state = PendingSendState.SENDING,
                    createdAt = createdAt,
                )
            viewModelScope.launch {
                commitReceiptAndEnqueue(owner, receipt, null, deleteSnapshotOnRollback = false) {
                    wsClient.send(
                        WsMethods.SESSION_INTERRUPT,
                        mapOf("session_id" to owner.agentSessionId),
                        onSent = { id -> trackHardInterrupt(id, userMessage.id, owner, interruptedTurnEpoch) },
                    )
                }
            }
            return true
        }

        dispatchPrompt(
            text = text,
            attachments = attachments,
            wasStreaming = wasStreaming,
            storageSessionId = storageSessionId,
            agentSessionId = agentSessionId,
            userMessage = userMessage,
            mode = if (wasStreaming) mode else BusySendMode.CORRECT,
            owner = captureSendOwner(storageSessionId, agentSessionId, scope),
            pendingReceipt =
                PendingSend(
                    id = userMessage.id,
                    scope = scope,
                    sessionId = storageSessionId,
                    text = text,
                    attachments = attachments,
                    mode = if (wasStreaming) mode else BusySendMode.CORRECT,
                    state = PendingSendState.SENDING,
                    createdAt = createdAt,
                ),
        )
        return true
    }

    /** True while a voice-note transcription owns the mic-to-send pipeline. */
    private var voiceNoteTranscriptionInFlight = false

    /**
     * Transcribe a recorded voice note through the dashboard's server-side
     * STT relay (`POST /api/audio/transcribe` — the desktop client's voice
     * path) and submit the transcript as the next message. The profile's
     * configured STT provider answers, not the phone's on-device recognizer.
     *
     * The recording belongs to the session that was active when it was taken:
     * transcription takes long enough for the socket, the profile, or the user
     * to move on, so a transcript that can no longer land in that session or
     * connection/profile scope is preserved in the composer instead of being
     * dropped. Only one
     * transcription runs at a time — [voiceNoteTranscriptionInFlight] keeps
     * every branch below single-flight (review, PR #1250).
     */
    fun sendVoiceNote(file: File) {
        if (voiceNoteTranscriptionInFlight) {
            file.delete()
            return
        }
        if (!canSubmitMessage()) {
            file.delete()
            _uiState.update { it.copy(errorMessage = VOICE_NOTE_OFFLINE_MESSAGE) }
            return
        }
        val recordedSessionId = _uiState.value.currentSessionId
        val recordedRuntimeSessionId = runtimeSessionId
        // The recording also belongs to the connection/profile scope it was
        // taken on: both session IDs can still be null/null across a scope
        // switch while a replacement session create is pending, so the data
        // scope is the deciding identity for that window (review, PR #1250).
        val recordedDataScope = AuthManager.currentDataScope()
        voiceNoteTranscriptionInFlight = true
        viewModelScope.launch {
            _uiState.update { it.copy(isTranscribingVoiceNote = true) }
            try {
                val result =
                    withContext(ioDispatcher) {
                        try {
                            voiceNoteRepository.transcribe(file)
                        } finally {
                            file.delete()
                        }
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        val transcript = result.data.trim()
                        if (transcript.isEmpty()) {
                            _uiState.update { it.copy(errorMessage = VOICE_NOTE_EMPTY_MESSAGE) }
                        } else {
                            deliverVoiceTranscript(
                                transcript = transcript,
                                recordedSessionId = recordedSessionId,
                                recordedRuntimeSessionId = recordedRuntimeSessionId,
                                recordedDataScope = recordedDataScope,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        Log.w(TAG, "Voice note transcription failed: ${result.error.message}")
                        _uiState.update {
                            it.copy(errorMessage = "$VOICE_NOTE_FAILED_MESSAGE: ${result.error.message}")
                        }
                    }
                }
            } finally {
                // Always release the single-flight flag and the composer's
                // transcription state, even if a branch above throws.
                voiceNoteTranscriptionInFlight = false
                _uiState.update { it.copy(isTranscribingVoiceNote = false) }
            }
        }
    }

    /**
     * Submits a transcribed voice note, or keeps the transcript in the input
     * field when it can no longer land where it was recorded. The recording
     * file is already deleted by now, so a rejected send restores the text
     * instead of losing it.
     */
    private fun deliverVoiceTranscript(
        transcript: String,
        recordedSessionId: String?,
        recordedRuntimeSessionId: String?,
        recordedDataScope: DataScope?,
    ) {
        val sessionChanged =
            _uiState.value.currentSessionId != recordedSessionId ||
                runtimeSessionId != recordedRuntimeSessionId
        // A connection/profile switch can leave the session IDs null on both
        // sides of the recording (a replacement session create may still be
        // pending), so the data scope is the ownership check that catches
        // that move (review, PR #1250).
        val scopeChanged = AuthManager.currentDataScope() != recordedDataScope
        if (sessionChanged || scopeChanged || !sendMessage(transcript)) {
            _uiState.update {
                it.copy(
                    composerTextToRestore = transcript,
                    errorMessage = VOICE_NOTE_UNSENT_MESSAGE,
                )
            }
        }
    }

    private fun canSubmitMessage(): Boolean =
        _uiState.value.pendingReasoningLevel == null &&
            wsClient.connectionStatus.value == ConnectionStatus.CONNECTED &&
            (
                (_uiState.value.isSessionReady && runtimeSessionId != null) ||
                    (
                        _uiState.value.currentSessionId == null &&
                            pendingInitialPrompt == null &&
                            idToMethod.any { (id, method) ->
                                method == WsMethods.SESSION_CREATE &&
                                    sessionRequestById[id]?.let { !isStaleSessionRequest(it) } == true
                            }
                    )
            )

    private fun dispatchPrompt(
        text: String,
        attachments: List<Attachment>,
        wasStreaming: Boolean,
        storageSessionId: String,
        agentSessionId: String,
        userMessage: ChatMessage? = null,
        mode: BusySendMode = BusySendMode.CORRECT,
        queued: Boolean = false,
        owner: SendOwner = captureSendOwner(storageSessionId, agentSessionId),
        pendingReceipt: PendingSend? = null,
        rollbackReceipt: PendingSend? = null,
        deleteSnapshotOnRollback: Boolean = false,
        outboundAlreadyStarted: Boolean = false,
    ): Job {
        val dispatchGeneration = owner.generation
        AuthManager.setLastOpenedSessionId(storageSessionId)
        val msgToPersist =
            userMessage ?: ChatMessage(
                role = MessageRole.USER,
                content = text,
                attachments = if (attachments.isNotEmpty()) attachments else null,
                tokenCount = TokenEstimator.estimate(text).takeIf { it > 0 },
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )
        val attemptedReceipt =
            pendingReceipt
                ?: sendStore.all().firstOrNull { it.id == msgToPersist.id }?.copy(state = PendingSendState.SENDING)
                ?: PendingSend(
                    id = msgToPersist.id,
                    scope = owner.scope,
                    sessionId = owner.storageSessionId,
                    text = text,
                    attachments = attachments,
                    mode = mode,
                    state = PendingSendState.SENDING,
                )

        // PR #1254: callers draining FIFO work retain ownership until preparation/enqueue ends.
        return viewModelScope.launch(ioDispatcher) {
            val fileRefs = mutableListOf<String>()
            val preparedAttachments = mutableListOf<PreparedAttachment>()
            var receiptCommitted = outboundAlreadyStarted
            var outboundStarted = outboundAlreadyStarted

            suspend fun <T> enqueueOwned(action: () -> T): T? {
                val value =
                    if (!receiptCommitted) {
                        commitReceiptAndEnqueue(
                            owner,
                            attemptedReceipt,
                            rollbackReceipt,
                            deleteSnapshotOnRollback,
                            action,
                        )
                    } else {
                        withCurrentSendOwner(owner, action)
                    }
                if (value == null) {
                    if (outboundStarted) {
                        sendStore.update(msgToPersist.id) { it.copy(state = PendingSendState.UNKNOWN) }
                        publishPendingSends()
                    }
                    return null
                }
                receiptCommitted = true
                outboundStarted = true
                return value
            }

            try {
                for (attachment in attachments) {
                    when (val result = prepareAttachment(attachment)) {
                        is PrepareAttachmentResult.Success -> {
                            preparedAttachments += result.prepared
                        }

                        PrepareAttachmentResult.TooLarge -> {
                            if (!outboundStarted) {
                                restoreKnownUnsentReceipt(
                                    attemptedReceipt,
                                    rollbackReceipt,
                                    deleteSnapshotOnRollback,
                                )
                            }
                            if (!outboundStarted && rollbackReceipt != null) {
                                withCurrentSendOwner(owner) {
                                    removeUnconfirmedBubble(msgToPersist.id)
                                    _uiState.update { it.copy(errorMessage = attachmentTooLargeMessage(attachment)) }
                                }
                            } else {
                                rejectOversizedAttachment(
                                    attachment,
                                    attachments,
                                    msgToPersist,
                                    wasStreaming,
                                    dispatchGeneration,
                                )
                            }
                            return@launch
                        }

                        PrepareAttachmentResult.Unreadable -> {
                            if (!outboundStarted) {
                                restoreKnownUnsentReceipt(
                                    attemptedReceipt,
                                    rollbackReceipt,
                                    deleteSnapshotOnRollback,
                                )
                            }
                            if (!outboundStarted && rollbackReceipt != null) {
                                withCurrentSendOwner(owner) {
                                    removeUnconfirmedBubble(msgToPersist.id)
                                    _uiState.update {
                                        it.copy(
                                            errorMessage = "Attachment unavailable: ${attachment.name}",
                                            isAgentTyping = mainTurnBusy,
                                        )
                                    }
                                }
                            } else {
                                failOutgoingBeforeSubmit(
                                    msgToPersist.id,
                                    "Attachment unavailable: ${attachment.name}",
                                    dispatchGeneration,
                                )
                            }
                            return@launch
                        }
                    }
                }

                if (withCurrentSendOwner(owner) { true } != true) {
                    if (!outboundStarted) {
                        restoreKnownUnsentReceipt(
                            attemptedReceipt,
                            rollbackReceipt,
                            deleteSnapshotOnRollback,
                        )
                    }
                    return@launch
                }
                try {
                    repo.persistMessage(msgToPersist, owner.storageSessionId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to persist outgoing message", e)
                    if (outboundStarted) {
                        sendStore.update(msgToPersist.id) { it.copy(state = PendingSendState.UNKNOWN) }
                    } else {
                        restoreKnownUnsentReceipt(attemptedReceipt, rollbackReceipt, deleteSnapshotOnRollback)
                    }
                    if (isCurrentSendContext(owner)) {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.filterNot { it.id == msgToPersist.id },
                                pendingAttachments = attachments + state.pendingAttachments,
                                composerTextToRestore = msgToPersist.content,
                                errorMessage = "Failed to save message",
                                isAgentTyping = wasStreaming,
                            )
                        }
                    }
                    return@launch
                }

                for ((attachment, encodedFile) in preparedAttachments) {
                    try {
                        val b64 = encodedFile.readText(Charsets.US_ASCII)
                        if (attachment.isImage) {
                            val deferred =
                                enqueueOwned {
                                    wsClient.request(
                                        WsMethods.IMAGE_ATTACH_BYTES,
                                        mapOf(
                                            "session_id" to owner.agentSessionId,
                                            "content_base64" to "data:${attachment.mimeType};base64,$b64",
                                            "filename" to attachment.name,
                                            "ext" to attachment.fileExtension,
                                        ),
                                    )
                                } ?: return@launch
                            val response = deferred.await()
                            val result = if (response is JsonElement) response.toAny() else response

                            @Suppress("UNCHECKED_CAST")
                            val ok = (result as? Map<String, Any?>)?.get("attached") as? Boolean
                            if (ok != true) error("Image attachment was not accepted")
                        } else {
                            val deferred =
                                enqueueOwned {
                                    wsClient.request(
                                        WsMethods.FILE_ATTACH,
                                        mapOf(
                                            "session_id" to owner.agentSessionId,
                                            "data_url" to "data:${attachment.mimeType};base64,$b64",
                                            "name" to attachment.name,
                                        ),
                                    )
                                } ?: return@launch
                            val response = deferred.await()
                            val result = if (response is JsonElement) response.toAny() else response

                            @Suppress("UNCHECKED_CAST")
                            val refText = (result as? Map<String, Any?>)?.get("ref_text") as? String
                            if (refText.isNullOrBlank()) error("File attachment was not accepted")
                            fileRefs.add(refText)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed to upload attachment ${attachment.name}", e)
                        failOutgoingBeforeSubmit(
                            msgToPersist.id,
                            "Upload failed: ${attachment.name}",
                            dispatchGeneration,
                        )
                        return@launch
                    }
                }

                val fullText =
                    if (fileRefs.isEmpty()) {
                        text
                    } else {
                        fileRefs.joinToString("\n") +
                            if (text.isNotBlank()) "\n\n$text" else ""
                    }
                if (isCurrentSendContext(owner)) {
                    ActiveSessionHolder.set(
                        owner.agentSessionId,
                        owner.storageSessionId,
                    )
                }
                captureTurnUsageBaselineIfNeeded()
                if (wasStreaming && attachments.isEmpty() && !queued) {
                    val sent =
                        if (mode == BusySendMode.GUIDE) {
                            enqueueOwned {
                                wsClient.send(
                                    WsMethods.SESSION_STEER,
                                    mapOf("session_id" to owner.agentSessionId, "text" to fullText),
                                    onSent = { id ->
                                        trackOutgoingRequest(
                                            id,
                                            msgToPersist.id,
                                            WsMethods.SESSION_STEER,
                                            dispatchGeneration,
                                            owner.storageSessionId,
                                        )
                                    },
                                )
                            }
                        } else {
                            enqueueOwned {
                                wsClient.sendRedirect(
                                    owner.agentSessionId,
                                    fullText,
                                    onSent = { id ->
                                        trackOutgoingRequest(
                                            id,
                                            msgToPersist.id,
                                            WsMethods.SESSION_REDIRECT,
                                            dispatchGeneration,
                                            owner.storageSessionId,
                                        )
                                    },
                                )
                            }
                        }
                    if (sent == null) return@launch
                } else {
                    if (!queued) prepareTurnCorrelation(owner.storageSessionId)
                    val sent =
                        enqueueOwned {
                            wsClient.sendMessage(
                                owner.agentSessionId,
                                fullText,
                                onSent = { id ->
                                    trackOutgoingRequest(
                                        id,
                                        msgToPersist.id,
                                        WsMethods.PROMPT_SUBMIT,
                                        dispatchGeneration,
                                        owner.storageSessionId,
                                    )
                                },
                                queued = queued,
                            )
                        }
                    if (sent == null) return@launch
                }
            } finally {
                preparedAttachments.forEach { it.encodedFile.delete() }
            }
        }
    }

    /**
     * Arms the reply-notification turn boundary for a prompt about to be
     * submitted. Best-effort by design: sending chat never depends on REST
     * health, so a failed read only makes this turn's reply notification
     * uncorrelatable — it stays active instead of being dismissed by a guess.
     */
    private suspend fun prepareTurnCorrelation(storageSessionId: String?) {
        if (storageSessionId.isNullOrBlank()) return
        captureTurnBoundary(scopeId = correlationScopeId(), sessionId = storageSessionId)
    }

    private fun captureTurnUsageBaselineIfNeeded() {
        if (_streamingState.value.turnUsageBaselineCaptured) return
        _streamingState.update {
            if (it.turnUsageBaselineCaptured) {
                it
            } else {
                it.copy(
                    turnUsageBaseline = _uiState.value.sessionUsage,
                    turnUsageBaselineCaptured = true,
                )
            }
        }
    }

    /** Snapshot one URI to private cache while enforcing the outbound frame limit. */
    private suspend fun prepareAttachment(attachment: Attachment): PrepareAttachmentResult {
        var encodedFile: File? = null
        return try {
            val context = getApplication<Application>()
            val uri = Uri.parse(attachment.uri)
            encodedFile = File.createTempFile("chat-attachment-", ".b64", context.cacheDir)
            val result =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    encodedFile.outputStream().buffered().use { output ->
                        encodeAttachmentBase64(input, output)
                    }
                } ?: return PrepareAttachmentResult.Unreadable.also { encodedFile.delete() }

            when (result) {
                AttachmentSizeResult.WITHIN_LIMIT -> {
                    PrepareAttachmentResult.Success(PreparedAttachment(attachment, encodedFile))
                }

                AttachmentSizeResult.TOO_LARGE -> {
                    PrepareAttachmentResult.TooLarge.also { encodedFile.delete() }
                }
            }
        } catch (e: Exception) {
            encodedFile?.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to prepare attachment: ${e.message}", e)
            PrepareAttachmentResult.Unreadable
        }
    }

    private fun rejectOversizedAttachment(
        attachment: Attachment,
        attachments: List<Attachment>,
        message: ChatMessage,
        wasStreaming: Boolean,
        generation: Long,
    ) {
        Log.w(TAG, "Rejecting oversized attachment: ${attachment.name}")
        if (generation != sessionGeneration) return
        removePendingSend(message.id)
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == message.id },
                pendingAttachments = attachments + state.pendingAttachments,
                composerTextToRestore = message.content,
                errorMessage = attachmentTooLargeMessage(attachment),
                isAgentTyping = wasStreaming,
            )
        }
    }

    private fun attachmentTooLargeMessage(attachment: Attachment): String =
        "Attachment too large: ${attachment.name} (maximum 10 MB)"

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

    fun addAttachments(attachments: List<Attachment>) = attachmentsDelegate.addAttachments(attachments)

    fun removeAttachment(index: Int) = attachmentsDelegate.removeAttachment(index)

    fun openAttachment(attachment: Attachment) = mediaDelegate.openAttachment(attachment)

    fun saveAttachment(
        attachment: Attachment,
        destination: android.net.Uri,
    ) = mediaDelegate.saveAttachment(attachment, destination)

    fun clearOpenError() {
        _uiState.update { it.copy(openError = null) }
    }

    fun consumeComposerTextRestore() {
        _uiState.update { it.copy(composerTextToRestore = null) }
    }

    fun gatewayPathFor(attachment: Attachment): String = mediaDelegate.gatewayPathFor(attachment)

    fun clearAttachments() = attachmentsDelegate.clearAttachments()

    private fun handleSlashCommand(command: String) {
        // Classify FIRST (pure logic) — /queue's optimistic bubble must show
        // the queued TEXT (prefix stripped, see QueuePrompt.displayContent) so
        // it matches the server echo and the transcript sync dedupes instead
        // of rendering a duplicate below its answer.
        val result = slashDispatcher.dispatch(command)

        if (result is SlashResult.SideQuestion) {
            handleSideQuestionCommand(result.question)
            return
        }

        val displayContent =
            if (result is SlashResult.QueuePrompt) result.displayContent else command
        val userMsg =
            ChatMessage(
                role = MessageRole.USER,
                content = displayContent,
                tokenCount = TokenEstimator.estimate(displayContent).takeIf { it > 0 },
                // Follow-up to #1253: stripped /queue text is an unconfirmed prompt,
                // not a permanently-local command or an ambiguous legacy cache row.
                messageProvenance =
                    if (result is SlashResult.QueuePrompt && displayContent != command) {
                        MessageProvenance.LOCAL_PENDING
                    } else {
                        MessageProvenance.UNKNOWN
                    },
            )
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + userMsg) }

        // Persist — OUTSIDE update{}
        if (sessionId != null && result !is SlashResult.QueuePrompt) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(userMsg, sessionId)
            }
        }

        if (result is SlashResult.Undo) {
            handleUndoCommand(result.count)
            return
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
                val currentTitle = _uiState.value.chatTitle
                if (currentTitle.equals("Bot Chat", ignoreCase = true)) {
                    // Bot Chat is a canonical forever-conversation — compact instead of creating an orphan
                    val sessionId = _uiState.value.currentSessionId
                    if (!sessionId.isNullOrBlank()) {
                        addAssistantMessage(
                            "Bot chats are one continuous conversation — compacting instead. " +
                                "For a throwaway session, create a standard chat.",
                        )
                        dispatchViaRpc("/compact")
                    } else {
                        createNewSession()
                    }
                } else {
                    createNewSession()
                }
            }

            is SlashResult.SessionBranch -> {
                branchSession(command)
            }

            is SlashResult.ModelSwitch -> {
                modelSwitchDelegate.handleModelSwitch(command)
            }

            is SlashResult.ReasoningSwitch -> {
                handleReasoningSlashCommand(result.level)
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
                handleQueueCommand(command, userMsg)
            }

            is SlashResult.SideQuestion -> {
                handleSideQuestionCommand(result.question)
            }

            is SlashResult.Undo -> {
                handleUndoCommand(result.count)
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
    private fun handleQueueCommand(
        command: String,
        userMessage: ChatMessage,
    ) {
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        if (arg.isBlank()) {
            addAssistantMessage("usage: /queue <prompt>")
            return
        }
        val storageId = _uiState.value.currentSessionId ?: return
        val runtimeId = runtimeSessionId ?: return
        val busy =
            mainTurnBusy ||
                sendStore.all().any {
                    it.scope == sendScope() && it.sessionId == storageId &&
                        it.state in setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
                }
        try {
            sendStore.put(
                PendingSend(
                    id = userMessage.id,
                    scope = sendScope(),
                    sessionId = storageId,
                    text = arg,
                    mode = BusySendMode.QUEUE,
                    state = if (busy) PendingSendState.QUEUED else PendingSendState.SENDING,
                ),
            )
        } catch (e: Exception) {
            removeUnconfirmedBubble(userMessage.id)
            _uiState.update { it.copy(errorMessage = "Could not save message for delivery") }
            return
        }
        publishPendingSends()
        if (busy) {
            viewModelScope.launch(ioDispatcher) { repo.persistMessage(userMessage, storageId) }
        } else {
            dispatchPrompt(
                text = arg,
                attachments = emptyList(),
                wasStreaming = false,
                storageSessionId = storageId,
                agentSessionId = runtimeId,
                userMessage = userMessage,
                mode = BusySendMode.QUEUE,
                queued = true,
            )
        }
    }

    private fun handleReasoningSlashCommand(arg: String) {
        val sessionId = runtimeSessionId
        if (sessionId.isNullOrBlank()) {
            addAssistantMessage("Reasoning controls require an active session.")
            return
        }

        val parsed = parseReasoningSlashArgs(arg)

        // Bare `/reasoning` is a status query, matching Desktop/TUI semantics.
        if (parsed == null) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val result =
                        wsClient
                            .request(
                                WsMethods.CONFIG_GET,
                                mapOf(
                                    "key" to "reasoning",
                                    "session_id" to sessionId,
                                ),
                            ).await()
                    val map = rpcResultMap(result) ?: error("Invalid reasoning config.get response")
                    val value = (map["value"] as? String)?.takeIf { it.isNotBlank() } ?: "unknown"
                    val display = (map["display"] as? String)?.takeIf { it.isNotBlank() } ?: "unknown"
                    addAssistantMessage("reasoning: $value · display $display")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addAssistantMessage("Could not read reasoning status: ${e.message ?: e.toString()}")
                }
            }
            return
        }

        val value = parsed.value.trim().lowercase()

        // Effort changes use the acknowledged delegate so the composer shows
        // pending state and prompt submission remains gated until config.set
        // succeeds. A global preference may target a future model, so do not
        // reject it based on only the current model's capabilities.
        if (value in REASONING_EFFORT_LEVELS) {
            if (parsed.scopeName != "global") {
                val caps = _uiState.value.currentModelCapabilities
                if (caps?.reasoningSupport == false) {
                    addAssistantMessage("Reasoning is not supported for the current model.")
                    return
                }
                if (value == "none" && caps?.can_disable_reasoning == false) {
                    addAssistantMessage("Reasoning cannot be disabled for this model (always on).")
                    return
                }
            }
            modelSwitchDelegate.setReasoningLevel(
                value,
                scopeName = parsed.scopeName ?: "session",
                enforceCapabilities = parsed.scopeName != "global",
            )
            return
        }

        // Display controls (`show`/`hide`/`full`/`clamp` and backend aliases)
        // and future config.set reasoning values go through the canonical
        // backend parser instead of a client-maintained allowlist. Invalid
        // values therefore surface the real 4002 error instead of being eaten
        // locally. Scope flags are forwarded exactly like Desktop.
        viewModelScope.launch(ioDispatcher) {
            try {
                val params =
                    buildMap<String, Any> {
                        put("key", "reasoning")
                        put("value", parsed.value)
                        put("session_id", sessionId)
                        parsed.scopeName?.let { put("scope", it) }
                    }
                val result = wsClient.request(WsMethods.CONFIG_SET, params).await()
                val map = rpcResultMap(result) ?: error("Invalid reasoning config.set response")
                val responseKey = map["key"] as? String
                val responseValue = map["value"] as? String
                if (responseKey != "reasoning" || responseValue.isNullOrBlank()) {
                    error("Invalid reasoning config.set response")
                }
                addAssistantMessage("reasoning: $responseValue")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addAssistantMessage("Could not change reasoning: ${e.message ?: e.toString()}")
            }
        }
    }

    // ── Side Questions via /btw (issue #1015) ─────────────────────────────

    fun dismissBtw() {
        _uiState.update { it.copy(btwState = null) }
    }

    fun submitSideQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            addAssistantMessage(
                "Usage: `/btw <question>` — Ask a side question about this session without mutating its history.",
            )
            return
        }
        val sessionId = runtimeSessionId ?: _uiState.value.currentSessionId
        if (sessionId.isNullOrBlank()) {
            addAssistantMessage("No active session for side questions. Start a chat first.")
            return
        }

        _uiState.update {
            it.copy(
                btwState =
                    BtwUiState(
                        question = trimmed,
                        isLoading = true,
                    ),
            )
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val rpcResult =
                    wsClient
                        .request(
                            WsMethods.PROMPT_BTW,
                            mapOf("session_id" to sessionId, "text" to trimmed),
                        ).await()
                val taskId = (rpcResult as? Map<*, *>)?.get("task_id") as? String
                if (!taskId.isNullOrBlank()) {
                    _uiState.update { state ->
                        state.btwState?.let { current ->
                            state.copy(btwState = current.copy(taskId = taskId))
                        } ?: state
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.btwState?.let { current ->
                        state.copy(
                            btwState =
                                current.copy(
                                    isLoading = false,
                                    error = e.message ?: "Failed to dispatch side question",
                                ),
                        )
                    } ?: state
                }
            }
        }
    }

    private fun handleSideQuestionCommand(question: String) {
        viewModelScope.launch {
            slashUsageStore.recordUse("btw")
        }
        submitSideQuestion(question)
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
        val storageSessionId = _uiState.value.currentSessionId
        _uiState.update { it.copy(isAgentTyping = true) }
        viewModelScope.launch(ioDispatcher) {
            // A queued prompt runs behind a turn that is already in flight, so
            // no clean lower bound exists for it (see /queue). It stays
            // uncorrelated rather than borrowing the running turn's boundary.
            if (!queued) {
                prepareTurnCorrelation(storageSessionId)
            }
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
                queued = queued,
            )
        }
    }

    private fun addAssistantMessage(text: String) {
        val msg =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = text,
                displayKind = "local_feedback",
            )
        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        val sessionId = _uiState.value.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch(ioDispatcher) {
                repo.persistMessage(msg, sessionId)
            }
        }
    }

    private fun handleUndoCommand(count: String) {
        val sessionId = runtimeSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session to undo.")
            return
        }
        viewModelScope.launch {
            slashUsageStore.recordUse("/undo")
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val result =
                    wsClient
                        .request(
                            WsMethods.COMMAND_DISPATCH,
                            mapOf("name" to "undo", "arg" to count, "session_id" to sessionId),
                        ).await()
                handleDispatchResult(result)
            } catch (e: HermesWsClient.HermesRpcException) {
                addAssistantMessage(e.message ?: "Failed to undo.")
            } catch (e: Exception) {
                addAssistantMessage(e.message ?: "Failed to undo.")
            }
        }
    }

    private fun handlePrefillResult(
        message: String,
        notice: String,
    ) {
        val storageSessionId = _uiState.value.currentSessionId
        if (message.isNotBlank()) {
            _uiState.update { it.copy(pendingPrefillText = message) }
        }
        val feedback =
            when {
                notice.isNotBlank() && message.isNotBlank() -> "$notice (Rewound to: \"$message\")"
                notice.isNotBlank() -> notice
                message.isNotBlank() -> "↶ Rewound to: \"$message\""
                else -> "↶ Rewound"
            }
        if (storageSessionId != null) {
            val generation = sessionGeneration
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    repo.clearMessagesForSession(storageSessionId)
                }
                _uiState.update { it.copy(messages = emptyList()) }
                loadSessionMessages(storageSessionId, generation)
                addSystemMessage(feedback, persist = true)
                fetchContextUsage()
            }
        } else {
            addSystemMessage(feedback, persist = false)
        }
    }

    fun consumePendingPrefill() {
        _uiState.update { it.copy(pendingPrefillText = null) }
    }

    // ── Session management ───────────────────────────────────────────────

    fun interruptSession() {
        val sessionId = runtimeSessionId ?: return
        _uiState.value.currentSessionId?.let { storageId ->
            sendStore.park(sendScope(), storageId)
            publishPendingSends()
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_INTERRUPT) },
            )
        }
    }

    /**
     * Send course-correction guidance to a live subagent child session (issue #1030).
     */
    fun steerSubagent(
        indicator: SubagentIndicator,
        message: String,
    ) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        val sessionId = runtimeSessionId ?: return
        val subagentId = indicator.subagentId
        val steerCommand =
            if (!subagentId.isNullOrBlank()) {
                "/steer $subagentId $trimmed"
            } else {
                "/steer $trimmed"
            }
        viewModelScope.launch(ioDispatcher) {
            wsClient.sendRedirect(
                sessionId,
                steerCommand,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_REDIRECT) },
            )
        }
        _uiState.update { current ->
            val updated =
                current.subagentIndicators.map { ind ->
                    if (ind.subagentId == indicator.subagentId &&
                        (ind.goal == indicator.goal || indicator.subagentId != null)
                    ) {
                        val newLogs =
                            (ind.logs + SubagentLogLine(text = "Course correction: \"$trimmed\"", isSummary = true))
                                .takeLast(30)
                        ind.copy(status = "steered", logs = newLogs)
                    } else {
                        ind
                    }
                }
            current.copy(subagentIndicators = updated)
        }
    }

    /**
     * Stop / interrupt a single live subagent early (issue #1030).
     */
    fun stopSubagent(indicator: SubagentIndicator) {
        val sessionId = runtimeSessionId ?: return
        val subagentId = indicator.subagentId
        val stopCommand =
            if (!subagentId.isNullOrBlank()) {
                "/stop $subagentId"
            } else {
                "/stop"
            }
        viewModelScope.launch(ioDispatcher) {
            wsClient.sendRedirect(
                sessionId,
                stopCommand,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_REDIRECT) },
            )
        }
        _uiState.update { current ->
            val updated =
                current.subagentIndicators.map { ind ->
                    if (ind.subagentId == indicator.subagentId &&
                        (ind.goal == indicator.goal || indicator.subagentId != null)
                    ) {
                        val newLogs =
                            (ind.logs + SubagentLogLine(text = "Stopped subagent", isError = true))
                                .takeLast(30)
                        ind.copy(status = "cancelled", logs = newLogs)
                    } else {
                        ind
                    }
                }
            current.copy(subagentIndicators = updated)
        }
    }

    fun hydrateSubagents(sessionId: String? = null) {
        subagentsDelegate.hydrateSubagents(sessionId ?: runtimeSessionId ?: _uiState.value.currentSessionId)
    }

    fun toggleSubagentTranscript(subagentId: String) {
        subagentsDelegate.toggleSubagentTranscript(subagentId)
    }

    fun retrySubagentTranscript() {
        subagentsDelegate.retryTranscript()
    }

    fun closeSubagentTranscript() {
        subagentsDelegate.closeSubagentTranscript()
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
                busySendMode = AuthManager.getBusySendMode(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
                messageStatsEnabled = AuthManager.isMessageStatsEnabled(),
                showUserMessageTokens = AuthManager.isUserMessageTokensEnabled(),
                showAssistantMessageTokens = AuthManager.isAssistantMessageTokensEnabled(),
                showTokensPerSecond = AuthManager.isTokensPerSecondEnabled(),
                showModelProvider = AuthManager.isModelProviderShown(),
            )
        }
        publishPendingSends()
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

    fun openModelPicker() = modelSwitchDelegate.openModelPicker()

    fun refreshModelOptions() = modelSwitchDelegate.refreshModelOptions()

    fun closeModelPicker() = modelSwitchDelegate.closeModelPicker()

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) = modelSwitchDelegate.togglePinModel(providerSlug, modelName)

    fun sendSlashModel(
        provider: String,
        model: String,
    ) = modelSwitchDelegate.sendSlashModel(provider, model)

    fun dismissModelSwitchConfirm() = modelSwitchDelegate.dismissModelSwitchConfirm()

    fun confirmModelSwitchExpensive() = modelSwitchDelegate.confirmModelSwitchExpensive()

    fun setReasoningLevel(level: String?) = modelSwitchDelegate.setReasoningLevel(level)

    fun toggleFastMode() = modelSwitchDelegate.toggleFastMode()

    fun getModelCapabilities(
        providerSlug: String,
        modelName: String,
    ): ModelCapabilities? = modelSwitchDelegate.getModelCapabilities(providerSlug, modelName)

    fun getCurrentModelCapabilities(): ModelCapabilities? = modelSwitchDelegate.getCurrentModelCapabilities()

    fun openTimeline() {
        if (_uiState.value.currentSessionId == null || _timelineState.value.isOpen) return
        searchDelegate.clearSearch()
        _timelineState.update {
            it.copy(
                isOpen = true,
                entries = emptyList(),
                isLoading = false,
                hasMore = false,
                nextCursor = null,
                errorMessage = null,
                windowErrorMessage = null,
            )
        }
        loadTimelinePage(reset = true)
    }

    fun closeTimeline() {
        timelineJob?.cancel()
        historyWindowJob?.cancel()
        activeTimelineRequestSequence = ++timelineRequestSequence
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _timelineState.update {
            it.copy(
                isOpen = false,
                isLoading = false,
                jumpingRowId = null,
            )
        }
    }

    fun retryTimeline() {
        if (!_timelineState.value.isOpen) return
        _timelineState.update { it.copy(windowErrorMessage = null) }
        loadTimelinePage(reset = true)
    }

    fun loadMoreTimeline() {
        val state = _timelineState.value
        if (!state.isOpen || state.isLoading || !state.hasMore || state.nextCursor == null) return
        loadTimelinePage(reset = false)
    }

    private fun loadTimelinePage(reset: Boolean) {
        val sessionId = _uiState.value.currentSessionId ?: return
        val generation = sessionGeneration
        val profile = AuthManager.activeProfileId.value
        val cursor = if (reset) 0 else _timelineState.value.nextCursor ?: return
        val requestSequence = ++timelineRequestSequence
        activeTimelineRequestSequence = requestSequence
        timelineJob?.cancel()
        _timelineState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                entries = if (reset) emptyList() else it.entries,
                hasMore = if (reset) false else it.hasMore,
                nextCursor = if (reset) null else it.nextCursor,
            )
        }
        val valid = {
            generation == sessionGeneration &&
                sessionId == _uiState.value.currentSessionId &&
                profile == AuthManager.activeProfileId.value &&
                requestSequence == activeTimelineRequestSequence
        }
        timelineJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall {
                                ApiClient.hermesApi.getSessionTimeline(
                                    sessionId = sessionId,
                                    profile = profile,
                                    limit = TIMELINE_PAGE_SIZE,
                                    afterRowId = cursor,
                                )
                            }
                        }
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val page = result.data
                            val nextCursor = page.pagination.next_cursor
                            _timelineState.update { current ->
                                if (!valid()) {
                                    current
                                } else {
                                    val entries =
                                        (if (reset) page.entries else current.entries + page.entries)
                                            .distinctBy { it.row_id }
                                    current.copy(
                                        entries = entries,
                                        hasMore = page.pagination.has_more && nextCursor != null,
                                        nextCursor = nextCursor,
                                        errorMessage = null,
                                    )
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            _timelineState.update {
                                if (valid()) {
                                    it.copy(errorMessage = "Failed to load timeline: ${result.error.message}")
                                } else {
                                    it
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        _timelineState.update { it.copy(errorMessage = "Failed to load timeline: ${e.message}") }
                    }
                } finally {
                    if (valid()) _timelineState.update { it.copy(isLoading = false) }
                }
            }
    }

    fun jumpToTimelineEntry(rowId: Int) {
        if (rowId <= 0) return
        val sessionId = _uiState.value.currentSessionId ?: return
        val generation = sessionGeneration
        val profile = AuthManager.activeProfileId.value
        val requestSequence = ++historyWindowRequestSequence
        activeHistoryWindowRequestSequence = requestSequence
        historyWindowJob?.cancel()
        _timelineState.update {
            it.copy(
                jumpingRowId = rowId,
                windowErrorMessage = null,
            )
        }
        val valid = {
            generation == sessionGeneration &&
                sessionId == _uiState.value.currentSessionId &&
                profile == AuthManager.activeProfileId.value &&
                requestSequence == activeHistoryWindowRequestSequence
        }
        historyWindowJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall {
                                ApiClient.hermesApi.getSessionMessagesAround(
                                    sessionId = sessionId,
                                    rowId = rowId,
                                    profile = profile,
                                    limit = HISTORY_WINDOW_SIZE,
                                )
                            }
                        }
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val response = result.data
                            if (response.messages.size > HISTORY_WINDOW_SIZE) {
                                _timelineState.update {
                                    it.copy(windowErrorMessage = "History response exceeded the page limit.")
                                }
                                return@launch
                            }
                            val page =
                                withContext(historyDispatcher) {
                                    mapServerMessages(
                                        sessionId = sessionId,
                                        messages = response.messages,
                                        offset = response.pagination.offset,
                                        latestPaging = false,
                                        liveMessages = _uiState.value.messages,
                                        isPagingOlder = true,
                                        stableRowIds = true,
                                        context = getApplication(),
                                    )
                                }
                            if (!valid()) return@launch
                            val targetId = "rest-$sessionId-$rowId"
                            if (page.none { it.id == targetId || it.canonicalRestId == targetId }) {
                                _timelineState.update {
                                    it.copy(windowErrorMessage = "The selected prompt is no longer available.")
                                }
                                return@launch
                            }
                            if (!valid()) return@launch
                            _timelineState.update {
                                it.copy(
                                    isOpen = false,
                                    historyMessages = page,
                                    historyAnchorRowId = rowId,
                                    historyHasOlder = response.pagination.has_older,
                                    historyHasNewer = response.pagination.has_newer,
                                    windowErrorMessage = null,
                                )
                            }
                        }

                        is NetworkResult.Failure -> {
                            _timelineState.update {
                                if (valid()) {
                                    it.copy(windowErrorMessage = "Failed to load message: ${result.error.message}")
                                } else {
                                    it
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        _timelineState.update { it.copy(windowErrorMessage = "Failed to load message: ${e.message}") }
                    }
                } finally {
                    if (valid()) _timelineState.update { it.copy(jumpingRowId = null) }
                }
            }
    }

    fun returnToLatestMessages() {
        historyWindowJob?.cancel()
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _timelineState.update {
            it.copy(
                jumpingRowId = null,
                windowErrorMessage = null,
                historyMessages = null,
                historyAnchorRowId = null,
                historyHasOlder = false,
                historyHasNewer = false,
            )
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
        val title =
            _uiState.value.sessions
                .find { it.id == sessionId }
                ?.title ?: "Hermes"
        val generation = resetSessionState(sessionId, title, isLoading = true)
        AuthManager.setLastOpenedSessionId(sessionId)
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

    /**
     * CPU work never runs in StateFlow.update: it may retry. If a WS event or another
     * page changes the transcript during compute, recompute against that newer snapshot.
     */
    private suspend fun mergeHistoryPage(
        isCurrent: () -> Boolean,
        cached: Boolean = false,
        prepend: Boolean = false,
        mapPage: (List<ChatMessage>) -> List<ChatMessage>,
    ): List<ChatMessage>? {
        while (isCurrent()) {
            val snapshot = _uiState.value
            // Receipt bubbles can be absent after rejection/reconnect. Offer their identities
            // to the canonical matcher without displaying unconfirmed messages again.
            val visibleIds = snapshot.messages.map { it.id }.toSet()
            val currentSendScope = sendScope()
            val receiptRows =
                sendStore
                    .all()
                    .filter { it.scope == currentSendScope && it.sessionId == snapshot.currentSessionId }
            val receiptBackedIds =
                receiptRows
                    .filter {
                        it.state in
                            setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
                    }.mapTo(mutableSetOf()) { it.id }
            val receiptCandidates =
                if (cached) {
                    emptyList()
                } else {
                    receiptRows
                        .filter {
                            it.id !in visibleIds &&
                                it.state in
                                setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
                        }.map {
                            ChatMessage(
                                id = it.id,
                                role = MessageRole.USER,
                                content = it.text,
                                attachments = it.attachments,
                                timestamp = it.createdAt,
                            )
                        }
                }
            val receiptCandidateIds = receiptCandidates.map { it.id }.toSet()
            val computed =
                withContext(historyDispatcher) {
                    val mapped = mapPage(snapshot.messages)
                    val currentById = snapshot.messages.associateBy { it.id }
                    // Durable provenance distinguishes new unsent prompts after process death.
                    // Legacy UUID-only USER rows remain unconfirmed, but restored placement is
                    // independent of delivery: UNKNOWN must not pin old prompts after fresh replies.
                    val page =
                        if (cached) {
                            mapped.map { message ->
                                message.copy(
                                    isHistoricalCache =
                                        when {
                                            currentById[message.id]?.isHistoricalCache == false -> false
                                            message.isPermanentlyLocal() -> false
                                            message.messageProvenance == MessageProvenance.LOCAL_PENDING -> false
                                            message.canonicalRestId != null -> true
                                            message.role == MessageRole.USER -> false
                                            else -> true
                                        },
                                    isRestoredUnconfirmed =
                                        when {
                                            message.role != MessageRole.USER ||
                                                message.canonicalRestId != null ||
                                                message.isPermanentlyLocal() ||
                                                message.messageProvenance == MessageProvenance.LOCAL_PENDING ||
                                                message.id in receiptBackedIds -> {
                                                false
                                            }

                                            currentById[message.id] != null -> {
                                                currentById.getValue(message.id).isRestoredUnconfirmed
                                            }

                                            else -> {
                                                true
                                            }
                                        },
                                )
                            }
                        } else {
                            mapped
                        }
                    val merged =
                        if (cached) {
                            mergeCachedTranscriptPage(page, snapshot.messages)
                        } else {
                            mergeTranscriptWithLive(
                                page,
                                snapshot.messages + receiptCandidates,
                                chronological = !prepend,
                                preserveLiveIds = true,
                            ).filterNot { it.id in receiptCandidateIds && it.canonicalRestId == null }
                        }
                    val stableMessages = if (merged == snapshot.messages) snapshot.messages else merged
                    Triple(page, stableMessages, hydrateTodosFromMessages(merged).ifEmpty { snapshot.todos })
                }
            if (!isCurrent()) return null
            var applied = false
            _uiState.update { current ->
                applied = isCurrent() && current.messages === snapshot.messages && current.todos === snapshot.todos
                if (applied) current.copy(messages = computed.second, todos = computed.third) else current
            }
            if (applied) return computed.first
        }
        return null
    }

    private fun publishHistoryAvailability() {
        _uiState.update { it.copy(hasOlderMessages = cacheHasOlder || serverHasOlder) }
    }

    private fun resetTimelineState() {
        timelineJob?.cancel()
        historyWindowJob?.cancel()
        activeTimelineRequestSequence = ++timelineRequestSequence
        activeHistoryWindowRequestSequence = ++historyWindowRequestSequence
        _timelineState.value = ChatTimelineState()
    }

    private suspend fun readCachedPage(
        sessionId: String,
        generation: Long,
        isCurrent: () -> Boolean = { isCurrentSessionRequest(sessionId, generation) },
    ): Boolean {
        val before = cacheCursor
        // Room suspend queries own their IO executor; entity mapping/dedup runs on Default.
        val page = withContext(historyDispatcher) { repo.loadPage(sessionId, before, MESSAGE_PAGE_SIZE) }
        val valid = { isCurrent() && cacheCursor == before }
        if (!valid()) return false
        mergeHistoryPage(valid, cached = true) { page.messages } ?: return false
        if (!valid()) return false
        cacheCursor = page.cursor
        cacheHasOlder = page.hasOlder
        cacheLoaded = true
        publishHistoryAvailability()
        return true
    }

    private fun loadCachedMessages(
        sessionId: String,
        generation: Long,
    ): Job {
        cacheJob?.cancel()
        return viewModelScope
            .launch {
                try {
                    readCachedPage(sessionId, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCurrentSessionRequest(sessionId, generation)) {
                        // The first read can be retried by the same older-history action.
                        cacheHasOlder = true
                        publishHistoryAvailability()
                        _uiState.update { it.copy(errorMessage = "Failed to load cached messages: ${e.message}") }
                    }
                } finally {
                    if (isCurrentSessionRequest(sessionId, generation) && _uiState.value.messages.isNotEmpty()) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            }.also { cacheJob = it }
    }

    private fun loadSessionMessages(
        sessionId: String,
        generation: Long,
    ) {
        if (activeHydrationRequestSequence != 0L && cacheJob?.isActive == true) {
            cacheJob?.cancel()
            if (!cacheLoaded) loadCachedMessages(sessionId, generation)
        }
        val requestSequence = ++hydrationRequestSequence
        activeHydrationRequestSequence = requestSequence
        hydrationJob?.cancel()
        olderJob?.cancel()
        syncJob?.cancel()
        isSyncingMessages = false
        _uiState.update { it.copy(isLoadingOlder = false) }
        val valid = { isCurrentHydration(sessionId, generation, requestSequence) }
        hydrationJob =
            viewModelScope.launch {
                try {
                    val latestResult = fetchMessagePage(sessionId, 0, MESSAGE_PAGE_SIZE, order = "latest")
                    if (!valid()) return@launch
                    // Backends without stable latest IDs use the legacy absolute-position protocol.
                    val useLatest =
                        latestResult is NetworkResult.Success &&
                            latestResult.data.pagination?.order == "latest" &&
                            latestResult.data.messages.all { it.id != null }
                    val (result, requestedOffset) =
                        if (useLatest || latestResult is NetworkResult.Failure) {
                            latestResult to 0
                        } else {
                            val count = fetchServerMessageCount(sessionId, generation, requestSequence)
                            if (!valid()) return@launch
                            val offset = (count - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
                            fetchMessagePage(sessionId, offset, MESSAGE_PAGE_SIZE) to offset
                        }
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val serverOffset = result.data.pagination?.offset ?: result.data.offset ?: requestedOffset
                            val raw = result.data.messages
                            val page =
                                mergeHistoryPage(valid) { current ->
                                    mapServerMessages(
                                        sessionId,
                                        raw,
                                        serverOffset,
                                        useLatest,
                                        current,
                                        context = getApplication(),
                                    )
                                } ?: return@launch
                            persistHistoryPage(page, sessionId)
                            if (!valid()) return@launch
                            latestPaging = useLatest
                            // Cursor counts RAW rows, including hidden reasoning/placeholder rows.
                            loadedMessageOffset = if (useLatest) serverOffset + raw.size else serverOffset
                            serverHasOlder = raw.isNotEmpty() &&
                                if (useLatest) raw.size >= MESSAGE_PAGE_SIZE else serverOffset > 0
                            sessionHasServerPresence = true
                            publishHistoryAvailability()
                            hydratedGeneration = generation
                            finishResumeWhenHydrated(generation)
                        }

                        is NetworkResult.Failure -> {
                            val error = result.error.message
                            if (error.contains("404", ignoreCase = true) ||
                                error.contains("not found", ignoreCase = true)
                            ) {
                                serverHasOlder = false
                                publishHistoryAvailability()
                                hydratedGeneration = generation
                                finishResumeWhenHydrated(generation)
                            } else {
                                if (hydratedGeneration == generation) hydratedGeneration = -1L
                                handleResumeFailure(sessionId, generation, "Failed to load messages: $error")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        hydratedGeneration = -1L
                        handleResumeFailure(sessionId, generation, "Failed to load messages: ${e.message}")
                    }
                } finally {
                    if (valid()) _uiState.update { it.copy(isLoading = false) }
                }
            }
    }

    private suspend fun persistHistoryPage(
        page: List<ChatMessage>,
        sessionId: String,
    ) {
        // A mapped page can reuse a live WS message. Never overwrite its newer persisted
        // version with the snapshot used for mapping; WS owns persistence of those IDs.
        val pageIds = page.mapNotNull { it.canonicalRestId }.toSet()
        val aliases = _uiState.value.messages.filter { it.restId in pageIds && !it.id.startsWith("rest-") }
        withContext(historyDispatcher) {
            repo.persistMessages(
                page.mapNotNull { message -> message.canonicalRestId?.let { message.copy(id = it, restId = null) } },
                sessionId,
            )
            repo.confirmIdentities(aliases, sessionId)
        }
        val scope = sendScope()
        val scopedPending =
            sendStore.all().filter { it.scope == scope && it.sessionId == sessionId }
        pendingSendIdsConfirmedByDurableAliases(aliases, scopedPending).forEach(::removePendingSend)
        publishPendingSends()
        drainPendingQueue()
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
        val finalized =
            streaming.copy(
                isStreaming = false,
                finishTimestamp = System.currentTimeMillis(),
            )
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
        // A slow drain belongs to the old session and must not block the resumed queue.
        queueDrainJob?.cancel()
        queueDrainJob = null
        connectionOperationDelegate.reset()
        mainTurnBusy = false
        lastMainCompletionAt = 0L
        cancelResumeRetry()
        contextUsageJob?.cancel()
        contextUsageJob = null
        modelGeneration++
        lastConfirmedSessionModel = null
        modelSwitchDelegate.reset()
        resumedGeneration = -1L
        hydratedGeneration = -1L
        runtimeSessionId = null
        pendingInitialPrompt = null
        ActiveSessionHolder.clear()
        cacheJob?.cancel()
        hydrationJob?.cancel()
        olderJob?.cancel()
        syncJob?.cancel()
        resetTimelineState()
        activeHydrationRequestSequence = 0L
        cacheCursor = null
        cacheHasOlder = false
        cacheLoaded = false
        serverHasOlder = false
        loadedMessageOffset = 0
        latestPaging = false
        isSyncingMessages = false
        streamingController.resetStreaming()
        _streamingState.value = StreamingState()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                isSessionReady = false,
                currentSessionId = sessionId,
                chatTitle = title,
                isAgentTyping = false,
                isSending = false,
                isThinking = false,
                thinkingText = "",
                isLoading = isLoading,
                isLoadingOlder = false,
                hasOlderMessages = false,
                streamingMessage = null,
                errorMessage = null,
                replyFailure = null,
                replyFailureProjection = null,
                openError = null,
                clarifyRequest = null,
                sudoPrompt = null,
                secretPrompt = null,
                showSessionPicker = false,
                showModelPicker = false,
                modelPickerLoading = false,
                modelSwitchConfirmMessage = null,
                currentSessionModel = null,
                currentModelCapabilities = null,
                reasoningLevel = null,
                reasoningWireLevel = null,
                pendingReasoningLevel = null,
                fastMode = false,
                isFastModeChanging = false,
                terminalBackend = null,
                usedContextTokens = null,
                fullContextTokens = null,
                contextBreakdown = null,
                compressionCount = null,
                sessionUsage = null,
                pendingAttachments = emptyList(),
                composerTextToRestore = null,
                reactionKind = null,
                subagentIndicators = emptyList(),
                todos = emptyList(),
                resumeError = null,
                isResumeRetrying = false,
                pendingPrefillText = null,
            )
        }
        publishPendingSends()
        return generation
    }

    private fun resumeSession(
        sessionId: String,
        generation: Long,
    ) {
        resumedGeneration = -1L
        hydratedGeneration = -1L
        _uiState.update { it.copy(isSessionReady = false) }
        val requestSequence = ++resumeRequestSequence
        activeResumeRequestSequence = requestSequence
        val connectionCheckpoint = connectionOperationDelegate.resumeCheckpoint()
        val profile = AuthManager.activeProfileId.value
        val params =
            mutableMapOf<String, Any>(
                "session_id" to sessionId,
                "omit_messages" to true,
            )
        if (!profile.isNullOrBlank()) {
            params["profile"] = profile
        }
        viewModelScope.launch(ioDispatcher) {
            wsClient.send(
                WsMethods.SESSION_RESUME,
                params,
                onSent = { id ->
                    trackSessionRequest(
                        id = id,
                        method = WsMethods.SESSION_RESUME,
                        generation = generation,
                        resumeSequence = requestSequence,
                        sessionId = sessionId,
                        connectionCheckpoint = connectionCheckpoint,
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
                isSessionReady = runtimeSessionId != null,
            )
        }
        drainPendingQueue()
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
        if (_uiState.value.messages.isNotEmpty()) {
            cancelResumeRetry()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isResumeRetrying = false,
                    resumeError = "Session not found on server (displaying cached messages)",
                )
            }
            return
        }
        sessionGoneRecoveryInFlight = true
        cancelResumeRetry()
        if (AuthManager.getLastOpenedSessionId() == sessionId) {
            AuthManager.clearLastOpenedSessionId()
        }
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
        _uiState.update { it.copy(errorMessage = null, isSessionReady = false) }

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
        val sessionId = _uiState.value.currentSessionId
        if (sessionId == null) {
            createNewSession()
            return
        }
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
        if (!state.hasOlderMessages || state.isLoadingOlder || olderJob?.isActive == true) return
        if (cacheJob?.isActive == true) return
        val fromCache = cacheHasOlder || !cacheLoaded
        if (!fromCache && (hydrationJob?.isActive == true || isSyncingMessages || !serverHasOlder)) return
        val generation = sessionGeneration
        val requestSequence = activeHydrationRequestSequence
        val valid = { isCurrentHydration(sessionId, generation, requestSequence) }
        _uiState.update { it.copy(isLoadingOlder = true) }
        olderJob =
            viewModelScope.launch {
                try {
                    while (cacheHasOlder || !cacheLoaded) {
                        val beforeMessages = _uiState.value.messages
                        if (!readCachedPage(sessionId, generation, valid)) return@launch
                        val afterMessages = _uiState.value.messages
                        val addedRows =
                            withContext(historyDispatcher) {
                                val previousIds = beforeMessages.mapTo(HashSet()) { it.id }
                                afterMessages.any { it.id !in previousIds }
                            }
                        if (!valid()) return@launch
                        if (addedRows) return@launch
                        // Hydration persists canonical rows behind the local cache cursor. Consume
                        // those already-visible echoes without spending another older-history action.
                    }
                    if (hydrationJob?.isActive == true || isSyncingMessages || !serverHasOlder) return@launch
                    // Cache reads may suspend through hydration/sync; use the current server cursor.
                    val useLatest = latestPaging
                    val oldOffset = loadedMessageOffset
                    val offset = if (useLatest) oldOffset else (oldOffset - MESSAGE_PAGE_SIZE).coerceAtLeast(0)
                    val limit = if (useLatest) MESSAGE_PAGE_SIZE else oldOffset - offset
                    if (limit <= 0) return@launch
                    val result = fetchMessagePage(sessionId, offset, limit, order = if (useLatest) "latest" else null)
                    if (!valid()) return@launch
                    when (result) {
                        is NetworkResult.Success -> {
                            val returnedOffset = result.data.pagination?.offset ?: result.data.offset ?: offset
                            val raw = result.data.messages
                            val page =
                                mergeHistoryPage(valid, prepend = true) { current ->
                                    mapServerMessages(
                                        sessionId,
                                        raw,
                                        returnedOffset,
                                        useLatest,
                                        current,
                                        isPagingOlder = true,
                                        context = getApplication(),
                                    )
                                } ?: return@launch
                            persistHistoryPage(page, sessionId)
                            if (!valid()) return@launch
                            loadedMessageOffset = if (useLatest) returnedOffset + raw.size else returnedOffset
                            serverHasOlder = raw.isNotEmpty() &&
                                if (useLatest) {
                                    loadedMessageOffset > oldOffset && raw.size >= limit
                                } else {
                                    returnedOffset < oldOffset && returnedOffset > 0
                                }
                            publishHistoryAvailability()
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    errorMessage = "Failed to load older messages: ${result.error.message}",
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) {
                        _uiState.update {
                            it.copy(
                                errorMessage = "Failed to load older messages: ${e.message}",
                            )
                        }
                    }
                } finally {
                    if (valid()) _uiState.update { it.copy(isLoadingOlder = false) }
                }
            }
    }

    fun syncCurrentSession() {
        if (!sessionHasServerPresence) return
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        if (isSyncingMessages || hydrationJob?.isActive == true || state.isLoading || state.isLoadingOlder ||
            state.isAgentTyping || _streamingState.value.streamingMessage != null
        ) {
            return
        }
        val generation = sessionGeneration
        val requestSequence = activeHydrationRequestSequence
        val valid = { isCurrentHydration(sessionId, generation, requestSequence) }
        val useLatest = latestPaging
        val nextOffset =
            if (useLatest) {
                0
            } else {
                state.messages
                    .mapNotNull { serverMessageIndex(it.canonicalRestId ?: it.id, sessionId) }
                    .maxOrNull()
                    ?.plus(1)
                    ?: loadedMessageOffset
            }
        isSyncingMessages = true
        syncJob =
            viewModelScope.launch {
                try {
                    val result =
                        fetchMessagePage(sessionId, nextOffset, MESSAGE_PAGE_SIZE, if (useLatest) "latest" else null)
                    if (!valid()) return@launch
                    if (result is NetworkResult.Success) {
                        val offset = result.data.pagination?.offset ?: result.data.offset ?: nextOffset
                        val page =
                            mergeHistoryPage(valid) { current ->
                                mapServerMessages(
                                    sessionId,
                                    result.data.messages,
                                    offset,
                                    useLatest,
                                    current,
                                    // Sync fetches recent replies, so confirm live completion identities.
                                    isPagingOlder = false,
                                    context = getApplication(),
                                )
                            } ?: return@launch
                        persistHistoryPage(page, sessionId)
                        // Never derive the older cursor from displayed rows or reset it to this latest page.
                        // Append-only growth shifts from-end offsets toward newer rows: the next older
                        // request may overlap, but stable IDs remove echoes without skipping any history.
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (valid()) _uiState.update { it.copy(errorMessage = "Failed to sync messages: ${e.message}") }
                } finally {
                    if (valid()) isSyncingMessages = false
                }
            }
    }

    internal fun onModelSwitchInitiated() {
        modelGeneration++
        contextUsageJob?.cancel()
        contextUsageJob = null
        _uiState.update { it.copy(fullContextTokens = null) }
    }

    private fun isCurrentContextFetch(
        sessionId: String,
        targetSessionGeneration: Long,
        targetModelGeneration: Long,
        targetRequestSequence: Long,
    ): Boolean =
        targetSessionGeneration == sessionGeneration &&
            targetModelGeneration == modelGeneration &&
            targetRequestSequence == contextFetchSequence &&
            _uiState.value.currentSessionId == sessionId

    internal fun isMatchingModel(
        currentModel: String?,
        info: com.m57.hermescontrol.data.model.ModelInfoResponse?,
    ): Boolean {
        if (currentModel.isNullOrBlank() || info == null || info.model.isNullOrBlank()) {
            return false
        }
        val restModel = info.model
        val restProvider = info.provider
        return if (currentModel.contains('/')) {
            val curProvider = currentModel.substringBefore('/')
            val curModel = currentModel.substringAfter('/')
            if (!restProvider.isNullOrBlank()) {
                curProvider.equals(restProvider, ignoreCase = true) &&
                    curModel.equals(restModel, ignoreCase = true)
            } else {
                curModel.equals(restModel, ignoreCase = true)
            }
        } else {
            currentModel.equals(restModel, ignoreCase = true)
        }
    }

    internal fun isMatchingRpcModel(
        currentModel: String?,
        rpcModel: String?,
    ): Boolean {
        if (currentModel.isNullOrBlank() || rpcModel.isNullOrBlank()) return false
        if (currentModel.equals(rpcModel, ignoreCase = true)) return true
        return currentModel.contains('/') &&
            currentModel.substringAfter('/').equals(rpcModel, ignoreCase = true)
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
        val targetSessionGeneration = sessionGeneration
        val targetModelGeneration = modelGeneration
        val targetRequestSequence = ++contextFetchSequence
        val isSwitchPending = modelSwitchDelegate.isSwitchPending()

        contextUsageJob?.cancel()
        contextUsageJob =
            viewModelScope.launch(ioDispatcher) {
                // Denominator fallback: full context window (cheap, public, rarely
                // changes). The RPC's context_max below overrides it when present.
                // Kept as a local (not a state write) so both sources resolve
                // before ONE atomic update below (issue #817 — two independent
                // writes let a stale pre-swap value override a fresh one mid-swap).
                val fullResult =
                    safeApiCall { ApiClient.hermesApi.getModelInfo() }
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                val restFull =
                    if (fullResult is NetworkResult.Success && !isSwitchPending) {
                        val info = fullResult.data
                        val currentModel = _uiState.value.currentSessionModel
                        // Issue #1103: only use profile REST model/info as fallback if it
                        // matches the current session model identity. If the session was switched to
                        // another model (e.g. Solar, Gemini), the profile-level model/info describes
                        // a different model and must never poison the session's context window.
                        if (isMatchingModel(currentModel, info)) {
                            modelSwitchDelegate.applyModelInfo(info, AuthManager.currentDataScope())
                            info.effective_context_length
                                ?: info.auto_context_length
                                ?: info.config_context_length
                        } else {
                            null
                        }
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
                        coroutineContext.ensureActive()
                        val ctx = parseContextBreakdown(result)
                        if (ctx != null) {
                            val currentModel = _uiState.value.currentSessionModel
                            val modelMatches =
                                if (ctx.model != null) {
                                    isMatchingRpcModel(currentModel, ctx.model)
                                } else {
                                    !isSwitchPending
                                }
                            if (modelMatches) {
                                rpcUsed = ctx.contextUsed?.takeIf { it > 0L }
                                rpcMax = ctx.contextMax?.takeIf { it > 0L }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best-effort: RPC error/timeout/disconnect — keep last values.
                    }
                    if (!isCurrentContextFetch(
                            sessionId,
                            targetSessionGeneration,
                            targetModelGeneration,
                            targetRequestSequence,
                        )
                    ) {
                        return@launch
                    }
                    // Compression count: how many times this session has been compacted
                    // (session.usage → compressions). Feeds the "compressed ×N" badge —
                    // the same usage snapshot the desktop status bar reads.
                    if (!isSwitchPending) {
                        try {
                            val usage =
                                sendRpcAndAwait(
                                    WsMethods.SESSION_USAGE,
                                    mapOf("session_id" to rpcSessionId),
                                )
                            coroutineContext.ensureActive()
                            val snapshot = parseUsageSnapshot(usage)
                            if (snapshot != null) {
                                _uiState.update { current ->
                                    if (!isCurrentContextFetch(
                                            sessionId,
                                            targetSessionGeneration,
                                            targetModelGeneration,
                                            targetRequestSequence,
                                        )
                                    ) {
                                        current
                                    } else {
                                        applyUsageSnapshot(current, snapshot)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Best-effort: keep the last known badge value.
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                // Issue #817 & #1103: single atomic denominator write. The RPC's live
                // context_max wins, REST model/info is the fallback, and a stale
                // value from a pre-swap fetch can never overwrite a fresh one —
                // the meter always shows ONE coherent window.
                _uiState.update { current ->
                    if (!isCurrentContextFetch(
                            sessionId,
                            targetSessionGeneration,
                            targetModelGeneration,
                            targetRequestSequence,
                        )
                    ) {
                        current
                    } else {
                        val fallbackFull =
                            if (skipRestFallback) current.fullContextTokens else restFull ?: current.fullContextTokens
                        current.copy(
                            usedContextTokens = rpcUsed ?: current.usedContextTokens,
                            fullContextTokens = rpcMax ?: fallbackFull,
                        )
                    }
                }
                // Detail-sheet accounting (cumulative REST counters, informational).
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                val usedResult =
                    safeApiCall { ApiClient.hermesApi.getSessionDetail(sessionId, profile) }
                coroutineContext.ensureActive()
                if (usedResult is NetworkResult.Success) {
                    val d = usedResult.data
                    val used = d.input_tokens
                    if (used != null) {
                        _uiState.update { current ->
                            if (!isCurrentContextFetch(
                                    sessionId,
                                    targetSessionGeneration,
                                    targetModelGeneration,
                                    targetRequestSequence,
                                )
                            ) {
                                current
                            } else {
                                current.copy(
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
    ) = historyFetchMutex.withLock {
        withContext(ioDispatcher) {
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
    }

    // ── UI actions ───────────────────────────────────────────────────────

    /**
     * Dismiss the active clarify prompt and reject it (tell the agent no answer
     * was given).
     *
     * The backend's clarify tool blocks the agent thread waiting for a response
     * (CLI timeout is 120s). A silent dismiss would leave the agent hanging
     * until that timeout, so we send an empty result frame for new gateways or
     * the legacy cancel sentinel for old notification-based gateways.
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
        val clarify = _uiState.value.clarifyRequest
        val clarifyId = clarify?.clarifyId
        val serverRequestId = clarify?.serverRequestId
        // Raw batch questions (non-empty only for true batch payloads).
        // Legacy singles keep questions empty and rely on questionId (nullable).
        val isBatch = !clarify?.questions.isNullOrEmpty()
        val displayQuestions = clarify?.resolvedQuestions.orEmpty()
        _uiState.update { it.copy(clarifyRequest = null) }

        addSystemMessage("Clarify dismissed — no answer sent", persist = true)

        viewModelScope.launch(ioDispatcher) {
            if (serverRequestId != null) {
                wsClient.respondToServerRequest(serverRequestId, buildJsonObject {})
                return@launch
            }
            if (isBatch) {
                // Send dismissal for every question in the batch
                for (q in displayQuestions) {
                    val params =
                        mutableMapOf<String, Any>(
                            "session_id" to sessionId,
                            "response" to CLARIFY_DISMISS_RESPONSE,
                            "answer" to CLARIFY_DISMISS_RESPONSE,
                            "question_id" to q.qid,
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
            } else {
                // Legacy: only send question_id when the original payload had one.
                val questionId = clarify?.questionId
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
                if (questionId != null) {
                    params["question_id"] = questionId
                }
                wsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params = params,
                    onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
                )
            }
        }
    }

    fun respondToClarify(option: String) = clarifyDelegate.respondToClarify(option)

    fun respondToClarifyBatch(
        answers: Map<String, String>,
        singleFallbackAnswer: String? = null,
    ) = clarifyDelegate.respondToClarifyBatch(answers, singleFallbackAnswer)

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

    fun respondToApproval(action: String) = approvalsDelegate.respondToApproval(action)

    // ── Sudo / secret prompt flow (issue #524) ──────────────────────────

    fun dismissSudo() = credentialPromptsDelegate.dismissSudo()

    fun dismissSecret() = credentialPromptsDelegate.dismissSecret()

    fun respondToSudo(password: String) = credentialPromptsDelegate.respondToSudo(password)

    fun respondToSecret(value: String) = credentialPromptsDelegate.respondToSecret(value)

    // ── Vault prompt flow (issue #1090) ──────────────────────────────────

    fun dismissVaultUnlock() = credentialPromptsDelegate.dismissVaultUnlock()

    fun respondToVaultUnlock(password: String) = credentialPromptsDelegate.respondToVaultUnlock(password)

    fun dismissVaultSaveLogin() = credentialPromptsDelegate.dismissVaultSaveLogin()

    fun respondToVaultSaveLogin(
        identifier: String,
        password: String,
    ) = credentialPromptsDelegate.respondToVaultSaveLogin(identifier, password)

    fun dismissVaultCode() = credentialPromptsDelegate.dismissVaultCode()

    fun respondToVaultCode(code: String) = credentialPromptsDelegate.respondToVaultCode(code)

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
        viewModelScope.launch {
            reloginAuthenticator.relogin(
                username = username,
                password = password,
                onSuccess = {
                    onResult(true, null)
                    reconnect()
                },
                onFailure = { msg ->
                    onResult(false, msg)
                },
            )
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
        connectionCheckpoint: ConnectionResumeCheckpoint? = null,
    ) {
        sessionRequestById[id] =
            SessionRequest(
                generation = generation,
                resumeSequence = resumeSequence,
                sessionId = sessionId,
                connectionCheckpoint = connectionCheckpoint,
            )
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

    fun respondToConnection(
        target: String,
        env: Map<String, String>,
        approved: Boolean,
    ) {
        viewModelScope.launch { connectionOperationDelegate.respond(target, env, approved) }
    }

    fun continueConnectionOperation() {
        viewModelScope.launch { connectionOperationDelegate.continueOperation() }
    }

    fun wakeConnectionOperation(operationId: String) {
        viewModelScope.launch { connectionOperationDelegate.wake(operationId) }
    }

    override fun onCleared() {
        super.onCleared()
        subagentsDelegate.closeSubagentTranscript()
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
