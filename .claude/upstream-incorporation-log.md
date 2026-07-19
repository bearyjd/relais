# Upstream Incorporation Log — `google-ai-edge/gallery` → Relais

Watermark + per-commit disposition so future upstream syncs are a cheap diff, not a re-triage.
See the method in `.claude/PRPs/plans/upstream-sync-incorporation.plan.md`.

---

## Sync watermark

| Field | Value |
|-------|-------|
| **Synced-to upstream HEAD** | `4895efa` ("Update Gradle wrapper to version 9.2.1.") — full `4895efae0d348aa10491cb9d7715bd932418c7fc` |
| **Merge-base (fork point)** | `bedc488` ("Add support for image to text models", 2025-05-16) |
| **Commits triaged** | **98 node-relevant of 424**, in two passes (path+keyword, then a path-scoped sweep). See **Coverage** below. |
| **Triage date** | 2026-06-21 |
| **Result** | **0 commits ported — verified, not inferred.** Every commit touching the node-shared dirs (`data/`, `runtime/`, `worker/`, `di/`) is dispositioned: already-present / superseded / live-fetched-data / out-of-scope. |
| **Relais litertlm dep** | `0.11.0` (`Android/src/gradle/libs.versions.toml`, `litertlm` version key) — exact parity with upstream HEAD. |

### Coverage (auditable)
"Node-relevant" = commits touching the directories the node compiles and shares — `data/`,
`runtime/`, `worker/`, `di/` — plus the litertlm/runtime layer. Re-derive the exact set with:

```bash
G=Android/src/app/src/main/java/com/google/ai/edge/gallery
git log bedc488..upstream/main --pretty=format:'%h %s' -- \
  "$G/data/" "$G/runtime/" "$G/worker/" "$G/di/" | sort -u
```

- **Path-scoped node-relevant set: 76 commits — 100% dispositioned** (21 in pass 1 + 55 in the
  pass-2 sweep), plus ~22 keyword/benchmark/allowlist commits from pass 1 ⇒ **98 read in total**.
- The remaining ~326 of 424 touch ONLY `ui/` (non-benchmark), `customtasks/*`, `assets/skills`,
  macOS/iOS, CI, or docs — surfaces that **cannot change node runtime behavior** (the node never
  invokes them). Excluded by construction, not by keyword; not individually read.
