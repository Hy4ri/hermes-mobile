# Cassy Control — Product Notes

Cassy Control is an Android-first control center for Hermes Agent, based on the native `Hy4ri/hermes-mobile` client rather than a generic OpenAI chat shell.

## Cassy product layer

- **Workspace desk:** local workspaces group related Hermes sessions without changing server history.
- **Session tabs:** up to eight recent tabs, pinning, close actions and immediate switching.
- **Per-session model preference:** selecting a pinned model stores a session-local preference and sends Hermes' `/model provider/model` command to the current session.
- **Run monitor:** periodic session/stat refresh and active-run status in the workspace header.
- **Adaptive layout:** compact phones use a two-row control strip; screens at 840dp and above gain a permanent session desk beside the chat.
- **HyperOS reliability:** direct shortcuts to Xiaomi Auto-start and battery settings, without silently requesting unrestricted battery access.
- **Cassy Signature theme:** warm graphite, restrained violet, champagne and cool-blue accents; light and dark schemes; Material dynamic color remains optional.
- **Private shell with user control:** biometric/device lock, encrypted storage, no Android backup and an optional screenshot/recents-protection switch.
- **Seamless NAS sign-in:** the known private dashboard is probed automatically, its injected session credential is consumed locally, and the resulting connection profile is retained for future launches.

## Existing Hermes-native capabilities retained

- WebSocket streaming and reasoning/tool event rendering.
- Approval, clarify, sudo and secret request flows.
- Attachments, camera, files and speech input.
- Session history, search, rename, archive/delete/prune actions.
- Skills, cron jobs, profiles, model management, MCP, plugins, webhooks, pairing, analytics, system/process views and update controls.
- Background completion notifications and notification replies.

## UX conventions

- Tabs are local navigation state; closing a tab never deletes the Hermes session.
- Long-press a session tab to pin or unpin it.
- Workspaces are local organization and do not mutate Hermes server records.
- Public cleartext server addresses are rejected; use HTTPS or a private/Tailscale address.
- On HyperOS, enable Auto-start and choose “No restrictions” only if background completion notifications are important.
