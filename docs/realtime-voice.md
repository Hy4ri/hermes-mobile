# Native Realtime voice integration

## Pinned source mapping

Mobile base: `f4a105b6b74b305c98a9d0053467d25252e14f70`.
Talk base: `3b8f88bc9b39e7916523964debdce504af4d4990`.
The supplied starter is a preflight sketch, not a working voice engine.

| Concern | Existing owning source | Android adapter |
| --- | --- | --- |
| Shared entry | Mobile `ChatScreen` → `ChatInputBar` → `ComposerToolbar` | Separate GraphicEq action; modal keeps chat mounted |
| Identity | Mobile `DataScope`, composer session | Immutable connection/auth generation/profile/session capture |
| Authorization | Talk `talk_target_catalog.py`, `talk_target_selection.py` | Exact local profile/session match; server-issued target ID only |
| Model/auth | Talk `talk_auth.py:76-95,322-343`, `talk_wire.py` | Native `gpt-realtime-2.1`, exact `codex-oauth`, ephemeral secret only |
| Audio | Talk dashboard `TalkTransport` | Native WebRTC PeerConnection, microphone, speaker, oai-events |
| Dialogue linkage | Talk dashboard `TaskContinuity` | Kotlin client adapter for staged input, response metadata and call IDs |
| Work/approvals/results | Talk `talk_dashboard_tasks.py` | Existing authenticated tool/event/state/text/input/result routes |
| Billing safety | Compatibility patch `/mobile/status`, `/mobile/session` | Require protocol 1; reject missing patch; server checks before mint |

GPT-Live (`voiceMode=live`) uses different `gpt-live-*` models. Its `/live/session`
route is **not** used. No STT→LLM→TTS cascade, worker runtime, OAuth store, tool
executor or task dispatcher is added to Android.

Primary sources:
- https://github.com/TheSmokeDev/hermes-talk/tree/3b8f88bc9b39e7916523964debdce504af4d4990
- https://github.com/Hy4ri/hermes-mobile/tree/f4a105b6b74b305c98a9d0053467d25252e14f70

## Deployment requirements

Configure the existing Talk host (not Android): `TALK_PROVIDER=openai`,
`TALK_VOICE_MODE=native`, `TALK_MODEL=gpt-realtime-2.1`,
`TALK_PREFER_CODEX_OAUTH=true`. Keep existing Codex login on the server.
Provider entitlement remains a live deployment check, not a code guarantee.
The Hermes host must support canonical dashboard task context, passive history
and durable linked-child dispatch, as required by Talk's task-continuity document.
Do not substitute a recent session or an unbound agent when those capabilities
are unavailable. The existing connection's custom headers/cookies/bearer are reused;
Talk's own dashboard auth gate must also accept that configured identity.

The compatibility patch adds only a subscription-only mint guard. Existing target,
work, approval, history, idempotency and result ownership stay in Talk/Hermes.
Android requires that patch and refuses an older server rather than risking a
key-billed mint after a status/session configuration race.

Calls are foreground-only. Leaving the foreground releases local audio and closes
the voice binding; accepted work is not cancelled. Reconnect reauthorizes the exact
captured chat with a fresh voice generation and never replays old function calls.
Mute disables only the microphone. Cancel voice interrupts provider output only.
Stop Work is a separate explicit canonical server action.
