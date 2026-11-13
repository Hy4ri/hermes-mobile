# Shared Realtime 2.1 acceptance record

**Global-mode source builds and 21 focused unit tests pass. Real-device voice
acceptance is not complete. A built APK is not proof of a working call.**

## Explicit scope change

After the initial bound-mode test, the user explicitly requested shared global
Talk without current-Bot/session binding and without a backend program patch.
The custom Talk API source was restored to its original deployed bytes. Only the
existing official `TALK_PREFER_CODEX_OAUTH=true` configuration remains. Dashboard
alone was restarted and returned HTTP 200; no container, database, task runtime
or OAuth program was replaced. Private deployment details remain local.

The earlier missing `dashboard_task_context` error belongs to the withdrawn
exact-Bot design. It is not a prerequisite for the existing unbound `/session {}`
lane, and it is not a current global-mode WebRTC test result. The Android client
now uses only `/status`, `/session`, `/tool`, `/runs`; the upgrade artifact contains
only Mobile customization.

## Observed Android results

- vivo X Fold3 Pro / V2337A, Android API 36.
- Original `com.m57.hermescontrol` 3.2.2 / 30202 was not overwritten or cleared.
- The separately signed `com.m57.hermescontrol.dev` was previously installed and
  authenticated by the user through normal app settings. Credentials were not
  exported from the original app.
- The earlier native dialog displayed the missing host capability after the
  safe setup-error classification fix. No audio session was established then.
- The new global-mode debug APK and instrumentation APK build successfully.
- The global-mode APK was installed. The installer lost its USB reply, so success
  was verified independently: installed `base.apk` and the delivery APK both have
  SHA-256 `67cafc22d0bfab050fab300f39c799d9f6a45da05b0081fe342492d8f630cfa7`.
  The app was opened and its shared `Hermes Talk` composer action was observed.
  The user subsequently confirmed installation and tested the app.

## Automated evidence

| Check | Observed result |
| --- | --- |
| `TalkProtocolTest` | 6 tests, 0 failures, 0 errors |
| `TalkRealtimeAdapterTest` | 15 tests, 0 failures, 0 errors |
| Debug APK + instrumentation APK build | Gradle BUILD SUCCESSFUL, 35 seconds |
| Changed Kotlin formatting | ktlint 1.8.0 formatter completed successfully |
| Physical global Compose tests | Not run yet |

Adapter tests exercise tool batching/output ordering, deduplication, uncertain
POST outcomes, barge-in, server-owned run completion, reconnect history and local
close. They do not prove provider entitlement, audible speech or native task
execution. The UI tests are isolated UI scenarios: no saved credentials, network
calls, microphone capture or permission grants are needed.

Earlier bound-protocol/backend tests and an interrupted physical instrumentation
run are historical evidence only. The previous UI run timed out without results;
it was not a pass. The temporary authenticated status probe was removed afterward.

## Current tool-capability gap

The global Talk schema advertises `delegate_task`, `check_work`, `list_agents`,
`talk_status`, `talk_capabilities` and related native tools. It has no dedicated
Kanban query tool. Its `delegate_task` parameters contain no Bot/target selector;
starting a background agent is not proof of assigning a named existing Bot.
Indirect Kanban access depends on the actual agent's enabled tools.

A temporary read-only device probe was prepared to call `/status`, `talk_status`
and `talk_capabilities`, without minting a voice credential or dispatching work.
Android rejected both diagnostic APK installation attempts with
`INSTALL_FAILED_ABORTED: User rejected permissions`, including the retry after
the user approved the diagnostic. Phone-side installation approval remains
required; the probe did not run. Its temporary source was removed.
This platform approval must not be bypassed. Runtime tool availability and
provider-to-tool execution therefore remain unverified.

## Live acceptance still required

| Scenario | Current evidence / remaining gate |
| --- | --- |
| Shared entry from Default/Bot composers | Latest build installed; shared action observed on current Chat; not every Bot tested |
| Native WebRTC SDP, ICE, DataChannel and bidirectional audio | Not verified on the real provider |
| Continuous conversation and transcripts | Adapter fixtures only; live voice not verified |
| Audible barge-in | Provider-event fixtures only |
| Mute, end and native audio release | Local-only code paths and fixtures; live-device behavior pending |
| Native delegation and server receipts | No live global work dispatched |
| Work continues after voice ends | No real accepted background job verified |
| Cloudflare Access | Previous authenticated Android REST probe passed; new global routes not yet exercised |
| Codex Subscription | Previous live status reported configured codex-oauth; mint/entitlement/audio pending |
| Network loss/reconnect without duplicate work | Deterministic adapter coverage only; live recovery pending |
| Bot A/B ownership isolation | Explicitly withdrawn; global Talk is not current-Bot-bound |

Do not mark first-version delivery complete until actual voice, work continuity,
Access/OAuth and resource-release checks pass. No API-key fallback or invented
host capability is an acceptable substitute.
