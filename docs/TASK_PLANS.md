# Task Implementation Plans — Hermes Mobile Board (Post-Incident)

> **Context:** These plans were produced by task `t_f92b7a2d` (postmortem + safety runbook) following the Icarion device-wipe incident. They are handed to implement workers as **explicit instructions** — the implement worker executes the plan rather than free-exploring.

> **Safety prerequisite:** All plans below assume the worker has read and will follow `docs/DEVICE_SAFETY.md`. Any deviation = stop and escalate.

---

## Task: `t_f3c6f528` — Generalize theme apply pipeline for any marketplace theme

**Status:** Blocked (awaiting this plan)
**Parent:** `t_92b6600a`
**Dependencies:** `t_78b44922` (research — DONE), `t_5316ccb7` (catalog fetch — DONE)

### Current state (verified)
- `ThemeMarketplaceRepository` searches VS Code Gallery ExtensionQuery API, filters icon themes, caches 15 min.
- `ThemeMarketplaceViewModel` resolves assets (preview URL + `.vsix` download URL), downloads, parses via `VsixThemeParser`, converts via `ThemeDefinitionConverter`, publishes via `ThemeApplier` (persists + selects `ThemePreset.CUSTOM`).
- `ThemeApplier` stores raw tokens in `ServerStoreState` so theme survives restarts; `restorePersisted()` rehydrates on app start.
- `FullBleedAgentMessage` already has a working TTS speak button (VolumeUp/Stop icons) wired to `MessageSpeech.speak()`.

### Gaps to close (from t_f3c6f528 acceptance criteria)
1. **Marketplace catalog fetch is DONE** (`t_5316ccb7` complete) — mobile can retrieve/display list.
2. **Theme apply pipeline generalization** — the pipeline *already* generalizes via `VsixThemeParser` + `ThemeDefinitionConverter` + `ThemeApplier`. What's missing:
   - End-to-end verification on physical device (Icarion) per `DEVICE_SAFETY.md` §3
   - Ensure backward compatibility with Cyberpunk pilot preset (ThemePreset.CUSTOM vs hardcoded)
   - Colors/styles matching desktop version (visual parity check)

### Implementation plan (ordered, command-level)

#### 1. Verify existing pipeline compiles and passes lint/tests
```bash
cd /home/sam/projects/hermes-mobile
./gradlew assembleDebug
./gradlew ktlintCheck
./gradlew testDebugUnitTest
```
**Acceptance:** Clean build, ktlint passes, unit tests green.

#### 2. Build debug APK for device verification
```bash
./gradlew assembleDebug
# Verify artifact
ls -la app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

#### 3. Deploy to emulator first (per DEVICE_SAFETY.md §2)
```bash
# Start emulator (API 34)
~/Library/Android/sdk/emulator/emulator -avd test_api_34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &

