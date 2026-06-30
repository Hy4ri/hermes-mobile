<div align="center">
  <br>
  <img src="https://img.shields.io/badge/Android-34DDDD?style=for-the-badge&logo=android&logoColor=black" alt="Android"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Material%20You-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material You"/>
  <br><br>
</div>

<h1 align="center">Hermes Mobile</h1>
<p align="center"><strong>Native Android companion app for your Hermes AI agent.</strong></p>

<p align="center">
  <a href="https://github.com/Hy4ri/hermes-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/Hy4ri/hermes-mobile?color=6750A4&label=Latest%20Release&logo=github" alt="Latest Release"></a>
  <img src="https://img.shields.io/github/actions/workflow/status/Hy4ri/hermes-mobile/android.yml?branch=main&label=CI&logo=githubactions" alt="CI">
  <img src="https://img.shields.io/badge/minSdk-26-brightgreen" alt="minSdk 26">
  <img src="https://img.shields.io/badge/targetSdk-36-brightgreen" alt="targetSdk 36">
</p>

---

## Overview

**Hermes Mobile** is the native Android client for [Hermes Agent](https://hermes-agent.nousresearch.com). It connects securely to your local Hermes gateway (REST API and WebSocket TUI Gateway) over LAN, giving you pocket control over your AI assistant.

---

## Features

- **Real-Time Chat:** Message your agent with Room-backed local database history.
- **System Config:** Manage active profiles, installed skills, plugins, and LLM model selections.
- **Operations:** Stream and filter live logs, manage cron jobs, edit environment keys, and test webhooks.
- **Gateway Status:** Monitor WebSocket connection, MCP servers, and messaging channel status.
- **Productivity:** View and manage tasks via integrated Kanban boards and track agent milestones.
- **Modern UX:** Native Material You design supporting dynamic color theming, scroll-aware TopBar, and pull-to-refresh.

---

## Quick Start

### Prerequisites
- **JDK 21+** (required for Kotlin compilation and the Gradle toolchain)
- **Android Studio** (Ladybug+) or a **Nix** development environment

### Build & Deploy
1. **Clone the repository:**
   ```bash
   git clone https://github.com/Hy4ri/hermes-mobile.git
   cd hermes-mobile
   ```
2. **Build the debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
3. **Install on your emulator/device:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

*Note: For release builds, ensure keystore environment variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are configured, or let the GitHub Actions release workflow handle it on tag push (`v*`).*

---

## Authentication

Once the app is installed, you need to point it at your Hermes gateway and authenticate.

### 1. Find your dashboard token

Your Hermes dashboard runs on `http://<host>:9119` (default). The token is displayed at the **bottom of the dashboard settings page** or inside `~/.hermes/dashboard-token.txt` on the host machine.

### 2. Configure in the app

Open the app's **Settings** screen (bottom nav or drawer) and fill in:

| Field     | Description |
|-----------|-------------|
| **Host**  | Your Hermes gateway IP/hostname (default: `127.0.0.1` or `192.168.x.x` on LAN) |
| **Port**  | Dashboard port (default: `9119`) |
| **Token** | The dashboard token from step 1 |

The app will automatically connect via WebSocket and you'll be able to chat and manage your agent.

### Connection profiles

If you have multiple Hermes gateways (e.g., local dev + production), you can add them as **connection profiles** in Settings. Each profile stores its own host, port, and token — switch between them anytime.

### In-app browser login (optional)

When creating a new connection profile, the app shows a **login screen** that opens the Hermes dashboard in a built-in WebView. You can authenticate there with your dashboard username/password (default: `admin` / `hermes`) to automatically capture the session token.

> **Security note:** The app communicates over plain HTTP — it's designed for **trusted local networks only**. Do not expose your Hermes gateway or dashboard token to untrusted networks.

---

## Project Structure

```
app/src/main/java/com/m57/hermescontrol/
├── data/          # Local (Room, EncryptedSharedPreferences) & Remote (Retrofit, OkHttp WS)
├── notification/  # Foreground service for message notifications
├── theme/         # Material You design system, status colors, spacing, and typography
└── ui/            # Compose screens (Chat, Settings, Profiles, Kanban, etc.) + Navigation
```

---

## Tech Stack

- **Language:** Kotlin 2.4.0 with KSP compiler plugin
- **UI & Layout:** Jetpack Compose (BOM 2026.03.01) & Material 3 / Material You
- **Navigation:** Navigation3 (Compose-first Routing)
- **Networking:** Retrofit 3.0.0, OkHttp 5.4.0, Gson 2.14.0
- **Database:** Room 2.7.1 with SQLCipher encryption
- **Security:** `EncryptedSharedPreferences` (AES256-GCM)
- **Formatting:** `ktlint` style rules (checked automatically in CI)

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for our branch workflow, code style guidelines, and PR checklist.

For developer-specific details, code conventions, and project architecture notes, refer to [AGENTS.md](AGENTS.md).

---

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
