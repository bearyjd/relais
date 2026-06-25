# Plan: ventouxlabs.com distribution identity (Play Store + IzzyOnDroid)

> **⚠️ Variant/appId structure is now owned by [`relais-play-channel-feature-matrix.plan.md`](./relais-play-channel-feature-matrix.plan.md)** (two flavor dimensions: `dist`×`policy` → `fullPlaysafe`/`fullOpen`/`degoogledOpen`; appId set per-variant via `androidComponents.onVariants`). This doc's `.fdroid`-suffix task details (Task 1, validation) are **superseded** by that scheme — read the channel-matrix plan first. What remains authoritative HERE: release signing from secrets, the release CI pipeline, Fastlane metadata, the IzzyOnDroid RFP, and the deep-link/Tasker ABI rename (D3).

## Summary
Re-base Relais's **install/distribution identity** on `ventouxlabs.com` for both build flavors, set up
**distinct** application IDs so the Play Store (`full`) and de-Googled (`degoogled`) builds can coexist,
align the public deep-link scheme + Tasker intent ABI to the new base, add **real release signing** from
CI secrets, and wire a release pipeline that produces the **Play AAB** (GitHub CI) and a **signed
de-Googled APK** published to GitHub Releases for **IzzyOnDroid** (NonFreeDep — the bundled litertlm AAR
is proprietary, so f-droid.org is not an option). Issues + CI + source stay on GitHub.

## User Story
As the Relais maintainer, I want the app's store/sideload identity rooted at `ventouxlabs.com` with a
clean Play + IzzyOnDroid release path, so that I can ship both the Play build and the de-Googled build
under a consistent, owned namespace without breaking the existing codebase.

