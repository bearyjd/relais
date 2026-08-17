# Dependencies, Flavors & Manifest Surface

<!-- Generated: 2026-08-17 | Files scanned: build.gradle.kts + libs.versions.toml + AndroidManifest + src/{full,degoogled,playsafe} + report-worker/package.json | main @ 4a283858 -->

## External dependencies (catalog: `Android/src/gradle/libs.versions.toml`)
| Dep | Version | Purpose | Flavor |
|---|---|---|---|
| litertlm | **0.12.0** (was 0.11.0; 0.14.0 tested+reverted — regresses G5 TPU, #150) | resident LLM inference | all |
| litert | 1.4.2 | bundled TFLite runtime (EmbeddingGemma) | all |
| **sherpa-onnx** | **1.13.4 (JitPack) [NEW]** | on-device TTS (Piper voice, `/v1/audio/speech`) | all — GMS-free |
| **commons-compress** | **1.27.1 [NEW]** | decompress TTS voice `.tar.bz2` bundle | all |
| **kotlinx-coroutines-test** | **1.10.2 [NEW]** | virtual-time testing (polling-pause regression) | test |
| mlkit-genai-prompt | 1.0.0-beta2 | AICore/Gemini Nano (NPU) | full |
| llmedge | **0.4.7.2** | sd.cpp image-gen (CPU on Pixel 10 — see `ImageGenBackendPolicy`) | full |
| room | 2.7.1 | SQLite ORM (schema now v6 — 5→6 adds `content_reports`, #258) | all |
| hilt-android | 2.58 | DI | all |
| bcpkix-jdk15to18 | 1.78.1 | self-signed TLS for LAN server | all |
| compose-bom | 2026.02.00 | UI | all |

Tensor SDK (G5 TPU) is **not a Gradle dep** — a committed native dispatcher (`libLiteRtDispatch_GoogleTensor.so` under jniLibs), spike-only. AGP 8.8.2, Kotlin 2.2.0, compileSdk/targetSdk 35, minSdk 31.

## Product flavors — `dist` × `policy` (unchanged shape)
| dist | policy | applicationId | Channel |
|---|---|---|---|
| full | playsafe | `com.ventouxlabs.relais` | Play Store |
| full | open | `com.ventouxlabs.relais.izzy` | IzzyOnDroid |
| degoogled | open | `com.ventouxlabs.relais.degoogled` | GrapheneOS/GitHub |

playsafe strip live (#76, `tools:node="remove"` × 6). degoogled = zero GMS, CI-enforced.

**appId ≠ namespace.** `namespace` stays `cc.grepon.relais`; `build.gradle.kts` `onVariants` sets the applicationId per channel (composing suffixes across both dimensions would double-suffix degoogled+open). This is why an on-device probe's instrument target is `com.ventouxlabs.relais.izzy.test` for `fullOpen`, not `cc.grepon.relais.test` — the latter resolves only to a pre-rebrand leftover package and fails at class-load. Resolve it per-device with `adb shell pm list packages | grep -E 'relais|ventoux'`.

**Releases** — published: `v1.0.16` (2026-08-04, the first), `v1.0.17`, `v1.0.18` (2026-08-14, capture half of #258); `v1.0.19` tagged 2026-08-17 (send path — release.yaml builds to a **draft** the maintainer publishes). Artifacts per release: `degoogled-open` APK, `full-open` APK, `full-playsafe` AAB, all past the build/permission/ABI/16 KB-alignment/signature gates. R8 is ON for release builds (#231); every keep rule was earned from a real on-device failure, and CI runs no R8 — so proguard changes and reflective dep bumps need an on-device *inference* check, not just a green build.

## report-worker (the one non-Android component) [NEW #258]
`wrangler` ^4.0.0 · `vitest` ^3.0.0 · `typescript` ^5.7.0 · `@cloudflare/workers-types` ^5.x ·
Node ≥22 (wrangler's own engines floor — Node 20 installs a CLI that won't reliably run).
Cloudflare custom domain `report.ventouxlabs.com`, KV binding `REPORTS`; `workers_dev`/`preview_urls`
pinned **false** in the committed template (a fresh deploy would otherwise publish a
`*.workers.dev` twin).

## External hosts the app talks to (grepped, `main/java`)
`huggingface.co` (model downloads + OAuth) · `github.com`/`raw.githubusercontent.com` (release
checks, skill fetch) · `report.ventouxlabs.com` (opt-in report send — the ONLY developer-bound
leg) · `dl.google.com` · webhooks = operator-configured, HTTPS-only unless allowlisted
(`WebhookGuard`).

## License split (CI-enforced) [NEW since 06-26]
`.github/workflows/license-lint.yml` — net-new Relais files require an AGPL-3.0 header; Google-origin files keep Apache-2.0; scans `src/main/*.kt` only.

## Manifest — exported surface
Unchanged core set (MainActivity, RelaisControlActivity, RelaisShareActivity, RelaisNfcActivity, RelaisTaskerActivity, tile/widget receivers) **+ new** `cc.grepon.relais.notifications.BootReceiver` (exported=true) alongside the existing opt-in `RelaisBootReceiver`.
