# Relais — Issue Decomposition Plan (2026-07-07)

**Status: CREATED on GitHub 2026-07-07 (approved "all").**
Mapping: E1=#96 · E3=#97 · E4=#98 · E2=#69 (existing, relabeled) ·
S1.2=#99 · S1.3=#100 · S1.4=#101 · S3.1=#102 · S3.2=#103 ·
T1–T21 = #104–#125 in plan order (T10a=#113, T10b=#114).
Repo: `bearyjd/relais` · branch `main` · CI green (secret-scan, license-lint, Build Android APK).

## Sources consulted

- `AUDIT.md` (control-panel UI/UX redesign spec, merged 2026-07-06, unimplemented — the largest body of decided-but-unbuilt work)
- `README.md`, `CLAUDE.md`, `DESIGN.md` (constraint), `relais-node-GOAL.md` (v1 gates — all shipped in 1.0.15)
- `docs/distribution.md` (names three explicit follow-up sub-projects: Play policy paperwork, store-listing metadata, IzzyOnDroid RFP)
- `SPIKE-FINDINGS.md` (deferred NPU/AICore gate `g4b_npuAicorePathOrSkip` — closable now that a Pixel 10 exists)
- `gh issue list --state all`: 1 open (#69, image-gen Vulkan deadlock), 4 closed. No duplication risk with anything below except #69, which is handled by *linking*, not duplicating.
- TODO/FIXME sweep: 22 hits. Most are inherited upstream Google-gallery TODOs in dormant fork code (`ui/llmchat`, `modelmanager`, `benchmark` — phase-2 per GOAL.md non-goals; deliberately **not** filed). Relais-specific actionable ones are folded in below (`RelaisEngine.kt:504` mid-decode stop).
- CI: last 15 runs all green; no CI issues to file.

## Proposed hierarchy at a glance

| Tier | Count | Items |
|---|---|---|
| Epic | 3 new + 1 existing | E1 control-panel redesign · E2 = existing #69 (relabel `epic`) · E3 store distribution · E4 engine follow-ups |
| Story | 6 | S1.1–S1.4 under E1 · S3.1–S3.2 under E3 |
| Task | 18 | 12 under E1 · 3 under E2/#69 · 4 under E3 · 2 under E4 (one E1 task is a decision task) |

Suggested new labels (create once, first): `epic`, `story`, `task`, `area:ui`, `area:engine`, `area:imagegen`, `area:distribution`, `size:S`, `size:M`.

---

## EPIC E1 — Control-panel UI/UX redesign (implement AUDIT.md)

**Labels:** `epic`, `area:ui`, `feature`

Implement the redesign specified in `AUDIT.md` (merged via PR #95): frequency-ranked
home screen with a single state-appropriate primary action, a separate CONFIGURE
surface for setup-time/rare controls, copyable endpoints, a phase-aware STARTING
state, a thermal sub-state on LIVE, and the corresponding `DESIGN.md` amendments.
AUDIT.md is the spec; DESIGN.md (amber `#FFB000` single accent, charcoal, monospace,
dark-only) is the constraint. No capability is removed; the adb `cmd` contract is
untouched.

Children: S1.1, S1.2, S1.3, S1.4 (+ decision task T1 and doc task T12, parented
directly to the epic).

### TASK T1 — Record decisions for AUDIT.md open questions Q1–Q6

## Summary
JD answers the six open design questions in `AUDIT.md` §6 and records the answers in the doc, unblocking every implementation task in this epic.

## Why (context a newcomer wouldn't have)
- `AUDIT.md` (the merged control-panel redesign specification) deliberately stops short of six decisions: Q1 Configure-surface form (second activity vs sheet), Q2 access-key masking, Q3 OFFLINE endpoint preview, Q4 CANCEL-during-download semantics, Q5 thermal plumbing scope, Q6 download-progress plumbing scope.
- Per this repo's convention, an implementation task must never require an undocumented architectural judgment call — so the judgment calls are this upstream task.
- Parent: Epic E1.

## Scope (what to touch)
- `AUDIT.md` §6 only (append a decision line per question). No code.

## Acceptance Criteria
- [ ] Every question Q1–Q6 in `AUDIT.md` §6 has a recorded decision (one line each, dated), committed to `main`.

## Implementation notes
- AUDIT.md already states a recommendation for Q1 (second full-screen activity, matching the existing `PromptTemplateEditorActivity` pattern); accepting recommendations wholesale is a valid outcome.

## Testing / Definition of Done
- `git log` shows the AUDIT.md decision commit; each downstream task can quote its governing decision.

## Size
S

## Depends on
none — **blocks nearly everything below**

## Labels
task, area:ui, size:S, feature

---

### STORY S1.2 — Home screen: one state-appropriate primary action, connection info first

**Labels:** `story`, `area:ui`, `feature` · Parent: E1

When the operator opens the panel they see status, connection values, and exactly
one primary button (START when offline, CANCEL while starting, STOP when live) —
with daily-use values copyable in one tap and once-ever setup controls gone from
the home screen. Fixes AUDIT.md problems P1, P2, P3, P4, P8, P10, P12.

Children: T2, T3, T4, T5, T6.

#### TASK T2 — Single full-width state-appropriate primary button (P1, P2)

## Summary
Replace the always-visible 50/50 START+STOP row with one full-width button whose identity follows node state: START (amber fill) when offline, CANCEL (outlined red) while starting, STOP (outlined red) when live.

## Why (context a newcomer wouldn't have)
- `RelaisControlActivity.kt` (the app's only screen) currently renders START and STOP side-by-side at equal weight regardless of state (`:337-354`), below once-ever config — AUDIT.md P1/P2.
- CANCEL wires to the same `RelaisNodeService.stop` as STOP; both mean "stop the node", which is why both use the reserved StopRed color (AUDIT.md §4.2). Q4's decision (partial-download handling) governs the CANCEL label honesty.
- Parent: S1.2 → E1. Spec: AUDIT.md §4.1–§4.3.

## Scope (what to touch)
- `Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt` (button row + state derivation).
- Out of scope: row reordering/Configure split (T6/S1.3), copy affordances (T4).

## Acceptance Criteria
- [ ] In each of the three node states exactly one primary button renders, full-width, last in the layout, with the state-correct label/style (START amber filled / CANCEL red outlined / STOP red outlined).

## Implementation notes
- State already derives from the 1s poll loop (`:163-170`) + `running`/`ready` flags used by `statusText` (`:172`); reuse it — don't add a new state source.
- DESIGN.md constraint: StopRed is used **only** for stop-the-node actions; START is the only filled-amber button.

## Testing / Definition of Done
- Build `:app:assembleDebug`, drive the node through offline→starting→live on-device (or Robolectric state test if the composable is extracted), verifying the button per state. A composable unit test asserting button presence per state triple is the named check.

## Size
S

## Depends on
T1 (Q4)

## Labels
task, area:ui, size:S, feature

#### TASK T3 — Merge the duplicated status line and delete the tagline (P8, P12)

## Summary
The header (dot + RELAIS + state word) becomes the single statement of node state, with a one-line state-dependent detail line under it; the redundant `STATUS` readout row and the marketing tagline are deleted.

## Why (context a newcomer wouldn't have)
- State is currently stated twice in two vocabularies (`LIVE/STARTING/OFFLINE` top-right at `:211-218` *and* a `STATUS` row "engine resident/starting…/stopped" at `:228`), and a permanent tagline line (`:220-225`) spends a row on decoration — AUDIT.md P8/P12.
- The detail line this task introduces is the mount point later tasks reuse for phase/progress (T10) and thermal (T11) text.
- Parent: S1.2 → E1. Spec: AUDIT.md §4.0.

## Scope (what to touch)
- `RelaisControlActivity.kt` header region only.
- Out of scope: phase-line *content* during STARTING (T10), thermal content (T11) — this task ships the static detail line (`node stopped · <model>` / `engine resident · <model>`).

## Acceptance Criteria
- [ ] The rendered home screen contains exactly one state word and no tagline, and shows a detail line of the form `<state phrase> · <model display name>` in both OFFLINE and LIVE.

## Implementation notes
- Keep the beacon pulse gated to LIVE exactly as today — it is the app's sole animation signature (DESIGN.md).

## Testing / Definition of Done
- Composable test (or screenshot check) asserting: no `STATUS` label row, no tagline string, detail line text per state.

## Size
S

## Depends on
none

## Labels
task, area:ui, size:S, feature

#### TASK T4 — Tap-to-copy affordance on endpoint rows (P4)

## Summary
The LAN and LOCAL endpoint rows become copyable with an explicit trailing `⧉` glyph that acknowledges with `COPIED` for 1.5s, matching the existing access-key chip behavior.

## Why (context a newcomer wouldn't have)
- The two values operators leave the app with are the endpoint and the key; today only the key chip copies (`AccessKeyChip:462-465`) while endpoint rows are inert text the operator retypes — AUDIT.md P4.
- Parent: S1.2 → E1. Spec: AUDIT.md §4.0 copy-affordance rule.

## Scope (what to touch)
- `RelaisControlActivity.kt`: the `Readout` idiom (add an optional copyable variant) + the two endpoint rows.
- Out of scope: key-chip masking (Q2 → note under T6), hero sizing (T5).

## Acceptance Criteria
- [ ] Tapping the LAN endpoint row puts the exact `host:port` value on the clipboard and swaps the glyph to `COPIED` for ~1.5s (same behavior on LOCAL when visible).

## Implementation notes
- Reuse the `AccessKeyChip` copy+timer mechanics rather than re-implementing; glyph renders in Amber at Label size.
- Q3's decision (T1) governs whether the OFFLINE screen shows a muted prospective LAN value or hides it.

## Testing / Definition of Done
- Compose UI test: perform click on the LAN row, assert `ClipboardManager` content and the transient `COPIED` text.

## Size
S

## Depends on
T1 (Q3)

## Labels
task, area:ui, size:S, feature

#### TASK T5 — LIVE layout re-rank: hero endpoint + new type ladder (P3, P10)

## Summary
When LIVE, the LAN endpoint renders as the screen's single hero element (17sp, Paper) above the key chip, using the new type scale (display 22 / hero 17 / value 13 / label 11+tracking / caption 11 / action 12 / button 14).

## Why (context a newcomer wouldn't have)
- The point of opening a LIVE panel is "give me endpoint + key", but today the LAN endpoint is a 13sp row identical to `POWER`, and the whole type ladder is flat (11–13sp) — AUDIT.md P3/P10.
- Parent: S1.2 → E1. Spec: AUDIT.md §4.0 type scale + §4.3.

## Scope (what to touch)
- `RelaisControlActivity.kt` (type constants + LIVE layout order), possibly `RelaisPalette.kt` if text styles live there.
- Out of scope: DESIGN.md text amendments (T12).

## Acceptance Criteria
- [ ] In LIVE the LAN endpoint is the only element at hero size (17sp) and renders above the access-key chip; no other screen state has a hero-tier element.

## Implementation notes
- 4dp base grid, 20dp between sections / 10dp between rows (replaces uniform 14dp `spacedBy`) — land the rhythm here since this task already touches the layout skeleton.

## Testing / Definition of Done
- Compose test asserting font size / ordering of the LIVE column; visual check on a 6.1" viewport that HOME fits without scrolling (the fit is finished by T6's Configure split).

## Size
M

## Depends on
T3 (single header), T4 (copyable rows)

## Labels
task, area:ui, size:M, feature

#### TASK T6 — MODEL summary row + CONFIGURE entry on home (P5 home-side)

## Summary
The home screen's MODEL row becomes a read-only summary (value + `›`) that opens the new Configure surface, and a `CONFIGURE ›` entry sits directly above the primary button; the HF-token field/save button and other rare controls disappear from home.

## Why (context a newcomer wouldn't have)
- Once-ever setup (HF token cleartext field `:249-265`, ALLOW UNRESTRICTED, toggles) currently occupies prime real estate above the daily primary action — AUDIT.md P5. This task is the *home-screen half* of the split; the Configure surface itself is S1.3.
- Parent: S1.2 → E1. Spec: AUDIT.md §3 IA table + §4.1.

## Scope (what to touch)
- `RelaisControlActivity.kt`: remove moved rows, add summary row + `CONFIGURE ›` link.
- Out of scope: the Configure activity content (T7–T9).

## Acceptance Criteria
- [ ] The home screen renders no HF-token field, no toggles, and no prompt-template/triage links; tapping MODEL or `CONFIGURE ›` opens the Configure surface; home fits a 6.1" display without scrolling.

## Implementation notes
- MODEL summary keeps the existing `nodeBusy` 0.5-alpha dim while starting, now with an explanatory caption (`model locked while starting`).
- Q2 (key masking) decision from T1 lands here if "mask by default" is chosen (chip renders `••••…last-4` + SHOW).

## Testing / Definition of Done
- Compose test: assert absent rows + intent/navigation fired on tap. Manual: full user path (change model, save token) still reachable via Configure.

## Size
M

## Depends on
T1 (Q1, Q2), T7 (Configure scaffold must exist to navigate to)

## Labels
task, area:ui, size:M, feature

---

### STORY S1.3 — CONFIGURE surface

**Labels:** `story`, `area:ui`, `feature` · Parent: E1

All setup-time and rare controls live on one Configure screen in identical panel
styling, one deliberate tap from home: MODEL (existing selector sheet), masked HF
token, POWER, and single-row toggles. Fixes P5, P9, P11.

Children: T7, T8, T9.

#### TASK T7 — Configure surface scaffold with MODEL and POWER sections

## Summary
Create the Configure screen (per the Q1 decision — recommendation: a second full-screen activity in identical charcoal/panel styling) containing the MODEL row (opens the existing `RelaisModelSelectorSheet` unchanged) and the POWER section (readout + `ALLOW UNRESTRICTED ›` with existing gating).

## Why (context a newcomer wouldn't have)
- AUDIT.md §4.5 defines a Configure surface with sections MODEL / HF TOKEN / POWER / INTEGRATIONS; this task ships the scaffold plus the two sections that move verbatim, so T8/T9 are pure section additions.
- The repo already has the second-activity pattern: `PromptTemplateEditorActivity`, `NfcWriteActivity`.
- Parent: S1.3 → E1.

## Scope (what to touch)
- New activity/screen file under `Android/src/app/src/main/java/cc/grepon/relais/`, `AndroidManifest.xml` entry, move of `ModelRow` + POWER block out of `RelaisControlActivity.kt`.
- Out of scope: HF token section (T8), toggles (T9), home-side entry point (T6).

## Acceptance Criteria
- [ ] Configure opens with `‹ CONFIGURE` header, shows MODEL (selection still works end-to-end via the unchanged sheet, confirmation caption rendered under the MODEL row only) and POWER (link visible only while restricted on `POLICY_OPEN` builds), and contains no START/STOP.

## Implementation notes
- Model-selection confirmation must be a caption local to the MODEL row — this begins retiring the shared `savedNote` string (P11); T8 finishes it.
- 20dp section rhythm, hairline dividers, same palette — read `DESIGN.md` before styling anything.

## Testing / Definition of Done
- On-device (or instrumented) pass: open Configure, pick a curated model, see "Selected … · Restart to apply" under MODEL, restart node, node serves the selected model.

## Size
M

## Depends on
T1 (Q1)

## Labels
task, area:ui, size:M, feature

#### TASK T8 — Masked HF token section with local confirmation (P5, P11)

## Summary
The HuggingFace token field moves to Configure as a masked password field (monospace bullets, SHOW toggle) with its own save action and its own confirmation caption, ending the cleartext always-visible token and the shared `savedNote` string.

## Why (context a newcomer wouldn't have)
- Today the HF token — a credential used roughly once, only for gated model repos — renders in cleartext permanently mid-panel (`:249-265`), and its "saved" confirmation shares one mutable string with model-selection confirmations, whichever wrote last wins (P11).
- Parent: S1.3 → E1. Spec: AUDIT.md §4.5 item 2.

## Scope (what to touch)
- The Configure screen file (T7) + removal of the field block from `RelaisControlActivity.kt` (coordinated with T6).
- Out of scope: token storage/encryption changes — persistence is untouched.

## Acceptance Criteria
- [ ] The token field renders masked by default with a working SHOW toggle, saving shows a confirmation caption under this section only, and the caption `gated repos only · restart to apply` is present; no `savedNote` shared-string writes remain in the codebase.

## Implementation notes
- `PasswordVisualTransformation` with the monospace font; keep the existing save path (same repository call) — this is a presentation move, not a storage change.

## Testing / Definition of Done
- Save a token, then select a model: the two confirmations render under their own sections and don't overwrite each other (the P11 regression is the named test).

## Size
S

## Depends on
T7

## Labels
task, area:ui, size:S, feature

#### TASK T9 — Single-row toggles for SHARE TARGET and NFC + relocated links (P9)

## Summary
SHARE TARGET and NFC WORKFLOWS become single label/value rows tappable across the row (value flips in place), with `WRITE NFC TAG ›` appearing under NFC while on, and `PROMPT TEMPLATES ›` / `NOTIFICATION TRIAGE ›` relocate to the INTEGRATIONS section.

## Why (context a newcomer wouldn't have)
- Each toggle today costs two rows — a readout row plus an amber command link stating the inverse (`:315-320`, `:324-334`) — and those amber links visually compete with genuinely primary actions (P9).
- Parent: S1.3 → E1. Spec: AUDIT.md §4.5 item 4.

## Scope (what to touch)
- Configure screen file; removal of the corresponding blocks from `RelaisControlActivity.kt`.
- Out of scope: any change to what the toggles *do* (share-target component enable, NFC dispatch) — existing gating (`nfcAvailable`/`nfcEnabled`, `POLICY_OPEN`) is preserved verbatim.

## Acceptance Criteria
- [ ] Each toggle is one row whose value flips on tap, `WRITE NFC TAG ›` shows only while NFC is on, and both feature flows (share into node, NFC tag write) still work end-to-end.

## Implementation notes
- Follow the toggle-row rule being added to DESIGN.md (T12): label left, `on/off` value right, whole row tappable — never paired readout + command-link rows.

## Testing / Definition of Done
- Manual on-device: toggle each, kill/relaunch app, state persisted; share an image into the node and write one NFC tag to prove no regression.

## Size
S

## Depends on
T7

## Labels
task, area:ui, size:S, feature

---

### STORY S1.4 — STARTING is legible: phase line, download progress, CANCEL (P6)

**Labels:** `story`, `area:ui`, `feature` · Parent: E1

A first-run start (multi-GB model download) currently shows an opaque "starting…"
for minutes; the operator can't distinguish downloading from hung. After this story
the header detail line names the phase (`resolving model…` / `downloading model ·
43% · 1.2/2.8 GB` / `loading engine…`) and the start is cancelable.

Children: T10a, T10b.

#### TASK T10a — Expose provisioning phase + download progress to the panel

## Summary
The model provisioning pipeline exposes a observable phase (resolve / download-with-progress / engine-load) that the control activity's poll loop can read; no UI change in this task.

## Why (context a newcomer wouldn't have)
- `statusText` collapses HF resolve → multi-GB download → engine load into one string (`RelaisControlActivity.kt:172`) because nothing upstream exposes phases (P6, open question Q6 — plumbing was explicitly deferred to a decision).
- Parent: S1.4 → E1.

## Scope (what to touch)
- The provisioning/download path (best guess: `data/DownloadRepository.kt`, `worker/DownloadWorker.kt`, `RelaisEngine.kt` / node service state — assignee confirms) + a getter reachable from the activity.
- Out of scope: rendering (T10b).

## Acceptance Criteria
- [ ] A unit/instrumented test observes the exposed state transition resolve → download (with monotonically advancing bytes/total during a real or faked download) → engine-load → ready.

## Implementation notes
- Per `CLAUDE.md`: before assuming a capability gap, check `docs/litertlm-native-api.md` — engine-load progress may or may not be exposable; if not, the phase name alone is acceptable (AUDIT.md: never a bare "starting…").
- Immutability preference: expose a state value (e.g. a data class in a `StateFlow`), don't mutate a shared var the poll loop races.

## Testing / Definition of Done
- Named test above green; existing start path unaffected (node still reaches LIVE).

## Size
M

## Depends on
T1 (Q6)

## Labels
task, area:engine, size:M, feature

#### TASK T10b — Phase line UI, determinate progress hairline, CANCEL wiring

## Summary
While STARTING, the header detail line renders the current phase (with `NN% · X/Y GB` during download and a 2dp amber hairline progress bar under it), and the single primary button is CANCEL wired to stop the start.

## Why (context a newcomer wouldn't have)
- Consumes T10a's state; completes P6. CANCEL semantics for partial downloads (discard vs keep-for-resume) follow the Q4 decision — the button's honest label depends on it.
- Parent: S1.4 → E1. Spec: AUDIT.md §4.2 + §5 motion rules (no pulse, no spinner during STARTING).

## Scope (what to touch)
- `RelaisControlActivity.kt` (detail line + progress hairline + CANCEL branch of the T2 button).
- Out of scope: provisioner behavior changes.

## Acceptance Criteria
- [ ] During a real model download the detail line shows advancing percentage and the hairline bar advances; tapping CANCEL stops the node (returns to OFFLINE) with partial-download handling matching the recorded Q4 decision.

## Implementation notes
- The hairline bar exists only during a determinate download phase — it is a readout, not decoration; remove it in all other phases.

## Testing / Definition of Done
- On-device first-run start with a cleared model dir: phases render in order; CANCEL mid-download verified.

## Size
M

## Depends on
T2, T3, T10a

## Labels
task, area:ui, size:M, feature

---

### TASK T11 — Thermal shed sub-state on the LIVE panel (P7)

## Summary
When the node is shedding load thermally, the LIVE screen's detail line reads `thermal · shedding load` in Paper (bright), fed by a thermal getter plumbed from the same source `/health` uses.

## Why (context a newcomer wouldn't have)
- The node already has a thermal governor (sheds with `503 + Retry-After`) and exposes shed state on `/health` and the web dashboard, but the panel's 1s poll loop reads only `RelaisEngine.isReady` (`:163-170`) — the person holding the hot phone gets no signal (P7).
- Scope was an open question (Q5: plumb now vs stub); this task assumes "plumb now" — if the T1 decision is "stub", split into layout-with-hook + follow-up.
- Parent: E1 (direct child; AUDIT.md treats it as a LIVE sub-state, not a screen state). Spec: §4.4.

## Scope (what to touch)
- `RelaisEngine.kt` (or node service — wherever `/health`'s thermal source lives; assignee confirms) for the getter; `RelaisControlActivity.kt` for the detail line.

## Acceptance Criteria
- [ ] With shed state active (driven synthetically in a test or via sustained load), the LIVE detail line shows `thermal · shedding load` in Paper while the dot keeps the normal LIVE pulse, and it reverts when shedding clears.

## Implementation notes
- No new color, no new motion: amber stays "live/good", StopRed stays stop-only; salience is by brightness (Paper) per the dashboard's established rule.

## Testing / Definition of Done
- Unit test on the state mapping + a manual sustained-load check (`docs/soak` harness can drive it).

## Size
M

## Depends on
T1 (Q5), T3

## Labels
task, area:engine, area:ui, size:M, feature

### TASK T12 — Apply the DESIGN.md amendments (AUDIT.md §7)

## Summary
DESIGN.md gains the seven amendments specified in AUDIT.md §7 (type scale, rhythm, surfaces rule, STARTING/THERMAL status mapping, motion additions, copy/toggle affordance rule, decisions-log row) so it stays the source of truth the implementation was built against.

## Why (context a newcomer wouldn't have)
- `CLAUDE.md` mandates DESIGN.md as the binding design reference and AUDIT.md instructs these edits be applied "only alongside the implementation PR" — so this lands last, once the implemented reality matches the amended text.
- Parent: E1.

## Scope (what to touch)
- `DESIGN.md` only.

## Acceptance Criteria
- [ ] All seven §7 amendments appear in DESIGN.md with wording adjusted to match what actually shipped (any divergence from the audit spec is noted in the Decisions Log row).

## Implementation notes
- Diff each amendment against shipped behavior first; DESIGN.md must describe reality, not the plan.

## Testing / Definition of Done
- Review-only: a reviewer can navigate every DESIGN.md claim to a shipped screen.

## Size
S

## Depends on
T5, T6, T8, T9, T10b, T11 (the implementation it documents)

## Labels
task, area:ui, size:S, docs → use `feature`

---

## EPIC E2 — Image-generation engine deadlock (existing issue #69 — relabel, decompose)

**Action:** do **not** create a new epic issue. Label existing **#69** (`sd.cpp/ggml-vulkan
deadlocks on first compute dispatch, Tensor G5/PowerVR DXT`) as `epic`/`bug`, and create
its own next-steps checklist as three linked tasks. #69 already contains the full
evidence (all-threads-blocked deadlock, 5 configs ruled out, regression note).

### TASK T13 — Reproduce the image-gen Vulkan deadlock on Pixel 8 Pro (husky)

## Summary
Run the existing `ImageGenServiceProbe.bindGenerateOnceAndProcessExits` on the Pixel 8 Pro (Tensor G3, Mali GPU) to determine whether the first-dispatch deadlock is specific to the Pixel 10's PowerVR DXT driver.

## Why (context a newcomer wouldn't have)
- #69: image generation (stable-diffusion.cpp via `io.github.aatricks:llmedge:0.3.9`) loads the model then hangs at the first Vulkan compute dispatch on rango (Pixel 10 / GrapheneOS); five configurations ruled out excludes/flash-attention/process-isolation/reboot. The same setup *worked* (~90s SD-Turbo) earlier the same day — suspected GrapheneOS/PowerVR driver regression. A different-GPU repro is the branch point for everything downstream.
- Parent: #69.

## Scope (what to touch)
- No app code. Test execution + a findings comment on #69 (and `docs/images-generations-api.md` verdict table if it has one).

## Acceptance Criteria
- [ ] #69 has a comment recording the husky result (pass with timing, or hang with the same `top -H` thread-state evidence), explicitly stating whether the deadlock is G5/PowerVR-specific.

## Implementation notes
- Repro recipe is in #69 (`adb push sdturbo.gguf …` + probe). Capture the SmolSD logcat tail either way.

## Testing / Definition of Done
- The comment is sufficient for T14 to file upstream without re-running anything.

## Size
S

## Depends on
none (hardware: requires husky on hand)

## Labels
task, area:imagegen, size:S, bug

### TASK T14 — File the deadlock upstream (llmedge / sd.cpp ggml-vulkan) with evidence

## Summary
Open an upstream issue against `io.github.aatricks:llmedge` (and/or stable-diffusion.cpp's ggml-vulkan backend) carrying #69's evidence package, noting PowerVR DXT and the husky result.

## Why
- The deadlock is below Relais's code (first GPU submit never returns, all threads blocked); Relais's containment (`:imagegen` isolation) already works as designed. The fix must come from the engine or driver — an upstream report is the actionable move. Parent: #69.

## Scope
- External issue + cross-links (upstream URL into #69, #69 into the upstream body). No code.

## Acceptance Criteria
- [ ] An upstream issue exists containing: device/driver matrix (G5 hang, husky result), the ruled-out table, thread-state evidence, and llmedge version — linked from #69.

## Implementation notes
- Wait for T13 so the report says "PowerVR-specific" or "reproduces on Mali" — that difference redirects where it gets fixed.

## Testing / Definition of Done
- Upstream issue open and linked; #69 body's next-steps checklist updated.

## Size
S

## Depends on
T13

## Labels
task, area:imagegen, size:S, bug

### TASK T15 — Confirm SD-1.5 GGUF also deadlocks (cheap negative check)

## Summary
Run one generation attempt with an SD-1.5 GGUF instead of SD-Turbo on rango to confirm the deadlock is model-independent, closing the last open variable in #69.

## Why
- #69 rates this "unlikely to matter for a submit-level deadlock but cheap to confirm" — it removes a reviewer's "did you try another model?" from the upstream thread. Parent: #69.

## Scope
- Test execution + one-line result comment on #69. No code.

## Acceptance Criteria
- [ ] #69 records the SD-1.5 result (hang or success) with the model file identity (name + SHA).

## Implementation notes
- Registry is SHA-pinned (#82) — if the model isn't in the registry, side-load via the probe path rather than modifying the registry for a throwaway test.

## Testing / Definition of Done
- Comment posted; checklist box ticked in #69.

## Size
S

## Depends on
none (parallel with T13)

## Labels
task, area:imagegen, size:S, bug

---

## EPIC E3 — Store distribution completion

**Labels:** `epic`, `area:distribution`, `feature`

`docs/distribution.md` ships the release *pipeline* (done — 1.0.15 was cut through it)
and explicitly names the remaining sub-projects: Play policy paperwork (privacy
policy, Data Safety, content rating), completion of the store listing, and the
IzzyOnDroid RFP. Fastlane screenshots/icon landed via #92/#94; the paperwork hasn't.

Children: S3.1, S3.2.

### STORY S3.1 — Google Play policy paperwork & listing submission

**Labels:** `story`, `area:distribution` · Parent: E3

Everything Play requires beyond the AAB: a hosted privacy policy, a truthful Data
Safety declaration, content rating, and the listing itself — ending with the
`fullPlaysafe` AAB submitted for review.

#### TASK T16 — Write and host the privacy policy

## Summary
Author a privacy policy for Relais (an app that by design sends nothing to any cloud) and host it at a stable public URL suitable for the Play Console field.

## Why
- Play requires a privacy-policy URL for any app; Relais's honest story is unusually strong (all inference on-device, LAN-only serving, no analytics) but the optional HuggingFace token + HF downloads and the LAN API surface must be described accurately. Parent: S3.1 → E3.
- Brand voice: `docs/brand-voice.md` governs tone ("honest first" — see also the CHANGELOG style rules).

## Scope
- New doc (suggest `docs/privacy-policy.md` as source of truth) + hosting (GitHub Pages or repo raw URL — assignee proposes; record choice in `docs/distribution.md`).
- Out of scope: Data Safety form answers (T17) — though they must not contradict this text.

## Acceptance Criteria
- [ ] A public URL serves the policy and it accurately covers: on-device processing, the optional HF token (stored encrypted, sent only to HuggingFace), LAN API exposure, and the absence of analytics/tracking.

## Implementation notes
- Check `NOTICE`/AGPL obligations don't need mention; keep it plain-language.

## Testing / Definition of Done
- URL loads publicly; text reviewed against the actual data flows (grep for any network egress beyond HF + LAN before signing off).

## Size
M

## Depends on
none

## Labels
task, area:distribution, size:M, feature

#### TASK T17 — Data Safety + content-rating declarations (documented mapping)

## Summary
Produce the exact answers for Play's Data Safety form and content-rating questionnaire as a committed doc, derived from the app's real data flows, so the console session is transcription rather than judgment.

## Why
- Data Safety answers are audited against app behavior; getting them wrong is a takedown risk. Relais's flows: HF token (user-provided credential, encrypted at rest, egress only to huggingface.co), model downloads, LAN serving, no ads/analytics. Parent: S3.1 → E3.

## Scope
- New section in `docs/distribution.md` (the doc says paperwork "will be appended here as they land").

## Acceptance Criteria
- [ ] `docs/distribution.md` contains a complete question→answer mapping for Data Safety and content rating, each answer annotated with the code-level fact justifying it (file/behavior reference).

## Implementation notes
- The `fullPlaysafe` variant is the one under review — verify its manifest/permissions (the release gates already strip playsafe permissions) and answer for *that* variant.

## Testing / Definition of Done
- A reviewer can trace every "No" to an absence in the codebase and every "Yes" to a named feature.

## Size
S

## Depends on
T16 (consistency)

## Labels
task, area:distribution, size:S, feature

#### TASK T18 — Complete the Play listing and submit the AAB for review

## Summary
Finish the Play Console setup end-to-end — listing text (from `fastlane/metadata`), screenshots/icon (already in-repo via #92/#94), policy forms (T16/T17 content), Play App Signing enrolment — and submit the `fullPlaysafe` AAB.

## Why
- `docs/distribution.md` §"Cutting a release" step 5 marks the first Play upload as a manual sub-project; artifacts exist (1.0.15 AAB), paperwork lands in T16/T17. Parent: S3.1 → E3.

## Scope
- Play Console (external) + append the completed runbook steps to `docs/distribution.md`.
- Out of scope: reacting to Play review feedback (file follow-ups if rejected).

## Acceptance Criteria
- [ ] The app is submitted for Play review (state: "In review" or beyond), and `docs/distribution.md` records the enrolment + submission steps actually taken.

## Implementation notes
- Play App Signing: the CI key is the *upload* key — enrolment happens on first upload; keep the distribution.md warning about the sideload key immutable-signature story intact.

## Testing / Definition of Done
- Console shows submission; runbook updated so the *next* release needs no rediscovery.

## Size
M

## Depends on
T16, T17

## Labels
task, area:distribution, size:M, feature

### STORY S3.2 — IzzyOnDroid listing

**Labels:** `story`, `area:distribution` · Parent: E3 · Single child task (thin story kept for tier consistency; collapse into E3 as a direct task if preferred).

#### TASK T19 — Submit the IzzyOnDroid RFP for the `fullOpen` APK

## Summary
File the Request-For-Packaging with IzzyOnDroid pointing at the GitHub-Release `fullOpen` APK (`com.ventouxlabs.relais.izzy`) so F-Droid-ecosystem users get updates.

## Why
- Named follow-up in `docs/distribution.md`; F-Droid main repo is impossible (proprietary litertlm blobs → `NonFreeDep`), IzzyOnDroid is the designated home and tracks GitHub Release assets, which 1.0.15 already publishes. Parent: S3.2 → E3.

## Scope
- External RFP (IzzyOnDroid issue tracker) + record the listing URL in `docs/distribution.md`.

## Acceptance Criteria
- [ ] An RFP is filed meeting Izzy's requirements (APK < size limit, reproducible metadata, fastlane dir present) and its URL is recorded in `docs/distribution.md`.

## Implementation notes
- Izzy reads `fastlane/metadata/android/` from the repo — verify the short/full descriptions and changelogs `<versionCode>.txt` (≤500 chars) exist for 33 before filing.
- Check the `fullOpen` APK size against Izzy's per-app cap; if over, the RFP needs the exemption request noted up front.

## Testing / Definition of Done
- RFP link recorded; listing appears (or Izzy feedback captured as follow-up issues).

## Size
S

## Depends on
none

## Labels
task, area:distribution, size:S, feature

---

## EPIC E4 — Engine follow-ups (deferred verifications & decode control)

**Labels:** `epic`, `area:engine` — deliberately thin (two tasks, no stories); kept as an
epic only so the tasks have a shared parent. Collapse to standalone tasks if preferred.

### TASK T20 — Close the deferred NPU/AICore gate on the Pixel 10 (rango)

## Summary
Run the existing `g4b_npuAicorePathOrSkip` instrumented test on the Pixel 10 and record whether the ML Kit GenAI / Gemini Nano (`RelaisAicore`) path actually serves, closing SPIKE-FINDINGS.md's UNVERIFIED gate.

## Why
- `SPIKE-FINDINGS.md` G4: the NPU path is fully wired with a real `checkStatus()` probe but was UNVERIFIED because no Pixel 10 existed — "the deferred gate closes by simply connecting a Pixel 10, no code change." Issue #69 shows a Pixel 10 (rango) is now in hand. The GOAL.md wedge ("if a decision weakens NPU acceleration, it is wrong by default") makes this the cheapest high-value verification in the repo.
- Parent: E4.

## Scope
- Test execution + results appended to `SPIKE-FINDINGS.md` (and follow-up issues for whatever it reveals). No app code.

## Acceptance Criteria
- [ ] `SPIKE-FINDINGS.md` records the rango result: either AICore generation verified (with tok/s) or the concrete failure mode (error code, e.g. a GrapheneOS-without-GMS limitation on the degoogled build).

## Implementation notes
- rango runs GrapheneOS (per #69) — ML Kit GenAI likely requires Google Play services; expect `FEATURE_NOT_FOUND`-class results on degoogled builds and test the `full` flavor with sandboxed Play services if available. Report what is, honestly.

## Testing / Definition of Done
- The instrumented run's logcat/test result is quoted in the doc — proof, not assumption (SPIKE-FINDINGS' own stop-condition rule).

## Size
S

## Depends on
none

## Labels
task, area:engine, size:S, feature

### TASK T21 — Investigate true mid-decode stop via the native LiteRT-LM API

## Summary
Determine whether the litertlm AAR exposes a real mid-decode cancel (today "native decode is bounded by maxNumTokens. True mid-decode native stop is a TODO" — `RelaisEngine.kt:504`) and either wire it or document the verified absence.

## Why
- A client disconnect or `stopResponse` mid-generation currently can't interrupt the native decode; the node burns battery/thermal budget finishing tokens nobody will read — directly against the always-on appliance thesis.
- `CLAUDE.md` warning applies verbatim: "feature plans in this repo have repeatedly been wrong" about native-API gaps — check `docs/litertlm-native-api.md` (raw prefill/decode, channels) and the AAR itself before concluding it's impossible.
- Parent: E4.

## Scope
- Investigation first (doc + probe); implementation in `RelaisEngine.kt` only if the API exists. If absent: update `docs/litertlm-native-api.md` with the verified finding and close.
- Out of scope: HTTP-layer disconnect detection changes.

## Acceptance Criteria
- [ ] Either a passing on-device test shows decode halting within ~1 token-interval of a stop call, or `docs/litertlm-native-api.md` gains a dated, probe-verified entry stating the capability is absent in the current AAR version.

## Implementation notes
- Follow the probe pattern in `Android/src/app/src/androidTest/java/cc/grepon/relais/*Probe.kt`; regenerate the API inventory with `scripts/dump-litertlm-api.sh` if the AAR was bumped since Jun 14.

## Testing / Definition of Done
- Probe result recorded either way — this task cannot end in "probably".

## Size
M

## Depends on
none

## Labels
task, area:engine, size:M, feature

---

## Deliberately NOT filed (and why)

- **Upstream Google-gallery TODOs** (`data/Model.kt`, `ModelManagerViewModel`, `ui/llmchat`, `benchmark`, `ModelImportDialog`): dormant fork code; GOAL.md marks the chat UI / agentchat surface as phase-2/non-goal. Filing 10 issues against code slated to stay quarantined is noise.
- **`SkillTesterBottomSheet.kt:189` "TODO(before launch)"** and **`SecretEditorDialog.kt:66` string-resources TODO**: agentchat (phase-2, dormant). Revisit when agentchat is activated.
- **Multi-hour Doze soak** (SPIKE-FINDINGS follow-up): the `docs/soak` harness exists and CHANGELOG claims Doze survival shipped; re-verify only if a field report contradicts it.
- **AUDIT.md Q-decisions as separate issues each**: bundled into T1 — six one-line decisions by the same person are one sitting.

## Creation order (Step 4)

1. Labels (`epic`/`story`/`task`/areas/sizes) → 2. E1, E3, E4 epics + relabel #69 →
3. Stories S1.2–S1.4, S3.1–S3.2 (bodies reference epic numbers) → 4. Tasks T1–T21
(bodies reference story/epic numbers) → 5. `gh issue edit` each story/epic to add
`- [ ] #N` child checklists.

## Dependency spine

T1 (decisions) → {T2, T4, T6, T7, T10a, T11} → T5/T6 need T3/T4/T7 → T10b needs T2+T10a → T12 last in E1.
E2: T13 → T14; T15 parallel. E3: T16 → T17 → T18; T19 parallel. E4: independent.
