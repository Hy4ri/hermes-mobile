# AGENTS.md

Guidance for AI coding agents working on this repository.
This complements [README.md](README.md) (for humans) with agent-focused context.

## Project Overview

**Hermes Control** is a Jetpack Compose Android app — a mobile control panel for
[Hermes Agent](https://hermes-agent.nousresearch.com). It talks to the Hermes
dashboard's REST API and WebSocket TUI Gateway (JSON-RPC 2.0) over a trusted LAN.

- **Package:** `com.m57.hermescontrol`
- **Min SDK 26 / Target SDK 36 / Compile SDK 36**
- **Kotlin 2.3.20**, KSP 2.3.9 (standalone versioning, NOT `kotlinVersion-kspVersion`)
- **Jetpack Compose**, Room 2.7.x, Navigation3, OkHttp WebSocket, Retrofit, Gson
- **Auth:** `EncryptedSharedPreferences` (AES256-GCM), Bearer token (REST) + `?token=` (WS)

## Build & Test Commands

### ⚠ No local Android SDK — CI is the only compilation truth engine

`./gradlew assembleDebug`, `./gradlew test`, `./gradlew ktlintCheck` all fail locally
(no Android SDK installed). **CI verifies compilation, lint, and tests.** Push small
and watch CI.

### What you CAN run locally

```bash
# Standalone ktlint binary (no SDK needed) — the PRIMARY local check
curl -sSLO https://github.com/pinterest/ktlint/releases/download/1.2.1/ktlint
chmod +x ktlint
./ktlint <file>              # check one file
./ktlint --format <file>     # auto-fix import ordering, indent, trailing commas
find app/src -name '*.kt' | xargs ./ktlint --format   # fix all
```

### CI pipeline (`.github/workflows/android.yml`)

| Job | Purpose |
|-----|---------|
| `ktlint` | ktlint 1.2.1 style check |
| `android-lint` | Android Lint |
| `unit-tests` | JUnit |
| `build` | assembleDebug (gated by the 3 above) |
| `ci-summary` | Aggregator (`if: always()`) — branch protection gates on THIS check |

Every Gradle job validates `gradle-wrapper.jar` and uses the remote build cache
(`GRADLE_ENCRYPTED_KEY` secret). `concurrency.cancel-in-progress: true`.

### Releasing (`.github/workflows/release.yml`)

Triggers on `git push tag v*`. Release APK uses R8 minification + resource shrinking.
Requires `permissions: contents: write` on the `build-release` job.

## Code Style

- **ktlint 1.2.1** is enforced in CI. Run `./ktlint --format` before pushing.
- Import ordering is the #1 CI failure: ktlint enforces ASCII-lexicographic order
  (uppercase before lowercase: `LaunchedEffect` before `collectAsState`).
- `const val` must use SCREAMING_SNAKE_CASE.
- Trailing commas required. No trailing whitespace.
- 120 char max line length.

## Architecture Conventions

### Navigation (Navigation3 — NOT Navigation Compose)

Uses `androidx.navigation3` (`NavKey`, `NavBackStack`, `NavDisplay`, `entry<T>`).

**⚠ Always route navigation through `NavigationController.navigateTo()`.** Never call
`backStack.add(ScreenKey)` directly from UI callbacks — the controller has a
deduplication guard. Bypassing it stacks duplicate screen entries that compete for
touch events and become unresponsive.

### Shared Components

- **`HermesScaffold`** — drawer-aware Scaffold + TopAppBar with refresh slot,
  pull-to-refresh, optional snackbar host. New screens MUST use this instead of raw
  `Scaffold`. API: `title: @Composable () -> Unit` (not `String` — wrap in
  `{ Text("...") }`).
- **`StateViews`** — `LoadingState`, `ErrorState`, `EmptyState`. Every data screen
  must implement all three branches in its `when { }` block.

### Room Persistence

- `ChatMessageEntity` / `ChatMessageDao` / `HermesDatabase` — chat messages survive
  app kills.
- Room 2.7.x requires `room { schemaDirectory("$projectDir/schemas") }` DSL.
- `ChatViewModel` extends `AndroidViewModel` (needs Application for DB access).

### Theme

`Theme.kt` has `ENABLE_DYNAMIC_COLOR = true` (Material You on API 31+). Semantic
status colors (success/warning/error/info) are ALWAYS brand-defined via
`LocalHermesStatusColors`, never dynamic. Access them via
`LocalHermesStatusColors.current.success`, not `MaterialTheme.colorScheme`.

## Git Workflow

### PR-Always (ENFORCED)

**Every change goes through a PR. Never push directly to `main`.**

```bash
git checkout main && git pull origin main
git checkout -b fix/issue-N-description    # or feat/...
# make changes, run ./ktlint --format
git -c user.name='ronia' -c user.email='ronia@m57' commit -m "fix(#N): description"
git push -u origin HEAD
gh pr create --title "fix(#N): description" --body "Closes #N"
```

### Commit Conventions

- **Author:** `ronia@m57` (set via `-c user.name` / `-c user.email`).
- **NO co-author trailer.** No `Co-Authored-By`, no `Generated with ...`. Ever.
- **Short commits:** subject line + max 2 lines of body. Split into atomic commits
  rather than writing long bodies.
- **Conventional Commits** types: `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `chore`.
- Bug fixes annotated inline: `// B7 (Jun 18 2026, kanban t_xxx): description`.

## Security Considerations

- **Cleartext HTTP/WS is intentional** — trusted LAN only. `usesCleartextTraffic="true"`
  and `http://`/`ws://` URLs are by design. Don't "fix" this unless adding HTTPS support.
- **`HttpLoggingInterceptor.Level.BODY`** must stay gated on `BuildConfig.DEBUG` — it
  prints the Authorization header. (B1)
- **AuthManager token** is ephemeral — it becomes invalid when the Hermes dashboard
  restarts. Symptom: 401 on every call. Fix: re-extract the token from the running
  gateway, don't assume the stored one is valid.
- **Release signing** uses a keystore with env-var credentials (`KEYSTORE_PATH`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Don't commit keystore files or
  hardcode passwords.

## Things to Avoid

- Don't run `./gradlew` tasks locally expecting them to work — no Android SDK.
- Don't remove `@OptIn(ExperimentalMaterial3Api::class)` from screens that use
  `SegmentedButton`, `PullToRefreshBox`, or other experimental M3 APIs.
- Don't use `remember { AuthManager.getBottomNavItems() }` without a key —
  SharedPreferences is not Compose state; the value goes stale. Read it directly.
- Don't access `MaterialTheme.colorScheme.*` inside non-`@Composable` lambdas
  (`remember {}`, `buildAnnotatedString`, `LaunchedEffect`). Extract to a local
  `val` at the composable scope first.
- Don't add new screens without checking if an existing one already covers the
  functionality (19+ screens exist). Extend rather than duplicate.

## Project Layout

```
com.m57.hermescontrol/
├── data/          local (AuthManager), remote (Retrofit), ws (WebSocket), model
├── notification/  ChatNotificationService (foreground service)
├── theme/         Color, Theme, Motion, Spacing, Shapes, Type
├── ui/            chat, settings, common (HermesScaffold, StateViews), + 17 screens
├── util/          CronExpressionFormatter, etc.
├── Navigation.kt          Drawer + NavDisplay + entry wiring
├── NavigationController.kt  Central navigation guard (dedup)
└── MainActivity.kt
```

## Further Reading

- [README.md](README.md) — human-facing overview, features, screenshots, tech stack
- [TEST_INFRA.md](TEST_INFRA.md) / [TEST_READY.md](TEST_READY.md) — test infrastructure notes
- [.github/workflows/android.yml](.github/workflows/android.yml) — CI pipeline source of truth
