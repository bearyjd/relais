# Upstream Incorporation Plan — `google-ai-edge/gallery` → Relais

**Date:** 2026-06-21 · **Status:** PHASE 1 DONE → 0 ports · **Author:** session handoff

> **Phase 1 result (2026-06-21):** the **76-commit path-scoped node-relevant set** (commits touching
> `data/`/`runtime/`/`worker/`/`di/`) is **100% dispositioned** across two passes (98 commits read
> total). **Zero require porting** — every node-relevant change is already-present / superseded /
> live-fetched data / out-of-scope. The other ~326 of 424 touch only UI/customtasks/skills/macOS/CI/
> docs and cannot affect node runtime behavior. Full per-commit dispositions + auditable coverage +
> sync watermark (synced-to `4895efa`) in
> [`.claude/upstream-incorporation-log.md`](../../upstream-incorporation-log.md). Phases 2–3 below are
> the procedure for *future* syncs; nothing to execute now.

Selective, surgical incorporation of upstream Gallery changes into the Relais fork. This is a
**list-then-port-the-survivors** plan, not a sync/merge effort.

---

## Bottom line (measured this session)

- `upstream` remote is configured fetch-only (`github.com/google-ai-edge/gallery.git`, push DISABLE).
- Real fork point (merge-base) = `bedc488` (2025-05-16). Upstream HEAD = 2026-06-18. **424 upstream
  commits** in between.
- A straight `git merge upstream/main` → **104 conflicted files / 240 touched**, AND it would
  re-apply ~a year of work the fork already incorporated as different SHAs (the fork pulled upstream
  through ~1.0.16 via cherry-pick/rebase, so git's common ancestor fell back to May 2025). **A plain
  merge is off the table.**
- Upstream's year of work is **~95% Gallery UI/app chrome** (`ui/common` 460 file-touches, `data`
  161, `ui/llmchat` 118, `modelmanager` 102, `agentchat` 81…). The model-access engine layer
  (`runtime/`) saw only **9**; `runtime/aicore` only **5**.
- The marquee runtime features upstream shipped are **already in Relais**: speculative decoding
  (`RelaisEngine.kt`), thinking/reasoning (`RelaisReasoning.kt`), HF model handling
  (`RelaisHuggingFace.kt` / `RelaisModelProvisioner.kt`).
- **`RelaisInference` (local in-process path) wraps `RelaisEngine.generate`** — Relais-authored, NOT
  derived from upstream. The "access on-device locally vs. through the API" capability already
  exists; upstream provides none of it (upstream has no API and is itself all-local).

**Conclusion: the genuinely portable set is a SHORT TAIL of runtime/worker robustness fixes
(~3–6 commits). Treat upstream as a reference, not a feed.** The most valuable durable output of this
work is a **watermark ledger** so the *next* sync is cheap.

---

## Hard constraints (guardrails)

1. **Never `git merge upstream/main`.** Cherry-pick / hand-port individual commits only.
2. **Path rename:** upstream paths are `com/google/ai/edge/gallery/...`; Relais is
   `cc/grepon/relais/...`. `git cherry-pick -x` *may* map via rename detection, but expect most to
   need a **hand-port** (read the upstream diff, apply the equivalent to the renamed Relais file).
   Always pass `-x` (or record the source SHA) for provenance.
3. **License:** Gallery is Apache-2.0, Relais net-new files are AGPL-3.0. Pulling Apache→AGPL is
   one-way OK. Preserve the **Apache header + `NOTICE` attribution** on upstream-origin files; do not
   stamp AGPL headers onto ported upstream code.
4. **Flavor reality:** AICore-only fixes apply to the `full` flavor only (degoogled has no AICore).
   Don't compromise `full` for `degoogled` or vice-versa.
5. **One logical change per commit.** Verify **both flavors** green
   (`:app:testFullDebugUnitTest :app:testDegoogledDebugUnitTest`, one flavor per gradle invocation —
   both-at-once OOMs the 2GB daemon) and the **degoogled dex GMS=0** gate before each PR.
6. Allowlist JSON (`model_allowlists/*.json`) is fetched at RUNTIME from `google-ai-edge/gallery` —
   **not ported via code**. Skip all allowlist-data commits.

---

## Candidate ledger (the "ones that matter" — first cut)

Extracted from `bedc488..upstream/main` touching `runtime/`, `worker/`, or litertlm/session/inference
subjects. **Verdicts marked VERIFY require Phase-1 dedup against current Relais.**

