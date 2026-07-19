# Dependencies, Flavors & Manifest Surface

<!-- Generated: 2026-07-19 | Files scanned: build.gradle.kts + libs.versions.toml + AndroidManifest + src/{full,degoogled,playsafe} | main @ ab345ff -->

## External dependencies (catalog: `Android/src/gradle/libs.versions.toml`)
| Dep | Version | Purpose | Flavor |
|---|---|---|---|
| litertlm | **0.12.0** (was 0.11.0; 0.14.0 tested+reverted — regresses G5 TPU, #150) | resident LLM inference | all |
| litert | 1.4.2 | bundled TFLite runtime (EmbeddingGemma) | all |
| **sherpa-onnx** | **1.13.4 (JitPack) [NEW]** | on-device TTS (Piper voice, `/v1/audio/speech`) | all — GMS-free |
| **commons-compress** | **1.27.1 [NEW]** | decompress TTS voice `.tar.bz2` bundle | all |
| **kotlinx-coroutines-test** | **1.10.2 [NEW]** | virtual-time testing (polling-pause regression) | test |
| mlkit-genai-prompt | 1.0.0-beta2 | AICore/Gemini Nano (NPU) | full |
| llmedge | 0.3.9 | sd.cpp/Vulkan image-gen | full |
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

## License split (CI-enforced) [NEW since 06-26]
`.github/workflows/license-lint.yml` — net-new Relais files require an AGPL-3.0 header; Google-origin files keep Apache-2.0; scans `src/main/*.kt` only.

## Manifest — exported surface
Unchanged core set (MainActivity, RelaisControlActivity, RelaisShareActivity, RelaisNfcActivity, RelaisTaskerActivity, tile/widget receivers) **+ new** `cc.grepon.relais.notifications.BootReceiver` (exported=true) alongside the existing opt-in `RelaisBootReceiver`.
