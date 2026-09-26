# Mobile theme marketplace browser + provable font selection — acceptance spec v1

Version: v1. Status: draft, pending human approval. Task: `t_d1282a61`.
No implementation in this stage; this document is the gate for it.

## 1. Problem statement

1. The Themes entry point exposes a preset named like a marketplace ("Marketplace"
   resolving to Default) without a live catalog behind it. The user cannot tell
   whether they are looking at a browsable catalog, an installed custom theme, or a
   built-in preset.
2. `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt` is a one-screen result list: tapping a card calls
   `viewModel.applyTheme(entry)` immediately (line 98). There is no select-then-apply
   step, no detail/preview, and no active badge, even though the ViewModel already
   exposes `activeCustomThemeId: StateFlow<String?>` (from `ThemeApplier`) and the
   screen never collects it.
3. `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:69` (`ThemePreset.CUSTOM -> custom ?: DefaultTheme`) is a correct
   crash-safety fallback, but when persisted tokens fail to restore the UI still
   presents whatever label was stored, which reads as a fake active Marketplace state.
4. The font selector (`AppFontFamily`, `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:20-24`) offers five options —
   System Default, Sans Serif, Serif, Monospace, and Cursive — but on Android `FontFamily.Default` and `FontFamily.SansSerif`
   may resolve to the same glyphs, and the current `AppFontFamilyTest` only asserts
   keys/display names exist — never that options render distinctly. Removing a key is not free:
   `ServerStoreState.chatFontFamily` persists the key string, so a removed key must keep round-tripping
   to `SYSTEM` via `AppFontFamily.fromKey` (`app/src/main/java/com/m57/hermescontrol/theme/Type.kt:29`).

## 2. Ground truth (read, not assumed)

### 2.1 Catalog source

- Desktop: `apps/desktop/electron/vscode-marketplace.ts` (hermes-agent repo, absolute
  path `/home/sam/projects/hermes-agent/apps/desktop/electron/vscode-marketplace.ts` —
  sibling checkout, not in this repo). `searchMarketplaceThemes` POSTs an
  ExtensionQuery payload (Target `Microsoft.VisualStudio.Code` + Category `Themes` +
  ExcludeWithFlags `4096`), filters with `looksLikeIconTheme`, `resolveExtension`
  takes the `Microsoft.VisualStudio.Services.VSIXPackage` asset as `vsixUrl`, and
  `extractThemes` parses the `.vsix` zip central directory for
  `contributes.themes[]` JSON only. Header comment: "No theme code is ever executed".
- Mobile port, same contract:
  - `app/src/main/java/com/m57/hermescontrol/data/theme/marketplace/GalleryQuery.kt` —
    `gallerySearchPayload` (flags 772), `galleryResolvePayload` (flags 914),
    FilterType constants 5/7/8/10/12 documented at lines 14-18.
  - `app/src/main/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepository.kt` —
    `GALLERY_BASE_URL = https://marketplace.visualstudio.com`,
    `GALLERY_QUERY_PATH = /_apis/public/gallery/extensionquery`, dedicated OkHttpClient
    with no app cookie jar/auth interceptors (lines 32-33), 15-min
    `query|limit|page` cache, `looksLikeIconTheme` ported verbatim (lines 280-288),
    4 MB response cap, 3 attempts with `jitteredBackoff` (defaultClient at line 291).
  - Tests: `app/src/test/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepositoryTest.kt`.

### 2.2 Apply pipeline (already built, not to be reinvented)

- `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt` —
  downloads the `.vsix`, extracts contributed `colors` maps via `ZipFile`, JSONC-tolerant
  parse. Data only; no code execution boundary to preserve (mirrors desktop).
- `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeDefinitionConverter.kt` —
  `buildFamily` converts `ThemeTokenSet` variants to a `ThemePalette`
  (seed+mix, desktop-ported).
- `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt` —
  `applyFamily(extensionId, displayName, variants)` persists
  (`ServerStoreState.themePreset=CUSTOM`, `customThemeId`, `customThemeName`,
  `customThemeTokensJson`) and publishes via `setCustomPalette`; exposes
  `activeCustomThemeId`, `activeCustomThemeName`, `applyError`;
  `restorePersisted(id, name, tokensJson)` is called from `AuthManager.init`
  (`app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:244`) and is a
  silent no-op on corrupt/empty input.
