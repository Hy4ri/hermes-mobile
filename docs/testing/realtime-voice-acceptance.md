# Realtime 2.1 acceptance record

This record is not a claim that physical acceptance has passed.

## Observed environment

- Mobile source base: `f4a105b6b74b305c98a9d0053467d25252e14f70`.
- Talk source base: `3b8f88bc9b39e7916523964debdce504af4d4990`.
- No physical Android device was attached (`adb devices -l` returned no devices).
- No target Hermes deployment or test subscription was supplied or accessed.
- API 37 platform was installed by Gradle without changing project SDK requirements.
- Disposable API 36 emulator failed with exit 139 after QEMU thread hangs.
- API 30/software-renderer fallback also failed with exit 139. Android Studio
  preview renderer was unavailable (`No running Studio instances found`).

## Automated evidence

Talk compatibility patch:

```sh
python -m pytest tests/test_dashboard_api.py tests/test_target_switching.py
# 69 passed in 0.91s
```

Run in a disposable virtual environment with an empty HOME and cleared environment;
no real OAuth store or production gateway was used. Tests include existing target
ownership/continuity cases plus mobile auth guards and configuration-change races.
The patch's `git apply --check` passed against pristine files from its pinned base.
`sh -n scripts/export-realtime-patches.sh` passed.

Android: `:app:testDebugUnitTest --tests 'com.m57.hermescontrol.ui.voice.*'`,
`:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed. Focused unit
results: 12 adapter tests and 6 identity/billing tests, zero failures or skips.
Instrumentation APK compilation is not a passing device run: the emulator did
not boot, so the two added UI tests remain unexecuted. Fixture tests prove
protocol behavior only, not microphone/provider entitlement.

## Required deployment matrix

| Scenario | Evidence / remaining gate |
| --- | --- |
| 1. Default Chat starts Realtime | Exact-target contract fixture; real launch not verified |
| 2. Bot A starts Realtime | Exact-target contract fixture; real launch not verified |
| 3. Bot B starts Realtime | Exact-target contract fixture; real launch not verified |
| 4. Bot A voice delegation receipt belongs to A | Adapter + server ownership fixtures; real receipt not verified |
| 5. Accepted A work continues after voice ends | Existing Talk ownership/close contract; real background job not verified |
| 6. Barge-in interrupts old output | Native provider cancel/clear and adapter fixtures; audible interruption not verified |
| 7. Mute does not cancel Agent work | Separate microphone control and canonical action paths; device behavior not verified |
| 8. Cloudflare Access | Captured existing server headers/cookies, isolated provider HTTP; deployed Access not verified |
| 9. Codex Subscription without key fallback | Server guard tests passed; real entitlement not verified |
| 10. Disconnect/reconnect does not duplicate work | No POST retry/replayed provider calls; canonical idempotency fixtures; real network change not verified |
| 11. Switching Bot does not contaminate Session | Exact scope/session validation and dismissal; physical two-Bot test not verified |
| 12. Microphone/speaker/resources released | Native close/focus restoration implemented; physical audio/resource observation not verified |

## Physical test procedure

Use a dedicated test connection with the patched Talk host and an existing
server-side Codex login. Keep Cloudflare credentials in the existing encrypted
connection-header settings, not logs or this record. Do not deploy the patch to
production without an explicit target/action approval.

Record installed Mobile/Talk/Hermes versions. In Default Chat, then Bot A and Bot B,
start voice from each actual shared composer and inspect the displayed owner.
Speak a unique harmless request in A; record the canonical action/run/child-session
receipt from Talk and verify B has no such receipt. End voice before A completes,
then rejoin A and read its original result. Do not infer completion from speech.

While listening/speaking, test mute, barge-in, foreground exit, back, microphone
permission denial, Wi-Fi interruption and explicit reconnect. Check that old tools
are not replayed and that reconnect remains on the exact original owner. Inspect
current approvals and explicitly deny a disposable request. Stop Work only in an
explicit disposable job; ending voice must not send that action.

Reject missing/ambiguous targets, unavailable task context, API-key auth source,
wrong model, expired OAuth/session, 401 and 403. Check AudioManager mode/focus and
microphone privacy indicator after hangup. Capture screenshots and opaque canonical
IDs without recording secrets, raw audio or private task text.

Known first-version limits: foreground calls only; no background/lock-screen capture
or advanced Bluetooth controls. Native speech presentation delivery remains unknown
rather than falsely marked played/verified. Future upgrade patches can conflict and
must pass the same contract and physical matrix before deployment.
