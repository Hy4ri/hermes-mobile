# Android App for Hermes — Chat & Control

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Build an Android app that lets m57 chat with his Hermes agent instance and send remote control commands from anywhere, directly from his phone.

**Architecture:** The app talks directly to Hermes' built-in **Dashboard REST API** (port 9119) for management and **Dashboard WebSocket** (`/api/ws`) for real-time chat via JSON-RPC. **No custom bridge needed.**

**Tech Stack:** Android (Kotlin/Jetpack Compose), OkHttp (WebSocket + Retrofit), JSON-RPC protocol, Hermes Dashboard API.

---

## Current State (What Already Works)

| Component | Status | Endpoint |
|-----------|--------|----------|
| **Dashboard REST API** | ✅ Running on `127.0.0.1:9119` | `GET /api/status`, sessions, config, etc. |
| **TUI Gateway WebSocket** | ✅ Running at `/api/ws?token=<TOKEN>` | JSON-RPC over WebSocket |
| **Auth (loopback mode)** | ✅ `Authorization: Bearer <TOKEN>` or `X-Hermes-Session-Token: <TOKEN>` | For REST API |
| **WebSocket Auth** | ✅ `?token=<TOKEN>` query param | For WS upgrade |
| **Dashboard Token** | ✅ Stored in `~/.hermes/.env` | `HERMES_DASHBOARD_SESSION_TOKEN=y9oAS3...iTZ4` |

**Available JSON-RPC Methods (for Chat):**
- `session.list` — List all sessions
- `session.active_list` — List active sessions
- `session.status` — Get session status
- `session.history` — Get session message history
- `session.resume` — Resume an existing session
- `session.create` — Create a new session
- `prompt.submit` — **Send a message to Hermes!** (key endpoint)
- `session.interrupt` — Interrupt a response
- `session.steer` — Steer the agent
- `clarify.respond` — Respond to clarifications
- `approval.respond` — Approve/reject commands
- `session.delete` — Delete sessions
- `session.title` — Set session title
- `config.get` / `config.set` — Manage config
- `image.attach`, `file.attach` — Attach files
- And many more...

**Available REST Endpoints (for Management):**
- `GET /api/status` — Hermes version, gateway status, active sessions
- `GET /api/sessions` — List sessions with metadata
- `GET /api/sessions/{id}/messages` — Get messages for a session
- `GET /api/system/stats` — System stats
- `GET /api/config` — Full config
- `GET /api/logs` — Logs
- `GET /api/cron/jobs` — Cron jobs
- And many more (check /api/docs)

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   Android Device                      │
│  ┌─────────────────┐     ┌────────────────────────┐  │
│  │  Android App     │     │  Hermes (Termux/Ubuntu)│  │
│  │  (Kotlin/Compose)│────▶│  Dashboard (127.0.0.1) │  │
│  │                  │     │                        │  │
│  │  • REST client   │────▶│  REST API :9119        │  │
│  │  • WS client     │────▶│  WS /api/ws?token=...  │  │
│  │  • Local cache   │     │  TUI Gateway (JSON-RPC)│  │
│  └─────────────────┘     └────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

Since Hermes runs on the same Android device in Termux, the app connects to **localhost** — no network issues, no CORS, no latency.

---

## Phase 1: Android Project Setup

### Task 1.1: Scaffold Android Project

**Objective:** Create a new Android project with Jetpack Compose and the right SDK.

**Actions:**
1. Open Android Studio → New Project → Empty Compose Activity
2. Name: `HermesControl`, Package: `com.m57.hermescontrol`
3. Language: **Kotlin**, Minimum SDK: **26**
4. Build system: Gradle with Kotlin DSL

**Files:**
- `app/build.gradle.kts` — Dependencies
- `AndroidManifest.xml` — Internet permission

**Verify:** App builds and runs on device/emulator.

---

### Task 1.2: Add Dependencies

**Objective:** Add OkHttp (WebSocket + HTTP), Gson for JSON-RPC.

**File:** `app/build.gradle.kts`

```kotlin
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Local storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

**Verify:** Gradle sync succeeds.

---

## Phase 2: REST API Client (Management)

### Task 2.1: Data Models

**Objective:** Create Kotlin data classes for REST API responses.

**Files:**
- `data/model/StatusResponse.kt`
- `data/model/SessionListResponse.kt`
- `data/model/SessionMessagesResponse.kt`

```kotlin
// data/model/StatusResponse.kt
data class StatusResponse(
    val version: String,
    val gateway_running: Boolean,
    val active_sessions: Int,
    val gateway_platforms: Map<String, PlatformStatus>
)

