# Project: HermesControl Jetpack Compose Android App

## Architecture
The HermesControl Jetpack Compose Android application communicates with the local Hermes agent instance via two communication channels:
1. **REST API (Port 9119)**: For status queries, listing sessions, and system configuration.
2. **WebSocket Gateway (`/api/ws?token=<TOKEN>`)**: For real-time JSON-RPC messaging (chat, prompt submission, stream events, tool execution updates, etc.).

### Package Boundaries
- `com.m57.hermescontrol.data.model` — REST API data classes.
- `com.m57.hermescontrol.data.remote` — Retrofit ApiClient and HermesApiService.
- `com.m57.hermescontrol.data.ws` — JSON-RPC models, HermesWsClient, and EventParser.
- `com.m57.hermescontrol.data.local` — AuthManager (EncryptedSharedPreferences).
- `com.m57.hermescontrol.ui.connect` — ConnectScreen and ConnectViewModel.
- `com.m57.hermescontrol.ui.chat` — ChatScreen, ChatViewModel, and ChatBubble.
- `com.m57.hermescontrol.ui.settings` — SettingsScreen and SettingsViewModel.
- `com.m57.hermescontrol.theme` — Color, Theme, and Type.

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|---|---|---|---|
| M1 | Exploration & Setup | Explore codebase, identify dependencies and structure | None | DONE |
| M2 | Networking & Data Layer | Implement/Fix AuthManager, api clients, WS clients, EventParser | M1 | DONE |
| M3 | UI Screen Implementation | Implement ConnectScreen, ChatScreen, SettingsScreen, and Nav integration | M2 | DONE |
| M4 | Unit Testing | Implement unit tests for AuthManager, EventParser, Connect/Chat ViewModels | M2, M3 | DONE |
| M5 | Styling & Audit Validation | Perform ktlint formatting fixes, compiler error fixes, and run Forensic Audit | M4 | DONE |
| M6 | E2E Testing Track | Design and build comprehensive opaque-box integration tests (Tiers 1-4) | M5 | PLANNED |
| M7 | API Integration & Models | Add Retrofit endpoints and JSON serialization models for Skills & Cron Jobs | M5 | PLANNED |
| M8 | Skills Management UI | Implement Viewmodel and UI Screen for skills management | M7 | PLANNED |
| M9 | Cron Jobs Management UI | Implement Viewmodel and UI Screen for cron jobs control | M7 | PLANNED |
| M10 | Navigation Drawer | Integrate Material3 navigation drawer using Navigation3 keys | M8, M9 | PLANNED |
| M11 | E2E & Hardening Phase | Verify all E2E tests, perform adversarial coverage hardening (Tier 5) | M6, M10 | PLANNED |

---

## Interface Contracts

### REST API Service
- `getStatus()`: Returns `StatusResponse` (version, gateway status, active sessions)
- `getSessions()`: Returns `SessionListResponse` (all sessions and metadata)
- `getSessionMessages(sessionId: String)`: Returns `SessionMessagesResponse` (message history)
- `getSystemStats()`: Returns `SystemStatsResponse` (CPU, memory, etc.)
- `getSkills()`: Returns list of skills (`GET api/skills`)
- `toggleSkill(payload)`: Toggles a skill's state (`PUT api/skills/toggle`)
- `getCronJobs()`: Returns list of cron jobs (`GET api/cron/jobs`)
- `pauseCronJob(id)`: Pauses a job (`POST api/cron/jobs/{id}/pause`)
- `resumeCronJob(id)`: Resumes a job (`POST api/cron/jobs/{id}/resume`)
- `triggerCronJob(id)`: Triggers a job immediately (`POST api/cron/jobs/{id}/trigger`)
- `deleteCronJob(id)`: Deletes a job (`DELETE api/cron/jobs/{id}`)

### WebSocket Client (`HermesWsClient`)
- `connect()`: Establish connection to `/api/ws?token=<TOKEN>`
- `disconnect()`: Terminate connection
- `sendMessage(text: String)`: Submit prompt (`prompt.submit` JSON-RPC method)
- `resumeSession(sessionId: String)`: Resume a session (`session.resume` JSON-RPC method)
- Callbacks: `onConnected()`, `onDisconnected(reason: String)`, `onMessage(JsonRpcResponse)`

### Event Parser (`EventParser`)
- Parses incoming JSON messages from WebSocket and extracts event fields (`event`, `data`, `id`, `result`, `error`).
- Categorizes events: `message.complete`, `message.token`, `tool.start`, `tool.complete`, `clarify.request`, etc.

## Code Layout
- `app/src/main/java/com/m57/hermescontrol/` (Main Source)
- `app/src/test/java/com/m57/hermescontrol/` (Unit Tests)
