# ChatViewModel God Object Refactor (CODE-34) Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.
> **Plan mode — no code was written. This is the blueprint only.**

**Goal:** Split the 1226-line `ChatViewModel` into focused, testable components without changing app behavior.

**Architecture:** Extract five distinct concerns (WS event reduction, persistence, slash commands, search, pending request tracking) into their own classes/files. The ViewModel becomes a thin coordinator that wires them together and exposes a clean public API.

**Tech Stack:** Kotlin, Jetpack Compose, Room, OkHttp WebSocket, kotlinx.coroutines, kotlinx.coroutines.flow

**Risk:** HIGH — this is the core chat screen. Every event type, persistence call, and user action flows through ChatViewModel. Must ensure nothing breaks.

---
## Current State

### ChatViewModel has 7+ concerns tangled together

| Concern | LOC | Section |
|---------|-----|---------|
| **WS event dispatch & reduction** (12 event types) | ~400 | `handleWsEvent()`, `handleMessage*`, `handleTool*`, `handleRpc*` |
| **Message persistence** (Room DAO) | ~80 | `dao.upsert()`, `loadCachedMessages()`, `loadSessionMessages()` |
| **Slash command parsing & execution** | ~140 | `handleSlashCommand()` — 5 commands with REST calls |
| **Search** (in-chat text search) | ~80 | `toggleSearch()`, `setSearchQuery()`, `navigateSearchMatch()` |
| **Session management** | ~100 | `createNewSession()`, `switchSession()`, `loadSessions()` |
| **Pending request tracking & timeout cleanup** | ~50 | `trackRequest()`, `startPendingRequestCleanup()` |
| **Public UI action API** | ~80 | `sendMessage()`, `interruptSession()`, `respondToClarify()`, etc. |
| **Connection & lifecycle** | ~60 | `connectWebSocket()`, init, `reconnect()` |
| **Data classes** (ChatUiState, SessionUi, ClarifyUi, PendingRequest) | ~50 | Top of file |

### Files that will change

- `.../ui/chat/ChatViewModel.kt` — **slim down** from 1226 → ~300 lines
- `.../ui/chat/ChatWsEventReducer.kt` — **NEW**: pure reducer
- `.../ui/chat/ChatPersistenceRepository.kt` — **NEW**: DAO wrapper
- `.../ui/chat/SlashCommandDispatcher.kt` — **NEW**: command registry
- `.../ui/chat/ChatSearchController.kt` — **NEW**: search state + logic
- `.../ui/chat/ChatViewModelTest.kt` — **update**: refactor tests to match new structure
- `.../ui/chat/ChatScreen.kt` — possibly pass new dependencies (minimal changes)

### Data flow (current)

```
User types message
  │
  ▼
ChatViewModel.sendMessage()
  │
  ├─► SlashCommandDispatcher (currently inline)
  │     └─► REST call via ApiClient
  │
  └─► HermesWsClient.prompt()
        │
        ▼
  HermesWsClient.events (SharedFlow)
        │
        ▼
  ChatViewModel.handleWsEvent()
        │
        ▼
  ChatViewModel.handleMessageStart/Token/Complete/Done/ToolStart/ToolComplete
        │
        ▼
  ChatPersistenceRepository (currently inline dao.upsert())
  ChatUiState update via _uiState.update { }
```

### Data flow (target)

```
User types message
  │
  ▼
ChatViewModel.sendMessage()
  │
  ├─► SlashCommandDispatcher.dispatch(command)
  │     └─► REST call via ApiClient
  │
  └─► HermesWsClient.prompt()
        │
        ▼
  HermesWsClient.events (SharedFlow)
        │
        ▼
  ChatWsEventReducer.reduce(state, event) → newState
        │
        ▼
  ChatPersistenceRepository.persist(event)   (side-effect)
  ChatViewModel._uiState.update { ... }
```

---

## Step-by-Step Plan

### Task 1: Extract ChatPersistenceRepository