data class PlatformStatus(
    val state: String,
    val error_code: String? = null
)
```

**Verify:** Compiles cleanly.

---

### Task 2.2: REST API Interface

**Objective:** Create Retrofit service for Hermes dashboard REST API.

**Files:**
- `data/remote/HermesApiService.kt`
- `data/remote/ApiClient.kt`

```kotlin
// data/remote/HermesApiService.kt
interface HermesApiService {
    @GET("api/status")
    suspend fun getStatus(): Response<StatusResponse>
    
    @GET("api/sessions")
    suspend fun getSessions(): Response<SessionListResponse>
    
    @GET("api/sessions/{id}/messages")
    suspend fun getSessionMessages(@Path("id") sessionId: String): Response<SessionMessagesResponse>
    
    @GET("api/system/stats")
    suspend fun getSystemStats(): Response<SystemStatsResponse>
}

// data/remote/ApiClient.kt
object ApiClient {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Authorization", "Bearer ${AuthManager.getToken()}")
                .build()
            chain.proceed(request)
        }
        .build()
    
    val retrofit = Retrofit.Builder()
        .baseUrl("http://127.0.0.1:9119/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val hermesApi: HermesApiService = retrofit.create(HermesApiService::class.java)
}
```

**Verify:** API calls return valid data.

---

## Phase 3: WebSocket Client (Chat)

### Task 3.1: JSON-RPC Models

**Objective:** Create models for JSON-RPC over WebSocket.

**Files:**
- `data/ws/JsonRpcRequest.kt`
- `data/ws/JsonRpcResponse.kt`
- `data/ws/WsMethods.kt`

```kotlin
// data/ws/JsonRpcRequest.kt
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: Map<String, Any> = emptyMap()
)

// data/ws/WsMethods.kt
object WsMethods {
    const val SESSION_LIST = "session.list"
    const val SESSION_ACTIVE_LIST = "session.active_list"
    const val SESSION_STATUS = "session.status"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_RESUME = "session.resume"
    const val SESSION_CREATE = "session.create"
    const val PROMPT_SUBMIT = "prompt.submit"
    const val SESSION_INTERRUPT = "session.interrupt"
}
```

**Verify:** Data classes serialize/deserialize correctly with Gson.

---

### Task 3.2: WebSocket Client

**Objective:** Implement WebSocket connection to `/api/ws?token=<TOKEN>` with reconnection support.

**Files:**
- `data/ws/HermesWsClient.kt`

```kotlin
// data/ws/HermesWsClient.kt
class HermesWsClient(
    private val token: String,
    private val onMessage: (JsonRpcResponse) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private val requestId = AtomicInteger(0)
    
    fun connect() {
        val url = "ws://127.0.0.1:9119/api/ws?token=$token"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onConnected()
            }
            
            override fun onMessage(ws: WebSocket, text: String) {
                val response = Gson().fromJson(text, JsonRpcResponse::class.java)
                onMessage(response)
            }
            
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onDisconnected(t.message ?: "Unknown error")
            }
            
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onDisconnected(reason)
            }
        })
    }
    
    fun sendMessage(text: String) {
        val request = JsonRpcRequest(
            id = requestId.incrementAndGet().toString(),
            method = WsMethods.PROMPT_SUBMIT,
            params = mapOf("text" to text)
        )
        ws?.send(Gson().toJson(request))
    }
    
    fun resumeSession(sessionId: String) {
        val request = JsonRpcRequest(
            id = requestId.incrementAndGet().toString(),
            method = WsMethods.SESSION_RESUME,
            params = mapOf("id" to sessionId)
        )
        ws?.send(Gson().toJson(request))
    }
    
    fun disconnect() {
        ws?.close(1000, "App closing")
    }
}
```

**Verify:** App connects to WebSocket, receives JSON-RPC responses.

---

### Task 3.3: WebSocket Event Parser

**Objective:** Handle the event-based responses from Hermes (responses come as events, not just RPC replies).

**Files:**
- `data/ws/EventParser.kt`

The TUI Gateway sends:
- **RPC responses** (to our requests) with `id` matching
- **Events** (tool calls, streamed tokens, clarify requests) with `event` field

```kotlin
data class JsonRpcResponse(
    val jsonrpc: String?,
    val id: String?,
    val result: Any?,
    val error: JsonRpcError?,
    val event: String?,          // Event type (for push events)
    val data: Map<String, Any>?  // Event payload
)

