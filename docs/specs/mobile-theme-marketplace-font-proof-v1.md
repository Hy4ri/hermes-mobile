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
3. `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:66` (`ThemePreset.CUSTOM -> custom ?: DefaultTheme`) is a correct
   crash-safety fallback, but when persisted tokens fail to restore the UI still
   presents whatever label was stored, which reads as a fake active Marketplace state.
4. The font selector (`AppFontFamily`, `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:15-25`) offers System Default, Sans
   Serif, and Monospace, but on Android `FontFamily.Default` and `FontFamily.SansSerif`
   may resolve to the same glyphs, and the current `AppFontFamilyTest` only asserts
   keys/display names exist — never that options render distinctly.

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
    with no app cookie jar/auth interceptors (lines 32-33, 264-272), 15-min
    `query|limit|page` cache, `looksLikeIconTheme` ported verbatim (lines 255-262),
    4 MB response cap, 3 attempts with `jitteredBackoff`.
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
  persistence fields. `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:57-67` —
  dispatcher with CUSTOM→Default fallback.

### 2.3 Typography today

- Global: `app/src/main/java/com/m57/hermescontrol/MainActivity.kt:91-99` collects `AuthManager.fontFamilyFlow` and passes
  `AppFontFamily.fromKey(chatFontFamily).toFontFamily` into `HermesControlTheme`
  (`app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:106`), which builds `createTypography(fontFamily)` (`app/src/main/java/com/m57/hermescontrol/theme/Type.kt:46`) and
  installs it as `MaterialTheme.typography`. Persisted key:
  `ServerStoreState.chatFontFamily` (default `"system"`), written via
  `AuthManager.updateChatFontFamily` (`app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:892-895`), selected in
  `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt:236-260` (dropdown over
  `AppFontFamily.entries`).
- Chat bodies DO consume the global type scale: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt:200,347,656`
  (`bodyMedium`), `app/src/main/java/com/m57/hermescontrol/ui/chat/MarkdownText.kt:111,173,210+` (`bodyMedium` family), so a
  font-family change flows to normal message text.
- Intentional monospace overrides (must be preserved): `app/src/main/java/com/m57/hermescontrol/ui/chat/ToolBubble.kt`
  (many `bodySmall.copy(fontFamily = FontFamily.Monospace)` sites),
  `app/src/main/java/com/m57/hermescontrol/ui/chat/components/MessageCards.kt:203,300`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/DiffViewCard.kt:238,328`,
  `app/src/main/java/com/m57/hermescontrol/ui/chat/components/SubagentInspectionSheet.kt:632,919` (code/log surfaces).

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
  search-as-you-type (keep 300 ms, `ThemeMarketplaceViewModel:174`), pagination via
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
  Marketplace theme.
- M6. Persistence + honest recovery. Restart must restore via the existing
  `ThemeApplier.restorePersisted` path. If `themePreset == CUSTOM` but tokens are
  missing/corrupt (palette null), the UI must surface "Custom theme unavailable;
  using Default" with an action (re-apply or clear via `clearCustomTheme`), not a
  fake active Marketplace label. Implementation area: `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt`,
  `app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt` init path, settings/themes surfaces.
- M7. Offline test strategy. All of M1–M6 provable without network: fake
  `ThemeMarketplaceRepository` (gallery payload mapping, pagination, error mapping),
  fake `VsixThemeParser` (apply path, empty-colors rejection), selected-state mapping
  (`activeCustomThemeId` → badge), failed-restore case, and a no-execution assertion
  (import path parses JSON only — no classloading/eval/JS-engine invocation; test
  scans the import pipeline's dependency set or asserts parser output for a fixture
  `.vsix` containing a decoy JS file that must be ignored).

### F — Typography

- F1. Inventory. Spec implementation must enumerate every `MaterialTheme.typography`
  consumer in `ui/chat/` that inherits the global family (normal-chat behavior) vs
  every intentional monospace override (code/log). Baseline list is in §2.3; the
  implementer must re-verify by grep and attach the final list to the PR.
