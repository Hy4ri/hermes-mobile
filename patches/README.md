# Realtime 2.1 Android customization patch

The customization is isolated under `ui/voice/`, one resource file, three shared
composer hooks, one WebRTC dependency and one audio permission. Hermes Talk and
Hermes Agent program sources are not patched. The existing global Talk protocol
and official Codex OAuth preference are used instead of current-Bot binding.

## Export before upgrading Mobile

Run from the repository root after committing the feature:

```sh
./scripts/export-realtime-patches.sh \
  f4a105b6b74b305c98a9d0053467d25252e14f70 HEAD /tmp/realtime21-patches
```

This produces a **net Android patch**, not the obsolete intermediate backend
experiments. `BASES.txt` records the exact Mobile commits and Talk protocol source.
No Hermes Talk patch is generated or required.

Create a clean feature branch based on the newer Mobile upstream, then apply:

```sh
git apply --3way /tmp/realtime21-patches/hermes-mobile-realtime21.patch
```

`--3way` uses the original blob identities and stages cleanly applied changes.
Resolve actual conflicts against the new shared composer and lifecycle APIs.
Never ignore failed hunks, use `--reject`, or overwrite upgraded files with older
copies. A patch is not a guarantee of compatibility with arbitrary future code.

After an upgrade, run the focused `ui.voice` unit tests, build the debug APK and
repeat actual-device WebRTC/audio, barge-in, mute, end, reconnect and native Talk
work-continuity checks. Confirm Codex OAuth, the exact model and existing
Cloudflare Access settings. See [the integration contract](../docs/realtime-voice.md).
Do not restore withdrawn exact-Bot binding or custom mobile server routes.

## Existing Talk configuration

Talk protocol source:
`TheSmokeDev/hermes-talk@3b8f88bc9b39e7916523964debdce504af4d4990`.
Use the host's existing Codex login and native OpenAI `gpt-realtime-2.1` settings,
with official `TALK_PREFER_CODEX_OAUTH=true`. Long-lived credentials stay on the
server. Android refuses incompatible descriptors; it does not switch to API keys.
An upgrade of Talk requires checking its actual `/status`, `/session`, `/tool`,
`/runs` and DataChannel contracts, not applying a second backend implementation.

## Rollback

Revert the scoped Mobile feature commits or the applied Android patch on your
feature branch. Do not remove Talk task history, cancel accepted work, clear app
data or rotate credentials as part of rollback. No backend program rollback is
needed for this global-mode delivery.