data class JsonRpcError(
    val code: Int,
    val message: String
)
```

**Event types to handle:**
- `message.complete` — Agent finished responding
- `message.token` — Streaming token
- `clarify.request` — Agent needs clarification
- `tool.start` / `tool.complete` — Tool calls
- `session.updated` — Session state changed

**Verify:** Parse events correctly from Gson.

---

## Phase 4: UI Screens

### Task 4.1: Connection Screen

**Objective:** Enter/display connection info (token auto-loaded or configurable).

**Files:**
- `ui/connect/ConnectScreen.kt`
- `ui/connect/ConnectViewModel.kt`

**Features:**
- Text field for token (or load from SharedPreferences)
- "Connect" button
- Connection status indicator

---

### Task 4.2: Chat Screen

**Objective:** Full chat UI with real-time messaging.

**Files:**
- `ui/chat/ChatScreen.kt`
- `ui/chat/ChatViewModel.kt`
- `ui/chat/ChatBubble.kt`

**Features:**
- `LazyColumn` for message list (user + agent bubbles)
- Bottom input bar with send button
- Streaming text (update bubble as tokens arrive)
- Image/file attachment support
- Loading / typing indicator
- Session selector (pick which session to resume)

**Message types:**
- User messages (right-aligned, primary color)
- Agent messages (left-aligned, surface color)
- Tool calls (collapsible, monospace)
- System notifications (centered, muted)

---

### Task 4.3: Command Panel

**Objective:** Quick-action buttons for common Hermes commands.

**Files:**
- `ui/commands/CommandPanel.kt`
- `ui/commands/CommandViewModel.kt`

**Commands (can be customized):**
- `/status` — Check system status
- `/skills` — List skills
- `/memory` — Show recent memories
- `/sessions` — List active sessions
- `🔄 Restart Gateway` — Trigger gateway restart
- `📊 System Stats` — Show CPU/memory
- `⚡ Quick Prompt` — Pre-set prompts

---

### Task 4.4: Settings Screen

**Objective:** Configure connection parameters.

**Files:**
- `ui/settings/SettingsScreen.kt`
- `ui/settings/SettingsViewModel.kt`

**Settings:**
- Host/IP (default: `127.0.0.1`)
- Port (default: `9119`)
- Auth token (loaded from encrypted storage)
- Theme (Light/Dark/System)
- Auto-reconnect toggle

---

## Phase 5: Polish & Security

### Task 5.1: Encrypted Token Storage

**Objective:** Store the dashboard token securely.

**Files:**
- `data/local/AuthManager.kt`

Use Android `EncryptedSharedPreferences` to store the `HERMES_DASHBOARD_SESSION_TOKEN`.

**Verify:** Token persists across app restarts; is encrypted at rest.

---

### Task 5.2: Auto-Reconnect

**Objective:** Reconnect WebSocket if connection drops.

**Files:**
- Update `HermesWsClient.kt` — Exponential backoff reconnection.

**Verify:** Kill dashboard process → app shows "disconnected" → restart dashboard → app reconnects.

---

### Task 5.3: Error Handling & Offline Mode

**Objective:** Graceful handling of network errors.

**Files:**
- Update `ChatViewModel.kt` — Show offline banner, cache last N messages locally.

**Verify:** Turn off network → app shows offline state, cached messages visible.

---

## Task-by-Task Summary

| # | Task | Phase | Est. Time | Status |
|---|------|-------|-----------|--------|
| 1.1 | Scaffold Android Project | 1 | 10 min | ⬜ |
| 1.2 | Add Dependencies | 1 | 10 min | ⬜ |
| 2.1 | REST Data Models | 2 | 15 min | ⬜ |
| 2.2 | REST API Client | 2 | 20 min | ⬜ |
| 3.1 | JSON-RPC Models | 3 | 10 min | ⬜ |
| 3.2 | WebSocket Client | 3 | 30 min | ⬜ |
| 3.3 | Event Parser | 3 | 20 min | ⬜ |
| 4.1 | Connection Screen | 4 | 20 min | ⬜ |
| 4.2 | Chat Screen | 4 | 45 min | ⬜ |
| 4.3 | Command Panel | 4 | 25 min | ⬜ |
| 4.4 | Settings Screen | 4 | 20 min | ⬜ |
| 5.1 | Encrypted Token Storage | 5 | 15 min | ⬜ |
| 5.2 | Auto-Reconnect | 5 | 20 min | ⬜ |
| 5.3 | Error Handling & Offline | 5 | 15 min | ⬜ |

---

## What You DON'T Need Anymore (vs Original Plan)

| Originally Planned | Now? | Reason |
|--------------------|------|--------|
| Custom Python Flask bridge | ❌ Removed | Dashboard IS the bridge |
| Custom SQLite reads | ❌ Removed | Dashboard API handles sessions |
| Hermes DB schema analysis | ❌ Removed | REST API abstracts the DB |
| Queue-based message passing | ❌ Removed | WebSocket JSON-RPC handles it |

---

## App Connection Details

| Setting | Value |
|---------|-------|
| **REST API Base** | `http://127.0.0.1:9119/` |
| **WS Endpoint** | `ws://127.0.0.1:9119/api/ws?token=<TOKEN>` |
| **REST Auth** | `Authorization: Bearer *** or `X-Hermes-Session-Token: <TOKEN>` |
| **WS Auth** | `?token=<TOKEN>` query parameter (NOT header-based for WS) |
| **Current Token** | `y9oAS3frCosCSaynhmAofMkeCsuiNuF2ad2XB35iTZ4` (in `~/.hermes/.env`) |
| **Dashboard Binding** | `0.0.0.0:9119` (LAN accessible via `--insecure`) |

---

## 🧪 Verified Protocol Details (E2E Tested)

### REST API
| Endpoint | Auth | Returns |
|----------|------|---------|
| `GET /api/status` | ❌ Public | `version`, `gateway_running`, `active_sessions`, `auth_required` |
| `GET /api/sessions` | ✅ Token | `{"sessions": [...]}` — full session list with message counts |
| `GET /api/config` | ✅ Token | Full config as flat JSON dict |
| `GET /api/system/stats` | ✅ Token | System statistics |

**Auth methods tested:**
- `Authorization: Bearer <token>` ✅ works
- `X-Hermes-Session-Token: <token>` ✅ works
- No header → `401` ✅
- Bad token → `401` ✅

### WebSocket JSON-RPC Protocol

**Connection:** `ws://HOST:9119/api/ws?token=<TOKEN>`

