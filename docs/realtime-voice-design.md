# Realtime voice UI — Dots Focus

## Selected direction

A / **Dots Focus** replaces the scrolling control list with a voice-first stage
and a fixed Mute / End / Activity dock. Activity switches the central pane to
latest transcripts and native Talk work/results. The dock remains reachable when
content scrolls. Back from Activity returns to voice; End always ends voice.

Other explored directions: B / a large abstract orb with a lower information
tray; C / a compact voice rail beside a prominent activity feed. A keeps natural
conversation primary without hiding work results. The comparison prototype is
throwaway design code, not a WebView engine or evidence of working audio.

## References and limits

First-party references inspected on 2026-11-16:

- [OpenAI Voice help](https://help.openai.com/en/articles/20001274-chatgpt-voice):
  explicit microphone/exit controls and discoverable text during voice.
- [Official ChatGPT listing](https://apps.apple.com/us/app/chatgpt/id6448311069):
  restrained surfaces and a distinct voice entry in publisher imagery.
- [Grok product](https://x.ai/grok) and
  [official Grok listing](https://apps.apple.com/us/app/grok/id6670324846):
  restrained dark surfaces and separated lower conversation controls in the
  retrieved publisher camera-conversation imagery.

The requested four-dot and orb directions are **design inputs**, not claims that
the retrieved current products use those exact active-call visuals. No external
logo, brand palette, persona picker, camera feature or API capability is copied.

## Native behavior

- Four monochrome marks use the active Hermes theme. Actual finite/clamped mic
  RMS drives listening geometry, with a perceptual gain for quiet speech.
- Connecting, processing and speaking use restrained Compose-native activity
  motion, not a fabricated output-audio meter. Muting input does not stop output
  activity. Settled states do not run an infinite transition.
- Status labels are localized and announced politely. Audio level changes are
  not announced frame by frame. The dots are an accessible interrupt target only
  while a response can be interrupted.
- Fixed native 64dp controls keep mute, end and activity accessible. Scrollable
  content absorbs narrow widths and larger fonts; the main column is bounded on
  larger windows. Existing scaffold insets are not applied a second time.
- Permission refusal, safe connection errors, foreground exit and explicit
  reconnect remain distinct. A processing indicator does not claim a tool was
  called or work completed.
- Activity shows only the latest user/assistant text held by the controller and
  raw work reported by Talk. It is not a Kanban board. Stop Work still uses the
  existing confirmation dialog; mute, interruption and End do not cancel work.
- Traditional Chinese labels are provided through Android resources. Existing
  light/dark/dynamic themes remain authoritative; the call does not force a
  separate black theme.

No WebRTC negotiation, auth, backend source, Bot routing, tool protocol or task
state ownership changes are part of this redesign. Existing live acceptance
limitations remain in [the acceptance record](testing/realtime-voice-acceptance.md).

## Source checkpoint and validation

The production patch is based on upstream Mobile `main`
`008d9e88778ec3f7a70aab11af2b9917cf541c14`, fetched 2026-11-16.
The three-way port applied without conflicts and preserves that upstream's
atomic WebSocket reducer update.

The three-way design exploration is archived separately on
`prototype/voice-ui-dots-20261116` at `896af94360`, under
`ui/voice/RealtimeVoice.prototype.html` and `.md`. It is not in the production
patch. The local delivery includes the standalone HTML for comparison.

Five actual Compose preview renders cover dark/light, muted/listening/speaking,
Activity, 320dp width at 1.5× text, and an 840dp Traditional Chinese window.
Rendered component bounds confirm 64dp dock targets and a 48dp reconnect target.
The latest-base APKs build; 21 focused voice unit tests and the color guard pass.
Three UI interaction tests compile but were not run on a device. These results
verify this UI change, not the outstanding real-provider voice/tool acceptance.
