# Read-aloud playback checks

Listen uses `/api/audio/speak-stream` for PCM playback. If streaming is unavailable before any audio is submitted, it falls back to one whole-message `/api/audio/speak` request. That request has a speech-only timeout of 180–600 seconds; ordinary requests keep their existing timeout. A partial stream is never replayed through REST.

The message action shows a cancellable progress indicator during generation, then Stop during playback. Completed streams are cached as WAV files for replay. Stopping an incomplete stream removes its partial cache.

## Disposable-emulator tests

`StreamingListenDeviceTest` is opt-in because it changes the app's saved connection and writes real playback/cache files. Never run it on a physical device or an emulator with data you want to keep. It checks emulator hardware before changing auth state. Without `disposableSpeech=true`, the cases are skipped.

Install the matching debug APK and Android test APK on a disposable emulator, then run:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.m57.hermescontrol.ui.chat.components.StreamingListenDeviceTest \
  -e disposableSpeech true \
  com.m57.hermescontrol.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Four cases use a local MockWebServer and deterministic PCM tone. They exercise the production message action, controller, AudioTrack, and ExoPlayer:

- Playback starts while the end frame is held. After a normal server close, the remaining audio drains and replay makes no network request.
- Stopping during generation closes the socket without fallback or stale playback.
- Stopping after PCM starts releases audio and deletes the partial cache, without REST fallback.
- A real 31-second HTTP response delay survives the former 30-second timeout and plays through REST exactly once.

The optional fifth case uses a live gateway. Pass `liveGateway` with its URL and `liveCookie` with a fresh test session cookie. Do not commit cookies or include them in logs. It measures first playback and completion with the production streaming connection. A TCP relay can expose a LAN gateway through `adb reverse` without changing backend settings.

The tests save screenshots named `1883-*.png` in the debug app's files directory. Pull the specific files with `adb exec-out run-as com.m57.hermescontrol.dev cat files/<name>`. After collecting results, clear the disposable app's data and remove the temporary cookie and any test relay.

## Recorded run for #1883

Android 36, x86_64 Pixel 6 emulator, local debug APK explicitly approved for this run:

- Five tests passed, none skipped, in 70.288 seconds.
- Live gateway playback began after 3.508 seconds and completed/drained after 17.183 seconds for a 193-character input. This is one run, not a before/after benchmark or a physical-speaker listening test.
- The relay observed three binary speech frames and no REST synthesis in the live case.
- The delayed REST fallback, both cancellation cases, and cached replay passed.
- Runtime testing found two issues: continuation on a worker thread could access ExoPlayer off-main, and treating a normal WebSocket close as an error interrupted buffered audio. The controller scope is now explicitly main-bound, and normal close drains queued frames while the player still requires an end frame.
- 126 focused JVM tests and the compile, formatting, and color checks passed. The full unit suite runs in CI.

Generating and playing screenshots are in [`docs/screenshots/read-aloud`](../screenshots/read-aloud). Physical-device acceptance is a separate gate; emulator success does not claim it.
