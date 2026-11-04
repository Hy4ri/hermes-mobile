# Hosted-room transport verification

Hosted rooms use a separate route under **Bots → Groups → Hosted rooms**. They do not import legacy group history or use the legacy local group driver. Create the room on the gateway before testing; this screen does not create rooms.

## Gateway requirements

The active profile must advertise protocol version 2 through `groups.capabilities`, with:

- Methods: `groups.capabilities`, `groups.list`, `groups.state`, `groups.log`, `groups.send`, `groups.stop`, `groups.retry`, and `groups.approve`.
- Features: `idempotent_send`, `monotonic_log`, and `room_identity`.
- A positive `max_log_limit`.

A compatible gateway without an available driver remains read-only. A missing capabilities method or incompatible protocol shows an unavailable state. Network and authentication failures remain errors, not evidence of protocol incompatibility.

## Automated checks

```sh
./gradlew testDebugUnitTest
./gradlew ktlintCheck checkColorLiterals lintDebug assembleDebug compileReleaseKotlin compileDebugAndroidTestKotlin
```

Focused coverage is in `HostedRoomsSourceTest`, `HostedRoomsControllerTest`, and `WsTransportSettlementTest`. It checks wire parameters and typed receipts, replay validation, stale-response fencing, uncertain-send reconciliation, explicit retry identity, and rejection without transport queueing.

## Device and live-gateway acceptance

Use test rooms and harmless messages. Do not approve an unsafe command for testing.

1. Open a legacy group on an older gateway. Confirm its existing behavior is unchanged. Open Hosted rooms and confirm the unsupported message appears without breaking legacy rooms.
2. On a compatible gateway, select a listed room. Confirm its stable room ID, name, and replayed messages. Rooms with equal names must remain distinct.
3. Send one harmless message. Confirm one user event and the member reply appear in the room log.
4. Interrupt the connection while a send receipt is outstanding. After reconnect, confirm the app does not resend automatically. Replay must clear a pending send if the gateway already accepted it. Otherwise, **Retry send** must reuse its original ID and text.
5. Switch profiles or saved connections while a room read or send is outstanding. Confirm no old room data appears in the new scope. Repeat with two connections that use the same profile name.
6. Stop queued or running test work. Confirm the gateway stops that room's work, not a legacy session or another room.
7. For an indeterminate test task, confirm **Retry task** shows the duplicate-execution warning. Cancel first. Then confirm a safe retry and check the gateway's task generation.
8. For a harmless pending approval, confirm the displayed command and reason. Verify cancel, **once**, and **deny**. The app must not offer a persistent approval choice.
9. Confirm read-only behavior when the driver is unavailable and recovery when it becomes available. Confirm a disbanded room cannot send new messages.
10. Check narrow portrait, rotation, keyboard visibility, large font, Arabic/RTL, and TalkBack. Check hardware Back and toolbar Back. Leaving an unconfirmed send must show a warning.

## Limits

Pending-send retry identity is kept in memory across socket reconnects. It is not a durable Android process-recovery queue. If the app exits with an unconfirmed message, inspect the gateway log before sending that text again. Preview renders and JVM tests do not establish live-gateway or physical-device acceptance.
