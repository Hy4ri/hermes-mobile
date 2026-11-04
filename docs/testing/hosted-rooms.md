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

## Backend member progress (#418)

The hosted-room log shows backend member activity separately from legacy stream indicators.
`round_index` is zero-based on the wire and one-based on screen. The round limit comes from
`driver_status.max_rounds`. `started_at` and `progress_at` are fractional Unix **seconds**.
Elapsed time and progress age update only while the screen lifecycle is STARTED. A null time
or tool shows no invented duration or tool. Missing activity means unavailable, not completed;
an empty activity list means no live activity was reported, not proof of successful completion.

Additional device checks:

1. Run a harmless multi-member task. Check queued, running, and stopping labels, actual tool,
   thread, round, and backend round limit. Compare elapsed/progress ages against gateway times.
2. Background the app, then return. Check that time updates resume from current time.
3. Retry an indeterminate task. Old tool/progress must disappear immediately and must not return
   from an outstanding refresh. A new attempt must start with its own backend timestamps.
4. Change rooms, profiles, and saved connections during work. Reconnect during work. No old
   activity may survive the ownership change; only a fresh fenced snapshot may restore activity.
5. Use an older gateway that omits activity. Check the unavailable label, without a false
   completion or fabricated legacy tool indicator. Check null timestamps and tools.
6. Check narrow portrait, large text, RTL, and TalkBack with several live members.

## Shared context and routing (#417)

The settings action additionally requires `groups.context` in the advertised method list. Its absence does not disable the hosted-room transport. Purpose and shared notes are server-owned context, not the legacy group's local system prompt. Changes apply from the next message.

1. Open settings, edit purpose and notes, and save. Reopen to check the gateway's normalized values. Check another client sees the same context.
2. Configure lead routing on another client. Edit only notes on Mobile. Confirm the request omits both routing fields and preserves the current gateway policy.
3. Explicitly select Everyone. Confirm the gateway clears the lead. Select Lead and a current member. Confirm the room header identifies the lead only after server confirmation.
4. Remove the selected lead externally while settings are open. Confirm new saves require an explicit replacement or Everyone; Mobile must not silently change routing.
5. Lose the ACK for a lead save, then remove that lead. Retry the unchanged pending payload. Confirm the original event ID is reused and the backend can return its original receipt. Editing the payload must instead create a fresh event ID.
6. Fail loading or saving. Confirm errors remain visible, retry is available, and failed-save drafts are retained. Reopen settings after external edits to check fresh loading.
7. Switch room, profile, connection, or socket generation during loading or saving. Confirm stale completions cannot close or populate a new editor or change its confirmed context.
8. Check the settings dialog at narrow widths, large font, Arabic/RTL, with the keyboard, and with TalkBack. Confirm the toolbar action has a tooltip.

Context retry identity is held in memory only. Reconnect never automatically resubmits settings. If Android kills the process after an uncertain save, inspect the gateway before saving again.
