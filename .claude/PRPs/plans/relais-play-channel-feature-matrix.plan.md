# Plan: Build channels + feature gating — IzzyOnDroid (open) / Play (play-safe) / de-Googled (GMS-free)

## Summary
Add a second product-flavor dimension so Relais builds three shippable variants from two orthogonal
axes: **GMS** (`full` = has Google-Mobile-Services features / `degoogled` = GMS-free) × **policy**
(`open` = all features / `playsafe` = Play-policy-compliant subset). The load-bearing constraint:
**Play reviews the merged manifest**, so policy-risky permissions/components must be *physically absent*
from the `playsafe` manifest (source-set subtraction with `tools:node="remove"`), not merely gated by a
runtime `BuildConfig` flag. Dynamic-code features additionally move out of `src/main` so they aren't
even compiled into `playsafe`.

## Status / sequencing
- **Decisions LOCKED (2026-06-21):** Option A (two dimensions); keep the existing `dist` flavor names
  `full`/`degoogled` (avoids churning PR #67's `fullImplementation(llmedge)` + CI); GrapheneOS users are
  served by **sideloading** (IzzyOnDroid APK, Obtainium-from-GitHub, or a direct GitHub release) — the
  de-Googled build needs **no dedicated F-Droid repo**; Play `playsafe` v1 **drops** notification-triage
  + image-gen (revisable later with declarations/safeguards).
- **Sequence AFTER PR #67 (imagegen) merges** — that PR adds the `full`-flavor `:imagegen` service +
  `fullImplementation(llmedge)`; this plan layers the `policy` dimension on top without renaming `full`.
- **Supersedes** `ventouxlabs-distribution-identity.plan.md` **D2** (appId now follows *channel*, below);
  the rest of that plan (signing-from-secrets, release CI, Fastlane metadata, IzzyOnDroid RFP) still applies.

---

## Variant structure (Option A — existing `dist` names + new `policy` dimension)
```kotlin
flavorDimensions += listOf("dist", "policy")   // "dist" already exists (full/degoogled)
productFlavors {
  create("full")      { dimension = "dist" ;  buildConfigField("boolean","SUPPORTS_AICORE","true")  }
  create("degoogled") { dimension = "dist" ;  buildConfigField("boolean","SUPPORTS_AICORE","false") }
  create("open")      { dimension = "policy"; buildConfigField("boolean","POLICY_OPEN","true")  }
  create("playsafe")  { dimension = "policy"; buildConfigField("boolean","POLICY_OPEN","false") }
}

// Only THREE variants ship — drop the unused degoogled×playsafe combo:
androidComponents {
  beforeVariants(selector().withFlavor("dist" to "degoogled").withFlavor("policy" to "playsafe")) {
    it.enable = false
  }
  // Per-CHANNEL applicationId (suffix composition across 2 dims is awkward — set it explicitly):
  onVariants(selector().all()) { v ->
    val dist = v.productFlavors.first { it.first == "dist" }.second
    val policy = v.productFlavors.first { it.first == "policy" }.second
    v.applicationId = when {
      dist == "full" && policy == "playsafe" -> "com.ventouxlabs.relais"            // Play (canonical)
      dist == "full" && policy == "open"     -> "com.ventouxlabs.relais.izzy"       // IzzyOnDroid / sideload
      else /* degoogled, open */             -> "com.ventouxlabs.relais.degoogled"  // GrapheneOS / Obtainium / GH
    }
  }
}
```
`namespace` stays `cc.grepon.relais` (no source-package rename — see distribution-identity plan D1).

| Ships? | Variant | Channel | appId | GMS | Features |
|:--:|---|---|---|:--:|---|
| ✅ | `fullPlaysafe` | **Play Store** | `com.ventouxlabs.relais` | yes | play-safe subset |
| ✅ | `fullOpen` | **IzzyOnDroid** (+ Obtainium/GH) | `com.ventouxlabs.relais.izzy` | yes (NonFreeDep) | everything |
| ✅ | `degoogledOpen` | **GrapheneOS / direct** (Obtainium/GH) | `com.ventouxlabs.relais.degoogled` | no | open, minus GMS-bound |
| ❌ | `degoogledPlaysafe` | — | — | — | filtered out (`beforeVariants` disable) |

All three appIds are distinct → installable side-by-side.

---

## Gating mechanism (both layers required)

### Layer 1 — manifest subtraction (what Play actually checks) — `src/playsafe/AndroidManifest.xml` (NEW)
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
  <uses-permission android:name="android.permission.USE_EXACT_ALARM" tools:node="remove"/>
  <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" tools:node="remove"/>
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" tools:node="remove"/>
  <uses-permission android:name="android.permission.READ_CALENDAR" tools:node="remove"/>
  <application>
    <service  android:name=".triage.RelaisNotificationListenerService" tools:node="remove"/>
    <activity android:name=".triage.TriageControlActivity"             tools:node="remove"/>
    <receiver android:name=".RelaisWatchdogReceiver"                    tools:node="remove"/>
    <!-- + any agentchat dynamic-skill WebView activity declared in src/main -->
  </application>
</manifest>
```
The risky permissions/components REMAIN declared in `src/main` (so `fullOpen`/`degoogledOpen` keep them);
`playsafe` subtracts them. A `BuildConfig` flag alone does NOT remove a manifest permission — this layer is
what keeps Play happy.

### Layer 2 — code/UI gating + source-set exclusion
- `BuildConfig.POLICY_OPEN` guards entry points: control-panel links (TRIAGE ›, image-gen, watchdog
  toggle, battery-exemption prompt), and the exact-alarm scheduling call (which would `SecurityException`
  in `playsafe` without the perm) → use a WorkManager/inexact fallback when `!POLICY_OPEN`.
- **Dynamic-code feature must not be compiled into `playsafe` at all:** move the agentchat
  **URL→WebView skill loader** out of `src/main` into **`src/open/`** (a `policy=open` source set). Then it
  physically isn't in the `playsafe` APK, satisfying the dynamic-code policy at the strongest level.
  Anything that `import`s a removed component goes to `src/open` too, so `playsafe` compiles clean.

---

## Feature matrix
| Feature | Perm / component footprint | fullOpen (Izzy) | fullPlaysafe (Play) | degoogledOpen (GOS) | Play rationale |
|---|---|:--:|:--:|:--:|---|
| LAN OpenAI API node (FGS) | `FOREGROUND_SERVICE`,`*_DATA_SYNC`,`INTERNET`,`RelaisNodeService` | ✅ | ✅ | ✅ | Core; needs FGS declaration (dataSync; Android-15 6h/day cap) |
| Embeddings / RAG / tools / batch+webhooks | `INTERNET` | ✅ | ✅ | ✅ | Webhooks already SSRF-guarded |
| QS tile + widget | tile/widget services | ✅ | ✅ | ✅ | Low risk |
| NFC workflow | `NFC` | ✅ | ✅ | ✅ | Low risk |
| Tasker/Automate intent ABI | exported activity + action | ✅ | ✅ | ✅ | App-interop API |
| Boot auto-start (opt-in) | `RECEIVE_BOOT_COMPLETED` | ✅ | ✅ | ✅ | Keep opt-in |
| **Watchdog (exact-alarm)** | `USE_EXACT_ALARM`,`RelaisWatchdogReceiver` | ✅ | ❌→ WorkManager fallback | ✅ | `USE_EXACT_ALARM` restricted to clock/calendar apps |
| **Battery-opt exemption prompt** | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ✅ | ❌ (user can still grant manually) | ✅ | Restricted allowlist; common rejection |
| **Notification triage** | `BIND_NOTIFICATION_LISTENER_SERVICE` + listener/activity | ✅ | ❌ (v1) | ✅ | Allowed only w/ declaration + core-feature case |
| **agentchat URL→WebView skills** | agentchat activity/WebView (→ `src/open`) | ✅ | ❌ | ✅ | Fetched-code execution → Device/Network Abuse; known SSRF/injection sink |
| MCP client / mobileactions | network/tools | ✅ | ⚠️ keep, audit | ✅ | Audit device-action surface |
| Audio input (multimodal) | `RECORD_AUDIO` | ✅ | ⚠️ keep w/ disclosure | ✅ | Core multimodal; prominent disclosure + privacy policy |
| Camera | `CAMERA` | ✅ | ⚠️ audit/remove if vestigial | ✅ | Likely inherited from Gallery |
| Calendar agent skill | `READ_CALENDAR` | ✅ | ❌ | ✅ | Calendar-data policy friction for a niche skill |
| Share-image OCR (#13) | ML Kit (GMS) | ✅ | ✅ | ❌ stub | GMS allowed on Play |
| AICore / Gemini-Nano | GMS | ✅ | ✅ | ❌ stub | GMS allowed on Play |
| **Image generation (#16)** | llmedge (GMS) + AI content | ✅ | ❌ (v1) | ❌ stub (GMS-bound) | AI-Generated-Content policy: needs safeguards + in-app reporting first |

Legend: ✅ included · ❌ removed (manifest + code) · ⚠️ keep with disclosure/audit.

---

## Step-by-step tasks
### Task 1 — Add the `policy` dimension + per-variant appId + variant filter
- **ACTION**: `build.gradle.kts`: add `"policy"` to `flavorDimensions`; create `open`/`playsafe` flavors with `POLICY_OPEN` buildConfigField; add the `androidComponents { beforeVariants … ; onVariants … }` block above.
- **GOTCHA**: assign `applicationId` in `onVariants` (suffix composition across two dims double-suffixes the degoogled+open case). Keep `namespace` unchanged.
- **VALIDATE**: `./gradlew :app:assembleFullOpenDebug :app:assembleFullPlaysafeDebug :app:assembleDegoogledOpenDebug` all build; `degoogledPlaysafe` tasks don't exist.

### Task 2 — `src/playsafe/AndroidManifest.xml` removal manifest
- **ACTION**: create it with the `tools:node="remove"` block above. Risky perms/components STAY in `src/main`.
- **GOTCHA**: the `<service>/<activity>/<receiver>` removals must match the exact `android:name` in `src/main`.
- **VALIDATE**: `aapt dump permissions` on the `fullPlaysafe` APK shows none of: `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `READ_CALENDAR`, `BIND_NOTIFICATION_LISTENER_SERVICE`.

### Task 3 — Move dynamic-code + removed-component imports to `src/open`
- **ACTION**: relocate the agentchat URL→WebView skill loader (+ anything importing the triage listener / watchdog receiver / exact-alarm scheduler classes) from `src/main` into `src/open/`.
- **GOTCHA**: `src/open` is compiled into `fullOpen` + `degoogledOpen` only — NOT `fullPlaysafe`. Verify no `src/main` code references the moved classes (would break the `playsafe` compile). Add thin `POLICY_OPEN`-guarded shims in `src/main` where call sites remain.
- **VALIDATE**: `:app:compileFullPlaysafeDebugKotlin` green with the dynamic-skill classes absent.

### Task 4 — Watchdog fallback for `playsafe`
- **ACTION**: gate the exact-alarm scheduling on `BuildConfig.POLICY_OPEN`; provide a WorkManager periodic/inexact revival path when false.
- **VALIDATE**: `fullPlaysafe` node recovers after process death without `USE_EXACT_ALARM`.

### Task 5 — Entry-point gating
- **ACTION**: wrap control-panel links + feature toggles for triage / image-gen / watchdog / battery-prompt / calendar in `if (BuildConfig.POLICY_OPEN)`.
- **VALIDATE**: `fullPlaysafe` UI exposes none of the removed features; `fullOpen` exposes all.

### Task 6 — CI: build all three release variants + gates
- **ACTION**: update `.github/workflows` to build `bundleFullPlaysafeRelease` (AAB→Play), `assembleFullOpenRelease` (APK→IzzyOnDroid), `assembleDegoogledOpenRelease` (APK→GOS/Obtainium). Update unit-test tasks to the new variant names (`testFullOpenDebugUnitTest` etc.). Keep the GMS=0 dex gate on the **degoogledOpen** APK. **Add a new gate:** `aapt dump permissions` on the `fullPlaysafe` APK must contain none of the restricted perms (Task 2 list) — fail the build otherwise.
- **VALIDATE**: CI green; both gates enforced.

### Task 7 — Per-channel store metadata (ties into the distribution-identity plan)
- **ACTION**: Fastlane metadata per channel; IzzyOnDroid RFP points at the `fullOpen` (`…​.izzy`) release APK; Obtainium/GH instructions for `degoogledOpen`.
- **VALIDATE**: listings render; IzzyOnDroid tracks the correct APK/appId.

---

## Validation commands
```bash
# three variants assemble; the 4th doesn't exist
Android/src/gradlew -p Android/src :app:assembleFullOpenDebug :app:assembleFullPlaysafeDebug :app:assembleDegoogledOpenDebug
# play-safe permission gate (must print nothing)
APK=$(find Android/src/app/build/outputs/apk/fullPlaysafe -name "*.apk" | head -1)
"$ANDROID_HOME"/build-tools/35.0.0/aapt dump permissions "$APK" \
  | grep -E "USE_EXACT_ALARM|REQUEST_IGNORE_BATTERY_OPTIMIZATIONS|READ_CALENDAR|BIND_NOTIFICATION_LISTENER_SERVICE"
# degoogled GMS=0 (existing gate, new variant name)
Android/src/gradlew -p Android/src :app:assembleDegoogledOpenRelease
# unit suites (new variant names)
Android/src/gradlew -p Android/src :app:testFullOpenDebugUnitTest :app:testFullPlaysafeDebugUnitTest :app:testDegoogledOpenDebugUnitTest
```
EXPECT: 3 variants build; permission grep empty; GMS=0; suites green.

## NOT building
- No `nogms+playsafe` variant (filtered).  No source-package/namespace rename.  No f-droid.org/fdroiddata.
- No notification-triage or image-gen in the Play build for v1 (revisit with declarations/safeguards).
- No dedicated self-hosted F-Droid repo (GOS uses sideload/Obtainium/GH).

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Flag-gate instead of manifest-remove → Play still flags perm | Med | High | Task 2 + the CI `aapt dump permissions` gate |
| `playsafe` compiles a moved/removed class → build break or dead code | Med | Med | Task 3 `src/open` relocation + per-variant compile in CI |
| Variant-name churn breaks CI / local build muscle memory | Med | Low | Update CI in Task 6; document the 3 variant names |
| Exact-alarm removal weakens `playsafe` node reliability | Med | Med | WorkManager fallback (Task 4); accept slightly looser revival on Play |
| GOS users dislike GMS in `fullOpen` | Low | Low | `degoogledOpen` remains the GMS-free option via Obtainium/GH |

## Notes
- The two axes are independent; modeling them as two dimensions keeps each feature's gating in one place
  and shares the GMS code once (no Play↔Izzy duplication).
- The `aapt dump permissions` CI gate is the cheap guardrail (mirrors the degoogled GMS=0 dex gate) that
  keeps the Play build compliant as features evolve.
