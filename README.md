<div align="center">
  <br>
  <img src="https://img.shields.io/badge/Android-34DDDD?style=for-the-badge&logo=android&logoColor=black" alt="Android"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Material%20You-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material You"/>
  <br><br>
</div>

<h1 align="center">Hermes Control</h1>
<p align="center"><strong>Remote control for your Hermes AI agent — from your pocket.</strong></p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#screenshots">Screenshots</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#project-structure">Project Structure</a> •
  <a href="#tech-stack">Tech Stack</a> •
  <a href="#contributing">Contributing</a> •
  <a href="#releases">Releases</a>
</p>

<p align="center">
  <a href="https://github.com/Hy4ri/hermes-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/Hy4ri/hermes-mobile?color=6750A4&label=Latest%20Release&logo=github" alt="Latest Release"></a>
  <img src="https://img.shields.io/github/actions/workflow/status/Hy4ri/hermes-mobile/android.yml?branch=main&label=CI&logo=githubactions" alt="CI">
  <img src="https://img.shields.io/github/last-commit/Hy4ri/hermes-mobile?color=success&label=Last%20Commit" alt="Last Commit">
  <img src="https://img.shields.io/badge/minSdk-26-brightgreen" alt="minSdk 26">
  <img src="https://img.shields.io/badge/targetSdk-36-brightgreen" alt="targetSdk 36">
</p>

---

## Overview