| SHA | Subject | Area | Preliminary verdict |
|-----|---------|------|--------------------|
| `791e51e` | Improve error message for session re-initialization failure | runtime/session | **VERIFY → likely PORT** (node robustness) |
| `2781aac` | Fix download resumption (request identity/non-gzip encoding) | worker/download | **VERIFY → likely PORT** (provisioning robustness) |
| `8c077c8` | Fix a download resume bug | worker/download | **VERIFY → likely PORT** |
| `90eb033` | Load versioned model allowlist | worker/data | **VERIFY** (Relais may fetch differently) |
| `393e036` | Add `ModelCapability` / `capabilityToTaskTypes` to Model & Allowlist | data/model | **VERIFY** (metadata; node may not need) |
| `af17df1` | Update the load-history logic | runtime | **VERIFY** |
| `6341f8a` | Enable importing models from Hugging Face URLs | worker/UI | **VERIFY** (`RelaisHuggingFace.kt` may already cover) |
| `60e2847` | Fix hardcoded AICore prefill speed | runtime/aicore | **full-only**; VERIFY |
| `f36ec74` | Fix crash benchmarking on-device AICore models | runtime/aicore | **full-only**; low value (benchmark is a dead-end per memory) |
| `2cc8f5c` | Configure maxOutputTokens for AICore models | runtime/aicore | **full-only**; VERIFY |
| `d774e0a` / `af777f1` / `e67eefb` / `da0ed48` | LiteRT-LM Kotlin/conversation API migrations | runtime/litertlm | **VERIFY → likely ALREADY-PRESENT** (Relais on 0.11.0 w/ its own adapter) |
| `0a8afa0` / `88f0f85` / `c1450df` | Speculative decoding config + init flag | runtime | **ALREADY-PRESENT** (`RelaisEngine.kt`) |
| `b3cbb09` / `611456b` | Thinking mode (config + chat panel) | runtime/UI | **ALREADY-PRESENT** (`RelaisReasoning.kt`, `RelaisEngine.kt`) |

Everything else in the 424 = Gallery UI/app chrome, `customtasks/*`, macOS app, CI/build tooling,
docs, allowlist data → **out of scope** for the node (optional, only if a specific inherited-UI fix
is wanted).

---

## Phases

### Phase 1 — Build & triage the ledger (read-only; the "list" deliverable)
- Programmatically dump all 424 with files touched; filter to runtime/worker/session/inference/
  provisioning; drop UI-chrome, agentchat, allowlist-data, macOS, CI.
- For each survivor, **diff the upstream change vs current Relais code** and assign:
  `ALREADY-PRESENT` (skip) · `SUPERSEDED` (skip) · `PORT` (queue) · `FULL-ONLY` · `DROP/irrelevant`.
- Output: this ledger, fully resolved (no VERIFY left). *Good candidate to delegate to one
  general-purpose agent producing a structured table.*

### Phase 2 — Port the `PORT` survivors, smallest-first
- Per commit: `git cherry-pick -x <sha>`; on path/conflict failure (expected), hand-port the diff
  onto the renamed file. Keep Apache attribution.
- Group into a few focused PRs by subsystem, e.g.:
  - **PR: engine/session robustness from upstream** (`791e51e`, `af17df1`, …)
  - **PR: provisioning/download fixes from upstream** (`2781aac`, `8c077c8`, …)
  - **PR (full only): AICore fixes** (`60e2847`, `2cc8f5c`) — only if AICore is still a supported path.
- Each PR: both-flavor unit tests green, degoogled dex GMS=0, code-reviewer pass before merge.

### Phase 3 — Verify & watermark
- Confirm both flavors build + test; note any device-gated items (none expected — these are JVM/unit
  testable robustness fixes).
- **Write a watermark ledger** (`.claude/upstream-incorporation-log.md`): record (a) the upstream HEAD
  SHA synced-to, (b) every incorporated SHA, (c) every reviewed-and-skipped SHA with reason. This makes
  the next sync a cheap diff of `<last-watermark>..upstream/main` instead of re-triaging 424.

---

## Cadence (after this pass)
- **Reference, not feed.** Re-run Phase 1 only on a **litertlm dependency bump** or quarterly.
- Watch specifically for: litertlm/runtime fixes, model download/provisioning robustness, session
  lifecycle correctness. Ignore UI/app/customtasks churn — Relais de-emphasizes that surface.
- The node subsystem (`cc/grepon/relais/` non-UI) never conflicts with upstream; conflicts only ever
  arise in the inherited Gallery UI surface, which we are not tracking.