**Objective:** Move all Room DAO operations out of ChatViewModel into a dedicated repository.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatPersistenceRepository.kt`
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt` (remove DAO methods, inject repo)

**What to extract** — all methods that touch `dao` or `HermesDatabase`:

| Current method | Target |
|---|---|
| `dao.upsert()` in `handleMessageStart` | `repo.persistMessage(msg)` |
| `dao.upsert()` in `handleMessageComplete` | `repo.updateMessage(msg)` |
| `dao.upsert()` in `loadCachedMessages` | `repo.loadMessages(sessionId)` |
| `dao.upsert()` across slash commands | `repo.persistMessage(msg)` |

**Design:**

```kotlin
// ChatPersistenceRepository.kt
class ChatPersistenceRepository(
    private val dao: ChatMessageDao,
) {
    suspend fun persistMessage(
        message: ChatMessage,
        sessionId: String,
    ): ChatMessageEntity

    suspend fun updateMessage(
        message: ChatMessage,
        messageId: String,
    ): ChatMessageEntity

    fun loadMessages(sessionId: String): List<ChatMessage>
        // wraps dao.getMessagesForSession(sessionId).map { it.toDomain() }

    suspend fun upsertMessage(message: ChatMessage, sessionId: String)
}
```

**ChatViewModel changes:**
- Remove `private val dao = HermesDatabase.get(application).chatMessageDao()`
- Add `private val repo = ChatPersistenceRepository(HermesDatabase.get(application).chatMessageDao())`
- Replace all `dao.upsert(...)` calls with `repo.persistMessage(...)` / `repo.updateMessage(...)`
- Replace `loadCachedMessages` and `loadSessionMessages` to use repo

**Verification:**
- `./ktlint --format` passes
- Existing `ChatViewModelTest` still compiles and passes (mock the DAO at the repo boundary)

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatPersistenceRepository.kt
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): extract ChatPersistenceRepository from ChatViewModel"
```

---

### Task 2: Extract ChatWsEventReducer

**Objective:** Move all WS event handling into a pure reducer function.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatWsEventReducer.kt`
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt` (replace inline dispatch with reducer call)

**What to extract** — the pure state transformation from each WS event:

| Current method | Signature |
|---|---|
| `handleGatewayReady()` | `(ChatUiState) -> ChatUiState` |
| `handleMessageStart(event)` | `(ChatUiState, WsEvent.MessageStart) -> ReducerResult` |
| `handleMessageToken(event)` | `(ChatUiState, WsEvent.MessageToken) -> ChatUiState` |
| `handleThinkingDelta(event)` | `(ChatUiState, WsEvent.ThinkingDelta) -> ChatUiState` |
| `handleMessageComplete(event)` | `(ChatUiState, WsEvent.MessageComplete) -> ReducerResult` |
| `handleMessageDone(event)` | `(ChatUiState, WsEvent.MessageDone) -> ChatUiState` |
| `handleToolStart(event)` | `(ChatUiState, WsEvent.ToolStart) -> ChatUiState` |
| `handleToolComplete(event)` | `(ChatUiState, WsEvent.ToolComplete) -> ChatUiState` |
| `handleRpcResult(event)` | `(ChatUiState, WsEvent.RpcResult) -> ReducerResult` |
| `handleRpcError(event)` | `(ChatUiState, WsEvent.RpcError) -> ChatUiState` |
| `handleClarifyRequest(event)` | `(ChatUiState, WsEvent.ClarifyRequest) -> ChatUiState` |

**ReducerResult** — some events need side-effects (REST calls, persistence) that the reducer can't do:

```kotlin
data class ReducerResult(
    val state: ChatUiState,
    val effects: List<ReducerEffect> = emptyList(),
)