- F2. Honest options. Ship only options proven visually distinct on supported devices,
  or label true aliases as aliases. Concretely: measure System Default vs Sans Serif
  vs Monospace on the API 34 ATD emulator (CI instrumented-tests) with a
  discriminating sample (e.g. `Il1O0` + mixed case + digits); if Default ≡ SansSerif
  glyph metrics, either drop/merge one entry or render its label as
  "Sans Serif (same as System on this device)". Do not ship three labels that render
  identically. Bundling small licensed fonts is allowed as an alternative, with
  license files committed.
- F3. Deterministic proof. Unit level: glyph-width assertions on a discriminating
  sample per option (fails if two non-alias options measure identical). UI level:
  screenshot/golden or `createTypography(family)` style assertion that each option
  maps to a distinct `FontFamily`/metrics bucket. Persistence: round-trip
  `chatFontFamily` key through `ServerStoreState` + restart-restore test
  (`fromKey` unknown-key → SYSTEM fallback covered).
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
| M1 | `app/src/main/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepository.kt:34-43,63`; desktop `vscode-marketplace.ts:searchMarketplaceThemes` | UI test: header/copy cites VS Code Gallery; no Hermes-backend URL in catalog path | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt`, strings |
| M2 | `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt:1-60`; desktop `vscode-marketplace.ts:5-10` ("never executed") | Fixture-vsix unit test incl. decoy JS ignored; dependency scan of import path | `app/src/main/java/com/m57/hermescontrol/data/theme/import/VsixThemeParser.kt`, `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeDefinitionConverter.kt` |
| M3 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:66-114`; `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceViewModel.kt:72-92,132-171` | UI tests: initial/debounce/pagination/loading/empty/error/retry with fake repo | Screen + ViewModel |
| M4 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt:154-158` (tap-to-apply today) | UI test: tap selects, preset unchanged; Apply button applies | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceScreen.kt` (+ detail) |
| M5 | `app/src/main/java/com/m57/hermescontrol/ui/thememarketplace/ThemeMarketplaceViewModel.kt:57`; `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:31-32`; `app/src/main/java/com/m57/hermescontrol/theme/Theme.kt:57-67` | UI test: badge follows `activeCustomThemeId`; fallback not labeled Marketplace | Screen + ViewModel |
| M6 | `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt:84-94`; `app/src/main/java/com/m57/hermescontrol/data/local/AuthManager.kt:244`; `app/src/main/java/com/m57/hermescontrol/data/config/ServerStoreState.kt:20-26` | Unit/UI test: corrupt tokens → unavailable-state + clear/re-apply | `app/src/main/java/com/m57/hermescontrol/data/theme/import/ThemeApplier.kt`, init path |
| M7 | `app/src/test/java/com/m57/hermescontrol/data/theme/marketplace/ThemeMarketplaceRepositoryTest.kt` (existing) | All above run offline; CI green | `app/src/test/.../marketplace/` |
| F1 | `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/MarkdownText.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/ToolBubble.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/MessageCards.kt`, `app/src/main/java/com/m57/hermescontrol/ui/chat/components/DiffViewCard.kt` | Final consumer/override list attached to PR | `ui/chat/**` (read-only) |
| F2 | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:15-25`; `app/src/test/java/com/m57/hermescontrol/theme/AppFontFamilyTest.kt` (keys only today) | Metric test on ATD-discriminating sample; alias labeling or removal | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt`, `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt` |
| F3 | `app/src/main/java/com/m57/hermescontrol/theme/Type.kt:46 createTypography`; `ServerStoreState.chatFontFamily` | Glyph-width + persistence round-trip tests | `theme/`, `data/config/` tests |
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
- Path check: every `app/src/...:line` cited above was resolved against this worktree;
  the desktop `apps/desktop/...` path was resolved against the sibling hermes-agent
  checkout (`/home/sam/projects/hermes-agent`).
- Markdown/link check: local script (no network, no Gradle, no ADB).
