# Cassy Control 1.0.4 — live-session release acceptance

## Product requirements

- Android and Desktop share one durable Hermes session identity while each transport keeps its own short-lived gateway binding.
- Session creation, titles, completion state, tool activity and transcript updates propagate across connected clients, with polling only as a fallback.
- Reopening or switching a chat restores user, assistant, reasoning and named tool entries, including legacy string and structured content formats.
- The top-bar YOLO control applies globally, reports its live state and presents a dismissible safety warning before activation.
- The composer exposes a swipeable, searchable commands-and-skills sheet.
- A prompt submitted during a running task is held per session, can be edited or removed and is sent automatically after completion.
- Running work can be interrupted from the composer without removing voice input from the focused text-entry state.
- Recoverable stale-session and history-refresh failures do not expose raw JSON-RPC or decoder errors to the user.

## Reliability requirements

- A second client may attach to a live gateway session without taking event delivery away from the first client.
- Disconnecting one client keeps the live session attached to every remaining client.
- Concurrent Android startup and network callbacks create exactly one WebSocket; superseded socket callbacks cannot overwrite current connection state.
- History reconciliation is de-duplicated and rate-limited to avoid unnecessary phone, network and NAS load.
- The NAS image remains pinned and reproducible with the multi-client patch applied during the image build.

## Verified release gates

1. Kotlin formatting passes for every changed Kotlin source.
2. All 416 debug unit tests pass, including durable/gateway identity, structured history and concurrent-connect regressions.
3. Debug and release Android lint pass.
4. Debug and minified production release APKs assemble successfully.
5. Release badging reports `1.0.4-cassy` / `104`, package `com.akaro.cassycontrol`, min SDK 26 and target SDK 36.
6. APK Signature Scheme v2 verification succeeds with one signer, and the APK upgrades the installed production-signed app without clearing data.
7. On the Android 16 Xiaomi target, the formerly failing session reloads with reasoning and named tool cards and without the JSON decoder error.
8. Device logs show one WebSocket open on cold start and no fatal exception or history-refresh failure.
9. NAS image `cassy-agent-local:v2026.7.7.2-resilience3` is healthy; its runtime multicast/disconnect smoke test and model-harness audit pass.

## Artifact

- Path: `app/build/outputs/apk/release/app-release.apk`
- SHA-256: `6b6a3d97d08eaca965cdfe0fb6942bc1c92043da80fe71ed3ef289e41df1e3a1`