sealed class ReducerEffect {
    data class PersistMessage(val message: ChatMessage, val sessionId: String) : ReducerEffect()
    data class CreateSession : ReducerEffect()
    data class LoadSessions : ReducerEffect()
    data class FetchStatus(val command: String) : ReducerEffect()
    data class FetchSessions : ReducerEffect()
    // etc.
}
```

**Reducer design:**

```kotlin
// ChatWsEventReducer.kt
object ChatWsEventReducer {
    fun reduce(
        state: ChatUiState,
        event: WsEvent,
    ): ReducerResult = when (event) {
        is WsEvent.GatewayReady -> onGatewayReady(state)
        is WsEvent.MessageStart -> onMessageStart(state, event)
        is WsEvent.MessageToken -> onMessageToken(state, event)
        is WsEvent.ThinkingDelta -> onThinkingDelta(state, event)
        is WsEvent.MessageComplete -> onMessageComplete(state, event)
        is WsEvent.MessageDone -> onMessageDone(state, event)
        is WsEvent.ToolStart -> onToolStart(state, event)
        is WsEvent.ToolComplete -> onToolComplete(state, event)
        is WsEvent.ClarifyRequest -> onClarifyRequest(state, event)
        is WsEvent.RpcResult -> onRpcResult(state, event)
        is WsEvent.RpcError -> onRpcError(state, event)
        is WsEvent.SessionUpdated -> onSessionUpdated(state, event)
        is WsEvent.StatusUpdate -> onStatusUpdate(state, event)
        is WsEvent.Unknown -> onUnknown(state, event)
        is WsEvent.SessionInfo -> state // no-op
    }

    private fun onMessageStart(state: ChatUiState, event: WsEvent.MessageStart): ReducerResult {
        val msg = ChatMessage(
            id = event.messageId ?: event.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
        )
        val sessionId = state.currentSessionId
        return ReducerResult(
            state = state.copy(
                isAgentTyping = false,
                streamingMessage = msg,
                messages = if (sessionId == null) state.messages + msg else state.messages,
            ),
            effects = if (sessionId != null) {
                listOf(ReducerEffect.PersistMessage(msg, sessionId))
            } else emptyList(),
        )
    }
    // ... other handlers follow same pattern
}
```

**ChatViewModel changes:**
- Remove all `private fun handleMessage*()`, `handleTool*()`, `handleRpc*()` methods
- Replace `handleWsEvent(event)` body with:
  ```kotlin
  private fun handleWsEvent(event: WsEvent) {
      val result = ChatWsEventReducer.reduce(_uiState.value, event)
      _uiState.update { result.state }
      for (effect in result.effects) {
          when (effect) {
              is ReducerEffect.PersistMessage -> repo.persistMessage(...)
              is ReducerEffect.CreateSession -> createNewSession()
              is ReducerEffect.LoadSessions -> loadSessions()
              // ...
          }
      }
  }
  ```

**Verification:**
- `./ktlint --format` passes
- Existing test's event-driven tests still pass (they test through `viewModel.uiState`, which still works)
- The reducer itself should be testable without any mocking (pure function!)

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatWsEventReducer.kt
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): extract ChatWsEventReducer from ChatViewModel"
```

---

### Task 3: Extract SlashCommandDispatcher

**Objective:** Move all slash command logic into a dedicated dispatcher.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/SlashCommandDispatcher.kt`
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt`

**What to extract** — `handleSlashCommand()` and all command implementations (`/help`, `/status`, `/sessions`, `/stats`, `/stop`, `/interrupt`, `/new`).

**Design:**

```kotlin
// SlashCommandDispatcher.kt
class SlashCommandDispatcher(
    private val onInterrupt: () -> Unit,
    private val onCreateNew: () -> Unit,
    private val onAddAssistantMessage: (String) -> Unit,
    private val onFetchStatus: suspend () -> String,
    private val onFetchSessions: suspend () -> String,
) {
    private val commands: Map<String, suspend () -> String> = mapOf(
        "/stop" to { onInterrupt(); "" },
        "/interrupt" to { onInterrupt(); "" },
        "/new" to { onCreateNew(); "" },
        "/help" to { HELP_TEXT },  // static
        "/status" to { onFetchStatus() },
        "/sessions" to { onFetchSessions() },
        "/stats" to { onFetchStatus() },
        "/system" to { onFetchStatus() },
    )

    suspend fun dispatch(command: String): String? {
        val parts = command.split(" ")
        val cmd = parts[0].lowercase()
        return commands[cmd]?.invoke()
    }

    companion object {
        val SLASH_REGEX = Regex("^/(stop|interrupt|new|help|status|sessions|stats|system)\\b")

        val HELP_TEXT = """
            **Available Commands:**
            • `/help` - Show this help menu
            • `/status` - Check gateway and platform status
            • `/sessions` - List all chat sessions
            • `/stats` or `/system` - Check system resource usage
            • `/new` - Create a new chat session
            • `/stop` or `/interrupt` - Interrupt the active run
        """.trimIndent()
    }
}
```

