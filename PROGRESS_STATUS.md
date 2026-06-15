# Progress Status: HermesControl Android App

This document tracks the completed features and the pending backlog for the HermesControl Jetpack Compose Android application.

## 👥 Done (Implemented & Verified)

### 1. Base Infrastructure & Environment
* **Nix Flake Dev Environment (`flake.nix`)**: Fully configured with JDK 17, Android SDK (platforms 34-36), `ktlint`, and Gradle.
* **Scaffolding & Build Pipeline**: Android Jetpack Compose project compiles cleanly using `./gradlew assembleDebug`.
* **Code Formatting**: Code formatting checks integrated using `ktlint` (`./gradlew ktlintCheck`).
* **Unit Testing Suite**: Verified with `./gradlew test` (AuthManager, EventParser, ConnectViewModel, ChatViewModel, and new viewmodels/integration tests).
* **Network Capabilities**: Cleartext traffic enabled in `AndroidManifest.xml` to allow connection to local networks (e.g., `192.168.1.18` to `192.168.1.48`).
* **Git Repository**: Initialized and linked with GitHub (`https://github.com/Hy4ri/hermes-mobile.git`).

### 2. Implemented Features
* **Connection Screen (`ConnectScreen`)**: Saves Host, Port, and Auth Token to `AuthManager` (EncryptedSharedPreferences), with live verification via `GET /api/status`.
* **Chat Screen (`ChatScreen`)**: Live chat over WebSocket (JSON-RPC) message token streaming, thinking delta, tool call indicators, and clarifying inputs.
* **Skills Screen (`SkillsScreen`)**: Lists skills (`GET /api/skills`) and toggles them with optimistic state reversion.
* **Cron Jobs Screen (`CronJobsScreen`)**: Lists cron jobs (`GET /api/cron/jobs`) with actions (Trigger, Pause, Resume, and Delete) with optimistic updates.
* **Gateway Control Screen (`GatewayScreen`)**: Start/Stop/Restart with platform state reporting.
* **Profiles Management Screen (`ProfilesScreen`)**: Switch active profile, update soul personality description, and switch AI model providers.
* **Toolsets Screen (`ToolsetsScreen`)**: List and toggle toolsets.
* **Achievements Screen (`AchievementsScreen`)**: Displays gamified achievements list.
* **Device Pairing Screen (`PairingScreen`)**: List pairing approvals, approve/revoke access.
* **Raw Config Editor (`ConfigScreen`)**: Live edit `/api/config/raw`.
* **MCP Servers Management (`McpServersScreen`)**: List MCP servers, toggle, test connection, and delete.
* **Webhooks Management (`WebhooksScreen`)**: List webhook callback subscriptions and globally toggle webhooks.
* **Model Configuration (`ModelScreen`)**: Inspect model providers list.
* **Settings Screen (`SettingsScreen`)**: Change Host, Port, Token, Theme, and Auto-reconnect.
* **Logs View (`LogsScreen`)**: Live scrolling platform/stdout logs (`GET /api/logs`) with console-style layout.
* **Plugins View (`PluginsScreen`)**: Hub rescan, install, update, disable, enable with optimistic state updates.
* **Channels / Messaging (`ChannelsScreen`)**: Platform configurations and credentials manager (e.g. Telegram bot details).
* **Keys (`KeysScreen`)**: Live environment variables and credentials manager (`GET/PUT /api/env`, reveal option).
* **System View (`SystemScreen`)**: Diagnostics doctor reports (`GET /api/ops/doctor`) and backups trigger (`POST /api/ops/backup`).
* **Kanban View (`KanbanScreen`)**: Full task boards, task columns (To Do, In Progress, Done), new tasks dialog, and task movement with optimistic updates.
* **Navigation Drawer**: Material3 navigation drawer using Navigation3 keys to switch between all screens.

---

## ⏳ Left (To Be Implemented)

* Other minor backlogs include Skills Hub and Advanced Session management.
