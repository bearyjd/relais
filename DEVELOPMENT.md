# Development

Relais is a headless on-device LLM node — a fork of `google-ai-edge/gallery`. The node subsystem lives
under `Android/src/app/src/main/java/cc/grepon/relais/`. This file covers local build, the command
surface, and build-time configuration. For architecture see [`docs/CODEMAPS/`](docs/CODEMAPS/); for
operating a running node see [`docs/RUNBOOK.md`](docs/RUNBOOK.md).

> Code contributions are **not currently open** — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Prerequisites
- Android Studio, or a JDK 17 + Android SDK (build-tools + platform **35**).
- A device/emulator on **arm64-v8a or x86_64**, **Android 12+ (minSdk 31)** — the litertlm LLM AAR ships only those ABIs.
- The **first** build of any newly-added dependency needs network (not `--offline`).

## Product flavors
Two dimensions — `dist` (full / degoogled) × `policy` (open / playsafe) — yield three shipping variants
(the source namespace stays `cc.grepon.relais`; the `applicationId` follows the channel).

<!-- AUTO-GENERATED: from Android/src/app/build.gradle.kts (productFlavors + per-variant applicationId) -->
| Variant | applicationId | Channel | Notes |
|---|---|---|---|
| `fullOpen` | `com.ventouxlabs.relais.izzy` | IzzyOnDroid | all features (OCR, image-gen, AICore) |
| `fullPlaysafe` | `com.ventouxlabs.relais` | Google Play | Play-compliance manifest stripping (`src/playsafe/`) |
| `degoogledOpen` | `com.ventouxlabs.relais.degoogled` | GrapheneOS / GitHub | GMS-free (no ML Kit / llmedge / AICore) |
| ~~degoogledPlaysafe~~ | — | filtered out | not a shipping combo |
<!-- END AUTO-GENERATED -->

## Commands
<!-- AUTO-GENERATED: from .github/workflows/build_android.yaml -->
| Command | Description |
|---|---|
| `./gradlew assembleRelease` | Build all 3 shipping release variants (the CI build job) |
| `./gradlew :app:assembleFullOpenDebug` | Build one debug APK (swap the variant: `FullOpen`/`FullPlaysafe`/`DegoogledOpen`) |
| `./gradlew :app:installFullOpenDebug` | Build + install one debug variant to the attached device |
| `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` | JVM unit tests for every shipping variant (the CI test job) |
| `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` | Compile the on-device probe suite (`src/androidTest`; not run in CI) |
<!-- END AUTO-GENERATED -->

> ⚠️ `testDebugUnitTest` and `assembleDebug` are **ambiguous** across the two flavor dimensions — always
> name the variant. Building/testing more than one heavy variant in a single invocation can OOM the 2 GB
> Gradle daemon; prefer one variant per invocation.

## Build configuration (this project has no `.env`)
Build-time and runtime config come from the following, not environment variables:

<!-- AUTO-GENERATED: from ProjectConfig.kt + build.gradle.kts + RelaisConfig -->
| Setting | Where | Required | Purpose |
|---|---|---|---|
| HF OAuth `clientId` / `redirectUri` | `…/common/ProjectConfig.kt` (build-time) | Only for in-app model-download UI | HuggingFace login for the Gallery model picker |
| `appAuthRedirectScheme` | `build.gradle.kts` → `manifestPlaceholders` | Only for in-app model-download UI | Must match the HF app's redirect URL scheme |
| API key | `EncryptedSharedPreferences` (runtime, set via control panel) | Yes (for all routes except `/health`) | Bearer auth for HTTP + automation ABI |
| Model ID | plaintext prefs (runtime) | No (default `gemma-4-E4B-it`; **Tensor G5 → E2B**) | Which model the node serves |
| HF token | `EncryptedSharedPreferences` (runtime) | No | Download gated models |
| Opt-in flags (share / nfc / triage / session) | plaintext prefs (runtime) | No (most default OFF) | Feature gating |
| Webhook allowlist | plaintext prefs (runtime) | No | Batch-webhook SSRF guard |
<!-- END AUTO-GENERATED -->

### HuggingFace OAuth setup (only if you need the in-app model-download screen)
1. Create a HuggingFace OAuth app ([docs](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)).
2. In `Android/src/app/src/main/java/cc/grepon/relais/common/ProjectConfig.kt`, replace `clientId` and `redirectUri`.
3. In `Android/src/app/build.gradle.kts`, set `manifestPlaceholders["appAuthRedirectScheme"]` to match the redirect URL scheme.

The headless node itself does **not** require HF OAuth — models can be staged directly (see `docs/RUNBOOK.md`) or downloaded with an HF token.

## Build levers / gotchas
- **GMS gate:** the `degoogled` variant must dex **zero** `com.google.android.gms` / `aicore` / `mlkit` classes — CI enforces it (`assembleDegoogled…Release` + dex scan).
- **Play-compliance gate:** the `playsafe` build must drop the restricted permissions + notification-listener service — CI enforces it with an `aapt2` permission/component scan against `src/playsafe/AndroidManifest.xml`.
- **16 KB page alignment:** all shipped arm64 `.so` must be 16 KB-aligned (Pixel 10 / Android 16) — CI `readelf`-scans every variant.
- **Stale Hilt:** after switching branches across the internal rename, `hiltJavaCompile…` can fail on a missing `RelaisApplication`/`GalleryApplication` class → `./gradlew :app:clean`.
