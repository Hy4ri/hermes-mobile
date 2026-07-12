# Cassy Control 1.0.3 — release acceptance

## Product requirements

- Chat is the primary compact-phone surface; administrative screens live in a dedicated tools hub.
- The session drawer supports new chat, search, previews, current-session state, pin/unpin and direct resume.
- A cold launch resumes the persisted active session when it still exists and never creates a duplicate before the server session list is known.
- The onboarding path uses Cassy language, explains the private NAS connection and automatically probes the known server.
- Screenshot protection remains opt-in; credentials and session cookies remain encrypted and are not logged.
- Voice capture uses the Cassy NAS transcription endpoint by default, shows capture/processing state, is capped at 120 seconds, deletes temporary audio and offers Android recognition as a fallback.
- All retained Hermes administration, approval, attachment, notification and streaming capabilities remain reachable.

## Release gates

1. `ktlintCheck`, debug unit tests, debug/release Android lint and debug/release Kotlin compilation pass.
2. Debug and minified release APKs assemble successfully.
3. Release badging reports `1.0.3-cassy` / `103` and the production application ID.
4. The release APK verifies with the existing 4096-bit Cassy release signer and upgrades `1.0.2-cassy` without clearing app data.
5. On the Android 16 Xiaomi target: launch, app lock, auto-login, NAS REST/WebSocket connectivity, drawer search, existing-session resume, new chat, message response, attachment menu, voice capture/transcription, force-stop/restart and screenshot behavior are checked.
6. NAS conversation routing remains healthy before and after mobile WebSocket disconnect/reconnect.
7. No secret-bearing file, credential, audio payload or token is added to Git or release reports.

## Delivery

- Release artifact: `Cassy-Control-v1.0.3.apk`
- Signature: APK Signature Scheme v2, RSA 4096; signer certificate matches `1.0.2-cassy`.
- Package: `com.akaro.cassycontrol`; version `1.0.3-cassy` (`103`).
- Delivery occurs only after every gate above is evidenced.
