# Spec: Relais release pipeline (signing + release CI)

> Sub-project #1 of "distribution readiness" (publish on Google Play + IzzyOnDroid + GrapheneOS/GitHub).
> **Reconciles Tasks 5–6 of [`ventouxlabs-distribution-identity.plan.md`](./ventouxlabs-distribution-identity.plan.md)** to the current 3-variant scheme owned by [`relais-play-channel-feature-matrix.plan.md`](./relais-play-channel-feature-matrix.plan.md). That plan's Task 6 predates the `policy` dimension and names only `bundleFullRelease`/`assembleDegoogledRelease`; the real shipping variants are now `fullPlaysafe` / `fullOpen` / `degoogledOpen`.

- **Date:** 2026-06-30
- **Status:** design approved in brainstorming; pending user spec review → implementation.
- **Scope:** the single blocker gating **all three** stores — real release signing + a release CI that builds, gates, signs, verifies, and publishes the artifacts. Everything else (store metadata, Play paperwork, IzzyOnDroid RFP, targetSdk bump) is a separate sub-project.

## Goal
Replace debug-keystore release signing with **signing-from-secrets**, and add a **tag-triggered release workflow** that produces the three distributable artifacts, each passing the existing CI gates, signed and verified, published to a GitHub Release.

## Current state (the gap)
- `Android/src/app/build.gradle.kts:62` — `release` build type uses `signingConfigs.getByName("debug")`. No release signing config exists. `versionCode = 33`, `versionName = "1.0.15"`; minSdk 31 / target+compile 35; ABIs arm64-v8a + x86_64.
- `.github/workflows/build_android.yaml` runs `assembleRelease` (debug-signed APKs) and holds the gates: **GMS=0** dex scan on `degoogledOpen`, **playsafe-permission** removal gate (`aapt2 dump permissions` + `xmltree`), **16 KB** native-lib alignment (`readelf`). **No `bundle*Release` (AAB) task anywhere. No `release.yaml`.**

## Locked decisions
| # | Decision |
|---|----------|
| K1 | **One** release keystore/key signs every channel. The **user generates and solely owns it**; it **never rotates** (Izzy/Obtainium update continuity). |
| K2 | **Play App Signing**: the uploaded AAB is signed with this key as the **upload key**; Google holds the real app-signing key. **Manual** first upload to enroll — no automated `supply` in v1. |
| K3 | Signing config reads from **env vars**, with **debug-keystore fallback when env is absent** — local + contributor builds and `build_android.yaml` are unaffected (no secrets required to build debug). |
| K4 | Trigger: `push: tags: ['v*']` **+** `workflow_dispatch`. |
| K5 | Artifacts → assets on a **GitHub Release** for the tag: `fullPlaysafe` **AAB** (Play), `fullOpen` **APK** (IzzyOnDroid), `degoogledOpen` **APK** (GrapheneOS/GitHub). |
| K6 | The release artifacts **re-run the existing gates** (GMS=0, playsafe-perms, 16 KB) + `apksigner verify` before publish. |

## Files to change
| File | Action | What |
|---|---|---|
| `Android/src/app/build.gradle.kts` | UPDATE | Add `signingConfigs.create("release")` reading `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` from env (`System.getenv`), guarded: if `RELEASE_STORE_FILE` is set+exists use it, else fall back to debug. Point `buildTypes.release.signingConfig` at the chosen config. Replaces the `:62` debug line. |
| `.github/workflows/release.yaml` | CREATE | `on: push.tags ['v*']` + `workflow_dispatch`. temurin-21, working-dir `Android/src`. Decode `RELEASE_KEYSTORE_BASE64` secret → keystore file; export the 4 `RELEASE_*` envs. Build `bundleFullPlaysafeRelease assembleFullOpenRelease assembleDegoogledOpenRelease`. Run the three gates (copy from `build_android.yaml`) on the matching release artifacts. `apksigner verify` each APK; (AAB is verified at Play upload). `gh release create/upload` the AAB + 2 APKs. |
| `docs/distribution.md` | CREATE (release-runbook section) | keytool keystore generation, the 5 GitHub secrets, Play first-upload + App-Signing enrollment, the per-channel artifact→store mapping. (The full distribution doc + fastlane metadata belong to later sub-projects; the signing/release-secret runbook lands here now.) |

## Secrets the user adds (GitHub → repo → Settings → Secrets → Actions)
- `RELEASE_KEYSTORE_BASE64` — `base64 -w0` of the keystore file
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`

Keystore generation (user runs once, keeps the `.jks` + passwords backed up offline):
```
keytool -genkeypair -v -keystore relais-release.jks -alias relais \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
base64 -w0 relais-release.jks   # → paste into RELEASE_KEYSTORE_BASE64
```

## Validation
- `Android/src/gradlew -p Android/src :app:assembleFullOpenDebug` builds with **no** secrets present (debug fallback intact).
- With the 4 `RELEASE_*` envs set locally, `assembleFullOpenRelease` produces an APK that `apksigner verify` accepts (signed by the release key, not debug).
- A `v0.0.0-test` tag on a branch (or `workflow_dispatch`) → `release.yaml` emits the AAB + 2 APKs; **GMS=0 / playsafe-perm / 16 KB** gates all pass on the release artifacts; `apksigner verify` passes; a draft GitHub Release carries all three assets.
- `aapt2 dump packagename` on the 3 APKs → `com.ventouxlabs.relais` (playsafe) / `com.ventouxlabs.relais.izzy` (fullOpen) / `com.ventouxlabs.relais.degoogled` (degoogledOpen).

## Risks
- **Key lost/rotated** → Izzy/Obtainium/GrapheneOS users must reinstall. Mitigation: user owns + backs up the single never-rotate key (K1).
- **Editing `.github/workflows/*` can fail to auto-trigger** the Build Android workflow on PR (known quirk, prior handoffs). Mitigation: verify `release.yaml` via `workflow_dispatch` / a throwaway tag, not just PR.
- **playsafe still ships `RECORD_AUDIO` + `CAMERA`** — fine for the *pipeline*, but a Data-Safety disclosure item for the Play-paperwork sub-project (out of scope here).

## NOT in this sub-project
fastlane/metadata, Play paperwork (privacy policy / Data Safety / content rating), IzzyOnDroid RFP filing, targetSdk 36 bump, `supply` automation, the agentchat-playsafe-exclusion verification (cleanup sub-project).