**ChatViewModel changes:**
- Remove `handleSlashCommand()` and all command logic
- Add dispatcher as a field:
  ```kotlin
  private val slashDispatcher = SlashCommandDispatcher(
      onInterrupt = ::interruptSession,
      onCreateNew = ::createNewSession,
      onAddAssistantMessage = ::addAssistantMessage,
      onFetchStatus = { safeApiCall { ApiClient.hermesApi.getStatus() }.let { formatStatus(it) } },
      onFetchSessions = { safeApiCall { ApiClient.hermesApi.getSessions() }.let { formatSessions(it) } },
  )
  ```
- Replace `handleSlashCommand(command)` call with:
  ```kotlin
  val result = slashDispatcher.dispatch(command)
  if (result != null) {
      addAssistantMessage(result)
  }
  // or for side-effect-only commands (/stop, /new):
  // the dispatcher calls the callbacks directly
  ```

**Verification:**
- `./ktlint --format` passes
- Existing slash command tests still pass
- `/status` and `/sessions` REST calls still work (callbacks passed to dispatcher)

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/SlashCommandDispatcher.kt
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): extract SlashCommandDispatcher from ChatViewModel"
```

---

### Task 4: Extract ChatSearchController

**Objective:** Move all search state and logic into a dedicated controller.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatSearchController.kt`
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt`

**What to extract** — search-related state fields and methods:

| Current field/method | Target |
|---|---|
| `_uiState.isSearchActive` | Stays in ChatUiState |
| `_uiState.searchQuery` | Stays in ChatUiState |
| `_uiState.searchMatchIndices` | Stays in ChatUiState |
| `_uiState.currentSearchMatchIndex` | Stays in ChatUiState |
| `fun toggleSearch()` | `controller.toggle()` |
| `fun setSearchQuery(query)` | `controller.setQuery(messages, query)` |
| `fun navigateSearchMatch(direction)` | `controller.navigate(direction)` |
| `fun clearSearch()` | `controller.clear()` |

**Design:**

```kotlin
// ChatSearchController.kt
class ChatSearchController {
    fun toggle(currentState: ChatUiState): ChatUiState {
        return if (currentState.isSearchActive) {
            currentState.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        } else {
            currentState.copy(isSearchActive = true)
        }
    }

