# Realtime 2.1 custom patch series

The Mobile integration is isolated under `ui/voice/`, one realtime resource file,
three additive shared-composer hooks, one WebRTC dependency and one audio permission.
It does not fork Hermes Agent or install another execution runtime.

## Hermes Talk

Base: `TheSmokeDev/hermes-talk@3b8f88bc9b39e7916523964debdce504af4d4990`.
Apply the included compatibility patch to your Talk checkout, not Hermes Agent:

```sh
cd /path/to/hermes-talk
git apply --check /path/to/hermes-mobile/patches/hermes-talk/0001-mobile-subscription-guard.patch
git apply /path/to/hermes-mobile/patches/hermes-talk/0001-mobile-subscription-guard.patch
python -m pytest tests/test_dashboard_api.py tests/test_target_switching.py
```

The patch adds authenticated protocol-1 `/mobile/status` and `/mobile/session`.
It requires native `gpt-realtime-2.1`, `TALK_PREFER_CODEX_OAUTH=true` and resolved
`codex-oauth` **before** minting. It reuses existing target preparation, canonical
session attachment, tools and activation. A configuration change cannot select a
key-billed lane or different model after preflight.

Install/restart Talk through your existing deployment process; this repository
never deploys it automatically. Do not paste tokens into commands or issue text.

## Hermes Mobile

Export the feature's commits before upgrading (base for this delivery below):

```sh
./scripts/export-realtime-patches.sh \
  f4a105b6b74b305c98a9d0053467d25252e14f70 HEAD /tmp/realtime21-patches
```

On a clean branch based on the newer Mobile upstream:

```sh
git am --3way /tmp/realtime21-patches/hermes-mobile-realtime21.patch
```

For Talk upgrades, use `git apply --3way` on its patch in a clean Talk branch.
A conflict is a compatibility gate, not permission to overwrite the new upstream.
Reconcile the shared-composer hooks and protocol against the new source; rerun the
focused tests, build, and physical two-Bot voice acceptance matrix in
`docs/realtime-voice.md`. Never use `--reject`, ignore failed hunks, silently change
the model/auth source, or copy an older task runtime over the upgraded host.

A patch is not a promise of automatic compatibility with arbitrary future releases.
Pinned bases, isolated adapter files, explicit protocol version and test coverage
make the customization reproducible and failures visible. If future upstream
implements protocol 1 natively, drop the superseded Talk guard patch after testing.

Rollback the Mobile feature by reverting its feature commits. Reverting the Talk
patch removes only the mobile mint contract, not task history or accepted jobs;
Mobile refuses to start calls against a server without that contract.
