# Progress Status: HermesControl Android App

This document tracks the completed features and the pending backlog for the HermesControl Jetpack Compose Android application.

## 👥 Done (Implemented & Verified)

### 1. Base Infrastructure & Environment
* **Nix Flake Dev Environment (`flake.nix`)**: Fully configured with JDK 17, Android SDK (platforms 34-36), `ktlint`, and Gradle.
* **Scaffolding & Build Pipeline**: Android Jetpack Compose project compiles cleanly using `./gradlew assembleDebug`.
* **Code Formatting**: Code formatting checks integrated using `ktlint` (`./gradlew ktlintCheck`).
* **Unit Testing Suite**: Verified with `./gradlew test` (AuthManager, EventParser, ConnectViewModel, ChatViewModel).
* **Network Capabilities**: Cleartext traffic enabled in `AndroidManifest.xml` to allow connection to local networks (e.g., `192.168.1.18` to `192.168.1.48`).

### 2. Implemented Features (~45 of 213 endpoints, ~21%)
* **Connection Screen (`ConnectScreen`)**: Saves Host, Port, and Auth Token to `AuthManager` (EncryptedSharedPreferences), with live verification via `GET /api/status`.
* **Chat Screen (`ChatScreen`)**: Live chat over WebSocket (JSON-RPC) supporting:
  * Creating sessions, resuming sessions, listing sessions, and submitting prompts.
  * Message token streaming, thinking delta indicator, and interrupting sessions.
  * Basic Markdown-ish rendering (bold, code blocks) and expandable tool call indicators.
* **Skills Screen (`SkillsScreen`)**: Lists skills (`GET /api/skills`) and allows toggling them (`PUT /api/skills/toggle`) with auto-refresh and swipe-to-refresh. Toggles have optimistic state reversion on failure.
* **Cron Jobs Screen (`CronJobsScreen`)**: Lists cron jobs (`GET /api/cron/jobs`) with actions for Trigger, Pause, Resume, and Delete. Auto-refresh and swipe-to-refresh.
* **Gateway Control Screen (`GatewayScreen`)**: Integrates Start (`POST /api/gateway/start`), Stop (`POST /api/gateway/stop`), and Restart (`POST /api/gateway/restart`) with platform state reporting.
* **Profiles Management Screen (`ProfilesScreen`)**: Switch active profile, update soul personality description (`PUT /api/profiles/{name}/soul`), and switch AI model providers.
* **Toolsets Screen (`ToolsetsScreen`)**: List and toggle toolsets (`GET/PUT /api/tools/toolsets`) to enable/disable specific tool groups (e.g., Web Search, Python local run).
* **Achievements Screen (`AchievementsScreen`)**: Displays a gamified list of discovered/locked/unlocked achievements with progress bars (`GET /api/plugins/hermes-achievements/achievements`).
* **Settings Screen (`SettingsScreen`)**: Allows changing settings (Host, Port, Token, Theme, Auto-reconnect) and tests connection.
* **Navigation Drawer**: Integrated Material3 navigation drawer using Navigation3 for switching screens.

---

## ⏳ Left (To Be Implemented)

All remaining **168 endpoints** across the Hermes Dashboard API need to be implemented. The categories and their priority order are:

### Priority 1: Model & Provider Management (3 endpoints)
* List model providers (`GET /api/model/providers`)
* Get and set recommended default model (`GET/POST /api/model/recommended-default`)
* **UI**: Select model directly from a dropdown in Settings or Profiles.

### Priority 2: Skills Hub & Editing (8 endpoints)
* Browse hub, preview, install, uninstall, update skills.
* Create/edit custom skills by editing their raw YAML/markdown files (`GET/PUT /api/skills/content`).

### Priority 3: Advanced Sessions & Search (8 endpoints)
* Search through session transcripts.
* Delete sessions in bulk, prune empty sessions, export session transcripts.
* Get session statistics.

### Priority 4: Cron Creation & Blueprint Instantiation (3 endpoints)
* Form/UI to create new Cron Jobs (`POST /api/cron/jobs`).
* Form/UI to update Cron Jobs (`PUT /api/cron/jobs/{id}`).

### Priority 5: Kanban Plugin (46 endpoints)
* Full Kanban task boards, lists, cards, comments, attachments.

### Priority 6: Other Administrative Tools
* Env vars management, webhooks, system backup/restore, credential storage.
