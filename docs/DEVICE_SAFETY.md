# Device Safety Runbook — Hermes Mobile Board

**Status:** Permanent policy for this board. All workers on the `hermes-mobile` kanban board MUST read and follow this before any device operation.

---

## 1. Never Wipe Data on Sam's Daily-Driver Devices

**Rule:** `adb uninstall` (or any data-destroying operation) is **forbidden** on Icarion (100.101.185.66:5555) or any of Sam's real daily-driver devices without an explicit human-approved step recorded in the task.

- If a signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) or version downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`) blocks install: **STOP and report the conflict**. Do not route around it by wiping app data.
- The only recovery from an accidental wipe is Sam manually re-entering credentials — the app-private data (server_store.json, EncryptedSharedPreferences tokens, Hermes gateway connection profile) is **not recoverable** from source control or any backup.
- **Protected daily-driver classification:** Icarion and any device identified as a daily-driver in a task tag are off-limits for experimental builds. Only merged, release-signaled builds may be deployed.

---

## 2. Unmerged/Experimental Branches → Emulator or Disposable Device First

- Builds from unmerged worktree branches, experimental commits, or any non-mainline code **must** be verified on an emulator or a disposable test profile/device **before** touching Icarion.
- **Emulator-first with separate `.dev` applicationId:** Use a distinct `.dev` applicationId on the emulator (e.g., `com.m57.hermescontrol.dev`) separate from the production Icarion package. Never deploy experimental builds to Icarion using the production applicationId.
- Only a reviewed, merged build (or an explicit "go ahead" from Sam in the task comments) may be deployed to Icarion.

---

## 3. "Done" Requires Visual Verification on an Unlocked Device

A device-deploy task is **not complete** on "process is running" or "APK installed."

**Required verification (in order):**
1. Unlock the device (if locked, **stop and ask Sam to unlock it** — never attempt lock-screen bypass gestures/keyevents on a live personal device).
2. Navigate to the target screen via UI automation (`adb shell uiautomator dump` to read hierarchy, locate elements programmatically — prefer over blind `input tap` coordinates).
3. Take a screenshot **after the phone is unlocked**: `adb exec-out screencap -p > verify.png`.
4. Confirm the intended UI state visually (or via `vision_analyze` on the screenshot).
5. Force-stop and relaunch the app, then re-screenshot to confirm persistence across restart.
6. **Also** dump the persisted preference directly: `adb shell run-as com.m57.hermescontrol cat shared_prefs/*.xml` (debuggable builds only) to verify the setting is actually stored.

**Critical lesson from the Icarion incident:** `vision_analyze` was rate-limited during the incident and the worker treated that as "skip verification" instead of blocking. **Rate limits on verification tools are not a waiver — they are a blocker.** If you cannot verify, the task blocks for Sam.

---

## 4. Snapshot App-Private Data Before Any Mutation

Before any operation that reads or writes app-private data (server_store.json, shared_prefs, the app database):

- **Minimum:** `adb shell run-as com.m57.hermescontrol cat <file> > /tmp/<file>.bak` pulled to the workstation.
- **Preferred:** `adb backup -f /tmp/hermes-backup.ab -noapk com.m57.hermescontrol` (scoped to the package) so a mistake is fully reversible.

---

## 5. Encrypted Profile Export Feature

Before exporting or transferring any app profile data:

- Use the encrypted export API: `adb shell run-as com.m57.hermescontrol export-encrypted-profile --out /tmp/export.enc`
- The exported profile is GPG-encrypted with the worker's registered key
- Never transfer unencrypted profile data over any network
- Decryption requires the worker's authenticated session on Sam's dashboard

---

## 6. Screenshot Confirmation

- Every device-deploy task must include screenshot evidence of the final UI state
- Screenshots must be taken after device unlock and after any app restart/force-stop
- Screenshots are stored as task artifacts and reviewed as part of completion verification
- **Rate limits on screenshot capture are not a waiver — if you cannot capture, the task blocks for Sam**

---

## 7. Preflight Checks

Before deploying any build to a device:

- [ ] Build compiles cleanly: `./gradlew assembleDebug` passes
- [ ] ktlintCheck passes
- [ ] Unit tests green: `./gradlew testDebugUnitTest`
- [ ] Emulator verification completed per §2 (unmerged branches → emulator first)
- [ ] Signed build artifact identified (debug for development, release for production)
- [ ] Daily-driver device is NOT used for unmerged/ experimental builds unless explicitly approved
- [ ] Task comments document any deviations or approvals from Sam

---

## 8. Sam's Hermes Dashboard for Mobile Access

**URL:** `https://sleeper.chronicle-procyon.ts.net` (Tailscale)
**Auth:** Basic auth
**Username:** `silo`

**Do not** default to inventing a `127.0.0.1:9119` loopback config — that can never work from a phone over Tailscale. Future workers must use the above endpoint.

---

## 9. ADB Connection Details (Reference)

```bash
adb connect 100.101.185.66:5555
adb devices -l   # should show: model:SM_S938U device:pa3q
```

---

## 10. Build Toolchain (Sleeper)

- JDK 17 + Android SDK under `~/.local/share/android-build-tools/`
- Build command: `./gradlew assembleDebug` (or `assembleRelease` for signed builds)
- Lint: `./gradlew ktlintCheck`
- Tests: `./gradlew testDebugUnitTest`

---

## 11. Escalation Path

If any task instruction conflicts with this runbook, **stop and escalate to Sam via a task comment**. Do not improvise workarounds on live devices.

## 12. Persistence in AGENTS.md / Completion Contract

- Safety runbook updates and device-classification decisions persist in the board-level `AGENTS.md` and task completion contract
- Completed tasks must reference the DEVICE_SAFETY.md version tag in their post-comment
- Child task plans (`TASK_PLANS.md`) must be attached as comments or linked artifacts on completion
- The kanban completion metadata must include a summary of safety-verified changes
- Sam reviews the AGENTS.md/completion contract before any task is closed involving Icarion or daily-driver devices

---

*Created as part of postmortem task t_f92b7a2d following the Icarion device-wipe incident (t_c8c3250f).*