- **Why path-scoped, not keyword:** keyword filtering is blind to opaque subjects ("Internal
  changes", "No public description"). The node's exposure is defined by *which files it shares*, not
  by how Google titled a commit. (Pass 1 was keyword-based and missed 55 path-relevant commits;
  pass 2 closed that gap — none changed the verdict.)

**Next sync:** `git fetch upstream`, run the command above on `4895efa..upstream/main`, and diff
against the dispositions below — **READ each new diff; do not keyword-filter.**

---

## Per-commit dispositions (node-relevant candidate set)

All **REVIEWED — NOT PORTED**. Evidence cites Relais **symbols** (search the named function/field);
any line numbers are hints accurate as of 2026-06-21 (`4d702ae`) and may drift.

### Pass 1 (path + keyword)

| SHA | subject | disposition | evidence |
|-----|---------|-------------|----------|
| `2781aac` | download resume via `Accept-Encoding: identity` | ALREADY-PRESENT | `worker/DownloadWorker.kt` sets `identity` + `Range` (search `Accept-Encoding`) |
| `8c077c8` | download resume bug fix | ALREADY-PRESENT | `DownloadWorker.kt` partial-file/Range/Content-Range resume path |
| `6c0901a` | fix NPE on failed download | ALREADY-PRESENT | `data/DownloadRepository.kt` empty-taskId → launch intent (the exact fix). *(Inherited UI path; node provisions via `DownloadWorker`, not this repository.)* |
| `af17df1` | load-history / seed prior turns | ALREADY-PRESENT | node seeds via `ConversationConfig.initialMessages` in `RelaisEngine` (`request.history.mapNotNull { it.toResidentMessage() }`) |
| `90eb033` | load versioned model allowlist | ALREADY-PRESENT | `RelaisModelProvisioner` builds `…/{VERSION_NAME}.json`; `ModelManagerViewModel.getAllowlistUrl` |
| `393e036` | ModelCapability / capabilityToTaskTypes | ALREADY-PRESENT | `data/Model.kt` `ModelCapability`; `data/ModelAllowlist.kt` `capabilities`/`capabilityToTaskTypes` |
| `da18ec3` | allowlist offline local-file fallback | ALREADY-PRESENT | `ModelManagerViewModel.readModelAllowlistFromDisk` |
| `ccbe966` | download by git ref / commit pin | ALREADY-PRESENT | `data/RelaisModelRef.kt` `commitHash`; `RelaisModelProvisioner` `resolve/{commitHash}` URLs |
| `0a8afa0` `88f0f85` `c1450df` | speculative decoding (config/flag/probe) | ALREADY-PRESENT (deliberately OFF) | `data/Config.kt` `*_SPECULATIVE_DECODING`; `RelaisEngine.buildResidentEngine` sets `ExperimentalFlags.enableSpeculativeDecoding = false` (measured G4 regression rationale inline) |
| `b3cbb09` `611456b` | thinking mode (config + panel) | ALREADY-PRESENT | `data/Config.kt` `ENABLE_THINKING`; `RelaisReasoning.kt`; `RelaisEngine` reasoning seeding |
| `348166f` `da0ed48` `e67eefb` `af777f1` `d774e0a` `b812b4e` `18fe476` `eae0f3e` `5428bff` `dc9e50c` `aa70ac3` | litertlm SDK / conversation-API / callback / sealed-class migrations + bump→0.11.0 | ALREADY-PRESENT | dep parity at `0.11.0`; `RelaisEngine` uses current API (`EngineConfig`, `ConversationConfig`, `MessageCallback`, `createConversation`) |
| `6341f8a` | import models from HuggingFace URLs | SUPERSEDED | node-native `RelaisHuggingFace.search/resolve` + commit-pinned `RelaisModelRef` (`RelaisModelSelector`) |
| `232614c` | default runtime=litertlm for imports | SUPERSEDED | Relais only provisions `.litertlm` (`RelaisHuggingFace.ResolveResult.NoLiteRtLm`) |
| `6002ae6` | default backend from allowlist accelerator order | SUPERSEDED | modality-driven `BackendSelector.select(...)` in `RelaisEngine` |
| `8064a09` | resetSession support image/audio flags | SUPERSEDED | per-modality engine rebuild `RelaisEngine.buildResidentEngine` |
| `a78b476` | LLM engine cleanup bug | SUPERSEDED | node owns lifecycle (`RelaisEngine.close()`, `RelaisNodeService`) |
| `791e51e` | session re-init error message | DROP | UI-only string in inherited `LlmChatViewModel`; node never surfaces it |
| `42fc526` | gate config/reset until model init | DROP | UI button-enablement; node uses `RelaisEngine.isInitialized()` |
| `b7eef35` | LlmModelHelper refactor (+117) | DROP | inherited `runtime/LlmModelHelper.kt`; node uses `RelaisEngine` |
| `60e2847` | fix hardcoded AICore prefill speed | DROP | `ui/benchmark/…`; benchmark is a declared dead-end |
| `f36ec74` | fix AICore benchmark crash | DROP | `ui/benchmark/…`; dead-end |
| `97158a5` | memory-warning trigger condition | DROP | UI button gating only |
| `8ca33ac` | estimated peak memory usage | DROP (data) | edits only `model_allowlist.json` — fetched live from Google; never code-ported |
| `0fc172e` | memory-warning UI + est-peak-memory plumbing | DROP (UI) | `MemoryWarning.kt` dialog is interactive-only; node has `RelaisAdmission`/`ThermalGovernor` |
| `2cc8f5c` | AICore maxOutputTokens | FULL-ONLY (skip) | `runtime/aicore/AICoreModelHelper.kt`; Relais AICore path gated off in `RelaisEngine` |
| `99e5f06` | AICore integration (+693) | FULL-ONLY (skip) | Relais keeps a gated stub (`RelaisAicore.available()`→false) |
| `3bc84cc` | AICore no-access logs | FULL-ONLY (skip) | diagnostics only |

### Devil's-advocate spot-checks (2026-06-21)

| SHA | subject | disposition | evidence |
|-----|---------|-------------|----------|
| `e1ab2b8` | securely store access token (encrypted proto DataStore) | SUPERSEDED | Relais uses `EncryptedSharedPreferences` AES256-GCM + Keystore `MasterKey` + one-time `migrateSecrets()` (`RelaisConfig.kt`) — stronger than upstream |
| `858c66f` `9d36594` | session/skills reset bugfixes | DROP | Compose `mutableStateOf` bug in the inherited agentchat skills bottom-sheet UI; not the node's `RelaisEngine` session lifecycle |

### Pass 2 — path-scoped sweep of 55 previously-unread commits (2026-06-21)

Pass 1's keyword filter missed 55 path-relevant commits; all now read. **0 ports.** Node-relevant subset:

| SHA | subject | disposition | evidence |
|-----|---------|-------------|----------|
| `02572aa` | core system-prompt storage/retrieval | ALREADY-PRESENT | `data/SystemPromptRepository.kt` present; node prompt path = `RelaisOpenAiParser.systemPrompt` → `ConversationConfig.systemInstruction` |
| `6c7dcb0` `2efaa6d` | audio support / max audio clips | SUPERSEDED | node request-driven audio: `RelaisOpenAiParser` (`input_audio`→`audioWav`), `RelaisEngine` (`Content.AudioBytes`, audio backend routing) |
| `d446edf` `f403cce` | updatable model / download cleanup | SUPERSEDED | node `RelaisModelRef` (commitHash-pinned dirs) + `RelaisModelProvisioner` + `worker/DownloadWorker` lifecycle |
| `cecd921` | move/rename AuthConfig→ProjectConfig | ALREADY-PRESENT | `common/ProjectConfig.kt` present; node auth = `RelaisConfig` encrypted HF Bearer (separate path) |
| `8248905` | manual-DI → Hilt | ALREADY-PRESENT | node uses Hilt (`@HiltAndroidApp RelaisApplication`, `di/AppModule.kt @Module`, `@AndroidEntryPoint`) |
| `beb6ebc` `de60ffd` `bb45589` | NPU models / accelerator display | ALREADY-PRESENT / SUPERSEDED | NPU→TPU display correction in `data/ModelAllowlist.kt` (search `\bNPU\b`→`TPU`); node `BackendSelector` is modality+AICore-driven, ignores allowlist accelerator strings |
| `34e53ad` `4652fd4` | download-notification pending-intent / silent foreground worker | ALREADY-PRESENT | node `worker/DownloadWorker.kt` (`PendingIntent.getActivity`, `setForeground` IMPORTANCE_LOW). CameraX/icon/labels = UI DROP |
| `0335765` `3f0dee4` `e8fa789` | RuntimeType enum / big pre-fork reorg / package rename | SUPERSEDED | fork carries its own layout + `cc.grepon.relais`; node uses `RelaisModelRef`, not Gallery `Model.runtimeType` |
| *(remaining 42)* | UI chrome, skills/customtasks, benchmark, analytics, a11y, TOS, layout, config sliders, app icon, voice-input UI | DROP | inherited Gallery UI surfaces the node never invokes |

**Pass-2 tally (55):** PORT **0** · ALREADY-PRESENT 6 · SUPERSEDED 7 · DROP 42 · FULL-ONLY 0.

## Net
**0 ports — verified across the full path-scoped node-relevant set (76 commits, both passes).** The
node's substantive capabilities (inference engine, session seeding, provisioning/versioning, audio,
system prompt, auth, Hilt, download worker) are all present as Relais-authored node code or
absorbed/re-namespaced upstream code. Two notes for the record, NOT upstream ports:
- A peak-memory admission gate (consuming the `estimatedPeakMemoryInBytes` field already in Google's
  live allowlist) would be **net-new Relais code**, overlapping the existing thermal/resident-engine
  OOM design — track as a feature idea if desired, not a sync item.
- AICore (full-flavor) only matters if Relais decides to pursue Gemini-Nano on Pixel 10+.