    fun setQuery(state: ChatUiState, messages: List<ChatMessage>, query: String): ChatUiState {
        if (query.isBlank()) {
            return state.copy(
                searchQuery = query,
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        }
        val indices = messages.indices.filter { i ->
            messages[i].content.contains(query, ignoreCase = true)
        }
        return state.copy(
            searchQuery = query,
            searchMatchIndices = indices,
            currentSearchMatchIndex = if (indices.isNotEmpty()) 0 else -1,
        )
    }

    fun navigate(state: ChatUiState, direction: Int): ChatUiState {
        val indices = state.searchMatchIndices
        if (indices.isEmpty()) return state
        val current = state.currentSearchMatchIndex
        val next = when (direction) {
            1 -> (current + 1) % indices.size      // forward
            -1 -> (current - 1 + indices.size) % indices.size  // backward
            else -> current
        }
        return state.copy(currentSearchMatchIndex = next)
    }
}
```

**ChatViewModel changes:**
- Add `private val searchController = ChatSearchController()`
- Replace the inline search method bodies with delegation

**Verification:**
- `./ktlint --format` passes
- Search still works end-to-end via `viewModel.uiState`

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatSearchController.kt
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): extract ChatSearchController from ChatViewModel"
```

---

### Task 5: Extract PendingRequestTracker (optional but clean)

**Objective:** Move pending request tracking and timeout cleanup into its own class.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/PendingRequestTracker.kt`
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt`

**Design:**

```kotlin
// PendingRequestTracker.kt
class PendingRequestTracker(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 30_000L,
) {
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private var cleanupJob: Job? = null

    data class PendingRequest(
        val type: String,
        val startTime: Long,
        val resolve: (String) -> Unit,
    )

    fun track(id: String, type: String, resolve: (String) -> Unit) {
        pendingRequests[id] = PendingRequest(type, System.currentTimeMillis(), resolve)
    }

    fun resolve(id: String, result: String): Boolean {
        val request = pendingRequests.remove(id) ?: return false
        request.resolve(result)
        return true
    }

    fun startCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                val now = System.currentTimeMillis()
                pendingRequests.entries.removeIf { (_, req) ->
                    if (now - req.startTime > timeoutMs) {
                        req.resolve("TIMEOUT")
                        true
                    } else false
                }
            }
        }
    }

    fun stopCleanup() {
        cleanupJob?.cancel()
    }
}
```

**Verification:**
- `./ktlint --format` passes
- Existing pending-request timeout tests pass

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/PendingRequestTracker.kt
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): extract PendingRequestTracker from ChatViewModel"
```

---

### Task 6: Clean up ChatViewModel — thin coordinator

**Objective:** The ViewModel should now only wire dependencies and expose a clean public API.

**After all extractions, ChatViewModel should contain:**

```
// ── Fields ──
- repo: ChatPersistenceRepository
- slashDispatcher: SlashCommandDispatcher
- searchController: ChatSearchController
- pendingTracker: PendingRequestTracker
- wsClient: HermesWsClient (singleton ref)
- _uiState: MutableStateFlow<ChatUiState>
- streamingBuffer / thinkingBuffer / timestamps (streaming internal state)
- streamingMessageId: String?

// ── Public API ──
+ val uiState: StateFlow<ChatUiState>
+ fun sendMessage(text: String)
+ fun interruptSession()
+ fun createNewSession()
+ fun loadSessions()
+ fun refreshCurrentSession()
+ fun refreshSettings()
+ fun switchSession(sessionId: String)
+ fun toggleSessionPicker()
+ fun dismissClarify()
+ fun respondToClarify(option: String)
+ fun clearError()
+ fun reconnect()
+ fun toggleSearch()
+ fun setSearchQuery(query: String)
+ fun navigateSearchMatch(direction: Int)
+ fun clearSearch()
+ fun onCleared()   // lifecycle

// ── Private wiring ──
- handleWsEvent(event) — delegates to reducer, then dispatches effects
- connectWebSocket()
- handleSlashCommand(command) — delegates to slashDispatcher
- loadCachedMessages / loadSessionMessages — delegates to repo
- addAssistantMessage / addSystemMessage
```

**Target size:** ~250-300 lines (down from 1226)

**Verification:**
- `./ktlint --format` passes
- `ChatViewModelTest` still passes (public API didn't change)
- `ChatScreen.kt` needs no changes (same public API)

**Commit:**
```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt
git commit -m "refactor(#288): slim ChatViewModel to thin coordinator, wire new components"
```

---

### Task 7: Refactor ChatViewModelTest

**Objective:** Update tests to match the new architecture while keeping coverage.

**Files:**
- Modify: `app/src/test/java/com/m57/hermescontrol/ui/chat/ChatViewModelTest.kt`

**Strategy:**
- Keep all existing integration-style tests (they test through `viewModel.uiState` — public API unchanged)
- **Add new tests** for the pure components:

```kotlin
// Can be in ChatViewModelTest.kt or new test files