## Problem → Solution
- **Now:** `applicationId == namespace == "cc.grepon.relais"`, shared by both flavors (can't coexist);
  release builds are signed with the **debug** keystore; CI only runs `assembleRelease` (no AAB, no
  release artifact publish); no Fastlane/store metadata; not listed on any F-Droid-ecosystem repo.
- **Desired:** `full` → `com.ventouxlabs.relais`, `degoogled` → `com.ventouxlabs.relais.fdroid`
  (side-by-side installable); deep-link scheme + Tasker actions rebranded to `com.ventouxlabs.relais`;
  release signing from GH secrets; a tag-triggered CI job emits the Play **AAB** + a signed de-Googled
  **APK** GitHub Release; Fastlane metadata + an IzzyOnDroid RFP. **No Kotlin source-package rename.**

## Metadata
- **Complexity**: Medium (identity/build/CI/release; no business-logic change)
- **Source PRD**: N/A (free-form request)
- **PRD Phase**: N/A
- **Estimated Files**: ~12 source/config + new CI job + new `fastlane/metadata/**`

---

## Decisions (locked with the user 2026-06-21)
| # | Decision | Resolution |
|---|----------|------------|
| D1 | Rename scope | **applicationId only** changes per flavor. **Keep `namespace = cc.grepon.relais`** and the entire Kotlin source package `cc.grepon.relais.*`. Rationale: `R`/`BuildConfig` are generated at the `namespace` package; moving namespace would force ~**93 `import …R`** + **9 `BuildConfig`** rewrites — the exact source-churn we're avoiding. Gradle fully supports `applicationId != namespace`. *(This refines the user's "applicationId + namespace" answer: changing the install identity is done via `applicationId`; `namespace` is an internal codegen detail kept as-is. If a namespace move is later desired, it's a separate task that also rewrites those ~100 imports.)* |
| D2 | Channel IDs | **SUPERSEDED by `relais-play-channel-feature-matrix.plan.md`.** The appId now follows the **channel/variant** (set via `androidComponents.onVariants`, not a flavor suffix), because the build gains a 2nd `policy` dimension (open/playsafe): `fullPlaysafe`→Play = `com.ventouxlabs.relais`; `fullOpen`→IzzyOnDroid = `com.ventouxlabs.relais.izzy`; `degoogledOpen`→GrapheneOS = `com.ventouxlabs.relais.degoogled`. All distinct → side-by-side installable. |
| D3 | Public ABI | **Align to ventouxlabs now.** Deep-link scheme `cc.grepon.relais://` → `com.ventouxlabs.relais://`; Tasker actions `cc.grepon.relais.action.INFER[_RESULT]` → `com.ventouxlabs.relais.action.INFER[_RESULT]`. Pre-release, so breaking existing NFC tags / Tasker configs is acceptable. |
| D4 | F-Droid / sideload | **IzzyOnDroid (NonFreeDep)** tracks the signed **`fullOpen`** APK (`com.ventouxlabs.relais.izzy`) from **GitHub Releases**. GrapheneOS users get the GMS-free **`degoogledOpen`** APK via **Obtainium / direct GitHub release** — no dedicated F-Droid repo, no GitLab required. |

---

## UX Design
Internal/distribution change — **no in-app UX transformation**. User-visible effects: the app's package
identity (Settings → App info), the ability to install Play + de-Googled side-by-side, and the deep-link
scheme used by NFC tags / automation. Store listings (Play, IzzyOnDroid) are new surfaces, not in-app UI.

---

## Mandatory Reading
| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/build.gradle.kts` | 32–87, 58–63 | `namespace`/`applicationId`/flavor block + the debug-keystore signing line to replace |
| P0 | `Android/src/app/src/main/AndroidManifest.xml` | 19, 99–105, 121–129, 237–285 | deprecated `package=`, deep-link scheme, FileProvider `${applicationId}.provider`, NFC scheme + Tasker action |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/ui/common/Utils.kt` | 170–176 | **hardcoded** `cc.grepon.relais.provider` authority — breaks per-flavor; must use `BuildConfig.APPLICATION_ID` |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/automation/RelaisIntentAbi.kt` | 29–33 | `ACTION_INFER` / `ACTION_INFER_RESULT` constants |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/nfc/NfcWorkflowParser.kt` | 23–35 | `SCHEME` constant + URI builder/parser |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/ui/navigation/GalleryNavGraph.kt` | 470–495 | deep-link prefix parsing (`startsWith("cc.grepon.relais://…")`) |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/customtasks/agentchat/IntentHandler.kt` | 310–335 | internal agentchat deep-link URIs |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/data/DownloadRepository.kt` | 296–308 | model-manager deep-link URIs |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/nfc/NfcWriteActivity.kt` | 60–70, 158–162 | tag-writer help text + written URI |
| P1 | `.github/workflows/build_android.yaml` | all | existing build + the GMS=0 dex gate to preserve in the release job |

## External Documentation
| Topic | Source | Key Takeaway |
|---|---|---|
| Distinct flavor ids | Android Gradle `applicationIdSuffix` | Suffix appends to the flavor's id; `namespace` is independent of `applicationId`. |
| Play App Signing | play.google.com/console docs | Upload an **AAB** signed with an **upload key**; Google holds the app-signing key. |
| IzzyOnDroid inclusion | gitlab.com/IzzyOnDroid/repo (RFP issue) | Tracks a GitHub/GitLab **release** APK + `fastlane/metadata/android/`; flags `NonFreeDep` for the proprietary litertlm AAR; APK must be signed with a **stable** key. |
| Fastlane metadata layout | `fastlane/metadata/android/en-US/{title,short_description,full_description,images/,changelogs/}.txt` | Both IzzyOnDroid and Play (via `fastlane supply`) read this tree. |

---

## Patterns to Mirror

### FLAVOR_BLOCK (current)
```kotlin
// SOURCE: Android/src/app/build.gradle.kts:71-87
flavorDimensions += "dist"
productFlavors {
  create("full") {
    dimension = "dist"
    buildConfigField("boolean", "SUPPORTS_AICORE", "true")
  }
  create("degoogled") {
    dimension = "dist"
    buildConfigField("boolean", "SUPPORTS_AICORE", "false")
  }
}
```

### HARDCODED_AUTHORITY (the bug per-flavor ids expose)
```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/ui/common/Utils.kt:170-176
// (string literal "cc.grepon.relais.provider" — will NOT match degoogled's
//  com.ventouxlabs.relais.fdroid.provider authority once ids diverge)
```

### TASKER_ABI_CONSTANTS
```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/automation/RelaisIntentAbi.kt:29-33
const val ACTION_INFER = "cc.grepon.relais.action.INFER"
const val ACTION_INFER_RESULT = "cc.grepon.relais.action.INFER_RESULT"
```

### NFC_SCHEME_CONSTANT
```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/nfc/NfcWorkflowParser.kt:30
const val SCHEME = "cc.grepon.relais"
```

### CI_DEX_GMS_GATE (must be preserved in the new release job)
```yaml
# SOURCE: .github/workflows/build_android.yaml (build_apk job)
# unzip degoogled APK dex → grep -Ec "Lcom/google/(android/(gms|apps/aicore)|mlkit)" must be 0
```

---

## Files to Change
| File | Action | Justification |
|---|---|---|
| `Android/src/app/build.gradle.kts` | UPDATE | `applicationId = "com.ventouxlabs.relais"`; `applicationIdSuffix=".fdroid"` on `degoogled`; keep `namespace`; add `signingConfigs.release` from env; point `release` build type at it |
| `Android/src/app/src/main/AndroidManifest.xml` | UPDATE | deep-link scheme ×2 + Tasker `<action>` name → ventouxlabs; (optional) drop deprecated `package=`. FileProvider line already uses `${applicationId}` — verify only |
| `.../ui/common/Utils.kt` | UPDATE | replace literal `cc.grepon.relais.provider` with `"${BuildConfig.APPLICATION_ID}.provider"` |
| `.../automation/RelaisIntentAbi.kt` | UPDATE | rebrand `ACTION_INFER` / `ACTION_INFER_RESULT` |
| `.../nfc/NfcWorkflowParser.kt` | UPDATE | `SCHEME = "com.ventouxlabs.relais"` |
| `.../nfc/NfcWriteActivity.kt` | UPDATE | help text + written-URI doc strings |
| `.../ui/navigation/GalleryNavGraph.kt` | UPDATE | deep-link prefix parse strings |
| `.../customtasks/agentchat/IntentHandler.kt` | UPDATE | internal agentchat deep-link URIs |
| `.../data/DownloadRepository.kt` | UPDATE | model-manager deep-link URIs |
| `.github/workflows/release.yaml` | CREATE | tag-triggered: `bundleFullRelease` (AAB) + `assembleDegoogledRelease` (APK), sign from secrets, GMS=0 gate on degoogled, publish APK + AAB to a GitHub Release |
| `fastlane/metadata/android/en-US/**` | CREATE | title / short+full description / changelogs / images — consumed by IzzyOnDroid (+ Play via `supply`) |
| `docs/distribution.md` | CREATE | the release runbook: keystore secrets, Play upload, IzzyOnDroid RFP, the GitLab-optional note |

## NOT Building
- **No Kotlin source-package rename** (`cc.grepon.relais.*` stays — D1).
- **No `namespace` change** (stays `cc.grepon.relais` — avoids ~100 R/BuildConfig import rewrites).
- **No f-droid.org / fdroiddata submission** (blocked by the proprietary litertlm AAR — D4).
- **No GitLab self-hosted F-Droid repo** (IzzyOnDroid chosen instead; GitLab mirror optional).
- **No automated Play `supply` upload** in v1 (manual first upload to establish Play App Signing; automate later).
- **No per-flavor split of the deep-link scheme/Tasker action** (shared branding string; see Risks).

---

## Step-by-Step Tasks

### Task 1 — Per-flavor application IDs (keep namespace)
- **ACTION**: In `build.gradle.kts`, set `applicationId = "com.ventouxlabs.relais"` (defaultConfig) and add `applicationIdSuffix = ".fdroid"` to the `degoogled` flavor. Leave `namespace = "cc.grepon.relais"` untouched.
- **IMPLEMENT**: `full` → `com.ventouxlabs.relais`; `degoogled` → `com.ventouxlabs.relais.fdroid`.
- **MIRROR**: FLAVOR_BLOCK.
- **GOTCHA**: Do NOT touch `namespace` — R/BuildConfig stay at `cc.grepon.relais`, so zero source-import churn. `applicationId != namespace` is valid and intended here.
- **VALIDATE**: `./gradlew :app:assembleFullDebug :app:assembleDegoogledDebug` green; confirm ids (Task validation cmds below).

### Task 2 — FileProvider authority must follow the per-flavor id
- **ACTION**: Replace the hardcoded `"cc.grepon.relais.provider"` in `Utils.kt` with `"${BuildConfig.APPLICATION_ID}.provider"`.
- **IMPLEMENT**: import `cc.grepon.relais.BuildConfig` (same package — no namespace move, so this import path is stable).
- **MIRROR**: HARDCODED_AUTHORITY; manifest `android:authorities="${applicationId}.provider"` already resolves per-flavor — leave it.
- **GOTCHA**: With the `.fdroid` suffix, degoogled's authority becomes `com.ventouxlabs.relais.fdroid.provider`; a hardcoded string would mismatch the manifest provider and crash FileProvider share/OCR (#13) on degoogled. This is the single highest-risk correctness item.
- **VALIDATE**: build both flavors; on-device share-an-image (#13) round-trip on the degoogled APK does not throw `IllegalArgumentException: Failed to find configured root`.

### Task 3 — Rebrand the deep-link scheme to `com.ventouxlabs.relais`
- **ACTION**: Replace scheme string `cc.grepon.relais` → `com.ventouxlabs.relais` in: manifest `<data android:scheme>` (MainActivity deep link + NFC), `NfcWorkflowParser.SCHEME`, `GalleryNavGraph` prefix checks, `IntentHandler` URIs, `DownloadRepository` URIs, `NfcWriteActivity` doc text.
- **MIRROR**: NFC_SCHEME_CONSTANT.
- **GOTCHA**: Keep the scheme identical across both flavors (branding, not id). `GalleryNavGraph` parses `startsWith("…://model/")` / `== "…://global_model_manager"` — update every literal, not just the constant.
- **VALIDATE**: `adb shell am start -a android.intent.action.VIEW -d "com.ventouxlabs.relais://global_model_manager"` opens the model manager; old `cc.grepon.relais://…` no longer resolves (expected).

### Task 4 — Rebrand the Tasker intent ABI
- **ACTION**: Update `ACTION_INFER` / `ACTION_INFER_RESULT` in `RelaisIntentAbi.kt` and the matching `<action android:name>` in the manifest to `com.ventouxlabs.relais.action.*`.
- **MIRROR**: TASKER_ABI_CONSTANTS.
- **GOTCHA**: The constant and the manifest filter must match exactly or the Tasker trampoline (#8) becomes unreachable. `TaskerIntentProbe` (androidTest) asserts this — update it too.
- **VALIDATE**: `TaskerIntentProbe` passes; manual `am broadcast -a com.ventouxlabs.relais.action.INFER …` reaches the trampoline.

### Task 5 — Real release signing from CI secrets
- **ACTION**: Add a `signingConfigs.create("release")` reading `storeFile`/`storePassword`/`keyAlias`/`keyPassword` from env (e.g. `RELEASE_STORE_FILE`, …) with a guard that falls back to debug signing locally when env is absent; point `buildTypes.release.signingConfig` at it.
- **IMPLEMENT**: keystore base64 in a GH Actions secret, decoded to a file in the workflow.
- **MIRROR**: replaces `signingConfig = signingConfigs.getByName("debug")` (build.gradle.kts:62).
- **GOTCHA**: IzzyOnDroid requires a **stable** signing key across releases (key rotation = users must reinstall). Generate once, store securely, never rotate. Keep local debug-signed builds working (env-absent fallback) so contributors aren't blocked.
- **VALIDATE**: CI release job produces a signed APK/AAB; `apksigner verify` passes; local `assembleFullDebug` still works without secrets.

### Task 6 — Release CI job (AAB + de-Googled APK)
- **ACTION**: New `.github/workflows/release.yaml` on `push: tags: ['v*']` (+ `workflow_dispatch`): decode keystore → `./gradlew bundleFullRelease assembleDegoogledRelease` → run the **GMS=0 dex gate** on the degoogled APK (copy from `build_android.yaml`) → `apksigner verify` → publish the degoogled **APK** and the Play **AAB** as assets on a GitHub Release for the tag.
- **MIRROR**: CI_DEX_GMS_GATE; reuse the temurin-21 + working-directory `./Android/src` setup.
- **GOTCHA**: `bundleFullRelease` (AAB, Play) and `assembleDegoogledRelease` (APK, IzzyOnDroid) are different artifacts — Play takes AAB, IzzyOnDroid takes APK. The degoogled APK is the IzzyOnDroid-tracked asset.
- **VALIDATE**: tag a `v*` on a branch → job emits both artifacts; GMS=0 gate still passes for degoogled.

### Task 7 — Fastlane metadata
- **ACTION**: Create `fastlane/metadata/android/en-US/` with `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/<versionCode>.txt`, and `images/` (icon + screenshots) consistent with `DESIGN.md` (amber-on-near-black, monospace, beacon mark).
- **GOTCHA**: IzzyOnDroid reads this tree from the repo root by default; keep it at repo root (not under `Android/`), or configure the RFP to the correct path.
- **VALIDATE**: metadata renders (lint with a fastlane/supply dry-run or IzzyOnDroid's checker).

### Task 8 — IzzyOnDroid RFP + distribution docs
- **ACTION**: Write `docs/distribution.md` (keystore secret setup, Play first-upload + App Signing enrolment, IzzyOnDroid RFP steps pointing at the GitHub Releases of the **degoogled** APK, the `NonFreeDep` declaration for litertlm, and the GitLab-optional note). File the IzzyOnDroid RFP issue.
- **GOTCHA**: IzzyOnDroid will flag `NonFreeDep` (litertlm) automatically — document it so it's expected, not a surprise rejection. Confirm the RFP points at the `com.ventouxlabs.relais.fdroid` APK, not the `full` build.
- **VALIDATE**: RFP accepted; IzzyOnDroid build picks up the release APK; appId shows `com.ventouxlabs.relais.fdroid`.

### Task 9 — Identity cleanup + docs
- **ACTION**: Optionally remove the deprecated `package="cc.grepon.relais"` manifest attribute (it's ignored — build log warns). Update `CLAUDE.md` / README to note the dual id + distribution channels.
- **GOTCHA**: Do NOT mass-rename `cc.grepon.relais` in source — only the identity strings in Tasks 1–4. AGPL headers / `grepon.cc` copyright are unaffected by distribution identity.
- **VALIDATE**: `git grep "cc.grepon.relais://"` and `"cc.grepon.relais.action"` return zero hits; `git grep -c "package cc.grepon.relais"` unchanged (391).

---

## Testing Strategy
### Unit / instrumented
| Test | Input | Expected | Edge? |
|---|---|---|---|
| `testFullDebugUnitTest` + `testDegoogledDebugUnitTest` | both flavors | green | — |
| `TaskerIntentProbe` (androidTest) | new action name | trampoline reached | ABI rename |
| `NfcWorkflowParser` unit tests | `com.ventouxlabs.relais://workflow/<id>` | parse/build round-trips | scheme rename |
| FileProvider share (#13 ShareImageProbe) on **degoogled** | content:// image | no "Failed to find configured root" | per-flavor authority |

### Edge Cases Checklist
- [ ] Both flavors installed side-by-side (distinct ids) — both launch; node control works independently
- [ ] degoogled FileProvider authority = `com.ventouxlabs.relais.fdroid.provider` (matches manifest)
- [ ] Old `cc.grepon.relais://` deep links / NFC tags no longer resolve (expected break — D3)
- [ ] degoogled release APK dex GMS-class count == 0
- [ ] Release APK/AAB signed with the release key (not debug)
- [ ] Local debug build still works with NO signing secrets present

---

## Validation Commands
### Static / build
```bash
# both flavors assemble
Android/src/gradlew -p Android/src :app:assembleFullDebug :app:assembleDegoogledDebug
```
EXPECT: BUILD SUCCESSFUL

### Verify the resolved application IDs per flavor
```bash
Android/src/gradlew -p Android/src :app:assembleFullDebug :app:assembleDegoogledDebug
"$ANDROID_HOME"/build-tools/35.0.0/aapt2 dump packagename \
  Android/src/app/build/outputs/apk/full/debug/*.apk
"$ANDROID_HOME"/build-tools/35.0.0/aapt2 dump packagename \
  Android/src/app/build/outputs/apk/degoogled/debug/*.apk
```
EXPECT: `com.ventouxlabs.relais` and `com.ventouxlabs.relais.fdroid`

### Unit tests
```bash
Android/src/gradlew -p Android/src :app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest
```
EXPECT: all pass

### Degoogled GMS=0 gate (release)
```bash
Android/src/gradlew -p Android/src :app:assembleDegoogledRelease
APK=$(find Android/src/app/build/outputs/apk/degoogled/release -name "*.apk" | head -1)
unzip -p "$APK" 'classes*.dex' | strings | grep -Ec "Lcom/google/(android/(gms|apps/aicore)|mlkit)"
```
EXPECT: `0`

### Identity-string sweep
```bash
git grep -n "cc.grepon.relais://" ; git grep -n "cc.grepon.relais.action"
```
EXPECT: zero matches (all rebranded)

### Manual on-device
- [ ] Install both flavors side-by-side; both launch, control panels independent
- [ ] degoogled: share an image (#13) → no FileProvider crash
- [ ] `am start -d "com.ventouxlabs.relais://global_model_manager"` opens model manager
- [ ] Tasker `am broadcast -a com.ventouxlabs.relais.action.INFER …` reaches the trampoline

---

## Acceptance Criteria
- [ ] `full`=`com.ventouxlabs.relais`, `degoogled`=`com.ventouxlabs.relais.fdroid`; `namespace` unchanged
- [ ] No Kotlin source-package rename; `R`/`BuildConfig` imports untouched
- [ ] Deep-link scheme + Tasker actions rebranded everywhere (sweep returns 0)
- [ ] FileProvider authority dynamic (`BuildConfig.APPLICATION_ID`) — degoogled share works
- [ ] Release signing from secrets; debug fallback locally; `apksigner verify` passes
- [ ] `release.yaml` emits Play AAB + signed degoogled APK; GMS=0 gate green
- [ ] Fastlane metadata present; IzzyOnDroid RFP filed against the degoogled APK
- [ ] Both flavor unit suites green; `TaskerIntentProbe` + NFC parser tests green

## Completion Checklist
- [ ] Patterns mirrored (flavor block, ABI constants, CI dex gate)
- [ ] No hardcoded authorities/schemes/actions left (use constants / BuildConfig)
- [ ] Distribution runbook documented (`docs/distribution.md`)
- [ ] No unnecessary scope (no namespace move, no source rename)

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Hardcoded `…provider` authority missed → degoogled share/OCR crash | Med | High | Task 2 + degoogled ShareImageProbe on-device |
| Side-by-side installs share the same deep-link scheme + Tasker action → OS ambiguity (chooser / double-deliver) | Low | Med | Document; most users run one flavor. If clean separation needed, suffix scheme/action per flavor (follow-up) |
| Signing key lost / rotated → IzzyOnDroid users must reinstall | Low | High | Generate once; back up the keystore + secrets securely; never rotate |
| IzzyOnDroid rejects over the proprietary litertlm AAR | Med | Med | It's a `NonFreeDep` (allowed, flagged), not a hard reject like f-droid.org — declare it in the RFP |
| Missed an identity literal (deep-link parse / agentchat) | Med | Med | `git grep` sweep in validation; covers all literals not just constants |
| Play first upload + App Signing enrolment friction | Med | Low | Manual first upload documented; automation deferred |

## Notes
- **D1 nuance is the load-bearing call:** changing `applicationId` (the install/store/IzzyOnDroid identity)
  while keeping `namespace`/source package is the minimal, low-risk change. The earlier "+namespace" phrasing
  is realized as applicationId-only because moving `namespace` would churn ~100 `R`/`BuildConfig` imports for
  zero user-visible benefit.
- **GitLab:** choosing IzzyOnDroid (D4) means GitLab isn't required — IzzyOnDroid tracks the GitHub Release.
  If you still want GitLab in the loop, the only change is pointing the IzzyOnDroid RFP `Repo`/`UpdateCheck`
  at a GitLab releases URL instead of GitHub; the build/sign/metadata tasks are identical.
- Independent from feature #16 (image-gen). Land this on its own branch; it touches identity/CI/release only.
