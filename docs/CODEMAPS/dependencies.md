# Dependencies, Flavors & Manifest Surface

<!-- Generated: 2026-08-04 | Files scanned: build.gradle.kts + libs.versions.toml + AndroidManifest + src/{full,degoogled,playsafe} | main @ afc237c1 -->

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
| room | 2.7.1 | SQLite ORM (schema now v5) | all |
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

**Releases** — `v1.0.16` (2026-08-04) is the first published GitHub Release: `degoogled-open` APK 35.2 MB, `full-open` APK 77.9 MB, `full-playsafe` AAB 78.0 MB, all past the build/permission/ABI/16 KB-alignment/signature gates. R8 is ON for release builds (#231); every keep rule was earned from a real on-device failure, and CI runs no R8 — so proguard changes and reflective dep bumps need an on-device *inference* check, not just a green build.

## License split (CI-enforced) [NEW since 06-26]
`.github/workflows/license-lint.yml` — net-new Relais files require an AGPL-3.0 header; Google-origin files keep Apache-2.0; scans `src/main/*.kt` only.

## Manifest — exported surface
Unchanged core set (MainActivity, RelaisControlActivity, RelaisShareActivity, RelaisNfcActivity, RelaisTaskerActivity, tile/widget receivers) **+ new** `cc.grepon.relais.notifications.BootReceiver` (exported=true) alongside the existing opt-in `RelaisBootReceiver`.