// ChatWsEventReducerTest
@Test
fun `message start sets streaming message`() {
    val state = ChatUiState()
    val event = WsEvent.MessageStart(id = "msg-1", messageId = "msg-1")
    val result = ChatWsEventReducer.reduce(state, event)
    assertEquals("msg-1", result.state.streamingMessage?.id)
    assertTrue(result.effects.isEmpty()) // no session → no persist effect
}

// SlashCommandDispatcherTest
@Test
fun `help command returns help text`() = runTest {
    val dispatcher = SlashCommandDispatcher(...)
    val result = dispatcher.dispatch("/help")
    assertNotNull(result)
    assertTrue(result!!.contains("/status"))
}
```

**Verification:**
- `./gradlew test` passes (CI)
- All existing test cases still pass (unchanged public API)
- New pure tests validate the extracted components

**Commit:**
```bash
git add app/src/test/java/com/m57/hermescontrol/ui/chat/
git commit -m "test(#288): add pure unit tests for extracted components"
```

---

### Task 8: Final review and ktlint pass

**Objective:** Ensure everything compiles, passes ktlint, and the PR is ready.

**Steps:**
1. Run `find app/src -name '*.kt' | xargs ./ktlint --format`
2. Verify all imports are used (no warnings)
3. Push to PR branch
4. Check CI — expect all jobs to pass
5. Manual smoke test on device if possible

**Commit:**
```bash
git commit --amend  # if needed for ktlint fixes
git push -u origin HEAD
gh pr create --title "refactor(#288): split ChatViewModel god object (CODE-34)" --body "Closes #288"
```

---

## Risks and Tradeoffs

| Risk | Mitigation |
|------|-----------|
| **ReducerEffect pattern adds complexity** — side-effect queue between reducer and ViewModel | Only used when necessary (persistence after message events). Most events are pure state transforms. |
| **WebSocket streaming state** — `streamingBuffer`, `lastFlushMs`, `streamingMessageId` are tightly coupled to event timing | Keep these in ChatViewModel as they're inherently stateful (timer-based flush). The reducer handles the state transformation; the ViewModel handles the timer. |
| **Slash commands that need ViewModel context** — `/stop` calls `interruptSession()` which is a ViewModel method | Passed as callbacks/lambdas to `SlashCommandDispatcher`. Dispatcher doesn't know about ViewModel. |
| **Test granularity** — existing tests test through ViewModel UI, not through individual components | Add pure unit tests for each new component. Keep existing tests as integration tests. |
| **Merge conflicts** — PR touches the most actively developed file | Coordinate with any in-flight PRs. Refactor is mechanical (move code, don't change logic). |

## Open Questions

1. **Should `ChatUiState`, `SessionUi`, `ClarifyUi` stay in ChatViewModel.kt or move to their own file?** — They're data classes, not logic. Move to `ChatUiState.kt` for cleanliness.
2. **Should `PendingRequestTracker` stay in ChatViewModel or be extracted?** — The plan includes extraction (Task 5) but it's optional. It's only ~50 lines and tightly coupled to the WS RPC flow. Can stay in ViewModel if the extra file isn't worth it.
3. **Does `handleSlashCommand` need the user message persisted first?** — Yes, currently persists BEFORE dispatching. The dispatcher doesn't need to know about persistence — that stays in the ViewModel.

## Verification Checklist

- [ ] `./ktlint --format` passes on all changed files
- [ ] Existing `ChatViewModelTest` tests pass (no API changes)
- [ ] New pure tests for `ChatWsEventReducer` pass
- [ ] New pure tests for `SlashCommandDispatcher` pass  
- [ ] New pure tests for `ChatSearchController` pass
- [ ] CI: Build Debug APK passes
- [ ] CI: Unit & Integration Tests pass
- [ ] Manual: open chat, send message, receive response
- [ ] Manual: slash commands work (/help, /status, /new, /stop)
- [ ] Manual: search works (toggle, query, navigate)
- [ ] Manual: session switching works
- [ ] Manual: clarification flow works