- `app/src/main/java/com/m57/hermescontrol/data/config/ServerStoreState.kt:20-26` —
  persistence fields. `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:57-69` —
  dispatcher with CUSTOM→Default fallback.

### 2.3 Typography today

- Global: `app/src/main/java/com/m57/hermescontrol/MainActivity.kt:91-99` collects `AuthManager.fontFamilyFlow` and passes
  `AppFontFamily.fromKey(chatFontFamily).toFontFamily` into `HermesControlTheme`
  (`app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:104`), which builds `createTypography(fontFamily)` (`app/src/main/java/com/m57/hermescontrol/theme/Type.kt:45`) and
  installs it as `MaterialTheme.typography`. Persisted key:
  `ServerStoreState.chatFontFamily` (default `"system"`), written via
  `AuthManager.setChatFontFamily` (`app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:896-901`), selected in
  `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt:236-260` (dropdown over
  `AppFontFamily.entries`).
- Chat bodies DO consume the global type scale: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt:200,347,656`
  (`bodyMedium`), `app/src/main/java/com/m57/hermescontrol/ui/chat/MarkdownText.kt:111,173,210+` (`bodyMedium` family), so a
  font-family change flows to normal message text.
- Intentional monospace overrides (must be preserved): every `FontFamily.Monospace` use in the codebase is enumerated below. These are concrete code/log surfaces, not just the chat package:
  - `ui/chat/ToolBubble.kt` — lines 191, 225, 238, 344, 358, 374, 390, 569, 684 (many `bodySmall.copy(fontFamily = FontFamily.Monospace)` and direct `fontFamily = FontFamily.Monospace` sites)
  - `ui/chat/components/CodeTerminalCard.kt:128` (title label)
  - `ui/chat/components/ReplyErrorCard.kt:107`
  - `ui/chat/components/MessageCards.kt:203,300`
  - `ui/chat/components/DiffViewCard.kt:238,328`
  - `ui/chat/components/SubagentInspectionSheet.kt:632,919`
- `ui/bots/group/components/GroupChatToolChip.kt:157,186`
  - `ui/chat/markdown/MarkdownInlineStyler.kt:97,246` (inline code spans)
  - `ui/chat/ChatBubble.kt`, `ui/chat/MarkdownText.kt` — consume global type (inherited, NOT monospace overrides)
  - `ui/logs/LogsScreen.kt:260`
  - `ui/common/ActionProgressDialog.kt:158`
  - `ui/plugins/MemoryProviderDetailScreen.kt:439,447,454`
  - `ui/profiles/ProfilesScreen.kt:968`
  - `ui/toolsets/ToolsetDetailScreen.kt:430,515,672`
  - `ui/sessions/components/SessionCard.kt:87`
  - `ui/channels/components/PlatformCard.kt:191`
  - `ui/channels/ChannelsScreen.kt:295`
  - `ui/mcp/components/McpDialogs.kt:169,172`
  - `ui/mcp/components/ServerCard.kt:185,196,299`
  - `ui/config/ConfigScreen.kt:311,455,671,986`
  - `ui/skills/components/SkillEditorDialog.kt:145`
  - `ui/skills/components/SkillPreviewDialog.kt:75`
  - `ui/skills/components/SkillScanViews.kt:256`
  - `ui/providers/ProvidersScreen.kt:373,448,456`
  - `ui/keys/KeysScreen.kt:583,749`
  - `ui/webhooks/WebhooksScreen.kt:534`
  - `ui/cron/CronJobsScreen.kt:526`
  - `ui/kanban/components/KanbanTaskCard.kt:145`
  - `ui/kanban/KanbanTaskScreen.kt:1035`
  - `ui/system/components/ActionLogSection.kt:117`
  - **Total: 29 files / 55 sites** — implementer must re-verify by `grep -r "FontFamily.Monospace" app/src/main/java/` and attach the final list to the PR. Any new `FontFamily.Monospace` sites added in PR must be listed here.
  - **NOT monospace overrides**: `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:20-21,184` — `FontFamily.Default`/`FontFamily.SansSerif`/`FontFamily.Serif`/`FontFamily.Cursive` are the `AppFontFamily` enum values (global family selection), not monospace overrides.

### 2.4 Wiring and CI pins the implementer will need

- NavKey: `app/src/main/java/com/m57/hermescontrol/NavigationKeys.kt:83` (`ThemeMarketplaceScreen`); drawer entry
  wiring `app/src/main/java/com/m57/hermescontrol/ScreenRegistry.kt:145-150` (gesture-enabled primary screen,
  `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:63`).
- CI: `.github/workflows/android.yml:317-326` (`instrumented-tests`: `api-level: 34`, `target: aosp_atd`) — the only
  emulator F2/F3 metric proof can run on.
- Reusable components that already exist: `ui/common/DetailDialog.kt`, `ui/common/DetailRows.kt`,
  `ui/common/StateViews.kt`, `ui/common/HermesScaffold.kt`.

## 3. Requirements

### M — Marketplace browser

- M1. Catalog provenance. The screen header/copy must state it browses the public
  VS Code Gallery directly (same ExtensionQuery endpoint as desktop), not a
  Hermes-hosted catalog and not a local preset. Implementation area:
  `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt` + strings.
- M2. Data-only boundary. Fetch metadata, preview images, and color-theme JSON only;
  never execute third-party extension code. The existing
  `VsixThemeParser`+`ThemeDefinitionConverter` path is the only import route; no new
  downloader/parser may run code. Implementation area: `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt`,
  `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeDefinitionConverter.kt`.
- M3. Browser UX. Initial browse list (empty query = most-installed), debounced
  search-as-you-type (keep 300 ms, `ThemeMarketplaceViewModel:179`), pagination via
  LoadMore, and Loading/Empty/Error/Retry states (already present via
  `SkeletonListState`/`EmptyState`/`ErrorState`) must all remain and be covered by UI
  tests. Implementation area: `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt`,
  `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceViewModel.kt`.
- M4. Select-then-apply. Tapping a list card must SELECT (show detail/preview:
  name, publisher, installs, description, preview image, contributed-variant count
  where available) and must NOT change the app theme. A distinct explicit Apply
  control performs `applyTheme`. The current `Card.clickable { onApply() }`
  (`app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:154-158`) must be removed/replaced.
- M5. Active state. The list and detail must show an Active badge for the row whose
  `extensionId == activeCustomThemeId` (ViewModel already exposes it;
  `ThemeMarketplaceScreen` must collect it). The detail/header must show the active
  theme's display name + source extension id, distinguish built-in presets from the
  imported theme, and never label the CUSTOM→Default safety fallback as an installed
  Marketplace theme. Confusing-label mechanism (cite in the UI test): `Custom (marketplace)` is a real
  string (`app/src/main/res/values/strings.xml:359`, `name="theme_preset_custom"` — resolve by name, not line,
  while uncommitted marketplace strings shift it), rendered unconditionally whenever the stored preset
  is `CUSTOM` at `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt:144` (collapsed label)
  and `:202` (dropdown item) — regardless of
  whether a palette actually restored.
- M6. Persistence + honest recovery. Restart must restore via the existing
  `ThemeApplier.restorePersisted` path. Honest-unavailable predicate (state explicitly, do not re-derive):
  `themePreset == CUSTOM && activeCustomThemeId == null` (`app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:31-32`; `customPaletteFlow == null`,
  `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:45`, so `Theme.kt` renders `DefaultTheme`). `restorePersisted`
  (`app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:88-101`) returns
  early without publishing a palette and without clearing persisted state when tokens are blank (:93), the JSON
  is undecodable or decodes to an empty list (:97), or `ThemeDefinitionConverter.buildFamily` throws (:98); it is
  called from `AuthManager.init` (`app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:244`), leaving `customThemeName`/`customThemeId` populated while
  nothing is applied. The UI must surface "Custom theme unavailable;
  using Default" with an action (re-apply, or clear via `ThemeApplier.clearCustomTheme`, `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:68`),
  not a fake active Marketplace label. Implementation area: `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt`,
  `app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt` init path, settings/themes surfaces.
- M7. Offline test strategy. All of M1–M6 provable without network: fake
  `ThemeMarketplaceRepository` (gallery payload mapping, pagination, error mapping),
  fake `VsixThemeParser` (apply path, empty-colors rejection), selected-state mapping
  (`activeCustomThemeId` → badge), failed-restore case, and a no-execution assertion
  (import path parses JSON only — no classloading/eval/JS-engine invocation; test
  scans the import pipeline's dependency set or asserts parser output for a fixture
  `.vsix` containing a decoy JS file that must be ignored).
  Named offline seams (use these; do not write apply-path tests against the live network):
  `ThemeMarketplaceRepository` ctor (`app/src/main/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepository.kt:37-42`: base URL, `cacheTtlMs`, clock,
  `OkHttpClient`) driven with MockWebServer (`app/build.gradle.kts:214` okhttp-mockwebserver, `:216` mockk);
  `VsixThemeParser` ctor (`app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt:28-31`) takes an `OkHttpClient`, so the decoy-vsix fixture is served
  locally; `ThemeApplier` is a Kotlin `object` writing through `AuthManager.serverStore`, which throws unless
  initialised — use the established reflective-injection pattern
  (`app/src/test/java/com/m57/hermescontrol/data/local/AuthManagerTest.kt:80-89`).

### F — Typography

- F1. Inventory. Spec implementation must enumerate every `MaterialTheme.typography`
  consumer in `ui/chat/` that inherits the global family (normal-chat behavior) vs
  every intentional monospace override (code/log). Baseline list is in §2.3; the
  implementer must re-verify by grep and attach the final list to the PR.
- F2. Honest options. Ship only options proven visually distinct on supported devices,
  or label true aliases as aliases. **JVM object inequality of `FontFamily` instances
  is NOT acceptance proof** — `FontFamily.Default` and `FontFamily.SansSerif` are
  distinct Compose objects that may resolve to identical Android glyphs on some devices.
  Concretely: measure System Default vs Sans Serif vs Serif vs Monospace vs Cursive
  on the API 34 ATD emulator (CI instrumented-tests) with a discriminating sample
  (e.g. `Il1O0` + mixed case + digits) and compare actual rendered glyph advance widths
  via `Paint.measureText()` or screenshot pixel-diff; if two non-alias options measure
  identical widths (within 1% tolerance), either drop/merge one entry or render its
  label as "(same as System Default on this device)". Do not ship three labels that
  render identically. Bundling small licensed fonts is allowed as an alternative,
  with license files committed.
  Proof evidence required for F2: a machine-readable artifact (CSV/JSON of measured
  widths per option, or golden-diff PNG with pass/fail metadata) attached to the PR.
- F3. Deterministic proof. Unit level: glyph-width assertions on a discriminating
  sample per option using `android.graphics.Paint.measureText()` or equivalent
  (fails if two non-alias options measure identical widths within 1% tolerance).
  **`createTypography(family)` producing distinct `Typography` objects is NOT
  acceptance proof** — Compose `FontFamily` objects are always distinct even when
  they resolve to the same Android typeface. UI level: screenshot/golden-diff
  comparison (pixel-level pass/fail on a discriminating sample text) OR measured
  glyph-width assertions on an API 34 ATD device/emulator. Persistence: round-trip
  `chatFontFamily` key through `ServerStoreState` + restart-restore test
  (`fromKey` unknown-key → SYSTEM fallback covered). Each non-alias option must
  have its own measured-width or golden-diff evidence; identical-measure pairs must
  be merged or labeled as aliases.
- F4. Monospace preservation. Code/log surfaces listed in §2.3 keep explicit
  `FontFamily.Monospace` regardless of the global setting; normal chat body follows
  the global setting. State both behaviors in the implementation PR description and
  cover with a Compose UI test (code block stays monospace while body follows
  selection).

### Non-goals (explicit)

- No server-side catalog, no new backend endpoint, no Hermes dashboard dependency.
- No physical-device work: no ADB, no Icarion install, no app-private device data
  access, never `adb uninstall`.
- No Gradle builds in the spec stage; implementation PR uses CI.

## 4. Acceptance criteria

- A1. Fresh install → Themes → marketplace browser shows a live list with search,
  pagination, loading/empty/error/retry states (UI tests with fakes, no network).
- A2. Tapping a card opens detail/preview and does NOT change the app theme
  (UI test asserts palette/preset unchanged after tap).
- A3. Explicit Apply changes the theme, sets the Active badge on that extension id,
  shows name + source, survives restart (fake-backed ViewModel + applier test and
  persistence round-trip test).
- A4. Corrupt/missing persisted tokens with preset=CUSTOM → "unavailable; using
  Default" state with re-apply/clear action; never a fake active Marketplace label.
- A5. Import path proven data-only: fixture `.vsix` with decoy executable content
  imports colors while ignoring the decoy (unit test); no new code-execution
  dependency in the import pipeline.
- A6. Font options each proven distinct (metric assertions) or honestly labeled as
  aliases; selection persists across restart; code blocks remain monospace while
  body text follows the selection (unit + Compose UI tests).

## 5. Requirement-to-evidence table (machine-checkable)

| Req | Source evidence | Acceptance proof | Implementation area |
| --- | --------------- | ---------------- | ------------------- |
| M1 | `app/src/main/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepository.kt:32-33,58-63`; desktop `/home/sam/projects/hermes-agent/apps/desktop/electron/vscode-marketplace.ts:searchMarketplaceThemes` | UI test: header/copy cites VS Code Gallery; no Hermes-backend URL in catalog path | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt`, strings |
| M2 | `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt:1-60`; desktop `/home/sam/projects/hermes-agent/apps/desktop/electron/vscode-marketplace.ts:6` ("never executed") | Fixture-vsix unit test incl. decoy JS ignored; dependency scan of import path | `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt`, `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeDefinitionConverter.kt` |
| M3 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:65-133`; `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceViewModel.kt:73-100,133-179` | UI tests: initial/debounce/pagination/loading/empty/error/retry with fake repo | Screen + ViewModel |
| M4 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:154-158` (tap-to-apply today) | UI test: tap selects, preset unchanged; Apply button applies | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt` (+ detail) |
| M5 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceViewModel.kt:58`; `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:31-32`; `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:57-69` | UI test: badge follows `activeCustomThemeId`; fallback not labeled Marketplace | Screen + ViewModel |
| M6 | `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:88-102`; `app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:244`; `app/src/main/java/com/m57/hermescontrol/data/config/ServerStoreState.kt:20-26` | Unit/UI test: corrupt tokens → unavailable-state + clear/re-apply | `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt`, init path |
| M7 | `app/src/test/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepositoryTest.kt` (existing) | All above run offline; CI green | `app/src/test/.../marketplace/` |
| F1 | `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/MarkdownText.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/ToolBubble.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/MessageCards.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/DiffViewCard.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/CodeTerminalCard.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/ReplyErrorCard.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/SubagentInspectionSheet.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/markdown/MarkdownInlineStyler.kt`, `app/src/main/java/com/m57/hermescontrol/ui/bots/group/components/GroupChatToolChip.kt`, `app/src/main/java/com/m57/hermescontrol/ui/logs/LogsScreen.kt`, `app/src/main/java/com/m57/hermescontrol/ui/common/ActionProgressDialog.kt` (plus 17 more files per §2.3 enumeration) | Final consumer/override list (29 files, 55 sites) attached to PR; grep-verified | `ui/chat/**`, `ui/**` (read-only) |
| F2 | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:15-25`; `app/src/test/java/com/m57/hermescontrol/theme/AppFontFamilyTest.kt` (keys only today) | Measured glyph-width CSV/JSON per option on ATD, or golden-diff PNG; alias labeling or removal | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt`, `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt` |
| F3 | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:45 createTypography`; `ServerStoreState.chatFontFamily` | `Paint.measureText()` width assertions per option (unit) + screenshot/golden-diff (UI) + persistence round-trip | `theme/`, `data/config/` tests |
| F4 | §2.3 override list | Compose UI test: code monospace + body follows setting | `ui/chat/**` tests |

## 6. Suggested implementation shape (non-binding)

- `ui/thememarketplace/ThemeMarketplaceScreen`: collect `activeCustomThemeId`; split card tap
  (select → detail, possibly a bottom sheet or detail pane reusing
  `DetailDialog`/`DetailRows` per project conventions) from Apply button.
- Detail content: display name, publisher, installs, description, preview
  (`ThemeAssets.previewUrl` via existing lazy `resolveAssets`), variant count from
  a lightweight parse or post-apply metadata; Apply + Cancel.
- Recovery banner component for M6, shared between Themes screen and appearance
  settings if both display preset state.
- Font proof: extend `AppFontFamilyTest` with metric/distinctness assertions;
  instrumented golden or width assertion for F2/F3 on the API 34 ATD job.

## 7. Validation performed for this spec

- Read the mobile files cited in §2–§5 and the desktop
  `apps/desktop/electron/vscode-marketplace.ts` function/signature level directly.
- Path check: every `app/src/...:line` cited above was resolved against this worktree
  at commit `bfc25e1b` (the committed baseline); the desktop `apps/desktop/...`
  path was resolved against the sibling hermes-agent checkout
  (`/home/sam/projects/hermes-agent`). Uncommitted working-tree work that landed
  after the freeze (if any) is out of scope for these pins — re-verify line
  numbers if the baseline moves.
  (Confirmed 2026-09-26: repository pins `looksLikeIconTheme` `:280-288` and `defaultClient` `:291` are exact
  at `9554507b`. Concurrent uncommitted implementation in this worktree — `getEntryById` inserted after
  repository line ~170 plus marketplace strings/NavKey additions — shifts worktree lines below the insertion
  point (e.g. `strings.xml` `theme_preset_custom` `:359`→`:370`) without invalidating the baseline pins.
  Re-run `check_citations.py` against the implementation-PR baseline before building.)
- Markdown/link check: local script (no network, no Gradle, no ADB).
- Typography inventory: `grep -r "FontFamily.Monospace" app/src/main/java/` confirmed
  28 files / ~50+ sites across the entire codebase, not just the `ui/chat/`
  package (audit correction applied).
- Font proof correction: replaced `createTypography(family)` distinct-object
  assertion with `Paint.measureText()` glyph-width assertions and screenshot/golden-diff
  evidence requirements. `FontFamily.Default` vs `FontFamily.SansSerif` object
  inequality is explicitly called out as NOT being acceptance proof (audit correction applied).
- Re-verified at this commit: `AppFontFamily` five entries (`app/src/main/java/com/m57/hermescontrol/theme/Type.kt:20-24`, `fromKey` fallback `:29`);
  monospace inventory `29 files / 55 sites` including `app/src/main/java/com/m57/hermescontrol/ui/bots/group/components/GroupChatToolChip.kt:157,186`;
  confusing-label string (`strings.xml` `name="theme_preset_custom"`, `:359` at baseline — resolve by name:
  uncommitted marketplace strings above it shift the line) rendered at `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt:144,202`;
  unavailable predicate `themePreset == CUSTOM && activeCustomThemeId == null`
  (`app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:31-32`, `restorePersisted` early-returns `:93,97,98`, `clearCustomTheme` `:68`);
  offline seams (repository ctor `:37-42`, MockWebServer `app/build.gradle.kts:214`, mockk `:216`,
  `VsixThemeParser` ctor `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt:28-31`, reflective `AuthManager` injection `app/src/test/java/com/m57/hermescontrol/data/local/AuthManagerTest.kt:80-89`);
  wiring (`app/src/main/java/com/m57/hermescontrol/NavigationKeys.kt:83`, `app/src/main/java/com/m57/hermescontrol/ScreenRegistry.kt:145-150`, `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:63`);
  CI emulator (`.github/workflows/android.yml:317-326`, api 34 `aosp_atd`).
- Stale preset-count follow-ups (separate cards, not this spec): `ThemePreset` has seven built-ins plus
  `CUSTOM` (`app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:32`); prose still saying "six" (e.g. `AGENTS.md` theme section) is stale.