**First message (always):** `gateway.ready` event — server pushes this immediately on connect.

**RPC methods tested:**
| Method | Params | Response | Status |
|--------|--------|----------|--------|
| `session.list` | `{}` | `{"sessions": [...]}` — all TUI sessions | ✅ |
| `session.active_list` | `{}` | `{"sessions": [...]}` — active TUI sessions | ✅ |
| `session.status` | `{}` | `{"sessions": [...]}` — session statuses | ✅ |
| `session.create` | `{}` | `{"session_id": "xxx", "stored_session_id": "xxx", ...}` | ✅ |
| `session.resume` | `{"id": "session_id"}` | Session data | ✅ (when valid session) |
| `prompt.submit` | `{"session_id": "xxx", "text": "message"}` | `{"status": "streaming"}` then events | ✅ |
| `nonexistent.method` | `{}` | `{"error": {"code": -32601, "message": "..."}}` | ✅ |

### Chat Flow (Event Sequence)

After `prompt.submit`, events arrive on the same WebSocket in this order:

1. `session.info` — Session metadata, model info, tool list
2. `message.start` — Processing began
3. `thinking.delta` (0+) — Reasoning tokens (model-dependent)
4. `message.token` (0+) — Streaming text tokens (model-dependent)
5. `status.update` — Status changes
6. `tool.start` / `tool.complete` — Tool execution (when applicable)
7. `clarify.request` (optional) — Agent needs input
8. **`message.complete`** — **Final response!** `payload.text` contains the full response text.
9. `message.done` — Processing fully finished

**Key finding:** The actual response text is in `message.complete.payload.text`, NOT nested under `payload.message.content`. Use:
```kotlin
val responseText = event.payload["text"] as? String
```

### Important Implementation Notes

1. **Agent build time:** After `session.create`, the AI agent needs ~15-20s to initialize (tool discovery, model loading, skill loading). The Android app should:
   - Show a loading/connecting state during this time
   - Listen for events but ignore them until `message.complete` or set a timer
   - OR create a session on app startup and keep it alive for instant responses

2. **Session reuse:** Multiple `prompt.submit` calls work on the same session_id. Create one session at startup and reuse it.

3. **No heartbeat needed:** The WS connection stays alive without pings.

4. **Error codes:**
   - `4001` — session not found (invalid/missing session_id)
   - `4009` — session busy (agent still processing previous prompt)
   - `-32601` — unknown method
   - `-32600` — invalid JSON-RPC format

---

## Open Questions

1. **Chat flow**: Do you want the app to resume the current active session, or start fresh each time?
2. **Deep linking**: Want to add a way to share messages/commands from other apps?
3. **Notifications**: Want push notifications when Hermes completes a task? (Would need a server component for push)

---

*Plan updated: 2026-06-15. Simplified architecture using Hermes Dashboard + TUI Gateway. Ready to build!* 💋