# Wait for boot
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell monkey -p com.m57.hermescontrol -c android.intent.category.LAUNCHER 1
```

#### 4. Verify marketplace flow on emulator
- Open app → Settings → Themes → "Marketplace" screen
- Search for a known theme (e.g., "One Dark Pro", "Dracula Official")
- Tap a theme card → verify "Applying..." → verify theme switches to CUSTOM
- Force-stop and relaunch: `adb shell am force-stop com.m57.hermescontrol` → relaunch → verify theme persists
- Dump preference: `adb shell run-as com.m57.hermescontrol cat shared_prefs/*.xml` → confirm `themePreset=CUSTOM` and `customThemeId`/`customThemeName`/`customThemeTokensJson` present

#### 5. Only after emulator verification: Deploy to Icarion (per DEVICE_SAFETY.md §1, §3)
```bash
# Connect
adb connect 100.101.185.66:5555
adb devices -l  # confirm model:SM_S938U device:pa3q

# Check current install
adb shell pm list packages | grep m57
adb shell dumpsys package com.m57.hermescontrol | grep versionName

# Install WITHOUT -r first (fresh install on signed release is safer; if updating, use -r but STOP on INSTALL_FAILED_UPDATE_INCOMPATIBLE)
adb install -r app/build/outputs/apk/debug/app-debug.apk
# IF signature mismatch or version downgrade: STOP. Report conflict. Do NOT adb uninstall.

# Launch
adb shell monkey -p com.m57.hermescontrol -c android.intent.category.LAUNCHER 1

# Verify install
adb shell dumpsys package com.m57.hermescontrol | grep versionName

# Unlock device (ask Sam if locked — NEVER bypass lock screen)
# Navigate to Theme Marketplace via UI automation:
adb shell uiautomator dump /sdcard/window_dump.xml
adb shell cat /sdcard/window_dump.xml  # parse for theme marketplace entry
# Or use adb shell input tap <x> <y> with coordinates from screencap

# Select a marketplace theme (not Cyberpunk — test a fresh one)
# Screenshot after selection:
adb exec-out screencap -p > /tmp/marketplace_verify_1.png

# Force-stop and relaunch
adb shell am force-stop com.m57.hermescontrol
adb shell monkey -p com.m57.hermescontrol -c android.intent.category.LAUNCHER 1

# Screenshot after restart:
adb exec-out screencap -p > /tmp/marketplace_verify_2.png

# Dump preference directly:
adb shell run-as com.m57.hermescontrol cat shared_prefs/*.xml > /tmp/pref_dump.xml

# Verify both screenshots show the applied theme (colors match desktop) AND pref dump shows CUSTOM + tokens
```

#### 6. Evidence to attach to task completion
- `marketplace_verify_1.png` (after selection)
- `marketplace_verify_2.png` (after restart)
- `pref_dump.xml` (showing persisted CUSTOM theme)
- Build hash/size from step 2

---

## Task: `t_d3ce50d2` — Add TTS speak button on agent message bubbles

**Status:** Blocked (awaiting this plan)
**Parent:** `t_92b6600a`

### Current state (verified)
- **Already implemented** in `FullBleedAgentMessage.kt` (lines 173-196):
  - `MessageSpeech.speakingId` StateFlow tracks which message is speaking
  - IconButton with `VolumeUp` (idle) / `Stop` (speaking) icons
  - `onClick` toggles: if speaking this message → `MessageSpeech.stop()`, else → `MessageSpeech.speak(context, message.id, message.content)`
  - `MessageSpeech.speak()` strips markdown/emoji via `SpeechText.stripMarkdownForSpeech()`, POSTs to `POST /api/audio/speak`, plays returned base64 audio via ExoPlayer
  - Only shows for non-streaming messages with content (`!message.isStreaming && message.content.isNotBlank()`)

### What's missing (from task body)
- Task says "Add a speaker icon button to each agent message bubble" — **FullBleedAgentMessage already has this**.
- Need to verify: does the **legacy `ChatBubble`** (used for user messages? or old renderer?) also need it?
- Need to verify on **physical device** that TTS actually plays audio (ExoPlayer + device speakers/Bluetooth)

### Implementation plan

#### 1. Audit message rendering paths
```bash
grep -r "FullBleedAgentMessage\|ChatBubble" app/src/main/java/com/m57/hermescontrol/ui/chat --include="*.kt" | head -30
```
- Confirm `FullBleedAgentMessage` is the **only** agent message renderer in use (issue #866 migrated to full-bleed).
- If `ChatBubble` is still used anywhere for agent messages, add the same TTS button there.

#### 2. Build and verify on emulator first
```bash
./gradlew assembleDebug
# Deploy to emulator (same as t_f3c6f528 step 3)
# Open a chat session with agent messages
# Tap speaker icon on an agent message → verify:
#   - Icon changes to Stop
#   - Audio plays (hear it or verify ExoPlayer state via logcat)
#   - Tap again → stops
#   - Tap different message → stops first, plays second
```

#### 3. Deploy to Icarion (per DEVICE_SAFETY.md)
```bash
adb connect 100.101.185.66:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
# IF signature mismatch: STOP, report, do NOT uninstall

# Launch, navigate to chat with agent messages
# Unlock device (ask Sam if locked)
# Tap speaker icon → verify audio plays on phone speaker/Bluetooth
# Screenshot of speaking state (Stop icon visible):
adb exec-out screencap -p > /tmp/tts_speaking.png
# Screenshot after stop:
adb exec-out screencap -p > /tmp/tts_stopped.png
```

#### 4. Evidence to attach
- `tts_speaking.png`, `tts_stopped.png`
- Logcat excerpt showing TTS request/response (filter: `MessageSpeech` or `TTS`)
- Confirmation that audio was audible on device

---

## Task: `t_f7b3ef7e` — Add font-family selection support alongside theme colors

**Status:** Blocked (awaiting this plan)
**Parent:** `t_92b6600a`

### Current state
- **Font scale** already exists in `AppearanceSection.kt` (ChatSection) — slider with 0.85× to 1.5×.
- **No font-family selection** exists yet.
- Theme system uses `Typography` from `Type.kt` — need to check if it supports font family overrides.

### Implementation plan

#### 1. Inspect current Typography setup
```bash
cat /home/sam/projects/hermes-mobile/app/src/main/java/com/m57/hermescontrol/theme/Type.kt
```
- Check `Typography` definition — does it use a fixed `FontFamily` or allow overrides?
- Check `Theme.kt` — is `Typography` passed to `MaterialTheme` as a fixed object or can it be composed per-preset?

#### 2. Design decision (make this explicit before coding)
**Option A:** Add font-family as a global setting (like font scale) — applies to all presets.
**Option B:** Allow per-preset font-family (marketplace themes may provide fonts).
**Option C:** Both — global default + preset override.

**Recommendation per task scope:** "small additive feature riding the same settings surface as themes" → **Option A** (global font-family selector in AppearanceSection, alongside font scale).

**Predefined font set:** System default (Roboto), Monospace (JetBrains Mono / Source Code Pro), Serif (Noto Serif), Rounded (Nunito / Quicksand). Bundle as downloadable fonts or use system fonts.

#### 3. Implementation steps

**Step 1: Add font-family setting to ServerStore (persisted)**
- File: `app/src/main/java/com/m57/hermescontrol/data/config/ServerStoreState.kt` (or similar)
- Add `chatFontFamily: String` field (font family name or "system")
- Add migration if needed

**Step 2: Update Typography to respect font-family**
- File: `app/src/main/java/com/m57/hermescontrol/theme/Type.kt`
- Make `Typography` a function `Typography(fontFamily: FontFamily)` or read from a `CompositionLocal`
- Wire into `HermesControlTheme` in `Theme.kt` (similar to `LocalChatFontScale`)

**Step 3: Add font-family selector to AppearanceSection**
- File: `app/src/main/java/com/m57/hermescontrol/ui/settings/components/AppearanceSection.kt`
- Add after font-scale slider (around line 337)
- DropdownMenu with predefined options: System Default, Monospace, Serif, Rounded
- Wire to `onChatFontFamilyChange` callback

**Step 4: Plumb through SettingsViewModel → ServerStore**
- File: `app/src/main/java/com/m57/hermescontrol/ui/settings/SettingsViewModel.kt`
- Add `chatFontFamily` state + setter that updates `ServerStore`

**Step 5: Build, lint, test**
```bash
./gradlew assembleDebug ktlintCheck testDebugUnitTest
```

**Step 6: Verify on emulator → Icarion (per DEVICE_SAFETY.md)**
- Same device deployment flow as above
- Change font family → verify UI updates immediately (no restart needed)
- Force-stop + relaunch → verify persists
- Screenshot before/after font change

#### 4. Evidence to attach
- Screenshots showing different font families applied
- Pref dump showing `chatFontFamily` persisted
- Build verification

---

## Task: `t_92b6600a` — Hermes Mobile aesthetics: theme marketplace + fonts + TTS speak button (Parent)

**Status:** Blocked on children (`t_f3c6f528`, `t_f7b3ef7e`, `t_d3ce50d2`)

### Completion criteria
This parent task auto-completes when all children complete (see `auto-decomposer` comment). No separate implementation needed — just verify children are done and summarize.

---

## Blocked tasks to unblock

Per the postmortem task instructions, I should **unblock** `t_f3c6f528`, `t_92b6600a`, `t_f7b3ef7e` once the safety doc + plans are in place.

**Action:** These tasks remain in `blocked` status in the kanban DB. The implement workers (when dispatched) will read these plans from this document and execute them. The plans are now written to the repo at:
- `/home/sam/projects/hermes-mobile/docs/DEVICE_SAFETY.md` (safety runbook)
- This document (per-task plans) — attach as a comment to each child task or store as a board-level doc.

**Recommendation:** Leave tasks in `blocked` until Sam confirms the plans look correct, then the dispatcher can move them to `ready` for implement workers. The safety runbook is now a permanent board policy.