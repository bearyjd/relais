# Relais — Session Handoff (2026-06-20)

Point-in-time "resume here" for the Relais on-device LLM node. Durable knowledge lives in the agent
memory files (see **Memory pointers**); this is the session summary + next steps. **Newest section is
at the top.**

---

## 2026-07-07 (LATEST) — **AUDIT.md control-panel redesign SHIPPED (`PR #126`) + verified on-device; CI auto-merge race found+fixed (`#127`); E1 issues reconciled (17 closed); image-gen deadlock filed UPSTREAM (`Aatricks/llmedge#30`).** `main` @ `2907d1c`+, ZERO open PRs. START HERE.

### ⏩ RESUME-HERE — the redesign epic is fully closed out; remaining backlog is exactly E3 distribution (JD-gated) + E2/#69 (upstream/driver-gated) + E4 engine follow-ups
- **Control-panel redesign SHIPPED — `PR #126` (`8578b41`).** JD answered all six AUDIT.md §6 questions (all recommended options: Configure=second activity · key masked by default · muted OFFLINE endpoint preview · CANCEL keeps partial downloads · full thermal+progress plumbing) — annotated in AUDIT.md. Multi-agent run: executor build (TDD, pure `RelaisControlPanelState.kt` layer + `RelaisConfigureActivity` + `RelaisNodeProgress`) → parallel security review (**SHIP**) + code review (**APPROVE-WITH-NITS**) → fixes → delta re-review (**PASS**) → on-device state-walk on rango (OFFLINE / SHOW-HIDE / Configure / STARTING "loading engine…" / LIVE hero endpoint — all match spec) → PR. 764 JVM tests × 2 flavors. **Two real bugs fixed beyond spec (review finds):** failed-init honesty (perpetual "resolving model…" → honest OFFLINE + retry copy) and the **pre-existing failed-state START/watchdog-revive NO-OP** (`runStartup` only ran in `onCreate`; now a CAS-guarded `dispatchStartupIfNeeded()` also fires from `onStartCommand`). Memory: [[relais-control-panel-redesign-shipped]].
- **CI lesson (durable): the #93 no-op twin was RACY under auto-merge.** #126 (a mixed Android+docs PR) merged gated by the 3-second no-op checks while the real build was still running (real build passed post-hoc — no damage). **Fixed in `PR #127` (`2907d1c`):** single workflow, no trigger-level `paths:` filter, a `changes` diff job + job-level `if` skips — skipped check runs satisfy protection, exactly ONE check run per required name ⇒ nothing to race. Rule: **never two workflows reporting the same required check name when auto-merge is in play.** (Also learned: `workflow_dispatch` runs do NOT attach checks to a PR — unstick with `gh pr update-branch`.)
- **Issue-tracker reconciliation:** a parallel session had filed #96–#125 (plan: `docs/prp/issue-plan-2026-07-07.md`) treating AUDIT.md as unimplemented. Closed with per-issue evidence comments: **E1 epic #96, stories #99–#101, tasks #104–#116** (17 total).
- **Image-gen deadlock (E2/#69) moved:** #117 closed from existing evidence (verdict formalized: **G5/PowerVR-DXT-specific** — Mali G3 317s/311s + G4 5/5 both work); #118 closed — **filed upstream: `Aatricks/llmedge#30`** (full evidence package: ruled-out matrix incl. shader-disable env experiments, all-threads-S/0%-CPU proof, device matrix, ExecuTorch #17299 + Eden #398 corroboration, CPU-fallback feature ask). **Retest lever:** the Pixel 10 shipped PowerVR driver v1.602.400; the Android 16 QPR3 beta bumps it to v1.634.2906 (Vulkan 1.4). rango is still on build `2026062801` (old driver era) — when GrapheneOS ships the new driver, ONE session covers the #69 retest + #119 (SD-1.5 check).

### 🖥️ Device / tree state
- **rango:** redesign build installed (`118db12` pre-merge sha, content-identical to merged `8578b41`), E2B + embedder staged, **node STOPPED**. ⚠️ A secure lockscreen is now active on this device — screencap/tap sessions need JD to unlock first.
- **Working tree (NOT mine, left alone):** modified `CLAUDE.md`/`.gitignore`/`mcp/README.md`/`model_allowlists/README.md` + untracked `.agent_native/` belong to a concurrent session; the long-standing untracked `.claude/` plans + `run-fable.sh` unchanged.

### 🔜 NEXT (ranked — matches the open-issue backlog exactly)
1. **JD:** signing key + 4 GH secrets → `release.yaml` dry-run (unchanged blocker; gates #122/#123).
2. **E3 distribution (#97):** #120 privacy policy (device-free, doable any time) → #121 Data Safety mapping → #122 Play submission → #123 IzzyOnDroid RFP. fastlane metadata is fully ready (copy #86 + screenshots #92 + icon #94).
3. **E4 (#98):** #124 NPU/AICore deferred gate (needs rango unlocked) · #125 mid-decode stop via native API (read `docs/litertlm-native-api.md` first).
4. **E2 (#69):** wait for upstream response on llmedge#30 / the GrapheneOS driver bump, then retest + #119 in one session.

### 🧠 Memory: [[relais-control-panel-redesign-shipped]] (new), [[relais-imagegen-state]] (upstream filing + retest lever), [[relais-distribution-state]], [[relais-experiments-ondevice-verified]].

---

## 2026-07-06 — **Experiments on-device verification PASSED on rango/G5 (audio-encoder question CLOSED); fastlane images COMPLETE (`#92` screenshots + `#94` icon); CI docs-PR deadlock found+fixed (`#93`); control-panel redesign audit MERGED (`AUDIT.md`, `#95`).** `main` @ `4d19eb0`, ZERO open PRs.

### ⏩ RESUME-HERE — the two deferred-to-device items from the last session are DONE; next = Play paperwork / Izzy RFP / JD's key, or implement AUDIT.md (needs 6 answers)
- **On-device verification (#87–#90) — ALL GREEN on rango** (Pixel 10 Pro Fold / G5 / GrapheneOS): built `degoogledOpenDebug` from `32a2c80`, upgraded the existing install in place (staged E2B preserved), node LIVE in ~25 s, driven via `adb forward tcp:18080 tcp:8080` + the gstack headless browser:
  - **`/experiments` (#87):** 401 unauth / 200 with bearer; CSP intact (nonce script-src, `img-src data:`); JS runs in a real browser — Verify Link → LINKED with the full 7-model list.
  - **Audio (#88) — the open question is CLOSED: Gemma 4 E2B-it SHIPS a working audio encoder** (litertlm loads `static_audio_encoder`+`audio_adapter` XNNPack caches at init). Real speech (Harvard sentences, 16 kHz mono WAV) → **accurate transcription** via curl AND the browser module. A sine tone → "cannot transcribe", but `/generate` describe-audio proves the model heard it ("a single, continuous, low-frequency tone").
  - **Vision (#89):** multipart upload (`-F file` + `-F prompt`) → exact description of a synthetic test image; browser module too.
  - **Image-gen (#90→#16):** honest **501** on degoogled; the module surfaces "image generation not available on this build" gracefully. A live 200 render still needs a `full` build on comet/G4 (only remaining unexercised path — optional).
  - Text chat / `/v1/models` / `/health` all good. Memory: [[relais-experiments-ondevice-verified]].
- **Fastlane `images/` COMPLETE — the store-metadata sub-project is fully DONE** (copy #86 + images this session):
  - **`PR #92` (`23e0a96`):** 3 phone screenshots (LIVE panel / model-selector sheet / actions view), captured on rango with a demo-mode status bar. ⚠️ They show that rig's **LAN-only debug access key** — retake if that ever matters for the store set.
  - **`PR #94` (`345401f`):** `images/icon.png` 512×512 — rsvg-convert render of the CANONICAL sources (`relais_icon_foreground.xml` amber relay glyph over `#0B0B0D` from `ic_launcher.xml`), launcher-visible 72/108 dp crop, pixel-verified palette-exact.
- **CI deadlock found + fixed (`PR #93`, `15b41a9`):** `build_android.yaml` is path-filtered to `Android/**`, but its job names (`Build Android APK`, `JVM unit tests`) are REQUIRED checks — so the first docs-only PR (#92) stalled at "Expected" with auto-merge armed (the prior session's "no check can deadlock a PR" claim missed path filters). **Fix:** `build_android_noop.yaml` — a `paths-ignore: Android/**` twin reporting the SAME check names as 3-second no-ops (GitHub's documented pattern for skipped-but-required checks). Mixed PRs run both; protection takes the latest run per name and the real build always finishes last. **Proven organically on #94** (the no-ops satisfied protection unassisted). Also: repo `allow_auto_merge` was OFF → **enabled** (`gh api -X PATCH repos/bearyjd/relais -f allow_auto_merge=true`). Gotcha learned: a `workflow_dispatch` run does NOT attach its checks to a PR — to unblock a stuck PR use `gh pr update-branch <n>` (fresh pull_request event), not a manual dispatch.
- **Control-panel redesign audit → `AUDIT.md` at repo root (MERGED, `PR #95` → `4d19eb0`), spec-only, no code touched.** Buttonology-first per JD's Fable prompt: START/STOP sits below-the-fold behind once-ever config (screenshot-confirmed), both buttons always visible at equal weight, LIVE screen doesn't rank endpoint/key above controls, STARTING hides download progress, thermal shed invisible on-panel. Proposes a frequency-ranked home (status → hero endpoint → key → ONE state-appropriate button) + a Configure surface, per-state specs (OFFLINE/STARTING/LIVE/thermal), motion spec, and a DESIGN.md amendment list. **⚠️ Gate: §6 has 6 OPEN QUESTIONS needing JD** (Configure form: second activity vs sheet; key masking; offline endpoint display; cancel-during-download semantics; thermal + download-progress plumbing scope) before Opus implements.

### 🖥️ Device / tree state
- **rango:** upgraded `com.ventouxlabs.relais.degoogled` (vc 33 from `32a2c80`) installed; E2B + embedder staged; **node STOPPED**; demo mode exited; tmp files cleaned; adb forward removed. Restartable via the control ABI (key: read the ACCESS KEY chip via screencap).
- **Working tree:** `.gitignore` modified (+`.gstack/`, added by the browse skill — harmless, commit or drop at will); the long-standing untracked `.claude/` plans + `run-fable.sh` unchanged.

### 🔜 NEXT (ranked)
1. **JD:** signing key + 4 GH secrets → dry-run `release.yaml` via workflow_dispatch (unchanged blocker).
2. **Play paperwork** (privacy-policy URL, Data Safety, content rating, FGS disclosure) → **IzzyOnDroid RFP** — fastlane metadata is now fully ready for both.
3. **AUDIT.md:** JD answers the 6 open questions (§6) → hand to an executor for the Compose implementation (+ apply the DESIGN.md diff in §7).
4. Optional: live image-gen 200 through the Experiments canvas on comet/G4 (`full` build) — the one module path not exercised end-to-end.

### 🧠 Memory: [[relais-experiments-ondevice-verified]] (new), [[relais-distribution-state]] (fastlane COMPLETE; CI-fix noted), [[relais-imagegen-state]].

---

## 2026-07-05 — **Fable experiments UI pipeline COMPLETE — all 4 features merged (`PR #87`→`#90`), `main` @ `32a2c80`, ZERO open PRs.**

### ⏩ RESUME-HERE — the run-fable.sh 4-feature queue is fully built + merged; remaining work is on-device verification (needs a device)
- **What this was:** `run-fable.sh` (uncommitted, local-only at repo root) defines an autonomous **Fable-5** loop (`claude -p … --model claude-fable-5 --dangerously-skip-permissions`) queueing 4 UI features one-at-a-time. This session they were **hand-driven through the orchestrator** (not the unattended loop) under a user-chosen **full-auto-merge** policy: each feature = branch → Opus-executor build (TDD) → independent full-suite gate + adversarial security-review → squash-merge on green CI. All four are on `main`:
  - **#1 `PR #87` (`98b9ff7`):** `GET /experiments` — bearer-gated web control panel (the shell the other modules dock into), one nonce-gated inline script, strict CSP (`script-src 'nonce-…'`, `connect-src 'self'`).
  - **#2 `PR #88` (`c341f64`):** real `POST /v1/audio/transcriptions` (multipart) bridging to the existing resident-engine audio path. Introduced **`RelaisHttpIo.kt`** = a **byte-safe `HttpRequestReader`** (the shared body reader was UTF-8-char → refactored to raw bytes so binary uploads survive; JSON endpoints unchanged — `readBody` is now `String(bytes, UTF-8)`) + a **bounded multipart parser**. Security review caught + fixed 2 parse-amplification DoS vectors (offset-scan bound to `MAX_PARTS+1`; boundary length ≤70).
  - **#3 `PR #89` (`42c77cb`):** multipart image upload on `POST /v1/chat/completions` via a thin `buildMultipartChatRequest` adapter → synthetic OpenAI vision JSON → the SAME `handleOpenAi` (JSON path byte-for-byte unchanged; org.json escaping + fixed field allowlist = no injection). Security LOW; fixed 1 memory-amplification MEDIUM (decoded multipart image capped at 12MB → 413).
  - **#4 `PR #90` (`32a2c80`):** image-gen canvas module driving the existing `/v1/images/generations` (#16); adds exactly `img-src data:` to the `/experiments` CSP so the generated PNG renders (nothing else widened).
- **Key new infra:** `RelaisHttpIo.kt` (`HttpRequestReader` + `parseMultipartFormData`/`parseMultipartBoundary` + `buildMultipartChatRequest`) — a byte-safe reader + multipart parser now shared by the audio and vision paths. All pure / JVM-unit-tested (suite grew ~700 → 722).
- **✅ #90 merge caveat — RESOLVED (branch protection added):** #90 originally merged via `gh pr merge --auto` while its CI was still *pending*, because `main` had **no required-status-check gate** (#87–#89 were watched to green *before* merge). The post-merge `main` integration build for `32a2c80` **did go green** (Build APK + JVM tests + secret-scan + license-lint). **`main` is now protected** (`gh api PUT repos/bearyjd/relais/branches/main/protection`): required checks = `Build Android APK`, `JVM unit tests`, `gitleaks`, `trufflehog`, `headers`; `enforce_admins: true` (applies to admin merges too); `required_linear_history: true`; force-push + branch-deletion of `main` blocked; **no** required PR reviews (solo self-merge preserved); `strict: false` (no forced rebases). **Verified it blocks:** a throwaway PR showed `mergeStateStatus=BLOCKED` and a plain `gh pr merge --squash` was refused ("the base branch policy prohibits the merge"). **Going-forward merge pattern:** `gh pr merge <n> --squash --auto --delete-branch` (arms auto-merge, lands on green). **Emergency override:** temporarily lift `enforce_admins` (or the whole protection), merge, re-enable. (All 5 required checks run on every `pull_request → main`, so none can deadlock a PR.)
- **⚠️ Standing auth:** `run-fable.sh` uses `--dangerously-skip-permissions` and is prompted to push+merge unattended. The queue is now **complete** (all 4 built), so the loop has nothing left to build — but the script is still on disk (untracked).

### 🔜 NEXT — on-device verification (all deferred, need a device; the JVM suite covers wiring/escaping/state-handling only, NOT model-output quality)
- Open `https://<node-ip>:8443/experiments` in a LAN browser (needs the bearer key) and exercise each module on a real device: **audio** transcription (needs a resident model that actually ships an **audio encoder** — the open question from #2), **vision** (image→text via `visionBackend=GPU`), and **image generation** (needs a **Vulkan `full`** build for a live 200 render; `degoogled` → honest 501).
- Distribution track (independent, from the #86 session below) still owed: `fastlane/.../images/` screenshots → Play paperwork → IzzyOnDroid RFP → JD's signing key/secrets.

### 🧠 Memory: [[relais-distribution-state]], [[relais-imagegen-state]] (#16 image endpoint is what feature #4 drives).

---

## 2026-07-05 — **Fable copy/design deliverables + dashboard palette fix → `PR #86` MERGED (`main` @ `525f061`, ZERO open PRs)**. Ticks off the "fastlane metadata" distribution sub-project (copy done; images pending). START HERE.

### ⏩ RESUME-HERE — store listings + brand copy + a DESIGN.md palette fix landed; next store step is screenshots (needs a device)
- **What this session was:** ran the five "Fable-shaped" (prose/design, not systems-Kotlin) pieces via **Fable-model subagents**, all now squash-merged in **PR #86** (`docs(relais): store listings, brand voice, changelog + dashboard palette fix (#86)`; CI all green — Build APK 10m9s / JVM tests 6m46s / gitleaks+trufflehog+headers; branch deleted). Files on `main` @ `525f061`:
  - `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt` + `changelogs/33.txt` — Play + IzzyOnDroid listing copy (appId `com.ventouxlabs.relais`, publisher **VentouxLabs**); all within Play char limits (27/77/2292/438). This is **sub-project #1 (fastlane metadata) copy-half DONE**; the `images/` half (icon + phone screenshots) is the next store step and **needs a device**.
  - `docs/brand-voice.md` — positioning, pillars, taglines (primary locked: "Your own relay station for AI."), name story, boilerplate ×3, voice rules.
  - `CHANGELOG.md` — Keep a Changelog; first `[1.0.15]` entry (versionCode 33) seeded from #81–#85; reusable release-notes template.
  - `docs/dashboard-copy.md` — microcopy + DESIGN.md visual spec for the auth-gated `GET /` dashboard (feature-09).
  - `README.md` — **prose-only** polish (all code blocks / endpoints table / links / facts byte-identical, verified via diff).
- **Palette fix (real DESIGN.md violation, found while writing the dashboard spec):** the already-merged `RelaisDashboard.kt` hue-coded status with off-palette `#FFCC44` (warn) and Stop-only `#FF5247` (red) on the error/shed counters and 4xx/5xx request-log rows. DESIGN.md reserves amber as the only accent + `#FF5247` for Stop (no Stop control here). **Fixed:** salience by brightness — non-2xx / non-zero counters stay paper-bright, 2xx / zero go muted; dropped the dead `.stop`/`.warn` classes + `--stop` var; `RelaisDashboardTest` #6 rewritten to assert the new behavior + palette guards. `testFullOpenDebugUnitTest` green.
- **⚠️ Git state for the next session:** local `main` is **1 commit behind** `origin/main` (the #86 merge) — `git checkout main && git pull` before branching. The working tree is currently on **`feature/fable-experiments-ui`** (unrelated Fable-model experiments WIP: untracked `RelaisExperiments.kt`/`RelaisExperimentsTest.kt`/`run-fable.sh`, modified `RelaisHttpServer.kt`) — left untouched. Switch back there to resume the fable experiments.
- **🔜 NEXT (distribution, unchanged order):** capture `fastlane/.../images/` (icon + screenshots, device) → Play paperwork (privacy policy / Data Safety / content rating / FGS disclosure) → IzzyOnDroid RFP → cleanup. Plus JD still owes the signing key + 4 GH secrets from the #85 pipeline (below). Ranked list in [[relais-distribution-state]].

### 🧠 Memory: [[relais-distribution-state]] (store-listing copy now drafted + merged), [[relais-imagegen-state]] (#16 done).

---

## 2026-06-30 — **DISTRIBUTION workstream started → release pipeline IMPLEMENTED + MERGED (`PR #85`, `main` @ `93e014c`, ZERO open PRs)** (signing + `release.yaml` + runbook). Goal: publish on **Play + IzzyOnDroid** (NOT f-droid main).

### ⏩ RESUME-HERE — release pipeline built + verified + PR'd (#85); JD owes the signing key + secrets, then merge
- **What the "de-Google/rebrand UI" bigger-bet actually is:** a **DISTRIBUTION** goal (get on the stores), **not** a visual rebrand — palette is already Relais, app name "Relais", Firebase gone. Brainstorming (superpowers) drilled through "rebrand → upstream-friendly thin rebrand → **publish on Play + fdroid/izzy, nothing removed**". See [[relais-distribution-state]].
- **F-Droid main is OUT** (structural): proprietary `litertlm`/`litert` prebuilt native blobs in every flavor → f-droid.org can't accept any variant. **IzzyOnDroid** (NonFreeDep) is the F-Droid-ecosystem home. Decided + confirmed.
- **Release pipeline IMPLEMENTED this session** (sub-project #1 of 5; reconciles Tasks 5-6 of `ventouxlabs-distribution-identity.plan.md` to the current 3-variant scheme). **PR #85 MERGED** (squash → `main` @ `93e014c`; branch deleted; CI was all green — Build APK 8m0s / JVM tests 7m30s / gitleaks+trufflehog+headers; post-merge push:main integration build running) — 3 files:
  - `Android/src/app/build.gradle.kts` (+25/−1): env-based `signingConfigs.release` (`RELEASE_*`) with **debug fallback** when secrets absent. **VERIFIED:** `assembleFullOpenRelease` (no secrets) → BUILD SUCCESSFUL, APK signed `CN=Android Debug` (fallback works; release key takes over once secrets are set).
  - `.github/workflows/release.yaml` (new): `on push tags v* + workflow_dispatch` → decode `RELEASE_KEYSTORE_BASE64` → `bundleFullPlaysafeRelease` (AAB) + `assemble{FullPlaysafe[gate-only],FullOpen,DegoogledOpen}Release` (APKs) → re-run GMS=0/playsafe-perm/16KB gates + `apksigner verify` → **draft** GitHub Release (tag) / upload-artifact (dispatch).
  - `docs/distribution.md` (new): keytool keystore-gen + 4 GitHub secrets + release/Play-first-upload runbook. Spec: `.claude/PRPs/plans/relais-release-pipeline.plan.md`.
- **Signing decision (locked):** ONE never-rotate key, **JD generates+owns it** (keytool cmd in the doc), Play App Signing (key=upload key), manual Play first-upload.
- **🔜 NEXT (JD's move, then mine):** (1) JD: run the keytool cmd, add the 4 GH secrets (`RELEASE_KEYSTORE_BASE64`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`=relais/`RELEASE_KEY_PASSWORD`), back up the `.jks` offline. (2) dry-run `release.yaml` via `workflow_dispatch` (Actions → Release → Run workflow) to validate the CI pipeline end-to-end with the secrets. (3) remaining distribution sub-projects: fastlane metadata → Play paperwork (privacy policy/Data Safety/content rating/FGS disclosure) → IzzyOnDroid RFP → cleanup (targetSdk 36? mic/cam in playsafe? agentchat-playsafe-exclusion). Ranked list in [[relais-distribution-state]].

### 🧠 Memory: [[relais-distribution-state]] (new — distribution goal + foundation + release pipeline), [[relais-imagegen-state]] (#16 done).

---

## 2026-06-30 — image-gen #16 **BOTH PR-D residuals CLOSED → #16 has ZERO open residuals**: (1) G5 fails-safe (deadlock → 720 s watchdog → clean HTTP 500, node survives, on rango); (2) resident-LLM + image-gen coexistence measured on husky/G3 (both run, 12 GB holds it). `main` @ `b1f7443`, ZERO open PRs, NO source change.

### ⏩ RESUME-HERE — #16 image-gen is DONE end-to-end (shipped + every on-device gate proven). No buildable #16 work remains.
- **Coexistence residual CLOSED — husky (Pixel 8 Pro / Tensor G3 / 12 GB / `39300DLJG000BR`):** a throwaway `androidTestFull/ImageGenLlmCoexistenceProbe` loaded **E2B resident on the GPU via the native litertlm `Engine`** (the `RelaisBackendBenchmarkTest` recipe — runs IN-PROCESS so it needs no node/HTTP/access-key, which matters because **the device was LOCKED** and the per-install key is only readable from the unlocked control panel). Warmed it (reply "Ping"), ran ONE real `SdcppImageGenerator.generate()` **while resident** → real **452 KB PNG in 307 s**, then E2B still decoded ("Pong") → `OK (1 test)` 328.9 s. Sampler: izzy host PSS ~**3.4 GB** (E2B; true footprint larger — Mali GPU memory not in PSS) + `:imagegen` ~**2.56 GB** peak, **MemAvailable low-water ~1.9 GB on 12 GB**. `lowmemorykiller` evicted **cached background apps** (gms/gmail, adj 905, "low watermark breached") to make room — **NOT** the node/`:imagegen` (foreground priority, untouched, both produced correct output). **Verdict: resident LLM + process-isolated image-gen coexist + both function even on 12 GB** (at the cost of background-app eviction); the 15 GB deploy devices have +3.5 GB more → comfortable. Model staging needed `chmod -R a+rX` again ([[relais-adb-model-staging-perms]]). Probe deleted; husky restored (izzy trio uninstalled, E2B litertlm removed → only the pre-existing `com.ventouxlabs.relais` remains).
- **(prior, same session) G5 clean-failure — rango "fails safe, doesn't wedge the node" PROVEN:**
- **What was proven (rango / Pixel 10 Pro Fold / Tensor G5 / Android 17 / `57211FDCG0023C`):** a throwaway `androidTestFull/G5ImageGenDeadlockProbe` drove the SHIPPED `SdcppImageGenerator.generate()` ONCE on G5. Result: model loaded (~**2.05 GB flat PSS**), then **wedged at the first ggml-vulkan compute submit** (CPU one-burst 84 %→~0 cumulative, PSS dead-flat for 12 min = the #69 PowerVR deadlock, NOT thermal — skin temp flat 51 °C / status 0 throughout). The node-side **720 s watchdog fired** (`generate returned after 720398ms: bytes=null thrown="image generation timed out"`), **hard-killed + reclaimed the wedged `:imagegen` pid** (process gone + MemAvailable jumped **6.14 → 8.33 GB** = the 2 GB returned), and the node survived → **`OK (1 test)`, Time 720.741 s**.
- **"clean-501" was loose naming.** On `full` flavor G5 is READY (Vulkan present + model staged), so the route ATTEMPTS the generate; the timed-out throw propagates to `RelaisHttpServer`'s outer catch → a **clean HTTP 500** (the exclusive admission gate is released in `finally`, so the next request is served). 501 is only the no-backend / no-Vulkan path. Established by code; the watchdog+reclaim is what the device run proved.
- **Setup (to reproduce):** built `fullOpenDebug` app + androidTest from `b1f7443`, installed `com.ventouxlabs.relais.izzy` on rango, `adb push`ed host `/var/home/user/relais-sd15-convert/sdturbo.gguf` (SHA `d50be765…` == registry pin) → `…/Android/data/com.ventouxlabs.relais.izzy/files/relais/imagegen/sd_turbo-f16-q8_0.gguf` + `chmod -R a+rX` (GrapheneOS perms — see [[relais-adb-model-staging-perms]]). Ran `am instrument … G5ImageGenDeadlockProbe -e RELAIS_PROBE 1`. **Probe DELETED after** (throwaway); repo clean, no source change.
  Rango cleanup DONE this session — izzy trio uninstalled, staged model deleted; only the pre-existing `com.ventouxlabs.relais.degoogled` (stale, pre-PR-C) + `cc.grepon.gatepath` remain (both were there before, untouched). A G5 retest is unlikely to ever pass (#69 is a stable upstream driver bug).

### 🔜 NEXT — #16 has no buildable work left. The remaining bigger bets (all need a human or a brainstorm, no #16):
- **de-Google / rebrand the inherited Gallery UI** (large, brainstorm-first, no device).
- **Physical-only gates:** #15 NFC real-tag tap; #3 widget multi-size/instances (need a human, not adb).
- Tiny: port the one-way auth-drop one-liner to `EmbeddingModelProvisioner.streamTo` (the image provisioner is stricter than the embedder).

### 🧠 Memory: [[relais-imagegen-state]] (updated — BOTH residuals CLOSED; per-SoC matrix G3✓/G4✓/G5 fails-safe; coexistence measured on G3/12 GB; #16 ZERO residuals).

---

## 2026-06-30 — image-gen #16 **PR-D stability gate PASSED on comet (G4)**: 5/5 sequential generates via the REAL `SdcppImageGenerator` path, a fresh `:imagegen` process each, no leak/crash/throttle. `main` @ `44b1895`, ZERO open PRs (PR-D = on-device EVIDENCE, NO source PR).

### ⏩ RESUME-HERE — PR-D headline DONE; residuals = G5-clean-501 + true resident-LLM coexistence
- **PR-D core PROVEN (the "5+ sequential" gate; extends PR-C's 2-image proof):** a throwaway `androidTestFull/ImageGenerateProbe` drove the SHIPPED `SdcppImageGenerator.generate()` (model-select → provisioner file → bind `:imagegen` → one generate → 720 s watchdog → hard-kill-pid) **5× back-to-back on comet (Tensor G4)** → `OK (1 test)`, **1457 s** total. Per-image: **5 distinct valid 512×512 PNGs** (457 / 546 / 416 / 409 / 403 KB — varied ⇒ real output, not cached), **~290 s each** (cold; matches the "no warm path — every fresh process pays full ggml-vulkan shader-compile + tiled VAE" reality). Probe DELETED after the run (throwaway); rig left ready.
- **Lifecycle + RAM evidence (10 s adb sampler):** `:imagegen` spawns with a **distinct pid each image** (e.g. 21474, 22460), peaks **~2.6 GB PSS** (TOTAL), then `imagegen{}` (process GONE) between images — the fresh-process-per-image contract holds by construction. System **MemAvailable never < ~4.8 GB** (15 GB device), recovered to ~7.6 GB between images; **no lowMemory event** (probe asserts `!lowMemory` each iter), **display_therm flat 34.6 °C / status 0 → no thermal throttle/cancel**. No SIGSEGV, no hang, no deadlock, no leak across the whole run.
- **Setup used (to reproduce):** rebuilt `fullOpenDebug` (app + androidTest) from `main` @ `44b1895`, installed on the **`com.ventouxlabs.relais.izzy`** rig (live `cc.grepon.relais` node untouched). Staged SD-Turbo by `adb push` of host `/var/home/user/relais-sd15-convert/sdturbo.gguf` (SHA `d50be765…` **== the registry pin**) → copied to `…/Android/data/com.ventouxlabs.relais.izzy/files/relais/imagegen/sd_turbo-f16-q8_0.gguf` (size 2023745376 ⇒ `isProvisioned` true; **the chmod "Operation not permitted" is BENIGN here — scoped storage auto-maps the file to the app uid `u0_a322` rw**, so the app's `canRead()` is already true; the older `chmod a+rX` gotcha applied to shell-owned `mkdir` dirs, not this FUSE-mapped path). **Model is still staged on comet** → reruns skip the download.

### 🔜 PR-D RESIDUALS (the two items NOT closable this session)
- **G5 clean-501 confirmation** — needs **rango (Pixel 10 / G5)**, NOT attached this session (only comet / G4 was). On G5 the generate is expected to DEADLOCK (#69, PowerVR) and the 720 s watchdog should reclaim the wedged process → the endpoint surfaces a clean error rather than wedging the node; confirm when rango is attached.
- **TRUE resident-LLM coexistence peak RAM** — this run had NO LLM resident in the izzy app (the node was not started with a model). Measured `:imagegen` peak is ~2.6 GB PSS; with a resident E2B (~5.3 GB PSS from the original eval) the sum ~7.9 GB leaves headroom on 15 GB — consistent with the earlier "coexistence-with-headroom" datapoint, but a head-to-head (node serving an LLM **and** image-gen concurrently, sampled) is still owed.
- **Admission exclusivity (#16 follow-up) — DONE + MERGED this session → PR #84** (`main` @ `b1f7443`; CI fully green: Build APK 10m57s + JVM tests 7m9s + scans; branch deleted; ZERO open PRs): new pure-JVM `RelaisAdmissionGate` (shared = barging 1-permit, the old decode behavior; exclusive = bounded *fair* drain of ALL permits) + image-gen now routes through `acquireImageGenExclusiveOrReject`/`releaseExclusive` so it runs single-flight + decode-exclusive **by construction** (a successful drain ⇒ zero other in-flight work). Decode never blocks on the lock (barging `tryAcquireShared` → instant 429); a 2nd image-gen or a busy device → 503 (single-flight fast-fail / drain timeout `IMAGE_GEN_EXCLUSIVE_WAIT_MS=20s`). Independently **code-reviewed (APPROVE-WITH-NITS) + security-reviewed (SHIP-WITH-NITS), 0 CRITICAL/HIGH** — both converged on an `exclusiveInFlight` marker (single-flight fast-fail to kill a 20s pool-thread-park amplification, release-ownership CAS-guard, exclusive-aware Retry-After), all applied; `RelaisAdmissionGateTest` (8 tests) + both flavors green. Files: NEW `RelaisAdmissionGate.kt` + `RelaisAdmissionGateTest.kt`, edited `RelaisHttpServer.kt` (+57/−22) + a 1-line comment in `SdcppImageGenerator.kt`. **MERGED** (squash, `b1f7443`). Net DoS posture is *better* than before (old gate allowed up to 8 concurrent image-gen = 8 GPU contexts). Residual (LOW, by design): under sustained decode, image-gen is best-effort (may 503) since decode barges — documented in the gate KDoc. **On-device VALIDATED on comet/G4** (throwaway `ImageGenExclusivityE2eProbe` through a real loopback `RelaisHttpServer`, `OK` 294 s): while image #1 held the gate, a 2nd `/v1/images/generations` → **503** (single-flight), a concurrent `/v1/chat/completions` → **429 with `retry_after_seconds==30`** (the exclusive-aware hint, vs ~8 s for ordinary saturation — proving decode fast-fails, never blocks), and image #1 → **200** (lock released cleanly). Probe deleted after; no LLM needed (the gate rejects decode before the engine).

### 🧠 Memory: [[relais-imagegen-state]] (updated to PR-D-G4-stability-proven).

---

## 2026-06-30 — image-gen #16 PR-C **MERGED** (PR #83): sd.cpp generator + endpoint flip 501→503→200, LIVE on `full`. **G4 GENERATES** (no G5-style deadlock). `main` @ `44b1895`, ZERO open PRs.

### ⏩ RESUME-HERE — PR-C DONE; next = PR-D / exclusivity
- **PR #83 MERGED** (`main` @ `44b1895`). Both reviews APPROVE (security LOW/ship-able; code-review **delta APPROVE** — a HIGH + 3 MEDIUM all fixed), CI green, and the **on-device gate + 2-image stability pass PASSED on comet (G4)**. Throwaway probes deleted; local branch deleted. **Cleanup DONE:** comet's PR-B `com.ventouxlabs.relais.degoogled` pair uninstalled; the **`com.ventouxlabs.relais.izzy` fullOpen build is KEPT** (working image-gen + staged SD-Turbo model = the **ready PR-D rig**). Live node `cc.grepon.relais` untouched.
- **NEXT (image-gen #16, no open PR):**
  1. **PR-D** — broader stability (5+ sequential; 2 already proven), G5 clean-501 confirmation, peak-RAM-with-resident-LLM measurement. comet's `izzy` build is ready (reinstall fullOpen from current main first to pick up PR-C). Run a throwaway probe like the deleted `ImageGenerateProbe` (recreate from this section).
  2. **Admission exclusivity follow-up** — see FOLLOW-UPS below; the `RelaisHttpServer` READY-branch comment already flags it.

### ✅ WHAT PR-C DELIVERS (#16)
- **`full/SdcppImageGenerator`** : `RelaisImageGenerator` — `isAvailable` = Vulkan (`LLMEdge.isVulkanAvailable()`) && model provisioned; `generate()` binds the process-isolated `ImageGenService`, dispatches ONE generate (512², model/prompt/steps/seed/cfg), waits with a **720 s watchdog** + thermal `shouldCancel`, reads+deletes the PNG, **always hard-kills the `:imagegen` pid** + unbinds. Registration flavor-twins: `full` registers at node init (`RelaisNodeService`), `degoogled` no-op → endpoint stays 501. Endpoint = one atomic `generator.availability(context)` → READY(200)/PROVISIONING(503+Retry-After, kicks bg download)/UNAVAILABLE(501). `n` cap → 2 (D3).
- **The HIGH fix (key):** `ImageGenService` now acks its pid on **acceptance** (`MSG_STARTED`, before the long generate) so the node kills on ALL no-reply paths (timeout/thermal-cancel/hang) — fixing thermal-cancel-doesn't-stop-GPU + the 2nd-generate-into-same-process crash window.

### 🖥️ ON-DEVICE RESULT (decisive — updates the device matrix)
- **Tensor G4 (comet / Mali) GENERATES sd.cpp images** — it does NOT deadlock like G5. Stability pass: **2 sequential 512×512 PNGs (389 KB @ 290 s, 493 KB @ 291 s), each a fresh `:imagegen` process** → the fresh-process-per-image design works. So image-gen runs on **G3 + G4**; **G5 alone deadlocks** (#69, PowerVR/DXT) and even there the watchdog reclaims it cleanly.
- **Perf reality:** ~**5 min/image COLD** on Tensor (each image is a fresh process → always pays shader-compile + tiled VAE), NOT the optimistic ~90 s. Watchdog 720 s (service hang-guard 780 s).

### 🔜 FOLLOW-UPS (noted, out of PR-C scope)
- **Admission exclusivity (#16):** the admission gate is a `Semaphore`, so image-gen is bounded but NOT exclusive vs LLM decode; image-gen CONCURRENT with active decode is **on-device-untested** (coexistence-with-headroom was shown in the eval). True exclusivity (drain the gate) = follow-up. (Comment in `RelaisHttpServer` already corrected to say so.)
- **PR-D:** broader stability proof (5+ sequential — 2 done) + a G5 clean-501 confirmation + measure peak RAM with the resident LLM.
- Port the one-way-auth-drop one-liner from PR-B's review to `EmbeddingModelProvisioner.streamTo`.

### 🧠 Memory: [[relais-imagegen-state]] (update to PR-C-merged after merge).

---

## 2026-06-30 — image-gen #16 PR-B MERGED (PR #82): model provisioner + SHA-pinned PUBLIC registry; on-device provision gate PROVEN on comet (G4). `main` @ `d4074b8`.

**`main` @ `d4074b8` (#82). ZERO open PRs.** (CI green: Build APK 10m + JVM-test matrix 7m + scans.)

### ⏩ RESUME-HERE TL;DR
- **PR #82 MERGED** — the **model layer** for the sd.cpp image-gen backend (endpoint still honest-501; nothing calls it until **PR-C**). `imagegen/ImageModelSelector` = SHA-pinned registry (`turbo`=SD-Turbo default / `sd15` / `custom`); `imagegen/ImageModelProvisioner` = download→SHA-256-verify→reuse-if-complete→atomic-finalize (mirrors the embedder; HF token OPTIONAL); `RelaisConfig.imageModelId/Url/Sha`. All `src/main` (pure java.net/security → degoogled-safe).
- **Hosting = PUBLIC reuse, no upload, tokenless.** The local converts were byte-identical to public sd.cpp gguf repos, so the registry points straight at them: `turbo` → `Green-Sky/SD-Turbo-GGUF/sd_turbo-f16-q8_0.gguf` (sha `d50be765…`), `sd15` → `second-state/stable-diffusion-v1-5-GGUF/…-Q4_0.gguf` (sha `b8944e9f…`). JD's read-only HF token can't write; a one-time write token was offered but **NOT needed/used** (JD may revoke it).
- **On-device provision gate PROVEN on comet** (Pixel 9 Pro Fold / Tensor **G4** / `4A111FDKD0000C`): a throwaway `ImageProvisionProbe` (uncommitted) ran `ImageModelProvisioner.ensure(turbo)` → real ~1.9 GB download from the public repo (0→100% ~37 s on 864 Mbps wifi) → streaming SHA-256 matched the pin → `isProvisioned` true (`OK (1 test)`, 42 s). This also closed the reviewer's one residual (the public-artifact↔SHA correspondence, now confirmed by a real fetch).
- **Reviews:** code-reviewer COMMENT→fixes→**delta PASS**. Fixes: enforce server Content-Length on every download path (custom-URL truncation), one-way auth-drop to the original host, custom-filename sanitization + http(s)-scheme gate. 16 JVM tests.
- **Docs:** `docs/images-generations-api.md` rewritten from dead-MediaPipe to the sd.cpp + process-isolation reality (model/provisioning table).

### 🔜 NEXT (image-gen #16)
- **PR-C** = the full-flavor `SdcppImageGenerator : RelaisImageGenerator` + register at node init + flip the endpoint `501→503→200` (degoogled stub stays 501). **PR-D** = on-device generate + stability proof. **comet (G4/Mali) is the device to use** — G4 plausibly runs sd.cpp Vulkan (G5/PowerVR deadlocks #69; G3 works). Provisioning is already device-proven; the open question is whether *generation* works on G4.
- ⚠️ **OWED cleanup on comet:** the side-by-side debug build `com.ventouxlabs.relais.degoogled` (+ `.test` + the staged 1.9 GB `sd_turbo-f16-q8_0.gguf`) is still installed (comet dropped off adb before uninstall). The live node (`cc.grepon.relais`) is **untouched**. Uninstall the degoogled debug pkgs next time comet is attached (or keep the staged model to skip re-download for PR-C).
- Tiny follow-up: port the one-way auth-drop one-liner to `EmbeddingModelProvisioner.streamTo` (reviewer noted the image provisioner is now stricter than the embedder).

---

## 2026-06-30 — SSRF skill-fetch IP-pinning MERGED (PR #81). `main` @ `079ede8`.

**`main` @ `079ede8` (#81). ZERO open PRs.** (CI green: Build APK + JVM-test matrix + license-lint + gitleaks/trufflehog.)

### ⏩ RESUME-HERE TL;DR
- **PR #81 MERGED** — closes the documented DNS-rebinding **TOCTOU** in the agentchat *add-skill-from-URL* fetch. `SkillUrlPolicy.resolvePinned` now hands back the exact `WebhookGuard`-vetted `InetAddress`, and the new `SkillSourceFetcher` connects to that **pinned IP** (TLS SNI + hostname-verify against the original host, redirects refused, bounded body/timeouts) — mirroring `batch/WebhookDelivery`. The HTTP/1.1 GET exchange is the pure, unit-tested `SkillHttp`. The old `validate()`-then-unpinned-`openConnection()` block is gone; `validate()` stays as a thin wrapper.
- **Reviewed hard** (project rule: separate review pass): independent security + code review (opus). Security found a **MEDIUM chunked-decoder OOM** (Int-overflow cap bypass + unbounded `readLine` → process crash); fixed (Long-safe cap, reject negative sizes, 256-byte line bound) + a delta re-review confirmed **PASS ("ship it")**. **27 new JVM tests** (`SkillHttpTest` 17 + `SkillUrlPinTest` 10); both flavors compile.
- **Residual flagged (out of scope, pre-existing):** a skill's later **WebView script/asset loads re-resolve DNS and are NOT pinned** — only the `SKILL.md` fetch is. KDoc scoped accordingly. Candidate follow-up if agentchat ever ships. Another noted follow-up: extract a shared pinned-TLS helper (the socket/TLS block is ~duplicated with `WebhookDelivery`; left un-refactored to limit blast radius on security code).
- **Context:** this was a `/ultrawork` "work through the open items" run. Grounding found the GitHub backlog empty (image-gen #16 PR-A already merged as #67; remaining image-gen is G3/G4-gated, deadlocks on the attached G5 #69). The SSRF pin was the one genuinely-open, device-independent, fully-mergeable cleanup. Deferred as premature/speculative: image-gen output-size cap (belongs in PR-C, dead path while 501) and an audio facade param (no caller).

### 🔜 NEXT
- No buildable backlog. Remaining real work needs hardware/decisions: image-gen #16 PR-B/C/D (needs a **G3/G4**), physical gates #15 NFC / #3 widget (need a human), or the UI de-Google/rebrand (large, brainstorm-first).

---

## 2026-06-29 — RAG (#4) on-device VALIDATED on rango (Pixel 10/G5/GrapheneOS); rango re-provisioned as a restartable E2B node; adb model-staging perms gotcha nailed. `main` @ `aa3ced0`, ZERO open PRs, NO source change.

**`main` @ `aa3ced0`, ZERO open PRs. Handoff/memory only this session — no code change.**

### ⏩ RESUME-HERE TL;DR
- **#4 RAG fully proven end-to-end on rango (G5)** — the long-owed "code-complete but unvalidated (ingest→query)" gate. Built `degoogledOpenDebug` fresh from `aa3ced0` (1m30s), installed on rango (appId `com.ventouxlabs.relais.degoogled`), hand-staged E2B + EmbeddingGemma, started the node, drove the HTTP API over `adb forward tcp:18080 tcp:8080`:
  - **Ingest** 3 distinct-topic docs → `200`, chunked/stored; `GET /v1/rag/documents` → `doc_count=3, chunk_count=3`.
  - **Query** "How do green plants make their own food from sunlight?" → **#1 Photosynthesis `cos=0.5896` ≫ #2 Roman aqueducts `0.2224` ≫ #3 TCP `0.1452`** — correct topic on top by a wide margin (proves on-device EmbeddingGemma embed + MRL-256 truncation + brute-force cosine ranking).
  - **RAG-augmented chat** (`"rag":true` on `/v1/chat/completions`, E2B generation) → *"The green pigment chlorophyll lets plants capture light energy during photosynthesis."* — grounded in the retrieved chunk. Full pipeline (embed→retrieve→inject→generate) works on G5.
  - Cleaned up after: corpus deleted (empty), node stopped, forward removed. **rango left CLEAN but PROVISIONED** (E2B + embedder staged) → restart via the control ABI for a working G5 node.
- **E2B re-confirmed G5-safe:** loaded in ~30s, `health {ready:true, thermal_state:0}`, no SIGSEGV.

### 🔑 ON-DEVICE MODEL-STAGING GOTCHA (durable — `adb push` into `Android/data` on GrapheneOS/A17)
Staging a model by `adb push` into `/sdcard/Android/data/<appId>/files/…` FAILS until perms are fixed:
- dirs made by `adb shell mkdir -p` are owned by `shell` and are NOT traversable by the app uid → the provisioner's `File(path).exists()` reads **false** → it falls through to a DOWNLOAD, which then **EACCES** (the app can't write into the shell-owned dir).
- **Fix: `adb shell chmod -R a+rX /sdcard/Android/data/<appId>/files` AFTER pushing.** (The app's own top files dir can't be chmod'd by shell — "Operation not permitted" — harmless; the shell-owned subdirs/files get world read+traverse.) Re-`exists()` is then true and the staged model is **ADOPTED (no download)**. `chmod 0644 <file>` alone is NOT enough — the PARENT dirs need `+x`. A stop→start re-runs init after the perms fix.
- Embedder needs NO HF token when pre-staged (`EmbeddingModelProvisioner` adopts a complete on-disk pair tokenless); LLM adopted via the ref fast-path once readable. Host had the HF token at `~/.cache/huggingface/token` → downloaded E2B (2.59 GB, commit `361a4010`) + EmbeddingGemma generic `.tflite` (179,132,472 B) + `sentencepiece.model` (4,683,319 B) host-side, then pushed.

### ⚙️ Control ABI used (degoogled channel)
`adb -s 57211FDCG0023C shell am start -n com.ventouxlabs.relais.degoogled/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <KEY>` — `--es token` must MATCH the existing auto-generated `RelaisConfig.apiKey` (UUID-hex, generated on first activity launch; read it off the control-panel ACCESS KEY chip via `screencap`). HTTP loopback :8080, HTTPS LAN :8443. ⚠️ local host port 8080 was taken by an unrelated Entrevoix dev server → forward to **18080**.

### 🔜 NEXT STEPS / OPEN THREADS
- **#4 RAG: DONE (on-device).** No buildable backlog remains.
- Physical-only gates still owed (human, not adb): #15 NFC real-tag tap; #3 widget multi-size/instances.
- **Image-gen #16** = the headline remaining BUILD (PR-A `:imagegen` process backend, fully scoped) — its on-device gate needs a **G3/G4**, NOT the G5; #16 still honest-501, deadlocks on G5 (#69). Other big bet: de-Google/rebrand the inherited Gallery UI (no-device, brainstorm-first).
- husky / comet untouched this session.

---

## 2026-06-26 — post-#77 doc/cleanup queue merged (#78/#70/#68/#79/#80); rango fully wiped + husky old build removed; codemaps + doc/input-guide sync landed. `main` @ `aa3ced0`, ZERO open PRs. START HERE.

**`main` @ `aa3ced0`. ZERO open PRs.** (#77 integration build confirmed GREEN: Actions run 28094745587 — Build APK + JVM tests pass.)

### ⏩ RESUME-HERE TL;DR
- **5 docs/scratch PRs merged this session, in order:** **#78** = the deferred #71 appId-doc cleanup — `docs/tasker-intent-abi.md` adb `-n` component + `Model.kt` data-dir KDoc now use the per-channel ventouxlabs appIds (verified against `build.gradle.kts:141-143`, not the plan) → **#70** = per-device image-gen outcome note in `ImageGenServiceProbe` → **#68** = distribution + Play-channel feature-matrix PLAN docs (retrospective; the impl already shipped via #71/#74/#76) → **#79** = `docs/CODEMAPS/` (5 token-lean maps) + `docs/RUNBOOK.md` + synced `DEVELOPMENT.md` and rewrote the two inherited-Gallery guides (`Function_Calling_Guide.md`, `Bug_Reporting_Guide.md`); generated from source-of-truth, `CONTRIBUTING.md` left as-is (contributions closed) → **#80** = `docs/input-content-guide.md` (the node's input surface: text/image/audio × HTTP / share / NFC / Tasker / tile / triage).
- **New docs to lean on next session:** `docs/CODEMAPS/{architecture,backend,frontend,data,dependencies}.md` (subsystem/route/Room/dep maps) + `docs/RUNBOOK.md` (deploy/control-ABI/health/known-issues) + `docs/input-content-guide.md` (what content the node accepts). Correction baked in: shipped litertlm = **0.11.0** (0.13.1 was a G5-bug test build); CI test tasks are the two-dimension names (`testFullOpenDebugUnitTest` / `testFullPlaysafeDebugUnitTest` / `testDegoogledOpenDebugUnitTest`).
- **Device cleanup (both attached + unlocked this session):** found ~20 GB of leftovers the prior handoff did NOT record — stale **pre-rebrand `cc.grepon.relais`** builds on BOTH devices (16 GB rango, 3.4 GB husky) on top of rango's ventouxlabs builds.
  - **rango/G5 (`57211FDCG0023C`, GrapheneOS): WIPED all Relais** (old `cc.grepon.relais` + `com.ventouxlabs.relais` + `.izzy` + `.test`). ~22 GB reclaimed → **183 GB free**. Only non-Relais apps remain (`paperlessgo`, `portage.recv`). **No Relais build installed now.**
  - **husky/G3 (`39300DLJG000BR`): removed the stale old build** (`cc.grepon.relais` + `.test`, ~5 GB → **78 GB free**). KEPT `com.ventouxlabs.relais` (fullPlaysafe) — but it is now **UNPROVISIONED** (no staged model).

### 🔜 NEXT STEPS / OPEN THREADS
- **Physical-only gates still owed (need a human, not adb):** #15 NFC real-tag tap; #3 widget multiple-sizes/instances homescreen add.
- **husky** kept a clean `com.ventouxlabs.relais` but no model — re-stage one (E4B is known-good on G3) when a G3 node is actually needed.
- **Image-gen #16:** unchanged — works on G3 (~5 min cold), DEADLOCKS on G5 (issue #69), endpoint honest-501. Don't re-attempt on G5.
- **comet / Pixel 9 (G4) = the live node** — NOT attached this session; untouched.

### ⚙️ Control ABI (CURRENT — per-channel appId)
`adb -s <serial> shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <key>`
where `<appId>` = `com.ventouxlabs.relais` (Play) / `.izzy` (IzzyOnDroid) / `.degoogled` (GrapheneOS/GH). The source **namespace** stays `cc.grepon.relais`, so the activity FQN is `cc.grepon.relais.RelaisControlActivity`. ⚠️ The "Device state" table lower in this file predates this session's wipe — rango no longer has any Relais build staged.

### 🧠 Memory pointers (durable knowledge)
`relais-overnight-run-2026-06-22`, `relais-ondevice-merged-build-2026-06-23`, `relais-distribution-channels`, `relais-flavor-split`, `relais-g5-e4b-crash-upstream`, `relais-modality-boundaries`.

---

## 2026-06-24 — ventouxlabs distribution + hardening overnight queue ALL MERGED; on-device round-trip PROVEN on Pixel 10/G5; 16 KB page-align fix shipped.

**`main` @ `c426b5c` (#77). ZERO open PRs.** (Integration build for c426b5c was finishing at handoff time; identical code already passed on the PR-branch dispatch run 28072135605 — expected green.)

### ⏩ RESUME-HERE TL;DR
- **6 PRs merged this run, in order:** #71 ventouxlabs 3-channel build (dist×policy flavors → appIds: fullOpen=`com.ventouxlabs.relais.izzy`/IzzyOnDroid, fullPlaysafe=`com.ventouxlabs.relais`/Play, degoogledOpen=`com.ventouxlabs.relais.degoogled`/GrapheneOS; degoogledPlaysafe filtered out) → #74 deep-link scheme + Tasker ABI rebrand to `com.ventouxlabs.relais` (namespace stays `cc.grepon.relais`) → #75 coverage backfill (capTxt surrogate + thermal floor) → #72 agentchat SSRF guard → **#76** Play-compliance feature-stripping (playsafe drops 4 perms + RelaisNotificationListenerService + TriageControlActivity via `tools:node=remove`; **superseded #73** — see lesson) → **#77** 16 KB page-align fix.
- **On-device validation (rango / Pixel 10 Pro Fold / Tensor G5 / GrapheneOS):** per-channel appIds, deep-link scheme, Tasker action registration, Play-compliance stripping — all PASS. **Full HTTP inference round-trip PASS** (gemma-4-E2B, `/v1/chat/completions` real decode, no SIGSEGV — E2B is the G5-safe model). Tasker cross-app *decode* via adb did NOT complete (FGS-from-background `unavailable` on Android 16/GrapheneOS — needs a real foregrounded launcher; covered by in-process `TaskerIntentProbe`, not a regression).
- **16 KB page-size (`/investigate` → fix):** root cause was ONE 4 KB lib `libmlkitcommonpipeline.so` from `com.google.mlkit:vision-internal-vkp:18.2.2` ← `image-labeling:17.0.7` ← `llmedge:0.3.9` (unused transitive; all litert-lm libs were already 16 KB). Fixed in #77 by excluding image-labeling from llmedge + a CI gate that readelf-scans every arm64 `.so` in all 3 release variants (fail on sub-16 KB; hardened against false-green). Full flavor only; degoogled was already clean.

### 🔜 NEXT STEPS / OPEN THREADS
- **Deferred #71-owned cleanup (small PR, non-blocking):** `docs/tasker-intent-abi.md` adb `-n cc.grepon.relais/.automation.RelaisTaskerActivity` → must become `-n com.ventouxlabs.relais/cc.grepon.relais.automation.RelaisTaskerActivity` (appId≠namespace now); and `Model.kt:228-234` data-dir KDoc (`cc.grepon.relais` → the per-channel appId). Both flagged in #74.
- **rango leftovers:** two debug builds installed (`com.ventouxlabs.relais` + `.izzy`) + a staged 2.4 GB `gemma-4-E2B-it` model in the `.izzy` files dir. Node is STOPPED. Uninstall if not needed. To restart the node: control ABI `am start cc.grepon.relais.RelaisControlActivity --es cmd start --es token <KEY>` (KEY is in EncryptedSharedPreferences → read it off the control-panel "ACCESS KEY", screenshot via `screencap -d 0`; the Fold prepends a "[Warning] Multiple displays" line to `exec-out screencap` — strip before the PNG magic).
- **husky** (Pixel 8 Pro / G3, `39300DLJG000BR`) dropped off adb mid-session — reconnect for a 2nd-device pass.
- **Image-gen #16:** unchanged — works on G3 (~5 min cold), DEADLOCKS on G5 at first ggml-vulkan dispatch (issue #69); endpoint still honest-501. Don't re-attempt on G5.

### ⚠️ LESSONS THIS RUN (durable)
- **Never `gh pr merge --squash --delete-branch` the BASE of a stacked PR.** It deleted #71's branch, which CLOSED the stacked #73 (GitHub did NOT retarget) and the API refused to reopen it. Recovery: `git rebase --onto main <orig-base-head-sha> <stacked-branch>` to replay only the child's commits, force-push, open a FRESH PR (#76).
- **CI auto-trigger quirk:** a PR that EDITS `.github/workflows/build_android.yaml` can silently fail to auto-trigger the "Build Android APK" workflow on `pull_request` (zero runs, valid YAML). Workaround: `gh workflow run build_android.yaml --ref <branch>`. The `push:main` trigger DOES fire on merge.
- Devices: rango=`57211FDCG0023C` (spare, destructive-OK, GrapheneOS, Pixel 10/G5), husky=`39300DLJG000BR` (Pixel 8 Pro/G3), comet=Pixel 9 (live node, NOT attached this session).

### 🧠 Memory pointers (durable knowledge)
`relais-overnight-run-2026-06-22` (the queue + merges + lessons), `relais-ondevice-merged-build-2026-06-23` (on-device round-trip + 16 KB root cause + model-staging-adoption trick), `relais-distribution-channels`, `relais-flavor-split`, `relais-g5-e4b-crash-upstream`, `relais-modality-boundaries`.

---

## 2026-06-21 (evening) — post-clear session: #6 embeddings PROVEN on-device, #16 image-gen CLOSED (MediaPipe reverted) + sd.cpp rebuild FULLY SCOPED (blueprint + D1–D4 + dep-audit), #2566 nudged w/ G3, upstream triage (0 ports), claude-mem hook fixed.

**`main` @ `597316b` (#66). ZERO open PRs.**

### ⏩ RESUME-HERE TL;DR
- **API surface:** only image-gen is 501 (now clean + shelved); chat/tools/batch/**embeddings**/RAG all green.
- **Image-gen rebuild (#16) — FULLY SCOPED, ready to build (the obvious next coding chunk).** Blueprint: `.claude/PRPs/plans/feature-16-imagegen-sdcpp-process-backend.plan.md`. D1–D4 locked, **PR-0 dep-audit DONE** (full-only confirmed; ktor a non-issue; slim-excludes identified). **NEXT = PR-A** (dep+excludes + the `:imagegen` service + one-generate lifecycle) — needs a device for the generate-with-excludes check. See the build-prep subsection below.
- **Other bigger bets when wanted:** (a) **agentchat SSRF/prompt-injection** hardening (`customtasks/agentchat` add-skill-from-URL → WebView JS — real risk if the app ships); (b) **de-Google + re-brand** the inherited Gallery UI (still Nunito/marketing screens, points at google's allowlist).
- **#4 RAG** is code-complete + unblocked by #6 but NOT yet on-device-validated (ingest→query) — cheap next win.
- **Physical-only gates still owed:** #15 NFC real-tag tap; #3 widget multi-size.
- **Device NOW set up:** Pixel 8 Pro (**husky `39300DLJG000BR`, Tensor G3, Android 16, 12GB**) — full debug build + E4B (~4.4GB) + EmbeddingGemma (179MB) provisioned; **node STOPPED (clean)**. A ready G3 node for PR-A/D image-gen testing. (rango = Pixel 10/G5 spare `57211FDCG0023C`; comet = Pixel 9/G4 live node `4A111FDKD0000C`.)

### Shipped / merged this session
- **#65 MERGED** (image-gen verdict docs) → **#66 MERGED (`597316b`)**: reverted the dead MediaPipe backend (#64). Gone: `imagegen/MediaPipeImageGenerator.kt` + the `tasks-vision-image-generator` dep + the `libOpenCL-pixel.so` manifest entry (−187 lines; the 949 mediapipe classes drop out of the degoogled dex). Kept the #63 scaffold (interface/provider + `POST /v1/images/generations` honest-501 + JVM tests); corrected the stale "MediaPipe is the only path" comments to the sd.cpp verdict. **#16 is now SHELVED at honest-501** (sd.cpp via process isolation documented in `docs/images-generations-api.md` as the reopen plan).
- **#6 embeddings FINISHED = on-device validated** (NOT a code change — the code was already complete, wired, + 9 unit tests green; the "finish" was the missing on-device proof — the repo's verify-before-believing trap). On husky (G3, full build): `/v1/embeddings` → provisions 179MB EmbeddingGemma → **HTTP 200, dim=768, L2 norm=1.000**, OpenAI usage. **KEY correction:** the embedder is now **non-GMS** (bundled `org.tensorflow.lite.Interpreter`, shipped as **#52**) — the old memory's "de-Googled gets a 501 (GMS preflight)" is **STALE**; degoogled should serve (same `src/main`; not separately re-run today).
- **LiteRT-LM #2566 nudge POSTED** (comment) with a NEW datapoint: byte-identical `gemma-4-E4B-it` **serves on Tensor G3 (Pixel 8 Pro, ~1.6s 1st inference) AND G4**, SIGSEGVs only on **G5** → bug confirmed **G5-specific across three Tensor generations**. Full matrix in `.claude/upstream-litertlm-g5-bug-draft.md` (heading now says POSTED).

### Image-gen build prep this session (#16 sd.cpp) — blueprint + decisions + PR-0 audit
- **Blueprint:** `.claude/PRPs/plans/feature-16-imagegen-sdcpp-process-backend.plan.md`. Design = a disposable `:imagegen` OS process (`android:process`, **NOT a separate app** — same APK gives crash isolation), bound service, **PNG via file handoff** (>1MB binder limit), **node-side watchdog hard-kills the process** on reply/timeout. ONE generate per process (the only stable sd.cpp primitive — reuse SIGSEGVs, facade hangs). 5 PRs: PR-0 audit (done) → PR-A service+lifecycle → PR-B provisioning → PR-C impl + endpoint-flip 501→503→200 + degoogled-stub → PR-D on-device stability proof (5+ sequential images each in a fresh process; force a crash, node survives).
- **Decisions D1–D4:** D1 default **SD-Turbo** (cfg=1/steps=4/~90s) but build a **swappable named-model set** (turbo/sd15/custom-URL) for the quality upgrade JD wants. D2 host the converted gguf on **JD's own HF repo, SHA-pinned** (reuse the embedder's `resolve/main` path; license: SD-Turbo=Stability non-commercial, SD-1.5=OpenRAIL-M = the cleaner upgrade). D3 **n≤2**. D4 **sync** w/ long timeout.
- **PR-0 dep audit DONE — NOT merged (wiring goes in PR-A):** `fullImplementation(io.github.aatricks:llmedge:0.3.9)` via jitpack. **Full-only CONFIRMED + REQUIRED:** llmedge → `com.google.mlkit:text-recognition`+`image-labeling` → `play-services-base/basement/tasks` (would break degoogled GMS=0). With `fullImplementation` → degoogled classpath = **0** llmedge/mlkit/play-services/gms; both flavors build green. **ktor = non-issue** (Relais already on 3.4.3; llmedge's only straggler = inert `content-negotiation:2.3.12`). **Slim in PR-A** via `exclude(io.gitlab.shubham0204:sentence-embeddings)` (drops onnxruntime) + `exclude(io.ktor)` (drops the unused HF-download path) — verify generate-still-works on-device.

### Upstream sync (google-ai-edge/gallery) — triaged, NOTHING to pull
- The fork keeps `upstream` (fetch-only) with shared history. Upstream is **424 commits ahead**, but `git merge upstream/main` = **104 conflicts** AND double-applies a year (the fork incorporated upstream as DIFFERENT SHAs, so the merge-base fell back to May 2025). **Verdict: cherry-pick, never merge.** Path-scoped triage of the node-relevant set (`data/`+`runtime/`+`worker/`+`di/` = 76 commits) → **0 ports** (all already-present/superseded/live-fetched allowlist data). Watermark (synced-to `4895efa`) + per-commit dispositions in `.claude/upstream-incorporation-log.md`; method in `.claude/PRPs/plans/upstream-sync-incorporation.plan.md`. **Upstream is a reference, not a feed** — its ongoing work is all in the inherited Gallery UI the node doesn't use.

### Env fix (NOT the repo) + no-code
- **claude-mem hook** spammed "empty stdin / Malformed JSON" on every Read/Write: root cause = the hook prelude's `$($SHELL -lc 'echo $PATH')` login shell **drains the piped payload** because this distrobox container's `/etc/profile.d/distrobox_profile.sh` reads stdin. Fixed by adding `</dev/null` to the prelude in `~/.claude/plugins/cache/thedotmack/claude-mem/13.5.6/hooks/hooks.json` (effective next CC restart); reported as a comment on **claude-mem #2962**.
- **HF token: JD decided NOT to rotate it (will not, ever) — do NOT raise rotation/security/license again.** Valid + reusable, stashed at `/tmp/relaisrecv2/.hf_token`. (Memory [[feedback-no-hf-token-nag]] corrected — it had been wrongly recorded "rotated".)
- **Instincts recorded** (`/continuous-learning-v2`, in `~/.claude/homunculus`): `verify-code-state-not-the-plan` (relais project, 0.85 — the recurring "plans claim unbuilt, code is already wired" trap; bit twice this session), `relais-headless-node-control` (relais project — the adb control-intent + bearer-auth recipe), `hook-empty-stdin-from-login-shell-prelude` (global — the claude-mem root cause generalized).
- New `.claude/scripts/g3-e4b-probe.sh` (auth-aware G3 E4B crash-vs-serve harness).

### ⚠️ BUILD LEVERS (unchanged): `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest` (both in one invocation OK this session); degoogled dex GMS=0 + now 0 mediapipe (CI-enforced); first build with a NEW dep needs network.

---

## 2026-06-21 — #16 image-gen FULLY INVESTIGATED on-device → verdict: MediaPipe DEAD, sd.cpp viable but needs PROCESS ISOLATION. Also did a full codebase-learning pass.

**`main` @ `058c0cb` (#64). ONE open PR: #65 (docs verdict — merge it).** Long session. Two threads:
**(A)** finished the image-gen #16 investigation to a definitive verdict, and **(B)** ran a whole-codebase
learning pass (289 files / 59k LOC mapped). Endpoint `POST /v1/images/generations` is still an honest 501.

### ⏩ RESUME-HERE TL;DR (do these next)
1. **Merge PR #65** (`docs/relais-imagegen-engine-verdict`, docs-only, the verdict below).
2. **Decide image-gen direction** (operator already said "~90 s OK, but it must be stable"):
   - **Build the process-isolated sd.cpp backend** (the proven-stable design — see "Process isolation" below), OR
   - **shelve** and leave the endpoint at 501.
3. **Cleanup owed regardless:** revert the **dead** MediaPipe #64 dep + `imagegen/MediaPipeImageGenerator.kt`
   (ships ~tens of MB of native libs that can NEVER run on this stack). Keep `RelaisImageGenerator`
   interface/provider + the endpoint (honest 501). [[relais-modality-boundaries]] has the why.
4. JD no-code (long-standing): rotate the HF token; LiteRT-LM #2566 nudge.

### #16 image-gen — the full arc this session
- **Task-0 (GMS gate) → DONE:** `com.google.mediapipe:tasks-vision-image-generator:0.10.26.1` is transitively
  GMS-free → `implementation`, both flavors. (Necessary but NOT sufficient — see the protobuf wall below.)
- **PR #63 (`4b9e098`) MERGED — honest-501 scaffold:** `imagegen/RelaisImageGenerator` interface + provider
  (null→501), `RelaisImagesEndpoint.kt` pure helpers, `POST /v1/images/generations` route, 25 JVM tests, docs.
- **PR #64 (`058c0cb`) MERGED — MediaPipe backend impl/dep:** `imagegen/MediaPipeImageGenerator.kt` + the dep.
  **⛔ This is now known to be a DEAD END (don't build on it; revert it).**
- **MediaPipe BLOCKED on-device (rango Pixel 10/G5, PROVEN):** `ImageGenerator.createFromOptions` throws
  `NoSuchMethodError: Any$Builder.build()Lcom/google/protobuf/Any;`. MediaPipe's Java API needs **full
  `protobuf-java`** (covariant builder); the app is hard-locked to **`protobuf-javalite`** (6 `.proto`s + 7
  DataStore serializers, plugin `option("lite")`). Both define `com.google.protobuf.*` → cannot coexist;
  process isolation does NOT help (build-time dex clash). `javap`-confirmed javalite `Any$Builder` lacks `build()`.
- **Alt-engine research + on-device proof → sd.cpp is the only viable engine:**
  - Ran via the **`io.github.aatricks:llmedge:0.3.9`** AAR (its `libsdcpp.so` ships WITH Vulkan; `readelf` NEEDED libvulkan.so).
  - **Tier-1:** `LLMEdge.isVulkanAvailable()=true` on the G5 (1 device, ~15.5 GB unified VRAM; OpenCL=false — sd.cpp's OpenCL is Adreno-only).
  - **Tier-2:** valid 512×512 image via Vulkan, **coexists with the resident E2B LLM, no OOM** (E2B ~5.3 GB PSS, peak ~6.7 GB, ~3.4 GB free, `lowMemory=false`).
- **Performance (all cold, LLM resident):** SD-1.5 q4_0 @20 steps = **184 s**; SD-Turbo @4 steps = **90 s**.
  `timePerStep` ROSE 9.2→22.4 s as steps dropped ⇒ a **~50–70 s fixed overhead** (model→GPU load + ggml-vulkan
  shader compile + VAE) dominates → few steps don't rescue it. Realistic floor **~60–90 s/image**.
- **⚠️ STABILITY = the blocker (3 reproduced llmedge failure modes):**
  1. **reuse** (2nd generate, same client) → **SIGSEGV** in `libsdcpp.so nativeTxt2ImgArgb` (identical offset; NOT fixed by `RuntimeCacheConfig(maxEntries=1,maxMemoryMb=8000)` keep-resident).
  2. **fresh client per image** (close+recreate in one process) → **deadlock** (0% CPU).
  3. **`LLMEdge` facade path** → **hangs on the FIRST generate** (>450 s; thermal ruled out — SoC was only `mStatus=1` LIGHT, battery 25 °C).
  - **Only proven-stable primitive: a direct `ImageClient.create(ctx, scope)` doing exactly ONE generate** (~90 s, succeeded ~5×). Everything past the first in-process op breaks.
- **PR #65 (`docs/relais-imagegen-engine-verdict`, OPEN):** the above written into `docs/images-generations-api.md`.

### Process isolation — the design to build (when picked up)
A separate **`:imagegen` process** (`android:process=":imagegen"`): load the model → **one** `ImageClient.create(...).generate(ImageGenerationRequest(...))` → write the PNG (file/IPC handoff) → **process exits / is killed**. Never reuse a context, never close+recreate in-process, never use the `LLMEdge` facade. Stable *by construction* (every image = a fresh process's first-and-only generate) and contains any native crash/hang to a disposable process — the node never dies. Wire behind the shipped `RelaisImageGenerator` interface (#63). **Packaging (operator decision, locked):** image-gen rides the **`full` flagship** via `fullImplementation(llmedge)` (its onnxruntime/mlkit/play-services transitives are fine there); **`degoogled`** gets a best-effort stub → 501. **Never compromise `full` for `degoogled`.** Open infra: host the model (operator-set URL → tarball, HF default, SHA-pinned, mirror the embedder).

### llmedge API (javap'd from the AAR — saves re-discovery)
`LLMEdge.create(ctx, scope, config?)`; static `LLMEdge.isVulkanAvailable()/getVulkanDeviceInfo()/getImageBackendAvailability()`; `ImageClient.create(ctx, scope, config?)` (the stable direct path; the `.image` facade hangs); `imageClient.generate(ImageGenerationRequest(prompt,width,height,steps,seed:Long,flashAttention,cfgScale,model=ModelSpec.localFile(File)), cont): Bitmap` (suspend). `ModelSpec.localFile(File)`/`.huggingFace(...)`. `LLMEdgeConfig().copy(image=ImageRuntimeConfig(RuntimeCacheConfig(maxEntries,maxMemoryMb), preferPerformanceMode))`. SD-Turbo wants `cfg=1, steps=4`. llmedge AAR extracted at `/tmp/llmedge`.

### Artifacts left on disk (for re-test; deletable)
- **Local scratch `/var/home/user/relais-sd15-convert/`** (~10 GB): `v1-5-pruned-emaonly.ckpt` (4 GB), converted `bins/` (2 GB MediaPipe format), `sd15-q4_0.gguf` (1.5 GB), `sdturbo.gguf` (1.9 GB), `convert.py` (patched `weights_only=False`). Plus a host CPU-torch venv via `pip --user`.
- **On rango (`57211FDCG0023C`) `/data/local/tmp/relais/imagegen/`:** `bins/` (MediaPipe, useless now), `sd15/sd15.gguf`, `sdturbo/sdturbo.gguf`. App currently has a throwaway llmedge build installed (reinstall a clean `:app:installFullDebug` from main to drop it).
- **The throwaway probes were DELETED** (`StableDiffusionProbe.kt`, `ImageGenCoexistenceProbe.kt`) — they needed the llmedge scratch dep. Recreate from the API above if re-validating. Research hook: `.claude/PRPs/plans/feature-16-imagegen-research-hook.md`.

### (B) Codebase-learning pass (this session, via /claude-mem:learn-codebase)
Mapped all **289 Kotlin files / 59,255 LOC** (it's the `google-ai-edge/gallery` fork). Big picture: the **node subsystem** (Relais-authored, AGPL) = `RelaisHttpServer` (the OpenAI API + the thin shell over pure JVM seams in `core/`: Admission/FinishReason/Reasoning/SessionPolicy/StructuredOutput/ToolParsing/ClientConfig), `RelaisEngine` (litertlm adapter), `RelaisNodeService` (headless host: provision→engine→HTTP 8080 loopback + 8443 TLS LAN→mDNS→workers; `RelaisWatchdog` recovery), `core/RelaisInference` (cold-start-SAFE in-process inference for tiles/widgets/share/NFC/triage), `core/NodeState`+`RelaisNodeController` (state read-model). Feature subsystems: `embed/` (EmbeddingGemma + pure-Kotlin SentencePiece), `rag/` (chunk/embed/brute-force cosine, 10k cap), `nodetools/` (SafeCalculator/UnitConvert/datetime/rag_search), `batch/`+`worker/BatchWorker` (Room queue + SSRF-guarded signed webhooks), `triage/` `share/` `nfc/` `tile/` `widget/` (all: exported trampoline → pure decision fn → consumer-only worker; cold-start guard everywhere; untrusted input fenced as DATA). The whole `ui/` tree (~95%) + `customtasks/` (agentchat/MCP/skills, mobileactions, tinygarden) are **inherited Gallery**, re-namespaced, NOT de-Googled/re-styled. **Key flagged risks:** `customtasks/agentchat` SkillManager has an **unrestricted-URL SSRF** + **verbatim prompt-injection** sink (skills run JS in a WebView); `ui/` still points at `google-ai-edge/gallery` for the model allowlist + release-check (fork bugs), has live Gemma marketing screens (off-DESIGN.md), Nunito (not monospace) global font. Data: Room v4 (session/rag/batch, no destructive migration) + proto DataStore (the javalite protos that block MediaPipe). Gson-vs-Kotlin-null-safety defended throughout (`RelaisModelRef.fromJson` etc.).

### 1. #16 Task-0 (GMS/flavor gate) — DONE: MediaPipe is transitively GMS-FREE
- `com.google.mediapipe:tasks-vision-image-generator` **latest = `0.10.26.1`** (2025-07-15 — confirms "unmaintained"). Verified two ways: (a) POM trace of the full closure; (b) real `./gradlew :app:dependencies` on `degoogledDebugRuntimeClasspath` (scratch dep added then reverted) → `grep play-services|android.gms|aicore|mlkit` = **none**.
- **VERDICT: `implementation(...)`, ships in BOTH flavors, NO degoogled stub (plan Task 5 DROPPED).** Only `com.google.android.*` pkg pulled is `com.google.android.datatransport` (CCT telemetry — passes the dex gate; present≠used, de-Google heads-up). `protobuf-javalite 4.26.1` = exact repo match (no conflict). **Reintroduces guava 27.0.1-android** (the de-Google work had removed it — footprint note, not a gate issue). Recorded in the plan + memory [[relais-modality-boundaries]].

### 2. Honest-501 endpoint scaffold — BUILT (device-independent slice; mirrors the #6 embeddings "honest 501" pattern)
- **NEW `imagegen/RelaisImageGenerator.kt`** — interface (`generate(context, prompt, steps, seed, shouldCancel)` → PNG bytes) + `RelaisImageGeneratorProvider` singleton (null by default → 501). Mirrors `embed/RelaisEmbedder.kt`.
- **NEW `RelaisImagesEndpoint.kt`** (pkg `cc.grepon.relais`) — pure JVM helpers: `parseImageRequest`/`ImageGenLimits`(self-validating `init{require}`)/`ImageRequestResult` + `buildImagesResponse` (java.util.Base64 = NO_WRAP, JVM-testable) + `buildImagesError`.
- **`RelaisHttpServer.kt`** (+~60) — `POST /v1/images/generations` route (shed 503 → parse 400 → provider-null **501** → queue 429 → 200), `IMAGE_GEN_LIMITS` const (n≤4, steps 1–50 clamp, size `512x512` only), import, `endpointLabel` entry.
- **NEW tests:** `RelaisImagesEndpointTest` (22) + `RelaisImageGeneratorProviderTest` (3) — **25 pass, both flavors green** (`:app:testFullDebugUnitTest` + `:app:testDegoogledDebugUnitTest`). **NEW `docs/images-generations-api.md`** (truthfully documents the 501 state + OOM/unmaintained/GMS caveats).
- **Review:** code-reviewer **APPROVE** (0 C / 0 H; 2 MEDIUM + 4 LOW, all latent for the backend PR). Applied the cheap-now fixes: added the `shouldCancel` seam to the interface (avoids a future breaking change — the heaviest decode now has the thermal-cancel hook every other decode path has), self-validating `ImageGenLimits`, a `size` validated-but-carried comment, and a TODO for the output-size cap. Re-verified green.

### 3. MediaPipe backend impl + dep — MERGED as PR #64 (`058c0cb`); "conservative slice", endpoint STILL 501
Picked the conservative path (vs full-backend-now / device-prototype-first). Grounded the design first via a document-specialist deep-research pass on the REAL MediaPipe API (corrected several plan assumptions — captured in the plan's "VERIFIED API REALITY" block):
- **NEW `imagegen/MediaPipeImageGenerator.kt`** — impl against the verified API: `ImageGeneratorOptions.builder().setImageGeneratorModelDirectory(dir)` (model path is a **DIRECTORY** of ~hundreds of fp16 `.bin` files + bpe vocab, ~1.9 GB — NOT a single file) → `createFromOptions`; stepwise `setInputs`+`execute(showResult)` loop honoring `shouldCancel`; `generatedImage()`→`BitmapExtractor.extract`→PNG; `close()`. `synchronized(loadLock)` double-checked load (mirrors `EmbeddingGemmaEmbedder`). Device gates: **arm64-v8a only** (JNI .so is arm64), **OpenCL GPU required**, **API ≥31**, **~7 GB RAM floor** (OOMs on 6 GB at 512×512). `generate(prompt, iteration: Int, seed: Int)`; the interface's `Long?` seed is narrowed to low-32-bits explicitly.
- Dep `tasks-vision-image-generator:0.10.26.1` as **`implementation`** (both flavors); manifest `libOpenCL-pixel.so`.
- **NOT REGISTERED / never instantiated** → endpoint stays an honest 501. **Authoritative degoogled dex scan: `assembleDegoogledRelease` → GMS/aicore/mlkit = 0, with 949 mediapipe classes dexed in** (minify off → meaningful) + 341 datatransport. Closes Task-0's deferred "re-run with real referencing code". Both flavor units green; code-reviewer APPROVE (applied: explicit seed narrowing + synchronized load/close).

### RESUME HERE (#16) — only the device/artifact-gated tail remains
- ✅ Scaffold (#63) + backend impl/dep (#64) MERGED. Endpoint is an honest 501 until a generator registers.
- **NEXT (needs the Pixel + a hosted artifact — the genuinely blocked part):**
  1. **OOM/memory-coexistence prototype FIRST** (plan's #1 risk): on the Pixel, load MediaPipe SD-1.5 alongside the resident multi-GB LLM + generate one image; measure RSS/OOM + GPU backend. If they can't coexist, the single-flight-alongside-LLM design changes (evict/pause LLM during gen) → don't build the provisioner until this is known. **HARNESS READY (uncommitted throwaway): `src/androidTest/java/cc/grepon/relais/ImageGenCoexistenceProbe.kt`** — compile-verified; warms the LLM, runs the real `MediaPipeImageGenerator` against pushed bins, snapshots totalPss/nativeHeap/availMem at baseline→warm→load→generate, asserts a non-blank 512×512 PNG, logs OOM loudly. Needs ~1.9 GB bins pushed to `/data/local/tmp/relais/imagegen/bins` (override `-e model_dir`); run with `-e RELAIS_PROBE 1`. Producing the bins (convert.py) is the prerequisite.
  2. **Produce + host the converted bins:** download `v1-5-pruned-emaonly.ckpt` (4.27 GB, public HF, NO token) → MediaPipe `convert.py` (`.ckpt` only, needs torch) → ~1.9 GB `bins/` dir → host as a `.tar.gz` at an **operator-set URL** (`RelaisConfig`, mirrors the embedder's HF-token model).
  3. **`ImageModelProvisioner`** (download archive → unpack → verify → reuse-if-complete) + **registration wiring** behind an opt-in + provisioned gate, THEN flip the endpoint to 200.
- Carry into the backend PR: thermal-cancel should map to **503** (currently the impl's cancel `error()` → outer-catch 500 — there's a `TODO(#16 route follow-up)` at the throw); the stepwise `execute` step-count contract needs device confirmation; the `n×~15s` vs client-timeout + output-size cap MEDIUMs from #63.
- ⚠️ **BUILD LEVERS unchanged:** `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest` (bare ambiguous); ONE flavor per gradle invocation (OOM) — but unit tests for BOTH in one invocation was fine this session; adding a NEW dep needs NETWORK (not `--offline`) the first build; `assembleDegoogledRelease` + dex scan is the GMS gate (minify off → unused deps still dexed, so the scan is meaningful).

---

## 2026-06-20 — #15 NFC MERGED (#62). THREE merges total (#61 triage, #60 rename, #62 NFC). Buildable backlog EMPTY. Audio + image-gen plans written.

**`main` @ `4388464` (#62 NFC) → `0131248` (#60 rename) → `7fe0310` (#61 triage). ZERO open PRs.** This session shipped the last three backlog items and cleared the owed device gates; remaining work is plans + decisions + physical-only validation.

### Shipped + merged this session
- **#7 notification triage → PR #61** (opt-in, digest+urgent, 60-min; on-device-PROVEN on rango). Detail in the section below.
- **internal Gallery→Relais rename → PR #60.**
- **#15 NFC tap → workflow → PR #62** (`feature/relais-nfc-workflow`, merged 20:52Z). NDEF tag `cc.grepon.relais://workflow/<templateId>` (+ `?q=`) → resolve #12 template → run via `RelaisShareService` (gained an `EXTRA_SYSTEM` override) → VISIBILITY_PRIVATE notification. **Trust (AskUserQuestion):** opt-in (`RelaisConfig.nfcEnabled` default false), tag content UNTRUSTED (capped, plain inference only — no tools/LAN), never cold-starts. Files: `nfc/` (NfcWorkflowParser pure+12 tests, NfcTagWriter, RelaisNfcActivity exported NDEF trampoline scoped to scheme/host, NfcWriteActivity Compose writer). No flavor split (plain AOSP). code-reviewer **APPROVE** (0 C/H; exported-surface bounded). ⚠️ **2 MEDIUM follow-ups (non-blocking):** the `default` builtin template has empty system → NFC run falls back to the share "summarize" system (document/clarify); tag-bounce can post a benign "busy" notification (consider a debounce). ⚠️ **OWED on-device (needs a PHYSICAL NFC tag + live node):** write a tag → tap → verify the template runs + private notification; disabled → inert. NOT yet validated on hardware.

### POST-MERGE device-gate sweep on rango (Pixel 10/G5, GrapheneOS, Android 16) — PROVEN
- **GrapheneOS share + notification round-trip PROVEN:** text share "What is the capital of France?" → trampoline → FGS → on-device inference → result notification text=**"Paris"** (⚠️ fully-quote the `am` command or the prompt re-splits → `pkg=is`).
- **#3 Glance widget PROVEN (run-live + off-guard):** LIVE → tap "Terse Coder" → `WidgetPromptWorker` SUCCESS → result rendered; OFF → "open app to start" + `WidgetActions: node not ready … cold-start guard`, no cold-start. ("clear" sub-action not conclusively exercised.) 📌 **FOLLOW-UP (JD asked):** add **multiple widget sizes/instances** later (currently single).
- Device left on a feature build (≈ current main behaviorally for share/widget), **node may be running**, widget on homescreen, NFC OFF/access-revoked, a few test notifications in the shade.

### Plans written this session (not built)
- **Audio input → `.claude/PRPs/plans/wire-audio-input-api.plan.md`.** ⚠️ **PROBE-FIRST CORRECTION:** audio is ALREADY wired end-to-end through `/v1/chat/completions` (parser `input_audio` part, `RelaisRequest.audioWav`, `Content.AudioBytes` in stream/tool/history paths, `audioBackend=Backend.CPU()`, GPU-forcing `BackendSelector`, a JVM parser test, AND a committed on-device test `RelaisNodeTest.g1_residentMultimodalViaService`). The "audio not exposed" hunch was WRONG (the repo's recurring verify-the-AAR trap). Residuals: optional `RelaisInference` facade audio param, docs, verify the resident model ships the audio encoder. On-device HTTP verify was **thermally blocked** on rango (5/5 G5 shed); the in-process `RelaisNodeTest` is the proof path around the shed.
- **Image generation → `.claude/PRPs/plans/feature-16-image-generation.plan.md`.** Deep research: **no on-device image gen on LiteRT-LM** (it's text-out only). Only viable path = **MediaPipe Image Generator (SD v1.5)**, `com.google.mediapipe:tasks-vision-image-generator`, ~15s/image GPU, model too large to bundle (provision), **officially unmaintained**. Plan = new `imagegen/` runtime mirroring #6 embeddings + OpenAI `POST /v1/images/generations` (base64 PNG). **Top risk: memory coexistence with the resident multi-GB LLM (OOM)** — prototype that first. Flavor: `com.google.mediapipe` is NOT on the degoogled dex blocklist, so it ships in both flavors UNLESS it transitively pulls play-services (Task 0 verifies).

### RESUME HERE
- **Buildable backlog is EMPTY** — #1–#14 + #7 + #15 all shipped. Remaining is plans/decisions/physical:
  - **Build image-gen (#16)** — plan ready; start with the Task-0 GMS/flavor dependency check (no device) + the Task-2 memory-coexistence prototype (device) before the full build.
  - **Finish audio (#16-audio plan)** — small: facade param + docs + verify the encoder (in-process probe around the G5 shed).
  - **OWED on-device (physical):** #15 NFC real-tag tap; multiple-widget-sizes (#3 follow-up).
  - **JD no-code:** LiteRT-LM **#2566** nudge ([[relais-g5-e4b-crash-upstream]]); **ROTATE the HF token** (still exposed — was visible in a control-panel screenshot this session).
- ⚠️ **BUILD LEVERS (unchanged):** `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest` (bare ambiguous); ONE flavor per gradle invocation (OOM); `:app:clean` after switching branches across the #60 rename (stale-Hilt: GalleryApplication↔RelaisApplication); degoogled dex GMS=0; AboutLibraries needs network.

---

## 2026-06-20 — Feature #7 notification triage MERGED (PR #61) + internal rename MERGED (PR #60).

**`main` @ `0131248` (#60 rename) on top of `7fe0310` (#61 triage). BOTH PRs squash-merged this session; ZERO open PRs.** Triage was on-device-PROVEN on rango before merge (see below).

### POST-MERGE device-gate sweep on rango (Pixel 10/G5, GrapheneOS, Android 16) — 2026-06-20
Cleared long-owed physical gates while rango was connected + unlocked + node live (E2B warm, thermal cool, `smoke ok` decode):
- **GrapheneOS share + notification round-trip → PROVEN.** Fired a real text share into the exported `RelaisShareActivity` (⚠️ **fully-quote the `am` command** or multi-word text re-splits → `pkg=is`): "What is the capital of France?" → trampoline (`BAL_ALLOW_PERMISSION`) → FGS → on-device inference → result notification `Relais · result` text=**"Paris"** on GrapheneOS (`POST_NOTIFICATIONS` granted via `pm grant`+`appops`). Clipboard stays locked on GrapheneOS (notification is the authoritative observable).
- **#3 Glance widget → PROVEN (run-live + off-guard).** JD physically added the widget (the one step adb can't do — `appwidget` only has `grantbind`). Verified by driving taps: **LIVE** → tap "Terse Coder" → `WidgetPromptWorker` → `Worker result SUCCESS` ~6s → result rendered in-widget (```\nOK); header "relais ● live". **OFF** (node stopped) → header "relais ○ off — open app to start", buttons dimmed, tap → logcat `WidgetActions: node not ready; widget tap ignored (cold-start guard)` — **NO cold-start**. ⚠️ "clear" sub-action NOT conclusively exercised (result-area tap while off had no effect; clear affordance may be live-only).
- **📌 FOLLOW-UP (JD asked, 2026-06-20):** widget currently offers a **single size/instance** — add **multiple widget sizes (responsive Glance `SizeMode`) / multiple instances** later. Not yet planned/ticketed.
- Device state: rango on the feature build (== current main behaviorally for share/widget; pre-#60 rename, which doesn't touch these paths), **node STOPPED**, widget left on homescreen, port-forward 18080→8080 left up, a few test notifications left in the shade (benign).

### Built backlog feature #7 — on-device notification triage → PR #61 (`feature/relais-notification-triage`, branched off main)
Implements `.claude/PRPs/plans/feature-backlog-15-plans.md` §"Feature 7 — Notification Listener + AI triage". ⚠️ **GOTCHA: backlog feature numbers ≠ GitHub issue numbers.** GitHub issue #7 is a CLOSED de-Google refactor; "notification triage" is the *backlog* #7. Don't conflate (cost a beat this session).
- **JD decisions (via AskUserQuestion):** ship the conservative design; behavior = **digest + urgent surfacing**; cadence = **periodic 60-min** default (clamp 15–1440).
- **Design:** `RelaisNotificationListenerService` (opt-in + system Notification Access) → in-app **default-deny allowlist** gates reads → buffered in an in-memory ring (`NotificationTriageBuffer`, no disk) → `TriageUrgentWorker` (rate-limited CAS debounce `TriageRateLimiter`, ≤1 inference/cooldown, KEEP unique work) surfaces URGENT; periodic `TriageDigestWorker` (battery-not-low; `inProgress` AtomicBoolean gate shared with the "Triage now" entry so one digest at a time) summarizes the rest. Both workers gate `isReady`+`shouldShed`→`retry` (never drop), remove-by-key on success. Each notif delivered ONCE (digest excludes URGENT-classified records). **Zero egress** (in-process `RelaisInference.completeText` only — never LAN/log/metric-label; metrics are 2 content-free counters), VISIBILITY_PRIVATE, kill switch = revoke access (authoritative) / in-app disable cancels both workers + clears buffer. Prompt-injection fenced as DATA + closed-vocab parser (URGENT|NORMAL|LOW, unknown→NORMAL fail-safe).
- **Files:** new `cc/grepon/relais/triage/` (11 src + 5 tests), manifest (listener `<service>` behind `BIND_NOTIFICATION_LISTENER_SERVICE` + control `<activity>`, both exported=false; scoped `<queries>` MAIN/LAUNCHER for the allowlist picker, no QUERY_ALL_PACKAGES), `RelaisControlActivity` "NOTIFICATION TRIAGE ›" link, `RelaisMetrics` 2 counters, `strings.xml` 2 labels, `docs/notification-triage.md`. **No flavor split** (plain AOSP NotificationListenerService — works full+degoogled).
- **Verified:** both flavors compile; `testFullDebugUnitTest`+`testDegoogledDebugUnitTest` green; AGPL headers correct (net-new files get `Copyright (C) 2026 Entrevoix / grepon.cc` AGPL header — NOT Apache). ⚠️ Hit the **stale-Hilt gotcha AGAIN**: build dir had PR#60's `RelaisApplication` codegen but this branch (off main) still has `GalleryApplication` → `hiltJavaCompileFullDebug: Could not find class file for RelaisApplication` → fixed with `:app:clean`.
- **Reviews:** code-reviewer + adversarial critic → both APPROVE-WITH-FIXES; fixed all MEDIUMs (digest double-run via shared inProgress gate, kill-switch now cancels BOTH workers + UNIQUE_NOW, once-only delivery, allowlist surfaces stored-but-unlisted pkgs as removable, drain-on-disable). Delta-review of the fixes → APPROVE; applied its precision fix (digest filters URGENT-classified so "delivered once" is exact). Both confirmed zero-egress holds across every attacked path.

### ON-DEVICE PROVEN on rango (Pixel 10/G5, GrapheneOS, Android 16) — 2026-06-20
Throwaway `TriageDigestProbe` (androidTest, in-process warm like BatchE2eProbe) → **OK (1 test), 41.7s**, G5 thermal did NOT shed. Real-model evidence (logcat):
- **Urgency pass:** "card payment $420 overdue — act today" → **URGENT**; "50% off socks" → **LOW** (`1: URGENT | 2: LOW` → `{k1=URGENT, k2=LOW}`). Semantically correct.
- **URGENT** (payment) surfaced to the urgent channel + removed; **DIGEST** over the remainder → "Flash Sale Alert: Get 50% off all socks…" posted as "Relais · 1 notification", **VISIBILITY_PRIVATE asserted**; buffer drained. **Once-only delivery confirmed** (payment excluded from digest).
- **Listener** approved + bound by the OS (`dumpsys notification`), manifest `<service>`+BIND perm correct.
- **`TriageControlActivity` renders to DESIGN** (amber/charcoal/monospace): ON/OFF, NOTIFICATION ACCESS granted/not, URGENT SURFACING, **DIGEST EVERY −60 min+**, TRIAGE NOW ›, default-deny ALLOWLIST listing launcher apps via the `<queries>` element. Kill switch proven: DISABLE TRIAGE → OFF + revoke → "not granted".
- ⚠️ rango is **GrapheneOS** (allowlist showed `app.grapheneos.apps`, `app.attestation.auditor`) — notifications default importance NONE there, but `pm grant POST_NOTIFICATIONS` + the digest posted fine. Probe (`TriageDigestProbe.kt`, src/androidTest) is **UNCOMMITTED** — offer to add to PR #61 as the on-device gate (like #59). Device left on the feature build, **triage OFF + access revoked** (clean), test APK uninstalled.

### RESUME HERE
- **JD: merge PR #61 (triage) and #60 (rename)** — both green + reviewed. If merging #60 first, watch the squash-rebase stacked-branch gotcha (prior section); #61 is off main so it rebases cleanly.
- On-device triage smoke (above) once a node is live + unlocked.
- Remaining decision-gated: **#15 NFC** (trust model). Physical-only: #3 widget homescreen add; GrapheneOS share/notif gates.
- JD no-code: LiteRT-LM **#2566** nudge ([[relais-g5-e4b-crash-upstream]]); rotate HF token.

### ⚠️ BUILD LEVERS (unchanged — flavors): `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest` (bare testDebugUnitTest ambiguous); ONE flavor per gradle invocation (both OOM the 2GB daemon); degoogled dex GMS=0 (CI-enforced); AboutLibraries needs network (not --offline) but the rest builds offline.

---

## 2026-06-20 — Session wrap: 5 PRs merged → `main` @ `faaf8e6`; #13/#14 + #2 on-device-PROVEN; rename up as PR #60 (green). START HERE.

**`main` @ `faaf8e6`. One OPEN PR: #60 (internal rename, CI-green, ready to merge — JD merges).** rango (Pixel 10/G5, `57211FDCG0023C`) = fresh full debug build installed, **node LIVE**, QS tile added.

This session in order: (1) merged the 3 stacked PRs #57→#56→#58; (2) shipped + merged the #13/#14 e2e probes as #59; (3) **proved #13, #14 (incl. signed HTTPS webhook), and #2 QS tile on-device on rango**; (4) internal-identifier rename → PR #60. Full detail below.

### 1. Merged the 3 stacked PRs (squash, in order #57 → #56 → #58)
- **#57** `feat/relais-branding` → `9d1f1f7` (independent).
- **#56** `feat/degoogled-flavor` → `ca5f3f5` (the flavor split).
- **#58** `feat/aboutlibraries-licenses` → `7f6a57a` (FOSS license screen).
- ⚠️ **SQUASH-REBASE GOTCHA (will recur for any stacked branch):** after #56 squash-merged, #58 flipped **CONFLICTING** — its branch still carried #56's *individual* commits (`054f6eb`+`e097d30`) while `main` now had #56 as one squash, so the 3-way merge collided. Fixed by rebasing only the license commits onto the new main, dropping the redundant pair: `git rebase --onto origin/main e097d30 feat/aboutlibraries-licenses` → diff collapsed to license-only → `git push --force-with-lease` → CI re-green → merged.

### 2. #13/#14 on-device e2e probes — PR #59 (`feat/ondevice-gates-13-14`, test-only)
Implements `.claude/PRPs/plans/ondevice-gates-13-14.plan.md` Tasks 2–3:
- **`ShareImageProbe`** (`androidTestFull`): MediaStore `content://` PNG with known text → real `ACTION_SEND` into `RelaisShareActivity` → service → OCR → resident-model inference. Clipboard is best-effort (A10+ blocks bg reads); authoritative observable = logcat + result notification.
- **`BatchE2eProbe`** (`androidTest`): `POST /v1/batch` to a loopback `RelaisHttpServer` → enqueues into shared Room DB → kicks the real WorkManager `BatchWorker` → polls `GET` to `completed` → asserts a real LLM answer (the worker-through-LLM half `BatchProbe` deferred).
- **Webhook delivery** stays an operator runbook step (Task 4 — webhook.site, or the hermetic `.claude/scripts/webhook-receiver.py` + `adb reverse`). Untracked throwaway receiver added.
- **Verified `:app:compileFullDebugAndroidTestKotlin` BUILD SUCCESSFUL** (one fix: `RelaisInference` is in `cc.grepon.relais.core`, needs an explicit import). androidTest isn't in CI, so #59 CI only covers the unchanged app build/units/scans.
- ⚠️ **DOC CORRECTION:** `RelaisConfig.shareEnabled` **DEFAULTS TRUE** (line 369) — the plan + prior handoff said "default-OFF," which is **wrong**. The probe still calls `setShareEnabled(true)` defensively.

### ON-DEVICE VALIDATED on rango (2026-06-20) — both gates GREEN
- **#14 `BatchE2eProbe` → `OK (1 test)` in 24.7s.** Engine warmed E2B (`Resident multimodal engine ready: true`), batch submitted → real `BatchWorker` ran it through the LLM → polled to `completed` with a real answer. G5 thermal shed did NOT fire this run.
- **#13 `ShareImageProbe` → `OK (1 test)`** + **DEFINITIVE notification evidence:** `dumpsys notification` shows `channel=relais_share_result`, title `Relais · result`, text = E2B's answer *"…I need the content of the \"RELAIS INVOICE 4242\"…"*. The OCR'd marker **"RELAIS INVOICE 4242" appears verbatim in the answer** → proves content:// share → trampoline (FGS `BAL_ALLOW_PERMISSION`) → ML Kit OCR (`mlkit-google-ocr-models` loaded) → `completeText` → result. (Clipboard stayed unreadable from the test = A10+ restriction, as designed; the notification is the authoritative observable. POST_NOTIFICATIONS was already `granted=true`.)
- **Probe-run facts:** `am instrument` force-stops the pkg (watchdog re-warms via `should_run=true`); both probes warm the engine in-process so no resident node needed; instrumentation runs as `uidState: TOP` so the share FGS start is allowed. The benign `Stop FGS timeout` ~15s in is after the result was already delivered.

- **#14 webhook over-the-wire signed delivery → PROVEN.** Throwaway `WebhookDeliveryProbe` (uncommitted, deleted after) submitted a batch with a public `https://webhook.site/<uuid>` webhook → SSRF guard passed (202) → worker ran E2B → `WebhookDelivery` POSTed over real TLS → webhook.site received `{job_id,status:completed,result:{…}}` with `X-Relais-Signature: sha256=…`; **HMAC-SHA256 recomputed over the exact 754 raw bytes MATCHED.** Cleanup done: device logcat scrubbed (`logcat -c`), throwaway probe deleted, clean androidTest APK reinstalled (no secret-dumper), scratch removed. (The webhook HMAC secret was exposed in this session's logs — regenerable on the node if that matters.)

**ALL #13/#14 on-device gates are now CLOSED.** PR #59 (the 2 committed probes) is MERGED → `main` @ `faaf8e6`.

### Device gate sweep — #2 QS tile + #3 widget (2026-06-20)
- **#2 QS tile → FULLY device-proven.** Driven via `cmd statusbar`: **must `expand-settings` first** so the TileService starts listening (bare `add-tile`+`click-tile` does NOT invoke `onClick`); use the FQN `cc.grepon.relais/cc.grepon.relais.tile.RelaisTileService`. OFF→click→**START** (logcat `tile onclick … Background started FGS … RelaisNodeService`, `should_run=true`); LIVE→click→**STOP** (`RelaisNodeService: Node stopped`, `should_run=false`). Both directions clean. (Pure `tileAction`/`tilePresentation` already JVM-unit-tested.)
- **#3 Glance widget → NOT headlessly drivable (confirmed), needs a human.** The `appwidget` shell only offers `grantbind` (no add/instantiate); no bound instances exist; `WidgetPromptWorker.doWork` looks up the `glanceId` (needs a real bound widget) BEFORE the cold-start guard, so even a worker probe bails at `Result.failure()`. The cold-start guard is 3-layer code-gated (`canRun` in `provideGlance`, `shouldRunWidgetPrompt` in `RunPromptAction`, `isReady` re-assert in the worker) + the pure logic is JVM-unit-tested. **OWED (physical only):** add the widget to a homescreen across sizes → tap run (live→runs, off→"open app to start", no cold-start) → tap clear.

### RESUME HERE
- The #13/#14 e2e gates are **complete**; #2 QS tile device-proven; #3 widget needs a physical homescreen add (only remaining device gate that can't be driven headlessly). GrapheneOS share/notification gates also still physical.
- **Internal-identifier rename → PR #60** (`chore/internal-degoogle-rename`): `Theme.Gallery`→`Theme.Relais`, `GalleryApplication`→`RelaisApplication` (+ the `applicationName` manifest placeholder). Both flavors clean-build + unit tests pass; code-reviewer APPROVE. ⚠️ GOTCHA: first incremental build failed `hiltJavaCompile: Could not find class file for …GalleryApplication` — **stale Hilt-generated code after an app-class rename; needs `:app:clean`** (source was already clean). ⚠️ CORRECTION: `aicore_*` strings are NOT dead — they're referenced by main-flavor `AICoreAccessPanel`/`ChatPanel`; "trim from degoogled" is a flavor-source-set migration, not a cleanup. Many other `Gallery*` symbols remain by design (`GalleryApp`/`GalleryNavHost`/`GalleryTheme`/`GalleryEvent`/the `AiEdgeGallery` JS-bridge name/`rootProject.name`) — some are wire/analytics contracts; renaming them is behavioral, left intentionally.
- Decision-gated: #15 NFC + #7 notification-triage. Possible: cut a full/degoogled release for the IzzyOnDroid channel.
- Still physical-only (need hands at the device): #3 widget homescreen add/run/clear; GrapheneOS share/notification gates.
- **JD action (no code):** post the LiteRT-LM **#2566** nudge ([[relais-g5-e4b-crash-upstream]]).

### ⚠️ BUILD LEVERS (unchanged from #56 — flavors)
- `:app:testDebugUnitTest` is AMBIGUOUS → `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest`.
- Build ONE flavor per gradle invocation (both at once OOMs the 2GB daemon).
- Degoogled APK dex must be 0 `com.google.(android/(gms|apps/aicore)|mlkit)` (CI-enforced).
- AboutLibraries is NOT in the gradle cache → its build needs NETWORK (not `--offline`).

---

## 2026-06-20 — FOSS license screen (PR #58) + branding→RELAIS (PR #57) + on-device gate sweep + #13/#14 plan. THREE PRs GREEN, awaiting JD merge.

**`main` @ `9f4556f` (unchanged). THREE PRs up, all CI-green, awaiting JD merge (I do not merge):**
- **#56** `feat/degoogled-flavor` — the full/degoogled flavor split (the 2026-06-19 work below).
- **#57** `feat/relais-branding` (off main) — user-facing "Google AI Edge Gallery" → RELAIS.
- **#58** `feat/aboutlibraries-licenses` (stacked on #56; retargeted to main for CI) — FOSS license screen.

**Merge order:** #57 anytime (independent) → **#56** → **#58** (its branch contains #56's commits, so its diff collapses to just the license work once #56 lands; merging #56 first keeps #58 clean).

### 1. FOSS license screen — PR #58 (replaces the GMS oss-licenses viewer in BOTH flavors)
The GMS oss-licenses plugin only ever generated a hollow `"Debug License Info"` placeholder (verified — both flavors). Swapped to **AboutLibraries**.
- ⚠️ **KEY GOTCHA (memory [[relais-flavor-split]]):** the prebuilt **`aboutlibraries-compose-m3` UI CRASHES at runtime** on this project's Compose BOM 2026.02.00 — `NoSuchMethodError: FlowRow(...FlowRowOverflow...)` at `SharedLibrariesKt.Library`. 14.x would fix it but needs **compileSdk 36** (project is on 35); 11.6.3 (the compileSdk-35-safe line) is too OLD for the new Compose. **Resolution: drop compose-m3, keep `aboutlibraries-core 11.6.3` (pure-Kotlin parsing) + a hand-rolled DESIGN.md Compose screen** (`ui/home/LicensesActivity.kt`: LazyColumn + per-library license dialog). Compile / CI / dex-scan ALL passed while the prebuilt screen was DOA — **only an on-device render caught it. LESSON: verify on-device render for prebuilt-Compose-lib UIs; build-green ≠ runtime-safe.**
- **Reviews:** code-reviewer APPROVE-WITH-FIXES (fixed: empty/error+loading state, dropped the LazyColumn `key` collision, dialog `heightIn`, a11y `onClickLabel`, stale comment). An adversarial critic **BLOCK**ed claiming the `libraryColors` param names don't exist in 11.6.3 → **FALSE POSITIVE**, disproved by a forced clean recompile (`compileDegoogledDebugKotlin --rerun-tasks` = BUILD SUCCESSFUL). LESSON: settle contradictory reviewer API claims with the compiler, not decompilation.
- **On-device PROVEN on rango (unlocked):** `LicensesActivityProbe` (ActivityScenario — **NOT** Espresso/Compose-test, whose input-injection reflection is broken on Android 16) → **OK (2 tests)**: data loads (232 libs full / 205 degoogled — not hollow) + activity reaches RESUMED + draws, no crash. Both flavors green; degoogled dex GMS=0.
- **Also fixed an orphaned-probe bug:** `ImageOcrProbe` was in `src/fullAndroidTest/` (an UNRECOGNIZED source set) since #56 → never compiled into any test APK. Moved to **`src/androidTestFull/`** (the correct AGP flavor-androidTest dir). CI doesn't build androidTest, so it slipped through.

### 2. Branding → RELAIS — PR #57 (off main, CI green)
User-facing "Google AI Edge Gallery" → the **RELAIS** wordmark (DESIGN.md: amber, monospace), 8 spots: `app_name`, the home animated title (`AppTitle` + `AppTitleGm4` → single "RELAIS"), ToS welcome title, the Gemma-ToS line, the MCP "learn more" string, and the two notification-channel **display names**.
- **Left untouched (intentional — "user-facing text only"):** the FUNCTIONAL `github.com/google-ai-edge/gallery` URLs (the model-allowlist the node actually FETCHES from — changing them breaks provisioning) + source-attribution links; the `com.google.ai.edge.litertlm`/`litert` dep package names (Google's real libs); internal identifiers (`Theme.Gallery`, `GalleryApplication`) and notification channel **IDs** (changing an ID orphans the channel).

### 3. On-device gate sweep (rango) — partial
- **Full ("googled") build** installed + **serves inference on G5** (E2B via PR #19 substitution; E4B SIGSEGVs); no GMS-missing crash.
- **#13 OCR engine PROVEN:** `ImageOcrProbe` OK — bundled ML Kit reads rendered text via the production `fromBitmap` path.
- **Degoogled build** also proven serving earlier (06-19): node serves on G5, no GMS-missing crash, image-share degrades to "no text found" (OCR stub).
- ⚠️ **Device gotchas learned:** on the foldable, `screencap` returns BLACK when locked/dozing/wrong-display; **ActivityScenario can't reach RESUMED behind a SECURED keyguard — rango is PIN-locked, so JD must unlock for RESUMED-draw probes** (`wm dismiss-keyguard`/swipe don't clear a secured keyguard); adb sees no device until USB-debugging is authorized (restart adb server helps).

### 4. Plan to CLOSE the remaining e2e gates: `.claude/PRPs/plans/ondevice-gates-13-14.plan.md`
Self-contained plan (via `/prp-plan`) to finish **#13** (full content-URI share→OCR→inference→result via a new `ShareImageProbe` in `androidTestFull`) and **#14** (worker-through-LLM via a new `BatchE2eProbe` + a **real signed HTTPS webhook** via a public receiver like webhook.site — passes the SSRF guard with no allowlist, exercises the true TLS/SNI/chain path; hermetic HTTP fallback = `adb reverse` + allowlist `localhost`). Test-only (no prod changes). Gotchas captured: share **default-OFF** (`RelaisConfig.shareEnabled`), `isReady` needs a warm decode, **clipboard read is restricted on A10+** (use logcat + notification), SSRF guard blocks private/loopback IPs, HMAC over the exact envelope bytes. **Implement AFTER the 3 PRs merge** (the #13 probe needs the `androidTestFull` source set), branch off main; rango must be live + UNLOCKED.

### RESUME HERE
1. **Merge the 3 PRs:** #57 (anytime) → #56 → #58. All green.
2. **Close #13/#14 e2e** per `.claude/PRPs/plans/ondevice-gates-13-14.plan.md` (needs rango live + unlocked). Can start on `feat/aboutlibraries-licenses` now (it has the flavor split) if not waiting for merges.
3. **JD actions (no code):** post the LiteRT-LM **#2566** nudge ([[relais-g5-e4b-crash-upstream]]); **ROTATE the HF token**.
4. **Deferred:** GrapheneOS share/notification gates; #15 NFC + #7 notification-triage (decision-gated); internal-identifier rename (`Theme.Gallery`/`GalleryApplication` — branding was scoped "text only").

### ⚠️ BUILD LEVERS (flavors — from #56; still apply)
- `:app:testDebugUnitTest` is **AMBIGUOUS** → `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest`.
- Building BOTH flavors in ONE gradle invocation **OOMs the 2GB daemon** → build one flavor per invocation.
- Degoogled APK dex must be **0** `com.google.(android/(gms|apps/aicore)|mlkit)` (CI-enforced).
- **AboutLibraries is NOT in the gradle cache → its build needs NETWORK** (NOT `--offline` for tasks resolving it); the rest of the repo builds offline.

### Device state
rango (Pixel 10 / G5 / `57211FDCG0023C`): full "googled" debug build + full androidTest installed; node may be running; **PIN-secured** (JD unlocked it during the session). comet (Pixel 9) untouched.

---

## 2026-06-19 — full/de-Googled flavor split (PR #56, CI GREEN, awaiting JD merge) + G5 crash thread closed out

**`main` @ `9f4556f` (unchanged). All work is on branch `feat/degoogled-flavor` → PR #56 (OPEN, CI GREEN, both reviewers APPROVE — JD merges; I do not).** Post-queue session: built the DECIDED full/degoogled build-variant split, and closed the loose G5 upstream-bug thread.

### 1. full / de-Googled product-flavor split — PR #56, CI GREEN, AWAITING MERGE
`dist` flavor dimension: **`full`** (Play Store; keeps the GMS deps) and **`degoogled`** (IzzyOnDroid / self-hosted F-Droid; excludes ALL of them). Inference is already non-GMS (bundled litertlm+litert), so degoogled is a fully functional node — it drops ML Kit OCR (#13), AICore/Gemini-Nano, and the Play-Services OSS-licenses viewer. (Strict f-droid.org main repo still off the table while the LLM core is the proprietary litertlm AAR; "de-Googled" = no Play-Services runtime requirement.)
- **Seams** (`src/main/`→`src/full/` + GMS-free `src/degoogled/` stubs mirroring the exact public surface main uses): `RelaisAicore` (available()=false; generate() unreachable/fail-loud), `runtime/aicore/AICoreModelHelper : LlmModelHelper` (5 overrides no-op/error + downloadModel→onError), `share/ImageTextRecognizer` (recognize()=emptyList()). New flavored `ui/home/OssLicenses` seam gates the Settings "third-party libraries" row.
- **Manifest split:** AICore `BIND_SERVICE` permission + the 2 OSS-licenses activities → `src/full/AndroidManifest.xml`; degoogled merged manifest is GMS-free.
- **AICORE catalog gate (the review fix):** per-flavor `BuildConfig.SUPPORTS_AICORE` (full=true/degoogled=false) short-circuits `isAICoreSupported()` (`common/Utils.kt`) so AICORE models are filtered out of the degoogled catalog ENTIRELY (not listed-then-perma-failed). Was the critic's one MAJOR; fixed + independently re-verified RESOLVED.
- **Incidental:** `customtasks/tinygarden/TinyGardenScreen.kt` used Guava `BaseEncoding.base64()` reaching main ONLY transitively via the GMS deps → swapped to `java.util.Base64` (byte-identical RFC-4648; easter-egg literal still matches). `ImageOcrProbe` (imports ML Kit) → `src/fullAndroidTest/`.
- **Acceptance gate (CI-enforced):** degoogled APK dex = **0** `com.google.android.gms` / `apps.aicore` / `mlkit` class descriptors. `build_android.yaml` scans the degoogledRelease APK and fails on any hit. CI green on all checks (Build 9m45s incl. the gate; unit tests both flavors; gitleaks/trufflehog/headers).
- **Reviews:** code-reviewer APPROVE-WITH-FIXES (no blockers) + critic APPROVE (after the AICORE fix). 2 commits on the branch (split + review-fix).

### ⚠️ BUILD-LEVER CHANGES (flavors break the pre-existing commands — see memory [[relais-flavor-split]])
- `:app:testDebugUnitTest` is now **AMBIGUOUS** → use `:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest`. (CI updated to match.)
- `:app:assembleDebug` now builds BOTH flavors; `assembleRelease` aggregates both.
- **Building BOTH flavors in ONE gradle invocation OOMs the 2GB daemon** (`org.gradle.jvmargs=-Xmx2048m`) → build ONE flavor per invocation: `./gradlew :app:assembleDegoogledDebug :app:testDegoogledDebugUnitTest -x lint --offline --max-workers=2`, then the full equivalent.
- Acceptance scan: `unzip -p <degoogled apk> 'classes*.dex' | strings | grep -Ec "Lcom/google/(android/(gms|apps/aicore)|mlkit)"` → must be 0.

### 2. G5 E4B inference crash — CLOSED OUT (upstream-only thread; see memory [[relais-g5-e4b-crash-upstream]])
NOT a pending draft — already filed as **google-ai-edge/LiteRT-LM #2566** (2026-06-12, OPEN, no maintainer reply). Reproduces on the latest 0.13.1 → NO version-bump fix exists. Worked around: G5 default pinned to E2B (PR #19), which serves. Updated `.claude/upstream-litertlm-g5-bug-draft.md` (status + a ready-to-post nudge for JD) and banner-marked `pixel-10-inference-crash-investigation.plan.md` RESOLVED. Only open thread is upstream (a maintainer reply / a G5-capable E4B build / an init-time capability error).

### RESUME HERE
1. **Merge PR #56** (CI green, reviewed) → the degoogled story is shippable.
2. **Degoogled follow-ups (buildable, stack on #56):** a self-hosted FOSS license screen for degoogled (the row is currently hidden); trim dead `Theme.Gallery.OssLicenses` + `aicore_*` resources from the degoogled merged resources.
3. **Owed on-device gates (need a device):** degoogled debug smoke (node serves; image-share→"no text found"; Settings hides licenses row); #13 full screenshot→result e2e; #14 worker-through-LLM + over-the-wire HTTPS webhook delivery; GrapheneOS share/notification gates.
4. **Decision-gated:** #15 NFC (dep #12 merged; needs device + a trust-model decision), #7 notification triage (privacy — shelved).
5. **JD to do:** post the #2566 nudge; **ROTATE the HF token** (long-standing).

---

## 2026-06-18 — Overnight autonomous run: #6 embedder + #4 RAG + non-GMS + #9 tools + #14 batch + #13 OCR ALL MERGED. Queue COMPLETE.

**`main` @ `9f4556f`.** JD authorized an autonomous overnight run ("go"): for each queued feature, push → PR → squash-merge on green CI, project loop (TDD → offline build → TWO independent reviewers → fix all C/H/M → on-device validate on rango where possible). Worked the queue top-to-bottom; **the entire queue is now merged.**

### What MERGED this run
- **#6 EmbeddingGemma EMBEDDER (PR #50→#52):** `embed/EmbeddingGemmaEmbedder` demand-driven lifecycle; swapped to a **BUNDLED LiteRT runtime** (`com.google.ai.edge.litert:litert:1.4.2`, `Interpreter implements InterpreterApi`) — **no Play Services dependency**. Flips `/v1/embeddings` 501→200. Reviewers caught a production-unreachable bug (demand-driven 503 + background provision) + a transient-vs-integrity failure split.
- **#4 RAG (PR #51):** `rag/RagStore`+`RagChunker`+`RagVectorCodec`, Room **v2→v3** (`rag_documents`/`rag_chunks`), `POST /v1/rag/documents` + `/v1/rag/query`, per-request opt-in retrieval. IngestOutcome sealed; MAX_CORPUS_CHUNKS=10_000; atomic `withTransaction`.
- **non-GMS runtime (#52):** embeddings on the bundled CPU runtime — the de-Google lever (no `play-services-tflite`). **This is the constraint #13 OCR must not violate.**
- **#9 single-hop node tools (PR #53):** `nodetools/` — `SafeCalculator` (recursive-descent, no eval, depth+char caps), `UnitConvert`, `NodeTools` registry (calculator/datetime/unit_convert/rag_search); `generateWithNodeTools` advertises built-ins, client-name-collision-wins, fences tool output as untrusted DATA. **§4 decision: single-hop only** (Gemma-4 E2B too weak for autonomous agent loops).
- **#14 async batch + signed webhooks (PR #54 → `d942515`):** `POST /v1/batch` queue → Room **v3→v4** (`batch_jobs`) → `BatchWorker` drains → optional **HMAC-SHA256-signed, SSRF-guarded** webhook. Two reviews found real bugs; all fixed before merge: **DNS-rebinding TOCTOU closed by pinning the guard-vetted IP through to a raw-socket TLS connection** (SNI + cert-hostname verify + full chain validation, no redirects); atomic queued→running claim (no double-execution); per-run budget under WorkManager's 10-min ceiling; stale-`running` reaper; transactional queue cap; FD-leak fix; webhook delivery counters. **§4 decision: batch + HMAC webhooks + SSRF guard.** On-device: BatchProbe on rango (SSRF→400, submit→202, status→200). Deferred gates: worker-through-LLM end-to-end + over-the-wire https delivery.
- **#13 screenshot/OCR (PR #55 → `9f4556f`):** share an `image/*` into Relais → on-device OCR (bundled ML Kit Latin recognizer) → recognized text runs through the resident model (same flow as a text share). `share/ImageTextRecognizer.kt` (downsample-before-decode so a 50MP photo can't OOM the node; EXIF rotation; image-count cap; non-GMS `getClient` init failure degrades to "no text found"). `RelaisShareActivity` branches on MIME, collects `content://` URIs (content-only; `file://` rejected; parcelable reads hardened on the exported trampoline), hands them to the service with `FLAG_GRANT_READ_URI_PERMISSION` (recipient-scoped grant survives `finish()`); caption (`EXTRA_TEXT`) prefixes the OCR text. Two reviews → APPROVE-WITH-FIXES (grant-propagation fear refuted; OOM/cap/non-GMS-guard/BadParcelable/caption fixes applied). On-device: `ImageOcrProbe` on rango (bundled recognizer reads rendered text via the production `fromBitmap` path). **⚠️ `com.google.mlkit:text-recognition` is NOT GMS-free** — it transitively pulls `play-services-mlkit-text-recognition` + base/basement/tasks. Deferred gate: full screenshot→result end-to-end (correct-by-design + fails-safe per review).

### ⚠️ HF token (supplied in chat this run) — JD must ROTATE at hf.co/settings/tokens. Not committed; secret-scan (gitleaks+trufflehog) gates CI and passed on every PR.

### RESUME HERE — the queue is EMPTY; remaining work is decision-gated or device-bound
1. **[DECIDED, not built] full / de-Googled build-variant split (Play Store + F-Droid).** JD wants a Play (`full`) flavor and a de-Googled (`degoogled`, for IzzyOnDroid / own F-Droid repo) flavor. **Chosen this run: OCR (#13) ships in the single main build for now; the flavor split is a SEPARATE follow-up.** Plan when picked up: Gradle `productFlavors { full; degoogled }`; `degoogled` excludes the GMS-pulling deps — **`com.google.mlkit:text-recognition` (#13 OCR), `mlkit-genai-prompt` (dormant/AICore — platform-blocked), `play-services-oss-licenses`** — with OCR behind a source-set interface (`src/full/` ML Kit impl + `src/degoogled/` no-op stub). **CAVEAT JD must know: strict `f-droid.org` MAIN repo is OFF THE TABLE** while the LLM core is the proprietary prebuilt `litertlm` AAR; the realistic channel is IzzyOnDroid / a self-hosted repo where "de-Googled" = "no Play-Services runtime requirement" (which #52 already achieved for inference). 100%-FOSS would mean swapping the engine (e.g. llama.cpp/GGUF) — a separate project.
2. **#7 notification triage:** SHELVED (privacy posture — JD's call).
3. **#15 NFC:** depends on #12; left for JD.
4. **Deferred on-device gates owed:** #13 full screenshot→result end-to-end (content-URI grant + service OCR); #14 worker-through-LLM + over-the-wire https webhook delivery; the long-standing GrapheneOS share/notification gates.

---

## 2026-06-17 — #6 EmbeddingGemma TOKENIZER complete (PRs #47 + #48 MERGED, byte-exact vs real model).

**`main` @ `0d0858c`.** JD provided the HF token + accepted the gated EmbeddingGemma license (gated download now works). Built the on-device tokenizer for #6 as a **pure-Kotlin** SentencePiece encoder (NO JNI/NDK — keeps the project pure-JVM; chosen by JD over a SentencePiece native build).

### What MERGED
- **PR #47 (`fc85b42`) — #6 part 1:** pure-Kotlin SentencePiece **Unigram** encoder (`embed/SentencePieceModel.kt` hand-parses the ModelProto protobuf wire format — no protobuf dep; `embed/SentencePieceTokenizer.kt` Viterbi). Byte-exact vs reference `sentencepiece` 0.2.1 on a tiny committed model. Fail-loud guards on unsupported normalization.
- **PR #48 (`0d0858c`) — #6 part 1b:** **BPE** encoder + **USER_DEFINED atomic pre-split**, dispatched by `model_type`. Both reviewers APPROVE after the devil's-advocate caught the USER_DEFINED gap.

### KEY FACTS about the real EmbeddingGemma `sentencepiece.model` (verified by downloading it)
- **It is BPE** (`model_type=BPE`), **262144 vocab**, **identity normalizer (NO precompiled_charsmap)**, `add_dummy_prefix=false`, `escape_whitespaces=true`, `byte_fallback=true`. Special ids: **unk=3, bos=2, eos=1, pad=0**.
- **6410 USER_DEFINED pieces** — 92 multi-codepoint & text-matchable: `▁▁…`/`\n\n…`/`\t\t…` runs (up to 31), `[multimodal]`, `[toxicity=0]`. SP matches these ATOMICALLY (longest-match) before BPE — the tokenizer does this pre-split. (Reviewers initially ASSUMED nmt_nfkc+charsmap; WRONG — it's identity. Don't re-assume.)
- **No charsmap needed.** The fail-loud charsmap guard correctly PASSES this model.

### VALIDATION method (reusable)
Byte-exactness proven 2 ways: (a) committed CI tests use **tiny self-trained SP models** (`sp_tiny.model` unigram, `sp_tiny_bpe.model`, `sp_tiny_bpe_ud.model` with `user_defined_symbols`) — trained via Python `sentencepiece` (host has it: `pip install sentencepiece protobuf`), golden from `EncodeAsIds`; (b) the **real 262k model** (`/tmp/sp/real_spm.model`, gated — NOT committed) validated locally via a throwaway test reading the absolute path (deleted after). The real-model golden incl. prefixes, multi-space/newline/tab runs, `[multimodal]`, ZWJ emoji, ligatures — all byte-exact.

### RESUME HERE — #6 part 2 (the embedder) then #4 RAG
1. **`EmbeddingGemmaEmbedder : RelaisEmbedder`** (`embed/`): apply the prompt prefix (query=`task: search result | query: {q}`, doc=`title: none | text: {chunk}`) → `SentencePieceTokenizer.encode` → BOS(2)+ids+EOS(1) pad to seq_len=512 → **InterpreterApi** (`play-services-tflite-java/gpu/support 16.4.0` already deps) over the SoC `.tflite` (1 int32 input `[1,512]` → 1 float `[1,768]`, already-pooled) → `EmbeddingMath.truncateMrl(256)` then `l2Normalize` → register in `RelaisEmbedderProvider` (flips `/v1/embeddings` 501→200). Download the SoC `.tflite` + `sentencepiece.model` via `RelaisModelProvisioner` + the HF token (`RelaisConfig.setHfToken`).
2. **FIX the `EmbeddingModelSelector` filename bug FIRST** (confirmed via the real HF inventory): SoC variant filenames use `_google`/`_qualcomm`/`_mediatek` but the real files use **`.google`/`.qualcomm`/`.mediatek`** (dot) → every SoC URL 404s. Pin a test to the verified inventory.
3. On-device validate on rango (G5 → `…google.tensor_g5.tflite`, 192MB): download → real embeddings → cosine sanity. (Embeddings are a single forward pass; check whether `/v1/embeddings` admission gates on the G5 thermal-headroom shed — it may, like the ABI path.)
4. Then **#4 RAG** (per `feature-backlog-15-plans.md` recommendation: ingest→chunk→embed 256-dim→Room store→brute-force cosine top-k; retrieval = per-request opt-in flag; default-OFF).

### ⚠️ HF token is in the chat + stashed at `/tmp/relaisrecv2/.hf_token` — JD should ROTATE it at hf.co/settings/tokens after #6.

---

## 2026-06-16 — share-FGS parity (#46 MERGED) + gate sweep (#5/#11/#10 re-validated) + #44 cross-app ok=true still G5-thermal-blocked

**`main` @ `7fc8192`.** Continuation of the #44 work (JD: "keep going 1, 2, 3"). Same loop each time: build → 2 reviewers → consensus → CI green → squash-merge.

### 1. RelaisShareService hardened to #44 parity — PR #46 MERGED → `7fc8192`
The share path carried the SAME two latent bugs the pre-#44 automation path had: (a) **unguarded `startForeground()`** → an OS FGS-start rejection crashed the service uncaught, silently dropping the share → now `runCatching` → "unavailable" notification (gated on `!inFlight` so it can't clobber a live decode's result slot) + stop; (b) **naive `stopSelf(startId)`** → a concurrent busy share tore down the live decode → now main-thread-serialized `stopIfIdle(latestStartId)`; (c) corrected the misleading `scope.cancel()`-releases-lock comment. Both reviewers APPROVE after a delta (KDoc reword + slot guard + timeout-waiver — verified at `RelaisEngine.kt:520`'s 120s latch bound under the engine lock, so no `withTimeout` needed). No JVM-unit-testable pure logic (FGS lifecycle); validated by reviewer interleaving analysis + parity with the on-device-proven #44 service. **Share on-device round-trip stays a deferred gate** (GrapheneOS notif/clipboard locked).

### 2. #44 cross-app `ok=true` — STILL blocked by the persistent G5 thermal-headroom shed
Re-attempted 6 windows after hours of idle (07:23–07:25) → 8/8 `thermal_backpressure` while `/health thermal_state:0` and all sensors `mStatus=0`. Firmly a **persistent G5 `getThermalHeadroom()` forecast quirk** on rango (cf. upstream #1681), NOT transient heat → the ABI thermal gate sheds every good-token decode. NOT bypassed. Substance stays proven (in-process `ok=true` + cross-app decode-completion via the `/automation status=200` metric). **Owed:** a clean cross-app `ok=true` only if the G5 headroom forecast ever cooperates, OR an operator raising `RelaisConfig.shedHeadroom` on G5 (a tuning decision — not done here).

### 3. On-device gate sweep on current `main` (E2B/G5) — #5/#11/#10 RE-VALIDATED
- **#5 session memory:** `SessionGateProbe` enable/disable OK; `SessionMemoryProbe` stored+recalled 2 turns ("my budget is two thousand dollars" → "Noted: 2000 USD."). ✓
- **#11 clientconfig:** `ClientConfigEndpointProbe` (run WITH `-e RELAIS_PROBE 1`) → `OK (1 test)`, 0 failed — 200-with-key / 401-without. ✓
- **#10 metrics:** `/metrics` scrapes the full series (engine_ready, requests_total{endpoint}, inference_duration + completion_tokens histograms, shed_total, errors_total, build_info). ✓
- **#2 tile / #3 widget:** need physical UI interaction — not autonomously exercisable (prior-build validated; still owed on-device).
- **decode-regression probes** (reasoning/tools/structured/multiturn): SKIPPED — gated on `-e model <path>` (default `/data/local/tmp/relais/…` absent); the #44/#46 merges don't touch the inference engine, so no regression risk; already validated on prior builds.

### Probe-run gotchas (added)
- `ClientConfigEndpointProbe` is gated behind `-e RELAIS_PROBE 1`; the decode probes need `-e model <path>` to a test-readable `.litertlm` (the app's model under `/sdcard/Android/data/cc.grepon.relais/files/relais/…` is readable by the same-uid test process — pass that path).
- Device left node-STOPPED (force-stop), forward removed, throwaway receiver uninstalled, androidTest clean, stayon restored.

---

## 2026-06-15 — #44 MERGED (Tasker ABI foreground-service redesign, on-device validated on rango)

**`main` @ `d2d6c09`** — PR #45 squash-merged, **issue #44 CLOSED**. The Tasker/Automate ABI (#8) decode now survives a cross-app launch. Working tree clean except the intentionally-untracked `.claude/` docs.

### What shipped (PR #45 → `d2d6c09`)
- **`RelaisAutomationService`** (new; `dataSync` FGS, not exported): the #44 fix. Decode runs off the activity lifecycle; process-global single-flight; re-asserts readiness (never cold-starts); package-targeted RESULT broadcast; never echoes the token; `stopIfIdle` posted to the main thread so a busy/duplicate start can't cancel a live decode.
- **`RelaisTaskerActivity`**: now gate (auth → ready → thermal) + hand-off + finish. Dropped `singleTask`; `Theme.Translucent.NoTitleBar`. Guards `startForegroundService`.
- **Review-fix commit `52ffe9f`**: guarded `startForeground()` IN THE SERVICE → delivers `ERROR_UNAVAILABLE` instead of an uncaught crash on an FGS-start rejection (the residual silent-loss path); corrected the misleading `scope.cancel()`-releases-lock comment; documented the `result_package` trust model + `unavailable`/`inference_failed` codes in `docs/tasker-intent-abi.md`.
- On-device probes `TaskerIntentProbe` + `SessionGateProbe` (PR #45's original scope) also landed.

### Review cycle (the loop JD asked for: implement → PR → devil's-advocate → consensus + all C/H/M fixed → validate → merge)
Two independent Opus reviewers. `critic` initially **BLOCKed** (C1 silent FGS crash, C2 probe doesn't exercise cross-app, M1 lock comment, M2 result_package undocumented). All four fixed in `52ffe9f`; re-review → **critic APPROVE-WITH-FIXES (no blockers) + code-reviewer APPROVE** = consensus. CI green (build + unit tests + gitleaks/trufflehog/headers). Squash-merged.

### On-device validation (rango / Pixel 10 / G5 / E2B) — the C1-fixed APK
- **In-process `TaskerIntentProbe` ×2: PASS** — cold→`node_not_running`, bad→no broadcast, warm→**`ok=true`** (no token echo), single-flight→1 ok + 1 busy (both delivered).
- **Cross-app** (separate UID `cc.grepon.relaisrecv` + `adb shell am start`): the **decode RUNS TO COMPLETION** — `/metrics …endpoint="/automation",status="200"` increments, **no crash, no `ForegroundServiceStartNotAllowedException`** (a resident node's own FGS confers FGS-start eligibility — CONFIRMED). Cross-app targeted delivery works (reject results delivered to the separate app); bad-token→no broadcast; token never echoed.

### ⚠️ OWED on-device follow-up (non-blocking, documented in PR #45)
- A clean single-line cross-app **`ok=true`** capture was gated by the **G5 thermal-headroom forecast shedding persistently** (8/8 retries → `thermal_backpressure`, skin temp ~25°C — a G5 thermal-reporting quirk; the safety gate working, NOT bypassed). Success-delivery proven in-process; cross-app decode-completion proven by the metric. Re-confirm opportunistically when the G5 thermal forecast cooperates.
- **Parallel pre-existing fix worth doing:** `RelaisShareService` has the SAME unguarded `startForeground` + misleading cancel-comment (inherited; out of scope for #44). Apply the same guard.
- LOW: no request metric on the `unavailable` rejection path; manifest license header still Apache/Google (pre-existing).

### Reusable technique (added this session)
**Cross-app ABI validation without Tasker:** hand-build a signed receiver app (`javac → d8 → aapt2 link → zipalign → apksigner`; SDK at `/home/user/Android/Sdk`, build-tools 36.0.0) whose dynamic **exported** receiver is pinned alive by its OWN foreground service (a bare backgrounded activity gets killed before a multi-second decode result arrives). Fire `ACTION_INFER` from `adb shell am start` (external UID) — **quote the whole `am` command for the device shell** or it re-splits the multi-word prompt and silently `RESULT_CANCELED`s. Get the node key via a throwaway instrumented probe logging `RelaisConfig.apiKey(ctx)` (delete after). `am instrument` FORCE-STOPS the package → kills the warmed node, so **re-warm via HTTP AFTER any instrumentation**; `/health.ready` ≠ `RelaisEngine.isReady` (only true after a real decode). Receiver/build scratch: `/tmp/relaisrecv2/`.

### Device state (rango / 57211FDCG0023C)
Runs the merged-`main` debug build (== `d2d6c09`). Node STOPPED, port-forward removed, throwaway receiver uninstalled, androidTest APK clean (key-dumper scrubbed), stayon restored. comet (Pixel 9) untouched / not attached this session.

---

## 2026-06-14 — issue #22 MERGED + on-device gate sweep + #8 Tasker bug found (#44)

### Current state (one-glance)
- **`main` @ `e4edb69`.** Working tree clean except the intentionally-untracked `.claude/` docs.
- **Open PRs:** **#45** (test-only on-device probes — `TaskerIntentProbe` + `SessionGateProbe`; not run in CI; safe to merge or fold into the #44 work).
- **Open issues:** **#44** (the #8 Tasker ABI bug — the big concrete next piece) and **#22 is now CLOSED** (shipped this session).
- **Devices:** BOTH foldables were on USB this session (rare). `rango` (Pixel 10/G5, `57211FDCG0023C`) has the **uncommitted theme-fix debug build** (one manifest line off `main`), node stopped, E2B staged, key `7c031901d2b447dca8ea5377803b972e`, forward removed. `comet` (Pixel 9/G4, `4A111FDKD0000C`) untouched (old `1.0.15`, key `33b3e6d166e34695a5fee96a37e78cd4`).

### RESUME HERE — prioritized for the week
1. **[P1 — highest-value, fully buildable] #44: Tasker/Automate ABI foreground-service redesign.** The #8 success path is dead on real hardware (root-caused below). This is a small, cohesive, JVM+device-testable fix and the `TaskerIntentProbe` already exists to validate it. **Plan in the "#44 redesign" block below.** Do it via the project loop: TDD → offline build (`testDebugUnitTest`+`assembleDebug`) → 2 independent reviewers (code-reviewer + critic) → fix all C/H/M → on-device re-validate cross-app on `rango` with the receiver-app method → PR (JD merges).
2. **[P2 — highest product value, but gated] #6 EmbeddingGemma embedder → then #4 RAG.** Needs THREE inputs you must supply/decide: (a) your **HF token** (the `litert-community/embeddinggemma-300m` repo is gated — `RelaisConfig.setHfToken` exists), (b) a **SentencePiece-tokenizer dependency decision** (no MediaPipe/SentencePiece dep is present/cached — the embedder needs one before `InterpreterApi`; `play-services-tflite*` IS already a dep), (c) a **device**. Infra already shipped (PR #39: `/v1/embeddings` honest-501, `EmbeddingModelSelector` SoC→variant, `EmbeddingMath`). Then build #4 RAG per the recommendation in the older section below.
3. **[P3 — needs your §4 decisions] privacy/trust features:** #7 notification triage (privacy posture), #9 local tool-executor (LAN-exposed agent trust model — the biggest call), #14 batch webhooks (signing). See `.claude/PRPs/plans/feature-backlog-15-plans.md` §4.
4. **[P4 — needs hands-on device] remaining deferred on-device gates:** #1 share (default-off `share_enabled` + GrapheneOS notifications/clipboard locked), #3 widget (launcher interaction), real thermal-CRITICAL counter (#10) via a soak. Most need a human at the device or device-config changes — see the GrapheneOS notes in memory `relais-ondevice-verification`.
5. **[P5 — housekeeping] revert `rango` to `main`'s build** (`adb -s 57211FDCG0023C install -r` the merged-main `assembleDebug` APK) so the spare isn't on the un-merged theme line; or just let the #44 work overwrite it.

### #44 redesign — concrete plan (so it can be executed cold)
**Bug (CONFIRMED on `rango`, see issue #44 for the evidence trail):** `RelaisTaskerActivity` runs the decode in its `lifecycleScope` but uses `android:theme="@android:style/Theme.NoDisplay"` + `launchMode="singleTask"` + `noHistory`. Three compounding failures:
1. NoDisplay requires `finish()` before `onResume()` → the async success path hard-crashes under instrumentation and is silently destroyed before the decode on a normal launch (failure gates survive — they finish synchronously in `onCreate`).
2. Even switching to `Theme.Translucent.NoTitleBar` (the working share theme) is **insufficient cross-app**: a cross-app launch (real Tasker) tears the transparent activity down before the multi-second decode completes (proven — the good-token cross-app fire delivered NO broadcast, while the synchronous cold `node_not_running` path DID, with `token=null`).
3. `singleTask` makes `startActivityForResult` return `RESULT_CANCELED` immediately → the **package-targeted broadcast is the only viable automation channel** (delivery proven working).

**Fix (mirror the share path #1, which already does this correctly):**
- Move the decode out of `RelaisTaskerActivity.lifecycleScope` into a **started foreground service** (reuse `RelaisShareService` or a sibling `RelaisAutomationService`) that survives independently of the launching activity.
- Activity: run the gates (auth → `RelaisInference.isReady` → `ThermalGovernor.shouldShed` → single-flight), hand the request to the service, `finish()` immediately. **Move the single-flight latch INTO the service** (the current activity-held `inFlight` AtomicBoolean leaks when the activity is destroyed mid-decode).
- Deliver the result via the existing package-targeted `ACTION_INFER_RESULT` broadcast (the automation channel). Document that activity-result is best-effort only (singleTask limitation).
- **Drop `singleTask`; keep `Theme.Translucent.NoTitleBar`.**
- Keep the JVM `RelaisIntentAbiTest` (result shaping + no-token-echo still valid). Re-validate cross-app on device with the **receiver-app method** (below) — the `TaskerIntentProbe` (PR #45) is the in-process integration check.

### What shipped this session
- **PR #43 (MERGED, `e4edb69`) — issue #22:** `finish_reason:"length"` on thermal truncation. New pure `RelaisFinishReason` (STOP/LENGTH/TOOL_CALLS + `forCompletion(truncated)` + `applyCancel(prev,cause)` fold over `DecodeCancelState`); `RelaisResult.finishReason` (default STOP); `RelaisEngine.generate` tracks an `AtomicReference<DecodeCancelState>` distinguishing THERMAL truncation (→"length") from a BROKEN_PIPE abort (→stays "stop"); all 4 HTTP finish_reason sites read `result.finishReason`; `tool_calls` precedence preserved. Best-effort (litertlm has no native decode-cancel) — documented. Tests: `RelaisFinishReasonTest` + extended `ToolResponseShapingTest`.
- **On-device VALIDATED on `rango` (current main, E2B/G5):** #11 clientconfig (401/200), #10 metrics (`relais_completion_tokens` / `relais_inference_duration_seconds{endpoint}` / `relais_thermal_events_total{level}`), **#5 session memory full HTTP recall** (stored fact recalled as "Teal", GET `{turns:4}`→DELETE→`{turns:0}`), #2 QS tile OFF-toggle, and #22-no-regression (`"stop"` on a normal completion). E2B serves on G5, engine ready ~21s.
- **#8 Tasker bug filed → issue #44 + PR #45** (probes). Theme-fix branch `fix/tasker-nodisplay-lifecycle` created then reverted (not shipped).

### Reusable on-device techniques (full detail in memory `relais-ondevice-verification`)
- **Hand-build a signed cross-app receiver APK** (no gradle) to capture targeted broadcasts: `javac --release 11 -cp android.jar` → `build-tools/36.0.0/d8 --lib android.jar` → `aapt2 link -I android.jar --manifest M.xml -o app.apk --min-sdk-version 26` → `zip -qj app.apk classes.dex` → `zipalign -p -f 4` → `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey`. SDK at `/home/user/Android/Sdk`. A **dynamic exported receiver in a live process** is reliable; manifest receiver + `setPackage` was flaky on GrapheneOS (stopped-state). Sources for the receiver app are at `/tmp/relaisrecv/` (this session).
- **Flip a default-off pref on-device:** instrumented probe doing `getSharedPreferences("relais",MODE_PRIVATE).edit().putBoolean(key,true).commit()` (regular prefs = `relais`; API key = encrypted `relais_secure`), then restart node. `SessionGateProbe` does this for `session_memory_enabled`.
- `am start` from shell is an **unfaithful harness** for the transparent Tasker activity (tears down faster than in-app `startActivity`). `TaskerIntentProbe` self-warms via `RelaisEngine.generate(ctx, RelaisRequest(text="hi"))` (bypasses the eager NodeNotReady guard).
- `RelaisEngine.isReady` = `engine?.isInitialized()` — lazy, only true after the first `generate()`; `/health.ready` reports from service-start (can diverge). `/metrics` `relais_requests_total{endpoint}` labels are dynamic.
- GrapheneOS: app notifications default `importance=NONE`; `pm grant … POST_NOTIFICATIONS` + `cmd appops set … POST_NOTIFICATION allow` to unblock; clipboard locked.

---

## 2026-06-14 (cont.) — RALPH loop: ENTIRE JVM-buildable backlog SHIPPED (10 PRs) + EmbeddingGemma decided

JD ran `/oh-my-claudecode:ralph "continue all open prps via tdd, validate with code review, merge once all
critical/high/mediums are fixed, then document all lows."` Per-feature loop: TDD impl (opus executor) → offline
build (`cd Android/src && ./gradlew :app:testDebugUnitTest :app:assembleDebug -x lint --offline`, independently
re-run) → **two** independent opus reviewers (code-reviewer + devils-advocate critic) → **fix ALL C/H/M** (not
just consensus) → delta-review behavioral fixes → green CI → squash-merge → LOWs documented in the PR body.

**MERGED to `main` (all 10; head `2ab9e9c` = #8):** #8 (PR #42) — both reviewers APPROVE; the one MEDIUM
(unauthenticated-triggerable RESULT broadcast) fixed (`broadcast=false` on the bad-token path). Zero open PRs.
- **#31** `reasoning_content` (was open from prior session) → `a71b34a`.
- **#34 (#10)** metrics increments — per-endpoint latency histogram, `relais_completion_tokens`,
  `relais_thermal_events_total{level}`, **clamped** shed thresholds (device-safety: `>=SEVERE` shed is a hardcoded
  non-bypassable backstop; NaN-guard before `coerceIn`), Grafana/alerts docs. → `aa8a852`.
- **#35 (#11)** client-config/mDNS — dynamic TXT (live model/version/caps), bearer-gated `GET /v1/clientconfig`
  (Open WebUI/Continue.dev/Aider), `RelaisEngine.isMultimodal`, masked key; key structurally absent from cleartext
  TXT; cert UX leads with import-as-CA. → `458773d`.
- **#36 (#2)** QS tile — pure `tileAction(state,templateId,ready)` (cold-start- & HOT-safe). → `b1a5ba3`.
- **#37 (#5)** **DEFAULT-OFF** session memory — Room **v1→v2** migration + committed `2.json` + real
  MigrationTestHelper-style test, hashed-IP keys (no raw IP at rest/logs/labels), bare-turn-only precedence,
  TTL+per-session+global prune caps, `GET`/`DELETE /v1/sessions`. → `fcd7364`.
- **#38 (#3)** Glance widget — `androidx.glance:1.1.1`, 3-layer cold-start guard, 600-cap, plain Column. → `a26fbb6`.
- **#39 (#6 INFRASTRUCTURE)** `/v1/embeddings` endpoint (**honest 501** until an embedder registers) + pure
  `EmbeddingModelSelector` (SoC→variant) + `EmbeddingMath` (cosine/normalize/MRL). → `dc77615`.
- **#40 (#12)** in-app prompt-template editor (Compose) over the existing store CRUD; built-ins read-only. → `a2a116e`.
- **#41 (#1)** share-sheet inference target — trampoline Activity → `dataSync` foreground service; 3-layer
  cold-start guard + single-flight + guarded FGS start; result→notification+clipboard. → `90902b2`.
- **#42 (#8)** Tasker/Automate intent ABI — exported, **API-key-gated** (constant-time), prompt→response via
  activity-result + a **package-targeted** RESULT broadcast (never global, never echoes token); honors isReady +
  `ThermalGovernor.shouldShed()` + single-flight. **MERGING on green CI.**

**Test count 216 → ~434 JVM unit tests; all green offline + CI.** No device work this session (rango/comet untouched).

### EMBEDDINGS DECISION (JD made the call) — #6 model = **EmbeddingGemma**, build is device+auth-gated
JD: "EmbeddingGemma if possible, it future-proofs us… generic seq512 (179 MB) with auto-detect for specific builds."
Verified on HuggingFace `litert-community/embeddinggemma-300m` (the LiteRT `.tflite` repo, same org as the LLM):
- **Default = `embeddinggemma-300M_seq512_mixed-precision.tflite` (179 MB).** SoC auto-detect (already coded in
  `EmbeddingModelSelector`, JVM-tested): **Tensor G5 (Pixel 10/rango) → `…google.tensor_g5…` (192 MB)**; **Pixel 9
  (Tensor G4/comet) → GENERIC (there is NO tensor_g4 build)**; Qualcomm `sm8550/8650/8750/8850`; MediaTek
  `mt6991/6993`; else GENERIC. 768-dim, **Matryoshka-truncatable to 256** for the RAG store.
- **Provisioning = download-on-demand** (reuse `RelaisModelProvisioner`). The repo is **GATED** (needs the operator's
  HF token + EmbeddingGemma license acceptance — `RelaisConfig.setHfToken` already exists). Ships a separate
  `sentencepiece.model` (4.7 MB) → the embedder needs a **SentencePiece tokenizer** (no MediaPipe/SentencePiece dep
  is present/cached — a dep decision) before `InterpreterApi` (`play-services-tflite*` IS already a dep).
- **What's DONE (#6 infra, merged #39):** `/v1/embeddings` (501 contract), the SoC selector, the cosine/MRL math.
- **What REMAINS (the embedder itself — device + HF-auth gated, NOT buildable in this headless env):** implement
  `EmbeddingGemmaEmbedder` (download the SoC-selected `.tflite` + tokenizer via the gated HF path → SentencePiece
  tokenize → `InterpreterApi` → mean-pool → 768-dim, store 256 via MRL) and register it into `RelaisEmbedderProvider`
  (then 501 flips to real embeddings). Validate real vectors on rango (G5)/comet (G4).

### RAG (#4) RECOMMENDATION (JD deferred to me) — build AFTER the #6 embedder
Ingest text/markdown via `POST /v1/rag/documents` (chunk ~256-512 tok, embed, store **256-dim** MRL vectors in the
existing Room DB), brute-force cosine top-k (no sqlite-vec on Android → ~10k-chunk ceiling). **Retrieval = a
per-request opt-in flag** on chat completions (deterministic, client-controlled, no overhead when off — *not* silent
auto-inject, more reliable than hoping a small model calls a tool) **plus** a raw `POST /v1/rag/query` (chunks only).
**Default-OFF; defer PDF** (no PDF lib).

### ⚠️ DEFERRED ON-DEVICE GATES (run next on-device session on rango/comet — every UI/automation surface)
#2 QS tile add/toggle/canned-prompt · #3 widget add/run/clear across sizes + node-off-no-cold-start · #5 two-request
`X-Relais-Session` recall + DELETE + a real v1→v2 DB upgrade · #11 `/v1/clientconfig` 200/401 + Open WebUI connect ·
#10 thermal-event counter on real throttling · #1 share text → result notification+clipboard + node-off-no-cold-start ·
#8 Tasker Send-Intent round-trip (good token → RESULT_OK; bad → CANCELED, no broadcast; node-off → `node_not_running`).
Probes are written + `assumeTrue`-gated (ReasoningChannelProbe, ClientConfigEndpointProbe, SessionMemoryProbe,
ClientConfig/SessionMemory probes, etc.).

### RESUME HERE — the JVM-buildable backlog is EXHAUSTED; everything left needs device / HF-auth / a §4 decision
1. **#6 EmbeddingGemma embedder** (device + the operator's HF token + a SentencePiece-tokenizer dep decision) → then
   **#4 RAG** per the recommendation above. This is the highest-value next work.
2. **§4 privacy/trust decisions gate the rest:** #7 notification triage (privacy posture), #9 local tool-executor
   (a LAN-exposed agent trust model — the biggest call), #14 batch webhooks (signing). See `feature-backlog-15-plans.md` §4.
3. **#13 screenshot/OCR** (depends on #1 share + an ML Kit dep), **#15 NFC** (depends on #12). 
4. Run the deferred on-device gates above on rango/comet.

---

## 2026-06-14 (cont.) — 15-feature backlog planned; core-infra #32 MERGED; prompt-templates #33 (consensus, merge-on-green)

Drove a **TDD → PR → 2-reviewer devil's-advocate → merge-on-consensus** loop (JD: "do it via TDD, prs,
then devils advocate then merge if consensus"). Consensus-to-merge = neither the `critic` nor the
`code-reviewer` agent raises CRITICAL/HIGH, **and** CI green.

**Planning (untracked, `.claude/PRPs/plans/`):**
- `feature-backlog-15-plans.md` — the 15-feature competitive-backlog, **reconciled against the live repo**
  (the brief was stale): API is dual-stack HTTP :8080 loopback + **HTTPS :8443 LAN**; **#10 thermal+
  Prometheus /metrics already shipped**; #6 `/v1/models` + #9 tool-calls + #11 mDNS already half-done.
  **Hard blocker: litertlm 0.11.0 has NO embeddings API** → #4 RAG + #6 `/v1/embeddings` need a SEPARATE
  embedding model (bge-small int8 .tflite via the present GMS `InterpreterApi`) or a 501. Includes master
  sequencing + the shared-infra branch + **§4 Open human decisions** (embedding model #4/#6; node-as-agent
  #9; session retention #5; notification privacy #7; self-signed-cert UX #11).
- `feature-00-core-infra.plan.md` — the keystone plan that became PR #32.

**PR #32 — `feature/relais-core-infra` MERGED → `main` @ `f814ff7`** (squash, TDD, both reviewers APPROVE,
CI green). The shared dependency root the backlog builds on:
- `core/RelaisInference` (in-process inference facade; **eager `isReady` guard** — a UI tap can't cold-start
  the engine; `shouldCancel` wired for thermal parity), `core/NodeState`+`computeNodeState`,
  `core/RelaisNodeController`.
- `data/RelaisDatabase` (**Room 2.7.1 added** — KSP2-compatible; static `get(context)` singleton, not Hilt;
  `SchemaMeta` v1, `exportSchema` → `app/schemas/1.json` committed, empty `MIGRATIONS`, no destructive
  fallback). #4/#5/#14 extend it.
- `embed/RelaisEmbedder` + `RelaisEmbedderProvider` (null until #6 registers → single 501/RAG-off source).
- `RelaisEngine.lastInitFailed` flag (set in `RelaisNodeService` init try/catch) → `NodeState.ERROR`.

**PR #33 — prompt-templates MERGED → `main` @ `3565642`** (squash, both reviewers APPROVE, CI green).
- `templates/PromptTemplateStore` (canonical, JSON in filesDir, 4 seeded built-ins, bounds enforced on
  read+write, corrupt-file reseed), `TemplatePrecedence` (`resolveSystemPrompt` + `TemplateMode`),
  `WorkflowRegistry` façade. `RelaisHttpServer`: `template`/`x_relais_template` param on
  `/v1/chat/completions` + `/generate` folds into the system prompt; unknown id → 400. **Default-off**
  (no template + no system = unchanged). **Deferred follow-up: the in-app Compose editor + control-panel
  link** (built-ins usable via API now; custom-template editing UI is a separate design task).

**Verified native-API facts this session (also in memory `relais-litertlm-api-surface` + `docs/litertlm-
native-api.md`):** reasoning channel works via `extraContext["enable_thinking"]` → `message.channels
["thought"]` (feature-10a / PR #31); benchmark-on-conversation is a dead end; embeddings need a separate
model. Learned skill: `.omc/skills/litertlm-native-api-probe-first-expertise.md`.

**Build levers / git state:** local branch = `feature/relais-prompt-templates`; `main` @ `f814ff7`
(#32 in). `cd Android/src && ./gradlew :app:testDebugUnitTest :app:assembleDebug -x lint --offline`
(Room 2.7.1 cached). New JVM tests: core-infra 16, templates 27 — all green. **No device work this
session** (planning + JVM/CI-verified PRs); rango/comet untouched (node stopped from the prior session).

**Resume here:** (1) **PR #31 (`reasoning_content`) is still OPEN/unmerged — JD's call.** (2) Wave-1 of the
backlog through the same loop: **#11 client-config/mDNS** and **#10 metrics increments** (both small,
additive), then **#5 session memory** and **#2 QS tile / #3 widget** (consume core-infra + #12). (3) **#6
embeddings decision** gates #4 RAG — needs JD's call (separate .tflite model vs 501). (4) The #12 Compose
editor follow-up. (Both #32 core-infra and #33 prompt-templates are now on `main` @ `3565642`.)

---

## 2026-06-14 — feature-10a OpenAI `reasoning_content` BUILT (probe-first; on-device validated; PR pending)

Started the "unexploited native hooks" round (JD picked it; backlog otherwise exhausted). **Probe-first**
on rango/E2B resolved two doc claims that were wrong in OPPOSITE directions:
- **Reasoning channels — VERIFIED, then SHIPPED.** Gemma-4 E2B populates `message.channels["thought"]`
  **only** when `extraContext["enable_thinking"]="true"` is passed (NOT a `Channel` def, NOT default —
  Relais passed `emptyMap()`, so it was dormant). Visible answer stays clean; reasoning streams as
  per-token deltas. (The doc had marked this "unverified.")
- **Benchmark/exact-tokens — DEAD END.** `enableBenchmark=true` + `conversation.getBenchmarkInfo()`
  throws "Benchmark is not enabled … set BenchmarkParams in EngineSettings"; the 0.11.0 AAR has **no
  public BenchmarkParams/EngineSettings** — only the standalone `benchmark()` one-shot (re-loads the
  model). So exact `prompt_tokens` stays unreachable on the live path. (The doc had marked this
  "available".) Lesson → learned skill `.omc/skills/litertlm-native-api-probe-first-expertise.md`.

**What was built (feature-10a, behavioral → PR-and-pause, NOT yet committed/pushed):** OpenAI
`reasoning_content`, opt-in via `reasoning_effort` (absent/"none" = off → **zero behavior change**;
low/med/high = on). Files: `RelaisReasoning.kt` (new, pure: `thinkingEnabled` + `classifyStreamDelta`),
`RelaisEngine.kt` (`RelaisRequest.enableThinking`, `RelaisResult.reasoning`, `generate(onReasoning=…)`,
streaming `onMessage` siphons the thought channel via `classifyStreamDelta`; decode clock + token count
advance on VISIBLE tokens only), `RelaisHttpServer.kt` (parse `reasoning_effort`; non-stream
`message.reasoning_content`; stream `delta.reasoning_content` before content; `sendChunk`→`emitDelta`).
JVM: `RelaisReasoningTest` (12 tests). Probe renamed → `ReasoningChannelProbe.kt`. Docs:
`docs/litertlm-native-api.md` §6/§8 updated; memory `relais-litertlm-api-surface` updated.

**On-device PROVEN (rango/G5/E2B, live HTTP):** `reasoning_effort:"low"` → `reasoning_content` present,
content "43" clean, **`completion_tokens=2`** (NOT inflated by ~110 reasoning callbacks); no
`reasoning_effort` → no `reasoning_content` (regression clean); stream → `reasoning_content` deltas
before content (R→C). JVM `testDebugUnitTest` green; `assembleDebug` green. Independent code-review:
APPROVE-WITH-FIXES → both MEDIUMs fixed (throughput-clock-on-visible-only; pure `classifyStreamDelta`
+ tests) → delta re-review pending. **rango left node-STOPPED, port-forward removed, E2B ref intact.**

**v1 scope limit (documented):** tool path + structured-output path carry `enableThinking` but ignore it
(no reasoning on those paths yet). **Resume:** delta re-review → commit on a feature branch → PR-and-pause
(JD pushes/merges). After that, the unexploited-hooks backlog still holds `overwritePromptTemplate`
(niche) and the embeddings #06 decision (501 stub vs separate model).

---

## 2026-06-13 (cont.) — PR #30 sampler params LANDED (temperature/top_p/seed)

`main` @ **`59e333a`**. The hardcoded `SamplerConfig(64, 0.95, 1.0)` is gone — `RelaisRequest` now
carries `temperature`/`topP`/`seed` (parsed from the OpenAI body), resolved via
`RelaisRequest.samplerConfig()` (defaults preserved; temp clamped 0..2, top_p 0..1) and used by both
`generate` and `generateWithTools` (engine-init probe stays fixed). **On-device: `temperature:0` is
deterministic** (repeated calls identical). `RelaisSamplerTest` covers default/override/clamp.

**Finding:** forcing **temp 0 on the json_schema tool path SUPPRESSES the tool call** on E2B (greedy
decoding won't fire the function) — so structured output keeps the request's temperature rather than
defaulting low; clients tune it. json_object already produces valid JSON at the default temp.

This closes the "plumb temperature" follow-up. **Merge policy for the rest of this session: auto-merge
each validated + reviewed PR on green CI (per JD).**

**Resume here:** only `#06` embeddings remains from the competitive-scan backlog — **no native
embedding API in the AAR** (`docs/litertlm-native-api.md`), so it needs a 501 stub or a separate
embedding model (decide with JD). Otherwise the OpenAI-compat backlog is exhausted; consider the
"unexploited hooks" table in the native-API doc (reasoning channels, custom templates, real benchmark
tok/s, `extraContext` RAG) for the next round.

---

## 2026-06-13 (cont.) — PR #29 structured output LANDED + native-API learning (#28)

`main` @ **`509a6bf`**. Two landings:
- **PR #28** — **native-API-first learning checked into the repo**: `docs/litertlm-native-api.md`
  (curated full inventory of the 0.11.0 AAR + unexploited-hooks table + on-device verdicts),
  `scripts/dump-litertlm-api.sh` (regen after a version bump), and a native-API-first rule in
  `CLAUDE.md`. **Default going forward: check the native API before designing any fallback** — the
  plans were wrong about this three times. Also fixed the stale package path (`cc/grepon/relais`).
- **PR #29** — **feature-05 OpenAI `response_format`** (structured output):
  - `json_schema` reuses the native tool path (schema advertised as one tool; tool-call args become
    the content) — NO engine change. `json_object` = prompt nudge. Both gated by a minimal
    schema validator + prose/fence repair (largest-fragment) + bounded retry (MAX 2 ⇒ ≤3 calls);
    `stream`+`response_format` → 400; exhaustion → 422.
  - On-device (rango/G5/E2B): json_object → 200; json_schema(string) → 200; json_schema(strict int)
    → 422 graceful (E2B emitted `age:null`); stream+format → 400.
  - Pure logic in `RelaisStructuredOutput` (31 JVM tests). Review applied (prior-output retry nudge,
    largest-fragment repair, x_relais_ prefix).
  - **Verified:** `enableConversationConstrainedDecoding` is NOT a hard grammar enforcer in 0.11.0
    (ON ≡ OFF) — that's why we validate+retry, not rely on the flag.

**Highest-value follow-up (noted in PR #29):** plumb sampling **temperature** (hardcoded 1.0 in
`RelaisEngine`). Lower temp would materially improve structured-output + tool-arg reliability on small
models AND let the OpenAI `temperature` param work generally. Small `SamplerConfig` plumbing through
`RelaisRequest`/`generate`.

**Remaining researched PRPs:** `#06` embeddings (no native embedding API found — likely 501 stub or a
separate embedding model). After that the feature backlog from the competitive scan is exhausted.

---

## 2026-06-13 (cont.) — PR #27 tool-calling LANDED (native LiteRT-LM tool API)

`main` @ **`67a2c73`** — **PR #27 merged** (squash, CI-green, independently reviewed, on-device validated).
Feature-04 OpenAI tool-calling on `/v1/chat/completions`. **The plan's core premise was wrong** and was
discarded: it assumed no native tool API existed and designed a prompt-injection + bracket-scraping
fallback. Decompiling the AAR + an on-device probe proved the **native** path works on Gemma-4 E2B, so
this uses it (library parses tool calls into structured objects; no scraping).

- `tools` + `tool_choice` parsed; `buildPromptParts` is tool-aware (assistant `tool_calls`, `role:"tool"`
  turns, trailing-tool-run → live tool results, `tool_call_id`→name, user-first normalization).
- Engine `generateWithTools`: `ConversationConfig(tools=…, automaticToolCalling=false)` + BLOCKING
  `sendMessage`, reads `reply.toolCalls`. (automaticToolCalling=false ⇒ client executes, OpenAI-style;
  `execute()` never fires node-side.) `toResidentMessage` seeds prior tool_calls + tool responses.
- HTTP emits `tool_calls` + `finish_reason:"tool_calls"`; streaming delta carries per-call `index`;
  `created` added to ALL completion envelopes.
- **On-device proven (rango/G5/E2B):** emission → tool_calls; `role:"tool"` follow-up → "stop" citing
  the result ("12°C and cloudy"); `tool_choice:"none"` suppresses; streaming emits index+created.
- JVM tests: `RelaisToolParsingTest`, `ToolConversationParseTest`, `ToolResponseShapingTest`. On-device
  probe `ToolCallingProbe` (assumeTrue-gated).
- **Caveat (documented):** small models (E2B) sometimes emit nested/typed args
  (`{"city":{"type":"STRING","value":"Berlin"}}`) — model output quality, not plumbing; passed through verbatim.

**Resume here:** **feature-05 (structured output)** is unblocked. Before hand-rolling, check
`SamplerConfig`/`ConversationConfig`/the tool path for a native grammar/JSON-schema hook (the tool API
already constrains output to a schema — structured output may be expressible via a single forced "tool").
Then **feature-06 (embeddings)** — no native embedding API found yet (likely 501 stub or separate model).

---

## 2026-06-13 (cont.) — PR #26 multi-turn fix LANDED (replay redesigned)

`main` @ **`7a15a9b`** — **PR #26 merged** (squash, CI-green, independently reviewed, on-device validated).
The paused multi-turn parser PR is done. The engine-replay half was **redesigned**, not just validated:

- **The bug:** the original `replaySend` pushed system + each history turn through
  `sendMessageAsync(Contents)` → forced role=USER + a **full generate-and-discard decode per turn**
  (~22 s/turn on G5). Proven on rango: 1-exchange = 48.5 s, **2-exchange = HTTP 500**
  (`history replay timed out`, native `RunPrefillAsync`+`RunDecodeAsync` per turn).
- **The fix:** seed system + history at conversation creation via
  `ConversationConfig(systemInstruction = Contents.of(sys), initialMessages = List<Message>)` with
  correct roles (`assistant → Message.model`). LiteRT-LM **prefills** these; only the live turn
  decodes → **one generation regardless of depth**. `replaySend` deleted. Parser now normalizes
  history to **user-first** (drops orphan leading assistant) so `initialMessages` is never MODEL-first.
- **On-device (rango/G5/E2B, end-to-end HTTP):** system 3.2 s (was 8.8), 1-exchange 5.4 s (was 48.5),
  2-exchange **HTTP 200** (was 500), leading-assistant edge HTTP 200. JVM parser tests green
  (Case 6 updated, Case 8 added). New on-device probe `MultiTurnReplayProbe` (assumeTrue-gated).
- **rango state:** has the PR #26 debug build installed (== main after squash), node STOPPED, E2B ref
  intact, key `7c031901d2b447dca8ea5377803b972e`. **comet still runs the pre-#26 main build.**

**⚠️ BIG correction for #04/#05/#06 — the bundled LiteRT-LM 0.11.0 AAR DOES expose native APIs the
earlier scan missed** (verified by decompiling `classes.jar`): `Tool` / `ToolCall` / `ToolManager` /
`ToolProvider` / `ToolSet` / `Content.ToolResponse`, `ConversationConfig(tools=…, automaticToolCalling=…)`,
role-typed `Message.system/user/model/tool(...)`, and a `Session` API (`runPrefill`/`runDecode`).
So **feature-04 (tool-calling) likely needs NO hand-rolled fallback** — re-scope it around the native
ToolManager before assuming a parser-only approach. (See memory `relais-litertlm-api-surface`.)

**Resume here:** #03 is merged → **#04 tool-calling** + **#05 structured-output** are unblocked
(behavioral → PR-and-pause each). Start #04 by validating the native `ToolManager`/`automaticToolCalling`
path on-device before writing any fallback.

---

## 2026-06-13 — Overnight queue LANDED (5 PRs merged) + AICore investigated

`main` @ **`974ccd1`**. A ralph/ultrawork overnight loop executed `.claude/PRPs/plans/OVERNIGHT-RUNBOOK.md`
end-to-end — **5 PRs merged, CI-green, each independently code-reviewed, final architect GO**:
- **#16** — JVM unit-test harness: new `Android/src/app/src/test/` source set + a `unit_tests` CI job. CI
  previously ran NO tests; **`cd Android/src && ./gradlew testDebugUnitTest` is now the headless lever (43 tests).**
- **#17** — closed **#13** (Gson null-hardening: `safeToModel` wraps the boot-path `toModel()`→handled ISE;
  `curatedModelsFrom` drops malformed entries; `fromJson` validates `displayName`).
- **#18** — closed **#11** (`ensureModel` captures `idAtStart`; `shouldPersistPath` guards `remember`'s
  `setModelPath` so a mid-provision `--es modelId` drift no longer persists a stale path).
- **#19** — fresh **Pixel 10 → G5-compatible default** (`G5_DEFAULT_REF` = gemma-4-E2B-it, pinned commit
  `361a4010`, 2.59 GB) substituted at the TOP of `ensureModel` (single chokepoint, before the fast-paths) so the
  E4B default never reaches the G5 gate. `DEFAULT_MODEL_ID` + G5 gate unchanged.
- **#20** — Robolectric 4.14.1 backfill (provisioning/prefs tests run headless; `RelaisProvisionerTest` kept
  **two-tier** — androidTest retained for the on-device SELinux `length()=0` path Robolectric can't model).

**⚠️ DEFERRED ON-DEVICE GATES — these were NOT validated headless; run on `rango`/`comet` next on-device session:**
1. **#13** — boot the node with a crafted partial allowlist (null `taskTypes`/`description`) → confirm "Init failed"
   handled, no raw NPE / watchdog loop on the `relais-init` thread.
2. **#11** — start downloading model A, mid-download `am start … --es cmd start --es token <key> --es modelId <B>`,
   let A finish, restart → `logcat -s RelaisModelProvisioner` shows **B** re-resolving, not the stale A path.
3. **#19** — fresh `rango` (clear app data) → confirm it provisions + serves **E2B** (`/v1/chat/completions` http 200),
   not the E4B "Init failed" gate.

**Awaiting JD's prioritization (NOT merged — net-new product scope):** 8 feature PRPs in
`.claude/PRPs/plans/feature-0{1..7}+09*.plan.md` from a competitive scan (OpenAI-API completeness: `/v1/models`,
token-usage block, **multi-turn parser fix** — a real correctness bug, tool-calling, structured output, embeddings,
request queueing, web dashboard). Tool-calling/structured-output/embeddings each need a fallback — bundled
LiteRT-LM exposes no native tool-use / grammar / embedding API.

**AICore/NPU on Pixel 10:** investigated → **platform-blocked, not fixable in code** (`com.google.android.aicore`
not installed on the G5 build; `genai-prompt` already latest `1.0.0-beta2`). See the memory entry. Do not re-attempt.

## 2026-06-13 — Feature loop (OpenAI-compat) + on-device validation

A second loop executed the researched feature PRPs under a **hybrid policy** (auto-merge additive, pause behavioral).
**Merged to `main` (all CI-green, each independently reviewed — a real issue caught+fixed on every one):**
- **PR #21** `#01` `GET /v1/models` (TTL-cached catalog; auth-gated; ids match chat `model`).
- **PR #23** `#02` `usage{}` block on chat completions (exact `completion_tokens`; `prompt_tokens` estimated, flagged via top-level `x_relais_usage_note`).
- **PR #24** `#07` bounded admission queue + `429`+Retry-After backpressure (fair Semaphore, permit held for whole SSE stream; thermal 503 precedes 429).
- **PR #25** `#09` auth-gated read-only web dashboard at `GET /` (scriptless, strict CSP, all values HTML-escaped, DESIGN.md-faithful).

**PAUSED for review — [draft PR #26](https://github.com/bearyjd/relais/pull/26) `#03` multi-turn parser fix (DO NOT auto-merge).**
Parser half reviewed solid; **engine-replay half has on-device open questions** (see PR body: `<system>` chat-template
handling, turn-order role parity, KV-cache-on-discard, replay latency, replay-under-lock). Validate on-device before merge.
**`#04` tool-calling + `#05` structured-output are DEFERRED behind #03's merge** (they need its parser on `main`).
`#06` embeddings deferred (501 stub, no engine API). Filed **issue #22** (finish_reason="stop" on thermal truncation — pre-existing).

**On-device validation (rango / Pixel-10 / G5, this session):**
- **`#19` (Pixel-10 E2B default) — ✅ PROVEN on rango.** Fresh state (modelId=E4B default, ref/path dropped) → node
  auto-substituted **E2B** via `deviceDefaultRef`, served `/v1/chat/completions` **HTTP 200** ("Ping"), engine ready ~15s,
  no E4B gate / SIGSEGV. Persisted ref displayName became "Gemma 4 E2B-it (Tensor G5)" (= `G5_DEFAULT_REF`). **Bonus
  on-device confirms: `#02` usage block live in the 200 response; node serves through `#07`'s admission gate.**
- **`#13`** — NOT on-device-forceable (allowlist is a fixed remote URL; can't inject a malformed entry without a custom server). Covered by JVM tests + CI.
- **`#11`** — NOT run on-device (needs a multi-GB download race for low marginal value; logic hermetically tested). Run on request.
- rango left **node-stopped**, E2B ref persisted, E2B on disk (`…/litert_community_gemma_4_E2B_it_litert_lm/361a4010…/`). Key `7c031901d2b447dca8ea5377803b972e` (valid). Build on rango = current `main` (1.0.15, installed 2026-06-13 12:01).

**Resume here:** review/validate [PR #26](https://github.com/bearyjd/relais/pull/26) on-device → merge → then run `#04`/`#05` (behavioral → PR-and-pause each).

---

## TL;DR
- `main` @ **`6ac1344`** — **the Pixel-10 / Tensor-G5 inference crash is ROOT-CAUSED and FIXED (PR #15, merged).** The node now serves text-only models on both SoCs and refuses the known-bad model on Pixel 10 cleanly instead of native crash-looping. Working tree clean (`.claude/` docs intentionally untracked).
- **Root cause: a `gemma-4-E4B` × Tensor-G5 bug inside LiteRT-LM** — not the runtime version, not the backend config, not the model file. Triangulated on the SAME G5 unit: `gemma-4-E4B` crashes but `gemma-4-E2B` **and** `Qwen3-0.6B` serve; and `gemma-4-E4B` serves on Tensor G4. So it's E4B-specific.
- **Upstream filed: [LiteRT-LM #2566](https://github.com/google-ai-edge/LiteRT-LM/issues/2566)** (by `bearyjd`). Watch for a maintainer response / fix.
- **Both foldables on USB** (flaky — see gotcha). Live node = **Pixel 9 `comet`** (`4A111FDKD0000C`, Tensor G4) — runs the fixed build, gemma full-multimodal, node stopped. Spare = **Pixel 10 `rango`** (`57211FDCG0023C`, Tensor G5) — runs the fixed build; **E2B staged + serves**, E4B gated, node stopped.

---

## What shipped this session — PR #15 (merged → `6ac1344`)

Commit `42af536` `fix(relais): adaptive engine config + gate gemma-4-E4B on Tensor G5`. Files: `RelaisEngine.kt`, `RelaisEngineConfigTest.kt` (new), `SPIKE-FINDINGS.md`. litertlm kept at `0.11.0` (a 0.13.1 bump was tested on G5 and reverted — the version is not the cause).

- **Adaptive engine config** (`RelaisEngine.buildResidentEngine`/`buildIfModelAccepts`): try the full multimodal config; if the model lacks an image/audio encoder (`NOT_FOUND TF_LITE_*_ENCODER`, raised at `initialize()` on litertlm 0.11 and at `createConversation()` on 0.13 — both wrapped), fall back to a text-only engine. A NON-encoder error propagates (a multimodal model is never silently downgraded). **This fixed a real bug: the node previously hardcoded `vision/audioBackend` and so REJECTED every text-only model** (Qwen3 etc.) at init/createConversation.
- **Pixel-10 / Tensor-G5 pre-flight gate** (`isG5Incompatible`): a native SIGSEGV can't be caught, so refuse the known-incompatible `gemma-4-E4B` on Pixel 10 with a clear `IllegalStateException` BEFORE loading. Keyed off BOTH the model id (canonical `RelaisConfig.DEFAULT_MODEL_ID`) and the on-disk file name (divergence-safe).
- Hermetic `RelaisEngineConfigTest` (5 pure-predicate tests: gate + encoder detection, incl. id↔file divergence + a tightened-regex false-positive guard).
- Two independent code-review passes (initial approve-with-suggestions → fixes folded in → delta APPROVE); CI green; merged.

> Prior sessions (merged): Phase A picker (PR #10/#12 → `90c90d7`), Phase B HF search (PR #14 → `09dcec5`).

---

## The Pixel-10 finding (root-caused + verified this session)

`gemma-4-E4B` inits (`ready:true`) then SIGSEGVs natively (null-deref in `liblitertlm_jni.so`) on the FIRST inference on Tensor G5 — on litertlm `0.11.0` AND `0.13.1`, both CPU and GPU, and with a text-only engine config. Discriminated with direct-`Engine` instrumented probes (`am instrument`, non-destructive):

| Model | Tensor G4 (`comet`) | Tensor G5 (`rango`) |
|---|---|---|
| gemma-4-E4B (multimodal) | ✅ serves | ❌ SIGSEGV (1st inference) |
| gemma-4-E2B (multimodal, same config/GPU path) | ✅ serves | ✅ **serves** (http 200) |
| Qwen3-0.6B (text-only) | — | ✅ serves (~3.5 tok/s) |

→ exonerates the SoC-in-general, the Gemma-4 family, the model file, and the multimodal backends; the bug is **gemma-4-E4B's graph on G5**. HF asymmetry: E4B has no `_Google_Tensor_G5` build (E2B does), so there's no G5-specific E4B variant to fall back to → gating E4B on Pixel 10 is the correct remediation. Corroborating upstream: #1681 (G5 GPU misid), #2149 (E4B decode segfault, Linux/constrained-decoding), #2056 (E4B CPU vision SIGSEGV).

---

## Possible next steps (the main task is done — pick per priority)
- **Watch [#2566](https://github.com/google-ai-edge/LiteRT-LM/issues/2566)** for a Google response / a fixed litertlm version; when one lands, re-test E4B on `rango` and (if fixed) remove the denylist entry.
- **Pixel-10 default-model UX (deferred "Part 3"):** today a fresh Pixel 10 with the default (gemma-4-E4B) hits the gate and the operator must pick a G5-compatible model. Nicer: auto-default Pixel 10 to a working model (E2B serves on G5, or Qwen3) — needs a hardcoded Pixel-10 default ref since neither is allowlisted.
- **AICore / NPU path on Pixel 10 (original plan Task 6, never run):** `RelaisAicore.available()` returned false on `rango` (ML Kit `checkStatus()` non-available) — investigate why; the `_Google_Tensor_G5` model variants are a lead for the NPU route (image+text only, no audio).
- **Open GitHub issues #11 / #13** (pre-existing, from prior sessions — see below).

---

## Device state — two foldables on USB adb (`/home/user/Android/Sdk/platform-tools/adb`)

| Thing | Value |
|---|---|
| **Live node** | **Pixel 9 Pro Fold** `comet` / `4A111FDKD0000C` (Tensor **G4**) — runs the fixed build; gemma full-multimodal serves; node currently stopped. Key `33b3e6d166e34695a5fee96a37e78cd4`. gemma staged at `/sdcard/Android/data/cc.grepon.relais/files/relais/gemma-4-E4B-it.litertlm` (3.66 GB). |
| **Spare** | **Pixel 10 Pro Fold** `rango` / `57211FDCG0023C` (Tensor **G5**) — runs the fixed build; **E2B staged + serves** (`…/litert_community_gemma_4_E2B_it_litert_lm/361a4010/`); E4B gated (clear error, no crash); also holds the E4B + Qwen3 files. Node stopped. Session key `7c031901d2b447dca8ea5377803b972e`. |
| Reach a node | `adb -s <serial> forward tcp:8443 tcp:8443` → `curl -k https://localhost:8443/health` · `/v1/chat/completions` (Bearer key). |
| Headless start | `adb -s <serial> shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <key>` (`<appId>` = `com.ventouxlabs.relais` / `.izzy` / `.degoogled`; namespace stays `cc.grepon.relais`) |

- **Always pass `-s <serial>`** (two devices). **USB is very flaky** this session: attaching a 2nd device re-enumerates the bus and drops both; often only one enumerates at a time. Recovery: `adb kill-server && adb start-server`, then `sleep 1-2`; if `adb devices` is empty, check `/sys/bus/usb/devices/*/idVendor` for `18d1` — **if no `18d1`, the device isn't physically on the bus** (replug / different cable/port), not an adb problem.
- Do **destructive / model-swap work on `rango`** (spare). Wiping `comet` = a 3.66 GB re-download (the `/data/local/tmp` backup is GONE).
- **Set an HF (non-allowlist) model headlessly** by running a throwaway instrumented test that calls `RelaisHuggingFace.resolve(modelId,null)` + `RelaisConfig.setModelRef(...)` then forces a synchronous `commit()` (the `.apply()` write races process death), then `am start --es cmd start`. (How E2B/Qwen were staged.)

---

## Build / verify levers (run from `Android/src`)
```bash
./gradlew :app:compileDebugKotlin -x lint --offline          # fast Kotlin compile
./gradlew :app:assembleDebug -x lint --offline               # debug APK
./gradlew :app:assembleDebugAndroidTest -x lint --offline    # instrumented tests
# Non-destructive on-device probe (am instrument does NOT uninstall):
#   adb -s <serial> install -r .../app-debug.apk + .../app-debug-androidTest.apk
#   adb -s <serial> shell am instrument -w -e class <Class> cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
```
- CI (GitHub Actions): Build Android APK + gitleaks + trufflehog + headers (not instrumented tests). `main` unprotected; merge `gh pr merge <n> --repo bearyjd/relais --merge --delete-branch`.
- **Exception:** a `litertlm` version bump needs **network** (new AAR), not `--offline`.

---

## Open issues (GitHub — bearyjd/relais)
- **#11** — `ensureModel` must not persist a path whose modelId drifted mid-provision (the `adb --es modelId` race).
- **#13** — the Gson-ignores-Kotlin-null class in sibling code (`RelaisModelCatalog.isNodeRunnable`, allowlist-match `resolveModel`→`toModel()` NPE on the BOOT thread).
- Upstream **LiteRT-LM #2566** — the gemma-4-E4B / Tensor-G5 crash (filed this session).

---

## Process norms (reinforced this session)
- **Independent review on the FINAL diff before "ready to merge"** — and re-review the DELTA after responding to a review (initial → fixes → delta). The delta pass APPROVE'd here. (Memory: `review-before-suggesting-merge`.)
- **Compile-green ≠ tested. RUN it on a device.** The fix's first attempt passed compile + hermetic predicates but FAILED on-device (the 0.11 encoder rejection lands at `initialize()`, outside the first try block) — the device run caught it.
- **When init succeeds but the first inference crashes, discriminate model-vs-runtime with a SECOND simpler model + a text-only config BEFORE blaming the SoC/version.** That's what isolated this to E4B.
- **A native SIGSEGV can't be try/caught** — remediation must avoid entering the crashing path (gate/route), not catch it.
- Read `DESIGN.md` before any control-panel UI change. Never push/merge/file-upstream without explicit user go-ahead.

## Memory pointers (agent memory, persists across sessions)
- `relais-ondevice-verification` — Tier-1/2 + Phase-A/B PROVEN; the **Pixel-10/G5 root-cause + fix + E2B discriminator + #2566**; spare-device method; device gotchas + keys.
- `relais-gate3-oss-engagement` — what's done/verified vs deferred; AGPL + `cc.grepon.relais`.
- `relais-build-verification` — the offline-compile lever.
- `review-before-suggesting-merge` — the review-before-merge rule.