**Hermes Control** is a native Android companion app for [Hermes Agent](https://hermes-agent.nousresearch.com) — the open-source, multi-platform AI assistant by Nous Research.

Instead of pulling out a laptop or switching to Telegram/Discord, you can manage your Hermes agent directly from your phone:

- 💬 Chat with your agent in real time
- 🔧 Inspect and reconfigure system settings, profiles, and plugins
- 📊 Monitor gateway status, MCP servers, and webhook health
- 📋 Manage cron jobs, environment keys, and toolsets
- 🗂️ Track kanban boards and agent achievements
- 🔄 Pull-to-refresh every data screen

All this with a **Material You** design that adapts to your device's dynamic color theme.

---

## Features

### 🤖 Agent Chat
- Real-time message exchange with your Hermes agent
- Session management with profile switching
- Message history with persistent storage (Room)

### ⚙️ System Management
- **Profiles** — View and switch between Hermes agent profiles
- **Skills** — Browse installed skills with inline descriptions
- **Plugins** — Manage active plugins and their configurations
- **Toolsets** — See which toolsets are enabled per profile

### 🛠️ Operations
- **Cron Jobs** — View scheduled tasks, their schedules, and last-run status
- **Webhooks** — Configure and test webhook endpoints
- **Keys & Credentials** — Securely manage environment variables
- **Logs** — Stream and filter agent logs

### 📡 Connectivity
- **Gateway Status** — Monitor WebSocket and API gateway health
- **MCP Servers** — Manage Model Context Protocol server connections
- **Channels** — View connected messaging platform status

### 📊 Productivity
- **Kanban Boards** — Track tasks across boards, columns, and cards
- **System Info** — Runtime environment and configuration overview
- **Achievements** — Agent milestone tracker

### 🎨 UX
- **Material You** — Dynamic color theming from your wallpaper
- **Pull-to-Refresh** — Swipe down on any data screen to reload
- **Shared Scaffold** — Consistent navigation and layout across all screens
- **Scroll-aware TopBar** — Collapsing top bar on scroll

---

## Screenshots

> 📸 Screenshots coming soon. Build the app and see it in action!

| Chat | System | Profile | Kanban |
|:---:|:---:|:---:|:---:|
| 🔲 | 🔲 | 🔲 | 🔲 |

---

## Quick Start

### Prerequisites

| Tool | Version | Reason |
|------|---------|--------|
| [JDK](https://adoptium.net/) | 17+ | Kotlin/JVM compilation |
| [Android Studio](https://developer.android.com/studio) | Hedgehog+ | IDE + SDK management |
| Android SDK | 36 (compile), 26 (min) | Via Android Studio SDK Manager |
| Git | any | Clone the repo |

### Clone & Build

```bash
git clone https://github.com/Hy4ri/hermes-mobile.git
cd hermes-mobile
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Build with custom version

```bash
./gradlew assembleRelease -PversionName="1.2.0" -PversionCode="120"
```

> **Note:** Release builds use debug signing keys by default. For Play Store distribution, configure a proper signing config.

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit **Run ▶**.

---

## Project Structure

```
hermes-mobile/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/m57/hermescontrol/
│   │   │   │   ├── Navigation.kt              # Drawer + NavDisplay + entry wiring
│   │   │   │   ├── NavigationController.kt    # Central navigation guard (dedup)
│   │   │   │   ├── NavigationKeys.kt          # Screen key definitions
│   │   │   │   ├── MainActivity.kt            # Single activity entry point
│   │   │   │   ├── data/
│   │   │   │   │   ├── model/                 # API models / DTOs (20+ files)
│   │   │   │   │   ├── remote/                # Retrofit + OkHttp API client
│   │   │   │   │   ├── local/                 # Room DB (Dao, Entity, Mapper)
│   │   │   │   │   └── ws/                    # WebSocket TUI Gateway (JSON-RPC 2.0)
│   │   │   │   ├── notification/
│   │   │   │   │   └── ChatNotificationService.kt  # Foreground service for reply alerts
│   │   │   │   ├── theme/
│   │   │   │   │   ├── Theme.kt               # Material You dynamic theming
│   │   │   │   │   ├── Color.kt               # Color palette definitions
│   │   │   │   │   ├── HermesStatusColors.kt  # Semantic status colours (CompositionLocal)
│   │   │   │   │   ├── Type.kt                # Typography scale
│   │   │   │   │   ├── Motion.kt              # Shared transition animations
│   │   │   │   │   ├── Spacing.kt             # Spacing tokens (CompositionLocal)
│   │   │   │   │   └── Shapes.kt              # Shape tokens
│   │   │   │   ├── ui/
│   │   │   │   │   ├── common/                # Shared components
│   │   │   │   │   │   ├── HermesScaffold.kt  # Standardised screen layout
│   │   │   │   │   │   ├── StateViews.kt      # Loading, error, empty placeholders
│   │   │   │   │   │   └── SharedComponents.kt # SearchBar, FilterChips, ToggleRow, etc.
│   │   │   │   │   ├── chat/                  # Chat screen + view model
│   │   │   │   │   ├── settings/              # Settings screen + view model
│   │   │   │   │   ├── connect/               # Connect / pairing screen
│   │   │   │   │   ├── profiles/              # Profile management
│   │   │   │   │   ├── skills/                # Skills browser
│   │   │   │   │   ├── plugins/               # Plugin management
│   │   │   │   │   ├── toolsets/              # Toolset viewer
│   │   │   │   │   ├── cron/                  # Cron job viewer
│   │   │   │   │   ├── webhooks/              # Webhook management
│   │   │   │   │   ├── logs/                  # Log stream viewer
│   │   │   │   │   ├── keys/                  # Environment variable manager
│   │   │   │   │   ├── gateway/               # Gateway health monitor
│   │   │   │   │   ├── mcp/                   # MCP server manager
│   │   │   │   │   ├── model/                 # LLM model selector
│   │   │   │   │   ├── channels/              # Messaging channels
│   │   │   │   │   ├── pairing/               # Device pairing
│   │   │   │   │   ├── config/                # Raw config viewer
│   │   │   │   │   ├── system/                # System info screen
│   │   │   │   │   ├── kanban/                # Kanban board viewer
│   │   │   │   │   └── achievements/          # Agent achievements
│   │   │   │   └── util/                      # Utility classes
│   │   │   │       └── CronExpressionFormatter.kt
│   │   │   ├── res/
│   │   │   │   ├── values/
│   │   │   │   │   └── strings.xml            # String resources
│   │   │   │   ├── drawable/                  # Vector icons & drawables
│   │   │   │   └── mipmap-*/                  # Launcher icons
│   │   │   └── AndroidManifest.xml
│   │   └── test/                              # Unit tests
│   │       └── kotlin/com/m57/hermescontrol/
│   └── build.gradle.kts                       # App-level build config
├── gradle/
│   └── libs.versions.toml                     # Version catalog
├── .github/
│   └── workflows/
│       ├── android.yml                        # CI (ktlint + unit tests)
│       └── release.yml                        # Release APK builder
├── build.gradle.kts                           # Root build config
├── settings.gradle.kts                        # Project settings
└── gradlew                                    # Gradle wrapper
```
\n---

## Tech Stack

| Category | Library | Purpose |
|----------|---------|---------|
| **Language** | [Kotlin 2.3+](https://kotlinlang.org/) | Primary language |
| **UI** | [Jetpack Compose](https://developer.android.com/develop/ui/compose) (BOM 2026.03.01) | Declarative UI |
| **Design** | [Material3](https://m3.material.io/) (Compose) + Material You | Dynamic theming |
| **Navigation** | [Navigation3](https://developer.android.com/develop/ui/compose/navigation) | Screen routing |
| **Networking** | [Retrofit 2](https://square.github.io/retrofit/) + [OkHttp 4](https://square.github.io/okhttp/) | REST API calls |
| **Persistence** | [Room](https://developer.android.com/training/data-storage/room) (SQLite) | Local message history |
| **Encryption** | AndroidX Security Crypto | Secure key storage |
| **Background** | Foreground Service | Notification polling (`ChatNotificationService`) |
| **Serialization** | [Gson](https://github.com/google/gson) | JSON parsing |
| **Testing** | jUnit 5 + [MockK](https://mockk.io/) + [OkHttp MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver) | Unit testing |
| **Linting** | [ktlint](https://ktlint.github.io/) | Kotlin code style |

---

## Architecture

The app follows a **single-activity, Compose-first** architecture with MVVM:

```
View (Composable) → ViewModel → ApiClient (Retrofit) → Hermes Agent (HTTP)
```

- **Screens** are pure Compose functions that observe `StateFlow` from their ViewModel
- **ViewModels** manage UI state and call the API client
- **ApiClient** is a Retrofit-based HTTP client that talks to the Hermes Agent REST API
- **Navigation** uses a custom NavHost with drawer-based routing

---

## Contributing

PRs are welcome! Here's the workflow:

1. **Pick an issue** — or create one first to discuss the change
2. **Branch** from `main` — use `feat/issue-N-description` or `fix/issue-N-description`
3. **Implement** — one PR per logical change
4. **CI must pass** — ktlint + lintDebug + unit tests run on every PR
5. **Squash merge** into `main`

### Code style

- ktlint enforces consistent formatting (`./gradlew ktlintCheck`)
- Use `HermesScaffold` for new screens (never raw Material3 `Scaffold`)
- Compose parameters follow the standard order: `modifier`, callbacks, content

### PR checklist

- [ ] Branch from `main` and target `main`
- [ ] CI is green (ktlint, lint, tests)
- [ ] No unused imports or dead code
- [ ] `contentDescription` on all `Image`/`Icon` elements (accessibility)
- [ ] Follows the existing pattern for similar screens

---

## Releases

Pre-built APKs are available on the [Releases page](https://github.com/Hy4ri/hermes-mobile/releases).

Each release is built automatically by CI when a maintainer pushes a `v*` tag:

```bash
git tag v1.2.0
git push origin v1.2.0
```

The `release.yml` workflow will:
1. Run full CI (ktlint, lint, tests)
2. Build a release APK with the correct version
3. Create a GitHub Release with auto-generated notes
4. Attach the APK as a downloadable asset

---

## License

```
Copyright 2026 Hy4ri

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
