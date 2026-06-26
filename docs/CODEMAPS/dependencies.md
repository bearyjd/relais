# Dependencies, Flavors & Manifest Surface

<!-- Generated: 2026-06-26 | Files scanned: build.gradle.kts + libs.versions.toml + AndroidManifest + src/{full,degoogled,playsafe} | main @ 44879e6 -->

## External dependencies (catalog: `Android/src/gradle/libs.versions.toml`)
| Dep | Version | Purpose | Flavor |
|---|---|---|---|
| litertlm (`com.google.ai.edge.litertlm:litertlm-android`) | 0.11.0 | resident GPU LLM inference (native API: tool-calling, multi-turn, constrained decode) | all |
| litert | 1.4.2 | bundled in-APK TFLite runtime (GMS-free; powers EmbeddingGemma) | all |
| mlkit-genai-prompt | 1.0.0-beta2 | AICore/Gemini Nano (NPU) | **full** |
| mlkit-text-recognition | 16.0.1 | OCR for share-in images (#13); pulls GMS | **full** |
| llmedge (`io.github.aatricks`) | 0.3.9 | sd.cpp/Vulkan image-gen (#16); excludes onnxruntime/ktor/image-labeling | **full** |
| room | 2.7.1 | SQLite ORM (KSP2) | all |
| datastore + protobuf-javalite | 1.1.7 / 4.26.1 | proto settings | all |
| hilt-android | 2.58 | DI | all |
| ktor-client | 3.4.3 | HF auth + model download | all |
| mcp-kotlin-sdk | 0.8.0 | MCP tool transport | all |
| security-crypto | 1.1.0 | EncryptedSharedPreferences | all |
| bcpkix-jdk15to18 | 1.78.1 | BouncyCastle self-signed TLS for LAN server | all |
| aboutlibraries | 11.6.3 (pinned) | FOSS license screen (non-GMS) | all |
| compose-bom | 2026.02.00 | UI | all |
| glance-appwidget | 1.1.1 (pinned) | home widget (#3) | all |
| camera / appauth / moshi | 1.4.2 / 0.11.1 / 1.15.2 | OCR input / HF OAuth / JSON | all |

Build: AGP 8.8.2, Kotlin 2.2.0, KSP2, compileSdk/targetSdk **35**, minSdk **31**, ABIs **arm64-v8a + x86_64** (litertlm AAR constraint). Plugins: application, kotlin.android, compose, serialization, protobuf(lite), hilt, aboutlibraries, ksp.

## Product flavors — `dist` × `policy` (appId follows the channel; namespace stays `cc.grepon.relais`)
| dist | policy | applicationId | Channel | AICore | playsafe-strip |
|---|---|---|---|---|---|
| full | playsafe | `com.ventouxlabs.relais` | Play Store | yes | **yes** |
| full | open | `com.ventouxlabs.relais.izzy` | IzzyOnDroid | yes | no |
| degoogled | open | `com.ventouxlabs.relais.degoogled` | GrapheneOS / GitHub | no | no |
| degoogled | playsafe | — (filtered out) | — | — | — |

- **degoogled** excludes ALL GMS (mlkit/llmedge/AICore); `SUPPORTS_AICORE=false`; CI dex-scan enforces GMS=0. Still a full node (litertlm+litert) — drops OCR (#13) + image-gen (#16).
- **playsafe** = Play-compliance stripping is **LIVE** (#76): `src/playsafe/AndroidManifest.xml` uses `tools:node="remove"` (6 nodes) to drop the notification-listener service + triage activity + 4 dangerous perms; a CI aapt-permission gate enforces it. APK is also 16 KB-page-aligned (#77, image-labeling excluded).

## Manifest — exported surface (intent-reachable)
`MainActivity` (LAUNCHER + deep link) · `RelaisControlActivity` (LAUNCHER, key-gated start/stop) · `RelaisShareActivity` (SEND/SEND_MULTIPLE) · `RelaisNfcActivity` (NDEF `…://workflow/<id>`) · `RelaisTaskerActivity` (`com.ventouxlabs.relais.action.INFER`, key-gated) · `RelaisTileService` (QS tile) · `RelaisWidgetReceiver` (appwidget) · `BootReceiver` (BOOT_COMPLETED).
Non-exported services: `RelaisNodeService`, `RelaisShareService`, `RelaisAutomationService` (all FGS dataSync), `RelaisNotificationListenerService` (BIND perm, opt-in, **stripped in playsafe**).

## Dangerous permissions (open builds)
CAMERA, RECORD_AUDIO, NFC, READ_CALENDAR, POST_NOTIFICATIONS, FOREGROUND_SERVICE(+DATA_SYNC), INTERNET, WAKE_LOCK, USE_EXACT_ALARM/SCHEDULE_EXACT_ALARM, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED, ACCESS_NETWORK_STATE. Package visibility `<queries>` = LAUNCHER only (triage allowlist picker; no QUERY_ALL_PACKAGES). playsafe drops the notification-listener-related perms.
