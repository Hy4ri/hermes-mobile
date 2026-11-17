# Native shared Hermes Talk voice

## Scope and source mapping

This integration uses **global shared Hermes Talk**, as explicitly requested after
initial device testing. It does not bind voice to the current Bot or chat session.
Voice transcripts stay in the call. Do not claim that global Talk work belongs to
the Bot whose composer opened it.

Mobile base: `f4a105b6b74b305c98a9d0053467d25252e14f70`.
Talk protocol source: `3b8f88bc9b39e7916523964debdce504af4d4990`.

| Concern | Existing owner | Android integration |
| --- | --- | --- |
| Shared entry | `ChatScreen` → `ChatInputBar` → `ComposerToolbar` | Separate Realtime action; chat stays mounted |
| Authentication | Mobile `DataScope`, existing headers/cookies/bearer | Frozen connection credentials; close on identity changes |
| Model/auth | Talk `talk_auth.py`, `talk_wire.py` | Native `gpt-realtime-2.1`, exact `codex-oauth`; no API-key fallback |
| Session | Talk `GET /status`, `POST /session` | Empty session body; ephemeral credential only |
| Audio | Existing dashboard `TalkTransport` protocol | Native PeerConnection, microphone, speaker and `oai-events` |
| Tools | Talk `POST /tool` | Only `name` and `arguments`; ordered outputs and response barrier |
| Work status/results | Talk `GET /runs` | Server `runId`, `kind`, `status`, `output`; no client task registry |
| Completion announcement | Existing dashboard global run watcher | Watch only this call's `WORK_STARTED` receipts, announce once while idle |

Sources:
- https://github.com/TheSmokeDev/hermes-talk/tree/3b8f88bc9b39e7916523964debdce504af4d4990
- https://github.com/Hy4ri/hermes-mobile/tree/f4a105b6b74b305c98a9d0053467d25252e14f70

## No backend program patch

Use the existing Talk installation and Codex login. Its official configuration is
OpenAI provider, native voice mode, model `gpt-realtime-2.1`, and
`TALK_PREFER_CODEX_OAUTH=true`. Keep long-lived OAuth credentials on the server.
The custom mobile mint routes have been removed; no Hermes Agent core changes,
canonical Bot binding, new OAuth manager or task executor are required.

Both preflight and the session descriptor must declare Codex OAuth and the exact
native model. Missing subscription support is an error, not a reason to use an API
key, GPT-Live, WebView or STT→LLM→TTS. Entitlement still needs a real call test.

SDP is sent only to `https://api.openai.com/v1/realtime/calls` using an isolated
external client and the ephemeral credential. Dashboard tokens, cookies and
Cloudflare Access headers are not sent to OpenAI. Redirects and automatic retries
are disabled. Server errors are not copied verbatim into logs or UI.

## Lifecycle and work

Calls are foreground-only. Mute changes the local microphone. Barge-in and Cancel
voice response interrupt provider output only. End voice releases local media;
it never calls a task cancellation endpoint. Stop Work is a distinct confirmed
operation using Talk's existing `stop_work` tool after refreshing server runs.
Spoken approvals use the existing `resolve_approval` tool; no second approval
registry is introduced.

Reconnect is explicit, creates a fresh voice session and never replays old tool
calls. Previously accepted work remains in Talk. Its raw server status/results
are visible through `/runs`; historical runs are not automatically announced as
new completions. `accepted`, `completed` and `verified` are not interchangeable.

The earlier exact-Bot experiment refused a host without canonical task context.
That requirement and its backend patch were explicitly withdrawn. See the
[acceptance record](testing/realtime-voice-acceptance.md) for measured results and
remaining live-device checks. See [the patch guide](../patches/README.md) for
reapplying only the Android customization after upstream upgrades.
