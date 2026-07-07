# AUDIT.md — Relais Control Panel UI/UX Redesign Spec

**Scope:** `RelaisControlActivity` (the app's sole screen) + `RelaisModelSelectorSheet`.
**Sources read:** `Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt`,
`RelaisModelSelector.kt`, `RelaisPalette.kt`, `DESIGN.md`, `relais-node-GOAL.md`, `README.md`.
**Rule of this pass:** buttonology first, visuals second. No code is changed by this document.
DESIGN.md is a constraint: amber `#FFB000` single accent, charcoal, monospace, dark-only,
pulsing-dot signature. The redesign is a *more disciplined execution* of that system.

---

## 1. Current-state inventory

Top-to-bottom of the single scrolling `Column` (`RelaisControlActivity.kt:188-355`).
Frequency: **daily** = touched every session · **setup** = once-per-install · **rare** = occasional.

| # | Element | Kind | Location | Frequency |
|---|---------|------|----------|-----------|
| 1 | Status dot (pulses when LIVE) + `RELAIS` wordmark + `LIVE/STARTING/OFFLINE` text | readout | `:195-219` | daily (glance) |
| 2 | Tagline caption "on-device relay · OpenAI-compatible LAN endpoint" | readout | `:220-225` | never (decoration) |
| 3 | `STATUS` readout row ("engine resident" / "starting…" / "stopped") | readout | `Readout`, `:228` | daily (glance) |
| 4 | `LAN (https)` endpoint row | readout | `:229` | daily — **the value users leave with** |
| 5 | `LOCAL (http)` endpoint row | readout | `:230` | rare (on-device clients only) |
| 6 | `POWER` restricted/unrestricted row | readout | `:231` | setup (verify once) |
| 7 | `ALLOW UNRESTRICTED ›` action link (open builds, only while restricted) | control | `ActionLink`, `:235-237` | **once ever** |
| 8 | `ACCESS KEY` chip — tap-to-copy full key, "TAP TO COPY/COPIED" affordance | control | `AccessKeyChip`, `:445-497` | daily — the other value users leave with |
| 9 | `SHARE CONNECTION ›` (share sheet: base URL + key) | control | `:480-495` | rare |
| 10 | `MODEL` row → opens selector sheet (disabled while `running && !ready`) | control | `ModelRow`, `:248` | occasional |
| 11 | `HF TOKEN` text field (token in cleartext, always visible) | control | `:249-256` | **once ever** |
| 12 | `SAVE HF TOKEN` outlined button | control | `:257-265` | **once ever** |
| 13 | `savedNote` inline caption (transient confirmations, persists until next edit) | readout | `:266-268` | — |
| 14 | Model selector bottom sheet (Curated / HF search / manual id) | control | `RelaisModelSelector.kt:80-261` | occasional |
| 15 | `PROMPT TEMPLATES ›` action link | control | `:301-303` | rare |
| 16 | `NOTIFICATION TRIAGE ›` action link (open builds) | control | `:307-311` | rare |
| 17 | `SHARE TARGET` readout row + `ENABLE/DISABLE SHARE TARGET` action link | control | `:315-320` | rare toggle |
| 18 | `NFC WORKFLOWS` readout + `ENABLE/DISABLE NFC` + `WRITE NFC TAG ›` (NFC devices) | control | `:324-334` | rare toggle |
| 19 | `START` (filled amber) + `STOP` (outlined StopRed), 50/50 row, **last element** | control | `:337-354` | **daily — the primary action** |

Count: ~14 interactive elements on one flat, undifferentiated column. Three of them
(HF token field, save button, allow-unrestricted) are once-ever; they sit *above* the
daily primary action.

---

## 2. Problems

Each problem names the exact composable.

**P1 — The primary action is at the bottom of a scroll, below once-ever config.**
`START`/`STOP` (`:337-354`) render after the HF-token field, prompt-templates, triage,
share-target and NFC blocks. On a small window (Fold cover screen, split-screen) the one
button the operator came to press can be off-screen. Frequency inverted: daily controls
must dominate; once-ever controls must recede.

**P2 — START and STOP are both always visible, at equal weight, regardless of state.**
When OFFLINE, `STOP` is a dead control presented at 50% of the primary row. When LIVE,
`START` is dead weight. There is never a state in which both are meaningful. The screen
never has "exactly one obvious primary action."

**P3 — When LIVE, the connection info does not out-rank the controls.**
The point of opening the app while LIVE is "give me endpoint + key." The LAN endpoint is
a 13sp `Readout` row (`:229`) visually identical to `POWER` (`:231`), and the key chip
sits mid-column. Nothing about the LIVE layout says "this is what you came for."

**P4 — Endpoints are not copyable.**
Only the key chip has tap-to-copy (`AccessKeyChip:462-465`). `LAN (https)` and
`LOCAL (http)` are inert `Readout` rows — the operator retypes an IP:port by hand or
detours through `SHARE CONNECTION`. The two leave-the-app values have inconsistent
ergonomics.

**P5 — Setup-time concerns permanently occupy prime real estate.**
The HF token field + save button (`:249-265`) — used approximately once — sit
mid-panel between MODEL and the action links, in cleartext, forever. Same for
`ALLOW UNRESTRICTED` (`:235-237`) which at least self-hides once granted. Progressive
disclosure is absent: everything is always on screen.

**P6 — STARTING is one opaque state hiding three very different phases.**
`statusText` (`:172`) collapses HF resolve → multi-GB download → engine load into
"STARTING / starting…". A first-run model download can take minutes with zero progress
signal; the operator cannot distinguish "downloading 2.8 GB" from "hung." The
`nodeBusy` model-row lockout (`:247-248`) dims MODEL to 0.5 alpha with no explanation.

**P7 — Thermal shedding is invisible on the panel.**
The node has a thermal governor and exposes shed state on `/health` and the web
dashboard, but the control screen's status ternary (`:172`) has no thermal expression.
The operator holding the phone — the person who can feel it's hot — gets no signal.

**P8 — Status is stated twice, inconsistently.**
`LIVE/STARTING/OFFLINE` top-right (`:211-218`) *and* a `STATUS` readout row
("engine resident"/"starting…"/"stopped", `:228`). Two vocabularies for one fact,
two lines spent.

**P9 — Toggles are rendered as command links, costing two rows each.**
`SHARE TARGET` and `NFC WORKFLOWS` each spend a `Readout` row for state plus an
`ActionLink` row for the inverse command (`:315-320`, `:324-334`). Four to five rows of
amber commands for two rare booleans — and amber command links visually compete with
genuinely primary amber elements.

**P10 — The type ladder is flat.**
22 / 15 / 14 / 13 / 12 / 11 sp with nearly everything at 11-13sp. Label vs value vs
caption vs action all sit within 2sp of each other; hierarchy is carried almost entirely
by color. There is no display tier for the things that matter most in each state.

**P11 — `savedNote` is a transient message with no lifecycle.**
"HF token saved. Restart to apply." (`:266-268`) persists indefinitely and doubles as
the model-selection confirmation channel (`:282`, `:293`) — two unrelated confirmations
share one mutable string, whichever wrote last wins.

**P12 — The tagline spends a permanent line on marketing copy** (`:220-225`) in an
operator panel whose brand thesis is restraint.

What is *right* and must be kept: the `Readout` label/value idiom, hairline dividers,
the beacon pulse gated to LIVE, tap-to-copy + COPIED feedback on the key chip, StopRed
used solely for Stop, the selector sheet's no-egress-until-typed discipline, and the
key-gated adb `cmd` contract (`:108-122`) — none of this changes.

---

## 3. Proposed information architecture

Home screen = status, connection, one primary action. Everything setup/rare moves behind
one deliberate tap: a **`CONFIGURE ›` surface** (recommended form: a second full-screen
activity in identical panel styling — see Open Questions Q1).

| HOME (glance + daily) | CONFIGURE (setup + rare) |
|---|---|
| Beacon dot + `RELAIS` + state word (single status line, P8 merged) | `MODEL` row → existing `RelaisModelSelectorSheet` (unchanged) |
| Phase/detail line (state-dependent: progress, thermal, model name) | `HF TOKEN` (masked field + save; note "gated repos only") |
| `LAN` endpoint — tap-to-copy | `POWER` row + `ALLOW UNRESTRICTED ›` (open builds, while restricted) |
| `LOCAL` endpoint — tap-to-copy (LIVE only) | `SHARE TARGET` toggle (one row) |
| `ACCESS KEY` chip — tap-to-copy (unchanged idiom) | `NFC WORKFLOWS` toggle + `WRITE NFC TAG ›` (one row + conditional link) |
| `SHARE CONNECTION ›` | `PROMPT TEMPLATES ›` |
| `MODEL` summary row (read-only value + `›`, tap → Configure) | `NOTIFICATION TRIAGE ›` (open builds) |
| **One** state-appropriate primary button (START / CANCEL / STOP) | |
| `CONFIGURE ›` entry | |

Nothing is removed. Every current capability keeps a path; the adb/intent surface
(`handleCmd`) is untouched.

---

## 4. Redesigned screen specs

### 4.0 Shared frame (all states)

- Insets: `systemBarsPadding()`, horizontal padding 24dp (unchanged).
- Vertical rhythm on a 4dp base grid: **20dp between sections** (divider-separated
  groups), **10dp between rows** within a section. Replaces the uniform 14dp `spacedBy`.
- Type scale (the new ladder — see §7 for the DESIGN.md amendment):
  - **Display 22sp** — wordmark only (unchanged).
  - **Hero 17sp** — the state's one most-copied value (endpoint host:port when LIVE).
  - **Value 13sp** — readout values, key text.
  - **Label 11sp, letter-spacing 1.5sp, Muted** — readout labels, section labels.
  - **Caption 11sp Muted** — hints, confirmations.
  - **Action 12sp Bold Amber** — `… ›` links. **Button 14sp Bold, letter-spacing 2sp.**
- Header (all states): dot (11dp) + `RELAIS` + state word right-aligned — exactly as
  today (`:195-219`) but the `STATUS` readout row and the tagline are **deleted** (P8,
  P12); the header alone carries state, and a single **detail line** directly under the
  header carries the state's one-line elaboration (Caption tier).
- Copy affordance rule (P4): every copyable value row renders a trailing `⧉` glyph in
  Amber at Label size; on tap the glyph area swaps to `COPIED` for 1.5s (the
  `AccessKeyChip` timing, reused). Copyable = LAN endpoint, LOCAL endpoint, access key.
- The primary button is **full-width** (not a 50/50 pair) and is the **last element**,
  but the home screen must fit without scrolling on a 6.1" phone — achieved by the
  Configure split, not by shrinking. `CONFIGURE ›` sits directly above it.

### 4.1 HOME — OFFLINE

```
● (Muted, static)  RELAIS                    OFFLINE (Muted)
node stopped · <model display name>              ← detail line, Caption
─────────────────────────────────────────
LAN (https)      192.168.x.x:8443   ⧉        ← Value, Muted-dimmed (not yet live)
ACCESS KEY       [ chip — unchanged idiom ]
SHARE CONNECTION ›
─────────────────────────────────────────
MODEL            Gemma 4 E2B          ›      ← read-only summary, tap → Configure
CONFIGURE ›
[■■■■■■■■■■  START  ■■■■■■■■■■]              ← full-width, Amber fill, Charcoal text
```

- `STOP` is **absent** (P2). START is the only button.
- The LAN row shows the *prospective* endpoint (the bind is deterministic) but its value
  renders Muted, going Paper only when LIVE — pre-copyable for client setup, visibly
  not-yet-live. `LOCAL` row hidden when OFFLINE (rare + meaningless while down).
- MODEL on home is a **read-only summary** (P5): value + `›`, tapping opens Configure
  (not the sheet directly) — one deliberate tap to all rare controls, per the IA table.

### 4.2 HOME — STARTING

Same frame; changes only:

- Dot: Amber 60% alpha, **static** (no pulse — the pulse stays exclusive to LIVE).
- State word: `STARTING` in Amber 60%.
- Detail line becomes the **phase line** (P6), one of:
  - `resolving model…`
  - `downloading model · 43% · 1.2/2.8 GB` (when the provisioner exposes progress;
    if a phase's progress is genuinely unknowable, show the phase name alone — never
    a bare "starting…")
  - `loading engine…`
- Primary button: **`CANCEL`** — full-width **outlined** StopRed (StopRed's reserved
  meaning is "stop the node"; canceling a start is that same act — wired to
  `RelaisNodeService.stop`). START is absent.
- MODEL summary row dims to 0.5 alpha (existing `nodeBusy` rule) and its detail line
  explains why: caption under the row, `model locked while starting` (P6).
- Endpoint rows: as in OFFLINE (Muted values). Key chip unchanged.

### 4.3 HOME — LIVE

The screen re-ranks: **connection info out-ranks controls** (P3).

```
● (Amber, pulsing)  RELAIS                      LIVE (Amber)
engine resident · <model display name>
─────────────────────────────────────────
LAN (https)                                     ← Label
192.168.x.x:8443                        ⧉       ← HERO 17sp, Paper — the screen's peak
LOCAL (http)     127.0.0.1:8080         ⧉       ← Value 13sp row
ACCESS KEY       [ chip — unchanged ]
SHARE CONNECTION ›
─────────────────────────────────────────
MODEL            Gemma 4 E2B          ›
CONFIGURE ›
[───────────────  STOP  ───────────────]        ← full-width OUTLINED StopRed
```

- The LAN endpoint is the **only** Hero-tier element in the app; it is the visual peak
  of the LIVE screen, above the key chip. STOP is present but demoted: outlined (never
  filled), last, after `CONFIGURE ›`. START is absent.
- Key chip: idiom unchanged (border, TAP TO COPY → COPIED). See Q2 for optional masking.

### 4.4 HOME — THERMAL SHED (a LIVE sub-state, P7)

Not a fourth top-level state — the node is still serving. Expressed within LIVE:

- State word: `LIVE` stays Amber; the **detail line** becomes
  `thermal · shedding load` in **Paper** (bright = attention by brightness, the
  dashboard's established salience rule; no new color — amber stays "good/live",
  StopRed stays Stop-only).
- Dot: continues the LIVE pulse (the node *is* live). No new motion.
- Everything else identical to LIVE.
- *Implementation note (for Opus):* the panel's 1s poll loop (`:163-170`) reads only
  `RelaisEngine.isReady`; surfacing shed state needs a thermal getter plumbed from the
  same source `/health` uses. If that plumbing is out of scope for the first redesign
  PR, ship the layout with the hook stubbed and land thermal in a follow-up.

### 4.5 CONFIGURE surface

Full-screen, identical charcoal/panel styling, `‹ CONFIGURE` header (Label tier +
back affordance). Sections in this order, hairline-divided, 20dp section rhythm:

1. **MODEL** — the current `ModelRow` verbatim (value + amber `▸`), opens
   `RelaisModelSelectorSheet` unchanged. Disabled + explanation caption while starting
   (same `nodeBusy` rule). Selection confirmation ("Selected … · Restart to apply")
   renders as a caption **under this row only** — no shared `savedNote` string (P11).
2. **HF TOKEN** — masked text field (`PasswordVisualTransformation`, monospace bullets)
   with a `SHOW` label-tier toggle; `SAVE HF TOKEN` action; caption
   `gated repos only · restart to apply`. Its own confirmation caption (P11).
3. **POWER** — `POWER  restricted/unrestricted` readout; `ALLOW UNRESTRICTED ›` link
   while restricted on `POLICY_OPEN` builds (existing gating `:235-237` unchanged).
4. **INTEGRATIONS** —
   - `SHARE TARGET` as a **single row**: label left, `on/off` value right, whole row
     tappable to toggle (P9); value flips in place. No separate command link.
   - `NFC WORKFLOWS` same single-row toggle; `WRITE NFC TAG ›` link appears under it
     while on (existing `nfcAvailable`/`nfcEnabled` gating).
   - `PROMPT TEMPLATES ›`
   - `NOTIFICATION TRIAGE ›` (`POLICY_OPEN` builds, existing gating).

No START/STOP on Configure — one place to operate the node, one place to set it up.

---

## 5. Motion spec

- **Keep, unchanged:** the beacon pulse — alpha 0.3→1.0, 900ms, reverse repeat,
  **only while LIVE**. This remains the app's sole animation signature.
- **State transitions (OFFLINE→STARTING→LIVE):** the dot and state word crossfade color
  over ~300ms (a `animateColorAsState` tween). No slides, no scale, no layout motion —
  rows appear/disappear with a plain 150ms fade at most. STARTING gets **no** pulse and
  no spinner in the header; the phase line's changing text is the progress signal.
  (A determinate download may show a 2dp hairline progress bar in Amber directly under
  the phase line — a functional readout, not decoration; it disappears outside the
  download phase.)
- **Explicitly rejected:** pulsing during STARTING (dilutes the LIVE signature),
  animated button swaps, any decorative motion on Configure.

---

## 6. Open questions (need your call before implementation)

- **Q1 — Configure surface form.** Recommendation: **second full-screen activity**
  (matches the existing `PromptTemplateEditorActivity`/`NfcWriteActivity` pattern, and
  the model selector already being a bottom sheet makes sheet-from-sheet awkward).
  Alternatives: expandable section (keeps one screen, but re-clutters it), bottom sheet
  (conflicts with the nested model-selector sheet). Confirm: second activity?
- **Q2 — Access-key masking.** Today the full key renders in cleartext whenever the
  screen is open (shoulder-surf on a device that may sit on a desk LIVE). Option: show
  `••••…last-4` with the existing tap still copying the full key, and a `SHOW` toggle.
  Costs one glance-affordance; gains real exposure reduction. Mask by default?
- **Q3 — OFFLINE endpoint display.** Spec §4.1 shows the prospective LAN endpoint in
  Muted while offline (useful for pre-configuring clients). Alternative: hide endpoints
  entirely until LIVE (stricter "don't show what isn't true"). Keep the Muted preview?
- **Q4 — CANCEL during download.** §4.2 wires CANCEL to `RelaisNodeService.stop`.
  Should canceling mid-download also discard the partial download, or keep it for
  resume? (Current provisioner behavior decides the honest label.)
- **Q5 — Thermal plumbing scope.** Is the `RelaisEngine` thermal getter (§4.4) in scope
  for the redesign PR, or stub-now/land-later?
- **Q6 — Download-progress plumbing.** Phase-line percentages (§4.2) require the model
  provisioner to expose progress to the activity. Same scope question as Q5.

---

## 7. DESIGN.md diff (proposed amendments)

Concrete edits so DESIGN.md stays the source of truth. Apply only alongside the
implementation PR.

1. **Typography → Scale** — replace the current line with:
   > **Scale (control screen):** display 22sp (wordmark) / hero 17sp (the state's
   > single most-copied value — LIVE LAN endpoint only) / status 13sp / value 13sp /
   > label 11sp @ +1.5sp tracking / caption 11sp / action link 12sp bold / button 14sp
   > bold @ +2sp tracking. At most ONE hero element per screen state.
2. **Spacing & Layout** — add:
   > **Rhythm:** 4dp base grid; 20dp between divider-separated sections; 10dp between
   > rows within a section. **Primary action:** full-width, one per screen state, last
   > in the layout; the home screen must fit a 6.1" display without scrolling.
3. **Application** — add:
   > **Surfaces:** the control screen shows status, connection, model summary, and the
   > single state-appropriate action (START / CANCEL / STOP — never more than one).
   > Setup-time and rare controls live on the CONFIGURE screen, one tap away, in the
   > same panel styling.
4. **Color → Status mapping** — extend:
   > STARTING = amber 60%, static dot, with a phase line (resolve / download+progress /
   > engine load). THERMAL SHED = a LIVE sub-state: dot stays pulsing amber; the detail
   > line reads `thermal · shedding load` in Paper (salience by brightness — no new
   > color). StopRed covers STOP and CANCEL-start (both are "stop the node").
5. **Motion** — append:
   > State changes crossfade dot/status color over ~300ms; a determinate model download
   > may show a 2dp amber hairline progress bar under the phase line. Nothing else moves.
6. **New rule (Affordances):**
   > Every copyable value (endpoints, access key) carries an explicit trailing copy
   > affordance (`⧉` / TAP TO COPY) that acknowledges with `COPIED` for ~1.5s. Toggles
   > render as single label/value rows, tappable across the row — never as paired
   > readout + command-link rows.
7. **Decisions Log** — add a row:
   > | 2026-07-06 | Control-panel buttonology pass: frequency-ranked layout, single
   > state-appropriate primary action, Configure split, hero endpoint on LIVE, phase-line
   > STARTING, thermal sub-state | AUDIT.md redesign audit |

---

*End of audit. No Kotlin/Compose was modified in this pass. Implementation picks up from
§3-§4 after the Open Questions are answered.*
