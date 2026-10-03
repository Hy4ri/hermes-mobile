# Image attachment diagnostics

This diagnostic build addresses the invisible-failure part of [#1459](https://github.com/Hy4ri/hermes-mobile/issues/1459).
It does **not** establish the cause of the reporter's disappearing image, or normalize model-facing image payloads.

## Capture on the affected device

1. Install the debug APK (`com.m57.hermescontrol.dev`), which coexists with the release app. Configure the same gateway and authentication mode in it. Do not uninstall the release app or clear its data.
2. Open a fresh session, send one image with a short caption, and wait for the reply. Note whether the image stays, shows **Could not load image**, or disappears entirely. If an error card appears, tap **Retry** once.
3. Leave and reopen that session once to exercise cached and REST history as well.
4. Export only the diagnostic tag:

   ```sh
   adb logcat -d -v threadtime -s ChatImageDiag:D '*:S' > image-diagnostics.log
   ```

   On-device shell users can omit `adb`, provided their shell already has logcat access. Capture promptly after reproducing; logcat buffers are finite. Reproduce in the debug app, not the release app: release builds do not emit these diagnostics.
5. Share `image-diagnostics.log` and the observed behavior. The filtered tag contains no captions, filenames, paths, URLs, tokens, cookies, image bytes, or exception messages. Do not substitute a full unfiltered debug log; other tags may contain sensitive data.

## Reading the trace

`row` is a process-independent hash of message identity, **not** content. Thumbnail rows add an attachment index (`row=<key>:0`). History rows also show a hashed `canonical` alias, so optimistic and server rows can be correlated without printing session IDs. Hashes are correlation aids, not cryptographic anonymization. Markdown/standalone thumbnails have `row=standalone`.

- `merge=rest|cache stage=before|incoming|after`: whether the text still contains an `@image:` marker, attachment count, and source scheme (`content`, `file`, `http`, `https`, `data`, `other`). A before/incoming attachment followed by an after row with zero attachments points to state/merge loss. `imageRowsAfter=0` identifies complete row loss within that snapshot.
- `phase=start|success|error`: actual Coil thumbnail request callbacks. An error includes exception **types** and the HTTP status when Coil supplies an `HttpException`. `http=unknown` means unavailable, not success.
- A `source=content|file` error points to local access; `source=http|https` with 401/403 points to access/auth, 404 to the file/route, and decoder-related exceptions to payload/format handling. These are investigation directions, not automatic diagnoses.
- `phase=success` followed by disappearance without a new error points toward layout/state rather than that request failing. Compare history stages and image source changes.
- `phase=dispose` means the thumbnail left composition; normal scrolling, navigation, or a changed image model can do this. It is not itself an error.

The history trace is emitted only after a history merge successfully applies, never from a retried `StateFlow.update` lambda. It is restricted to image-bearing user rows and their aliases. There is no database or network behavior change.

## Local regression coverage

- `UserImageHydrationTest.issue1459PlainHistorySurvivesLiveMergeAndCacheRestore`: the supplied plain-string JSON through deserialization, mapping, live merge, entity round-trip, cache merge, and repeated REST refresh. This is a mapping/merge characterization, not a reproduction of the remote device's failure.
- `GifImageThumbnailTest`: real Coil loading on an emulator; missing local file → visible error → Retry → recovered image; HTTP 403 → visible error → Retry → valid PNG. Retry must not open the viewer. The diagnostic log must contain the error and success but not the private URL/path.
- `ChatImageDiagnosticsTest`: allowlisted source/error/status metadata, loss visibility, and redaction.

Final acceptance still requires the reporter's affected gateway/device flow.
