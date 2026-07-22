# AGENTS.md

Guidance for AI coding agents working on this repository.
This complements [README.md](README.md) (for humans) with agent-focused context.

## Project Overview

**Hermes Mobile** is a Jetpack Compose Android app — a mobile control panel for
[Hermes Agent](https://hermes-agent.nousresearch.com). It talks to the Hermes
dashboard's REST API and WebSocket TUI Gateway (JSON-RPC 2.0) over a trusted LAN.

- **Package:** `com.m57.hermescontrol`
- **Min SDK 26 / Target SDK 36 / Compile SDK 36**
- **Kotlin 2.4.10**, KSP 2.3.10 (standalone versioning, NOT `kotlinVersion-kspVersion`)
- **Jetpack Compose**, Room 2.7.x, Navigation3, OkHttp WebSocket, Retrofit, Kotlinx Serialization
- **Auth:** `EncryptedSharedPreferences` (AES256-GCM), Bearer token (REST) + `?token=` (WS)

## Build & Test Commands

### ✅ Local Android SDK — if available

If you have a local Android SDK (`ANDROID_HOME` set), these work:

```bash
./gradlew assembleDebug                     # full APK build
./gradlew testDebugUnitTest                 # unit tests (MockK)
./gradlew ktlintCheck                       # style check
```

**ktlint standalone** (no SDK needed):

```bash
# Download the matching binary
curl -sSLO https://github.com/pinterest/ktlint/releases/download/1.2.1/ktlint
chmod +x ktlint
./ktlint <file>                             # check one file
./ktlint --format <file>                    # auto-fix
```

**No SDK? CI handles everything** — push small and watch the checks below.

### CI pipeline (`.github/workflows/android.yml`)

| Job                  | Purpose                                                                                                                            |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `ktlint`             | ktlint 1.2.1 style check + `checkColorLiterals` (hardcoded Color guard, issue #622)                                                |
| `android-lint`       | Android Lint                                                                                                                       |
| `unit-tests`         | JUnit                                                                                                                              |
| `build`              | assembleDebug (gated by the 3 above)                                                                                               |
| `release-compile`    | compileReleaseKotlin — catches release-variant compilation issues (e.g. debugImplementation-scoped deps referenced in main source) |
| `instrumented-tests` | Compose UI tests on Android emulator (API 34 ATD)                                                                                  |
| `ci-summary`         | Aggregator (`if: always()`) — branch protection gates on THIS check                                                                |

Also: There is a separate workflow `merge-conflict-detector.yml` that auto-labels PRs with `has-conflicts`.

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

**Drawer gesture state is screen-owned (issue #619).** Each screen declares whether
the modal drawer's swipe gestures should be active while it is visible, via a single
source of truth — no global gesture set, no defensive `LaunchedEffect(snapTo(Closed))`,
no `closeDrawer` callback on `NavigationController`.

- `HermesScaffold(drawerGesturesEnabled = true)` — default; primary screens and
  most list screens. The scaffold reconciles this preference into the
  `DrawerGestureController` via a `SideEffect`.
- `HermesScaffold(drawerGesturesEnabled = false)` — drill-down sub-pages (e.g.
  `SettingsConnectionPage`, `SettingsAppearancePage`, …). The controller closes
  the drawer itself if it was open when the screen composed, so the scrim can't
  stick around and intercept the next tap.
- `DisableDrawerGestures()` — for entry screens that don't use `HermesScaffold`
  (Landing, AuthLogin, PairingCodeEntry).

`ModalNavigationDrawer` in `Navigation.kt` reads `gestureController.enabled`
(provided via `LocalDrawerGestureController`) and passes it to Material's
`gesturesEnabled` parameter. To change a screen's gesture behavior, edit the
screen — not `Navigation.kt`.

### Activity-Scoped ViewModels

Some ViewModels (e.g. `AuthLoginViewModel`) are created at the Activity scope via
`viewModel()` at the navigation entry level. This means they **survive navigation**
and cached state like `connectionSuccess` persists across screen entries.

Always pair transient state flags with a `DisposableEffect` cleanup:

```kotlin
DisposableEffect(Unit) {
    onDispose { viewModel.clearTransientState() }
}
```

The cleanup function should reset self-transitioning flags (`connectionSuccess`,
`isLoading`, `errorMessage`) so the screen can re-enter cleanly.

### WebSocket Lifecycle

`HermesWsClient` is a singleton that outlives individual screens. It must be
explicitly disconnected on logout and reconnected after login:

| Event         | Action                                                 |
| ------------- | ------------------------------------------------------ |
| Logout        | `HermesWsClient.disconnect()` before clearing tokens   |
| Login success | `HermesWsClient.connect()` after `ApiClient.rebuild()` |

The singleton's `connect()` has a guard (`if connected → skip`) so it's safe to
call unconditionally.

### Shared Components

- **`HermesScaffold`** — drawer-aware Scaffold + TopAppBar with refresh slot,
  pull-to-refresh, optional snackbar host. New screens MUST use this instead of raw
  `Scaffold`. API: `title: @Composable () -> Unit` (not `String` — wrap in
  `{ Text("...") }`).
- **`StateViews`** — `LoadingState`, `ErrorState`, `EmptyState`. Every data screen
  must implement all three branches in its `when { }` block.

### ⚠ HermesScaffold Padding Foot-Gun

**This is the #1 recurring bug in this codebase.** It has been re-introduced on 4+ screens
across 3+ PRs (Settings, Achievements, Webhooks, Config — PRs #445, #454, #455).

**Root cause:** `HermesScaffold` wraps content in an internal `Box(Modifier.padding(paddingValues))`
that already offsets for the top bar. But it also passes `paddingValues` into the content lambda,
which looks like it should be applied — and every new screen does exactly that:

```kotlin
HermesScaffold(...) { paddingValues ->      // ← scaffold already handles top bar offset via Box
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),         // ← ❌ double-stacked top padding
        contentPadding = listContentPadding, // ← extra 8dp, now triple-stacked
    ) { ... }
}
```

**The deeper issue:** Passing `paddingValues` into the lambda implies "you need to use this,"
when the scaffold has ALREADY pre-applied it in its own outer `Box`. This API design creates
a natural foot-gun: every developer instinctively adds `.padding(paddingValues)` on inner
content because it seems correct.

**Correct pattern — do NOT apply `paddingValues` on inner content:**

```kotlin
// ✅ List screens:
LazyColumn(
    modifier = Modifier.fillMaxSize(),    // no .padding(paddingValues)!!
    contentPadding = listContentPadding,  // this is the ONLY padding needed
    verticalArrangement = listItemSpacing,
)

// ✅ Non-list screens (Column/Box wrapping):
Column(
    modifier = Modifier.fillMaxSize(),    // no .padding(paddingValues)!!
) { ... }

// ✅ Loading/Error/Empty states — these DO need it:
LoadingState(modifier = Modifier.padding(paddingValues))
```

**Quick test:** If your screen's top gap is wider than CronJobsScreen's, you've double-stacked.

**See also:** `references/hermes-scaffold-padding.md` in the skill doc for the full
breakdown, edge cases, and timeline of previous occurrences.

### Room Persistence

- `ChatMessageEntity` / `ChatMessageDao` / `HermesDatabase` — chat messages survive
  app kills. `getMessagesForSession()` returns `suspend fun ...: List<ChatMessageEntity>`
  (not `Flow` — the caller controls the coroutine scope).
- Room 2.7.x requires `room { schemaDirectory("$projectDir/schemas") }` DSL.
- `ChatViewModel` extends `AndroidViewModel` (needs Application for DB access).

### Theme

`Theme.kt` uses a preset-based theme system with 6 built-in presets: Default,
Monochrome, Gruvbox, Catppuccin, AMOLED, and Neon Noir. Each preset provides both
light and dark color schemes plus matching semantic status colors.

Dynamic color (Material You on API 31+) is controlled by a `useDynamicColors`
parameter on `HermesControlTheme`, not a compile-time constant. When dynamic
colors are active they override the preset's `ColorScheme`, but semantic status
colors (success/warning/error/info) are ALWAYS resolved from the active preset
via `LocalHermesStatusColors` — never from `MaterialTheme.colorScheme`.

Access status colors via `LocalHermesStatusColors.current.success`, not
`MaterialTheme.colorScheme`.

## Git Workflow

### PR-Always (ENFORCED)

**Every change goes through a PR. Never push directly to `main`.**

```bash
git checkout main && git pull origin main
git checkout -b fix/issue-N-description    # or feat/...
# make changes, run ./ktlint --format
git commit -m "fix(#N): description"
git push -u origin HEAD
gh pr create --title "fix(#N): description" --body "Closes #N"
```

### Commit Conventions — STRICT

**⚠ Agent-authored commits:** Do NOT override git authorship (`--author`) with the agent's identity. Use the default git config. No `Co-Authored-By` or `Generated with` trailers.

- **Short commits:** subject line + max 2 lines of body. Split into atomic commits rather than writing long bodies.
- **Conventional Commits** types: `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `chore`.
- Bug fixes annotated inline with the tracking issue/regression ID.

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

- Don't run `./gradlew` tasks without an Android SDK — CI handles compilation, lint, and tests.
- Don't remove `@OptIn(ExperimentalMaterial3Api::class)` from screens that use
  `SegmentedButton`, `PullToRefreshBox`, or other experimental M3 APIs.
- Don't access `MaterialTheme.colorScheme.*` inside non-`@Composable` lambdas
  (`remember {}`, `buildAnnotatedString`, `LaunchedEffect`). Extract to a local
  `val` at the composable scope first.
- Don't add new screens without checking if an existing one already covers the
  functionality (28+ screens exist). Extend rather than duplicate.
- **⚠ Never scope a dependency to `debugImplementation` if its import is used in
  `main/` source code.** The CI `release-compile` job catches this, but save the
  cycle. `okhttp3.logging.HttpLoggingInterceptor` is the classic example — it's
  imported in `ApiClient.kt` (main source) so it must be `implementation`, not
  `debugImplementation`. If you want it debug-only, wrap the usage in
  `if (BuildConfig.DEBUG)` or extract it behind an interface.

### Hardcoded Color Guard (issue #622)

A custom Gradle task `checkColorLiterals` fails the build if `Color(0x...)` hex
literals or named `Color.White`/`Color.Black`/etc. appear outside the allowed
paths (`theme/`, `*Preview.kt`, `PairingScreen.kt`, `AuthLoginScreen.kt`).

Use `MaterialTheme.colorScheme.<token>`, `LocalHermesStatusColors.current.<semantic>`,
or `Color.Transparent` / `Color.Unspecified` instead. The guard runs in CI as part
of the `ktlint` job.

## Project Layout

```
com.m57.hermescontrol/
├── data/
│   ├── config/     ServerStore, ConnectionProfile, migrations
│   ├── local/      AuthManager, Room (ChatMessageEntity/Dao, HermesDatabase), AnalyticsCacheStore
│   ├── model/      40+ data classes for API responses + requests
│   ├── remote/     ApiClient, Retrofit service, OkHttp provider, cookie management
│   ├── session/    ActiveSessionHolder
│   └── ws/         HermesWsClient, JSON-RPC models, WsEvent, BillingRepository
├── notification/   ChatNotificationService, NotificationReplyReceiver
├── theme/          Color, Theme, Motion, Spacing, Shapes, Type, HermesStatusColors
│   └── presets/    Default, Monochrome, Gruvbox, Catppuccin, AMOLED, NeonNoir
├── ui/
│   ├── common/     HermesScaffold, StateViews, SharedComponents, DetailDialog, DetailRows
│   └── 28 feature packages (achievements, analytics, authlogin, billing, channels,
│       chat, config, connect, cron, gateway, kanban, keys, landing, logs, mcp,
│       model, pairing, plugins, process, profiles, providers, sessions, settings,
│       skills, system, toolsets, webhooks)
├── util/           CronExpressionFormatter, LocaleContextWrapper
├── HermesControlApp.kt     Application class
├── Navigation.kt           Drawer + NavDisplay + entry wiring
├── NavigationController.kt Central navigation guard (dedup)
├── NavigationKeys.kt       @Serializable NavKey data objects (28 screens + 6 settings sub-pages)
├── ScreenRegistry.kt       entry<T> registrations for all NavKeys
└── MainActivity.kt
```

## Further Reading

- [README.md](README.md) — human-facing overview, features, screenshots, tech stack
- [CONTRIBUTING.md](CONTRIBUTING.md) — contributor workflow, PR checklist, code style
- [.github/workflows/android.yml](.github/workflows/android.yml) — CI pipeline source of truth
- [.github/workflows/merge-conflict-detector.yml](.github/workflows/merge-conflict-detector.yml) — auto-labels conflicting PRs
