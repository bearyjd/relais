# Relais

A headless on-device LLM node: runs a model on the phone and serves an OpenAI-compatible API over the LAN. Forked from `google-ai-edge/gallery`; the Relais subsystem lives under `Android/src/app/src/main/java/cc/grepon/relais/`.

## Native-API-first (LiteRT-LM)
Relais runs on the bundled `com.google.ai.edge.litertlm` AAR. **Before designing any fallback,
prompt-injection scheme, output scraper, or new SDK integration, read
[`docs/litertlm-native-api.md`](docs/litertlm-native-api.md)** — the curated inventory of what the
native API already does (multi-turn seeding, native tool-calling, constrained decoding, channels,
custom templates, raw prefill/decode, real benchmark metrics, …). If a plan or comment claims a
capability is "not available," **verify against the AAR before believing it** — feature plans in this
repo have repeatedly been wrong about this. Regenerate the inventory after any litertlm version bump
with `scripts/dump-litertlm-api.sh`, and re-run the on-device probes
(`Android/src/app/src/androidTest/java/cc/grepon/relais/*Probe.kt`) to re-verify behavior claims.

## Design System
Always read `DESIGN.md` before making any visual or UI decisions. All font choices, colors,
spacing, the icon, and aesthetic direction are defined there (amber signal-relay on near-black,
monospace, broadcast-beacon mark). Do not deviate without explicit user approval. In QA/review,
flag any UI code that doesn't match `DESIGN.md`.

## Before touching a bug report or feature request
Read, in order: this file → `SPIKE-FINDINGS.md` (settled, do-not-re-derive device/backend facts —
e.g. the Tensor G5 `gemma-4-E4B` first-inference SIGSEGV is a known upstream LiteRT-LM bug, not a
Relais bug) → `.claude/HANDOFF.md` (latest section only, for current repo/PR state) → the relevant
`*-api.md` under `docs/` for the endpoint in question. `Bug_Reporting_Guide.md` is the human-facing
repro checklist (device/SoC, logcat tags, curl commands); `.agent_native/agent_roadmap.md` tracks
known gaps in agent-autonomous reproduction/verification for this repo — check it before assuming a
missing test harness is an oversight rather than a tracked gap.

## Build & test commands (verified against `Android/src/build.gradle.kts` and `.github/workflows/build_android.yaml`)
Do **not** run Gradle builds unless explicitly asked — they are slow and heavy; prefer reasoning
from source + the existing test suite. When you do need to run something:

| Command | Use |
|---|---|
| `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` | the CI unit-test job — device-free JVM tests, run this after any change under `Android/src/app/src/main` or `test/` |
| `./gradlew :app:assembleFullOpenDebug` | one debug APK (never `assembleDebug` — ambiguous across the `dist`×`policy` flavor matrix) |
| `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` | compiles (does not run) the on-device probe suite under `androidTest`/`androidTestFull` — these require physical hardware and are **not** part of CI |
| `./gradlew :app:clean` | fixes stale-Hilt build failures after switching branches |

Full flavor table, HF OAuth setup, and CI gate details (GMS-leak scan, Play-permission scan, 16KB
alignment scan) live in `DEVELOPMENT.md` — read it before changing `build.gradle.kts` or anything
under `src/full`/`src/degoogled`/`src/playsafe`.

## Style rules (observed, not aspirational — match these when editing existing files)
- Pure logic (parsers, tool-arg shaping, samplers, calculators) is unit-tested in device-free JVM
  tests under `test/java/cc/grepon/relais/` — one test file per subject file, `*Test.kt` suffix. Add
  a test in that style for any new pure function; do not require a device/Robolectric for logic that
  doesn't touch `Context`.
- Endpoint/IO code that needs a real capability check belongs in `androidTest`/`androidTestFull` as a
  `*Probe.kt` — these log to a `Relais*`-tagged logcat tag and are run manually on hardware, not in
  CI. Keep new probes runnable via a single `adb shell am instrument -e class …` line documented in
  the probe's own file header, matching `ToolCallingProbe.kt`/`RelaisBackendBenchmarkTest.kt`.
- Files in this codebase run long by Android convention (`RelaisHttpServer.kt` is ~1700 lines); the
  repo's own target is well under 800 — prefer extracting a new file (as `RelaisHttpIo.kt` was
  extracted from `RelaisHttpServer.kt`) over growing an existing large file further.
