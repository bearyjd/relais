# Share an image → on-device OCR → inference (#13)

Extends the share-sheet target (#1) to accept **images**, not just text. Share a screenshot or photo
into Relais and the node OCRs it on-device, then runs the recognized text through the resident model
(summarize / answer, same as a text share). No image leaves the phone.

## Flow

1. The share sheet sends `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with an `image/*` MIME to the
   transparent trampoline `RelaisShareActivity`.
2. The trampoline collects the `content://` image URIs (only content URIs — never a `file://` path),
   gates via `shouldStartImageShare` (enabled? engine resident? at least one image?), and hands the URIs
   to `RelaisShareService` with `FLAG_GRANT_READ_URI_PERMISSION`, then finishes. The grant to the
   service is independent of the trampoline, so it survives the activity finishing.
3. The service OCRs each image with the **bundled ML Kit Latin recognizer** (`ImageTextRecognizer`,
   on-device). Images are **downsampled before decode** (max ~2048px) so a 50+ MP photo can't OOM the
   process that hosts the multi-GB LLM, and the image count is **capped** (`MAX_SHARE_IMAGES`) so a
   hostile `SEND_MULTIPLE` can't drive unbounded OCR. Any caption shared alongside the image
   (`EXTRA_TEXT`) is prefixed to the recognized text. If nothing readable was found it posts a "no text
   found" notification (the post-OCR `EMPTY` gate the activity couldn't apply before the text was known).
4. Result is posted as a notification (capped) and copied to the clipboard, exactly like a text share.

If Play Services is absent (a de-Googled device), the recognizer fails to initialise and the share
degrades gracefully to "no text found" rather than crashing.

The cold-start guard is preserved: an image share when the node is off posts a status notification and
never starts the engine.

## Dependency / de-Google note

OCR uses `com.google.mlkit:text-recognition` (bundled — the model ships in the APK). **It is not
GMS-free**: it transitively pulls `com.google.android.gms:play-services-mlkit-text-recognition` plus
`play-services-base`/`-basement`/`-tasks`. It works on devices with Google Play Services. A future
de-Googled build flavor must **exclude** this dependency (and the OCR code), which is why OCR is kept in
its own `share/ImageTextRecognizer.kt` behind the share path. (Decision: OCR ships in the single main
build for now; the `full` / `degoogled` product-flavor split is a separate follow-up.)

## Verification

- **JVM:** `SharePayloadTest` covers `shouldStartImageShare` (the pre-OCR gate). The OCR text reuses the
  already-tested `extractSharedText` shaping (join, subject prefix, cap).
- **On-device:** `ImageOcrProbe` renders known text to a bitmap and asserts the bundled recognizer reads
  it back — isolates the ML Kit recognition capability from the share/content-URI plumbing.
- **Deferred on-device gate:** a real end-to-end image share (share a screenshot from another app →
  result notification) — the content-URI grant + service OCR path — still wants a manual on-device pass.
