# PR1 — JVM unit-test harness + CI wiring (foundation)

> **Do this first.** It unlocks headless TDD for PR2–PR5 and makes CI verify logic.
> Branch: `feat/relais-jvm-unit-tests`. No GitHub issue (infrastructure).

## Summary
Add a JVM unit-test source set (`Android/src/app/src/test/java/cc/grepon/relais/`), declare the
test dependencies, **port the 3 already-pure instrumented tests** to it, and add a CI job that runs
`./gradlew testDebugUnitTest`. After this PR, logic tests run with **no device** and CI fails on a
logic regression.

## Why
CI runs only `assembleRelease`; all 9 tests are instrumented (`androidTest`) and need a device.
An overnight loop can't run them. This harness is the precondition for TDD on PR2–PR5.

## Current state (verified)
- `app/build.gradle.kts`: `testImplementation(libs.junit)` **already present** (JUnit 4.13.2). No
  `testOptions`/`unitTests` block, no Robolectric, no coroutines-test/truth/mockk.
- `gradle/libs.versions.toml`: `junit = "4.13.2"`, `com-google-code-gson` (2.12.1) declared. No
  robolectric alias.
- These 3 instrumented tests are **pure JVM** (Gson/predicate only, no `Context`):
  - `RelaisEngineConfigTest.kt` — already plain JUnit (NO `@RunWith`); tests `isG5Incompatible` +
    `isMissingEncoder`. Pure.
  - `RelaisModelCatalogTest.kt` — `@RunWith(AndroidJUnit4)` only for convenience; tests
    `RelaisModelCatalog.curatedModelsFrom(allowlist)` over a Gson fixture. No `Context`.
  - `RelaisHuggingFaceTest.kt` — `@RunWith(AndroidJUnit4)` only; tests `buildRef`/`pickLiteRtLm`/
    `isGated`/`interpretInfo` over Gson + a mocked `HttpJsonResult` seam. No `Context`, no network.

## Changes

### 1. Dependencies — `gradle/libs.versions.toml`
Add (versions chosen for AGP 8.8.2 / Kotlin 2.2.0 / JVM 11):
```toml
[versions]
robolectric = "4.14.1"      # for PR5's Context/prefs tests; safe to land now
truth = "1.4.4"             # optional fluent assertions; only if you use it

[libraries]
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
```
> Robolectric is **not** needed by the ported tests; land it so PR5 doesn't re-touch gradle. The
> critical-path PRs (PR2/3/4) use plain JUnit + Gson only.

### 2. `app/build.gradle.kts`
- Add a `testOptions { unitTests { isIncludeAndroidResources = true } }` inside `android { }` (lets
  Robolectric read resources later; harmless now).
- Add deps:
```kotlin
testImplementation(libs.junit)                 // already there
testImplementation(libs.com.google.code.gson)  // ported tests parse allowlist/HF JSON on the JVM
testImplementation(libs.robolectric)           // for PR5; unused by ported tests
// testImplementation(libs.truth)              // only if you adopt Truth
```

### 3. Create `src/test/java/cc/grepon/relais/` and port the 3 pure tests
Copy each from `androidTest` → `test`, then:
- Remove `@RunWith(AndroidJUnit4::class)` and its import (plain JUnit 4 runs on the JVM).
- Keep everything else identical. They must compile and pass under `testDebugUnitTest`.
- **Delete the originals from `androidTest`** (don't run the same logic twice / drift). Leave the
  device-only tests (`RelaisNodeTest`, `RelaisBackendBenchmarkTest`, `RelaisGate3Test`, the
  provision/taskremoved ones) in `androidTest` for now — PR5 handles the Robolectric-needing ones.

### 4. CI — wire `testDebugUnitTest`
Add a job to `.github/workflows/build_android.yaml` (mirror its `paths: Android/**` trigger and
`working-directory: ./Android/src`):
```yaml
  unit_tests:
    name: JVM unit tests
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ./Android/src
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '21' }
      - name: Unit tests
        run: ./gradlew testDebugUnitTest
```

## Acceptance criteria
- `./gradlew testDebugUnitTest` runs locally and the 3 ported suites **pass** (this is the headless
  lever PR2–PR5 depend on).
- `./gradlew assembleRelease` still green.
- New `JVM unit tests` CI job present and green on the PR.
- `androidTest` no longer contains the 3 ported classes; the device-only ones remain.

## TDD note
This PR has no behavior change, so "RED" is: the ported tests **don't compile/run** until the
source set + deps exist. Prove the harness by making `testDebugUnitTest` discover and pass them.

## Guardrails / PR
- Branch `feat/relais-jvm-unit-tests` off `main`; PR to `main`; independent review pass on the diff;
  merge on green CI.
- First `testDebugUnitTest` needs network to resolve Robolectric/Gson — do not use `--offline`.
- Don't touch AICore. Don't change production code (test infra only; the sole non-test edits are
  `build.gradle.kts`, `libs.versions.toml`, the CI yaml).
