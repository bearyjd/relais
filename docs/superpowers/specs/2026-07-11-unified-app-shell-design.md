# Unified App Shell — Design Spec (Spec 1 of 2)

**Date:** 2026-07-11
**Status:** Approved (design); implementation plan pending
**Scope:** Fold the Relais node, in-app chat, and model selection into a single unified app
under one launcher icon and one navigation host, with the node dashboard as home. This is
**Spec 1 (the shell)**. Chat *depth* (persistence, generation controls, markdown, hybrid
transport) is deferred to **Spec 2 — Chat Depth**.

---

## 1. Problem

The app currently ships as **two disjoint UI islands in one APK**, connected only by two
separate launcher icons — not by any in-app navigation:

- **"Relais"** icon → `MainActivity` → the untouched upstream google-ai-edge/gallery Compose app
  (`GalleryApp` → `GalleryNavHost`): task tiles (`HomeScreen`), `ModelManager`, `BenchmarkScreen`,
  and its own per-task chat (`customtasks/agentchat`, `llmchat`, …).
- **"Relais Node"** icon → `RelaisControlActivity` → the node control surface, which Intent-hops
  to `RelaisConfigureActivity` (setup) and `RelaisChatActivity` (in-app chat). The model picker is
  a `ModalBottomSheet` inside Configure.

The two worlds share no navigation in either direction. The gallery world uses Jetpack Compose
Navigation (`NavHost`); the Relais world is purely Intent-to-activity. There is no unified
navigation host — the core structural obstacle to unifying them.

**Goal:** one launcher icon, one navigation host, node dashboard as home, the redundant gallery
pieces retired and the genuinely-useful ones folded in — all under the `DESIGN.md`
amber-on-charcoal signal-panel system.

## 2. Decisions (locked)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Home = node dashboard.** | The node is the product; chat is a built-in client. |
| D2 | **One launcher icon.** `MainActivity` (already `@AndroidEntryPoint` + NavHost + `ModelManagerViewModel` + the `com.ventouxlabs.relais://` deep-link filter + the "Relais" launcher) becomes the shell host; its NavHost start-destination flips to the node dashboard. `RelaisControlActivity`'s second LAUNCHER filter is removed. | Single unified entry point. **Refined during planning (2026-07-11):** host is `MainActivity`, not `RelaisControlActivity`, because the absorbed `BenchmarkScreen` needs `hiltViewModel()` + `ModelManagerViewModel`, which only the already-`@AndroidEntryPoint` `MainActivity` provides. Same user-visible outcome; lower DI risk. |
| D3 | **Absorb the useful gallery parts; retire the rest.** | Cohesion + keep capability without dragging the whole gallery DI graph in. |
| D4 | **Architecture A — one Compose NavHost, evolve existing screens.** | Reuses on-device-proven control/chat/selector code; least risky path to a unified feel. |
| D5 | **Bottom-nav pattern, styled to DESIGN.md.** DASHBOARD / CHAT / MODELS. | Chat one tap from anywhere; unified-app feel. New design-system component — see §7. |
| D6 | **MODELS = Relais's own selector/catalog, NOT the gallery `ModelManager`.** | Relais already has a self-contained, Hilt-free model UX; the gallery manager is Hilt-coupled and per-task-download oriented — redundant here. |
| D7 | **Ship the shell first (this spec); chat depth is Spec 2.** | Change is large; the shell gives chat a home, then we go deep on chat. |

## 3. Navigation model

Single Activity (`RelaisControlActivity`, now the app shell) hosting a Compose `NavHost`.

**Top-level destinations** (reached via the bottom nav):

- `dashboard` — **start destination.** The existing node control panel (status LIVE/STARTING/
  OFFLINE, LAN + local endpoints, access-key chip, model summary row, single state-appropriate
  primary action START/CANCEL/STOP). Rendered from the pure `computeControlPanelState`.
- `chat` — the existing `ChatScreen`, wired into the shell (depth deferred to Spec 2).
- `models` — a full-screen expression of the existing Relais model selector
  (`RelaisModelSelectorSheet` content + `RelaisModelCatalog`): curated allowlist, HF `.litertlm`
  search, manual repo-id fallback.

**Sub-screens** (pushed onto the back stack with the existing `‹ back` row pattern):

- `configure` — reached from `dashboard` (the CONFIGURE › link and the MODEL row). The existing
  `RelaisConfigureActivity` content: HF token, battery exemption, share/NFC toggles, links to
  prompt templates / NFC write / notification triage.
- `benchmark` — reached from `models`. The absorbed gallery `BenchmarkScreen`, re-themed.

The model picker remains a `ModalBottomSheet` invokable from `dashboard` (model row) and
`configure`.

**Deep links:** the `com.ventouxlabs.relais://…` `VIEW` filter and FCM `deeplink` handling
currently on `MainActivity` migrate into the shell's `NavHost` (Compose Navigation deep-link
routes) so existing links continue to resolve. Any deep link that targeted a now-retired gallery
destination (e.g. the LLM Agent Chat deep link) is remapped to the `chat` destination.

**Back-stack & up-navigation:** handled by the `navController`; the manifest
`parentActivityName` chains on the retired standalone activities are removed.

## 4. The absorb — what stays, ports, retires

**Reused as-is (already self-contained, on-brand):**
- `RelaisControlPanelState.kt` / `computeControlPanelState` (pure, JVM-tested)
- `ChatScreen` (from `RelaisChatActivity.kt`)
- `RelaisModelSelectorSheet` (`RelaisModelSelector.kt`) + `RelaisModelCatalog.kt`

**Ported into the shell as a destination, re-themed:**
- `BenchmarkScreen` (gallery) → `benchmark` sub-screen. Lifted off `GalleryTheme`/Material onto
  the Relais theme.

**Retired (unreachable; files kept on disk, not deleted):**
- Gallery `HomeScreen` (task tiles + promo gate)
- `customtasks/agentchat`, `llmchat`, `llmsingleturn`, and the other per-task custom-task chat
  screens (the Relais in-app chat replaces them)
- `GalleryApp` / `GalleryNavGraph` as the launcher path
- The gallery `ModelManager` / `GlobalModelManager` (superseded by the Relais selector — see D6)

Files are left in place (not deleted) to (a) minimise diff/risk and (b) leave something to diff
against for any future upstream cherry-pick.

## 5. Shared state

Node-status polling currently lives in a `LaunchedEffect` inside `RelaisControlActivity` (polls
`RelaisEngine`/`RelaisConfig` every ~1s → projects `RelaisControlPanelState`). It moves up into a
shared **`RelaisShellViewModel`** owned by the shell Activity and read by all destinations, so
Dashboard, Chat, and Models all observe one LIVE/STARTING/OFFLINE source of truth (e.g. Chat can
show a "node offline — running in-process" hint in Spec 2). State derivation stays pure via the
existing `computeControlPanelState` — the ViewModel only owns the polling loop and exposes the
state; it contains no rendering logic.

## 6. Re-theming

The absorbed `BenchmarkScreen` (and any composables it pulls in) are lifted off
`GalleryTheme`/Material onto the Relais theme: dark-only, background `#0B0B0D`, surface `#16171A`,
hairline `#2A2B30`, accent amber `#FFB000`, paper text `#EDEAE3`, muted `#8A8780`, monospace.
Panel rows follow the label-left / value-right + hairline-divider rhythm from `DESIGN.md §Spacing`.

**Bottom nav styling:** charcoal `#16171A` surface, hairline `#2A2B30` top divider, amber
`#FFB000` active label + icon, muted `#8A8780` inactive, monospace labels at the `label` scale
(11sp @ +1.5sp tracking), no Material ripple/elevation. Three items: DASHBOARD · CHAT · MODELS.

## 7. DESIGN.md deviation to bless

The bottom-nav bar is a **new component** the design system does not currently cover, and
`DESIGN.md` mandates no deviation without explicit approval. It was approved during brainstorming
(2026-07-11) with the styling in §6. A Decisions-Log entry will be added to `DESIGN.md`:

> | 2026-07-11 | Unified app shell: single launcher, Compose NavHost, node dashboard home; new
> DESIGN.md-conformant bottom nav (DASHBOARD/CHAT/MODELS) | Unified-app-shell design spec |

## 8. Testing

- **Pure logic stays JVM-tested** (device-free) under `test/java/cc/grepon/relais/`, one
  `*Test.kt` per subject file, per repo convention. `computeControlPanelState` is already covered;
  any new pure function (e.g. a nav-state / start-destination mapper, or deep-link → route
  resolver) gets its own `*Test.kt`.
- **No new on-device capability** is introduced by the shell, so **no new `*Probe.kt`** is needed.
- The change is verified against the CI unit-test job
  (`testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`).

## 9. Risks & mitigations

- **Hilt/DI coupling.** Retiring gallery `HomeScreen`/`ModelManager` and removing `MainActivity`
  as launcher must not break Hilt graph construction the rest of the app still needs. *Mitigation:*
  keep `MainActivity` and the gallery classes on disk (only remove the launcher intent-filter and
  stop routing to them); verify the app assembles for all three flavors before claiming done.
- **Deep-link regressions.** Links previously resolved by `GalleryNavHost` must map to shell
  routes. *Mitigation:* enumerate every existing deep-link route and add an equivalent shell route
  or an explicit remap; cover the resolver with a `*Test.kt`.
- **Theme leakage.** The absorbed `BenchmarkScreen` may pull Material components that don't honor
  the Relais theme. *Mitigation:* re-theme at port time; visually verify on-device against
  `DESIGN.md`.
- **Flavor matrix.** Launcher/manifest changes touch `src/main` shared across the
  `dist`×`policy` flavor matrix. *Mitigation:* confirm the manifest merges cleanly for
  `fullOpen` / `playsafe` / `degoogled` (do not run heavy Gradle builds unless asked — reason from
  the manifest + `DEVELOPMENT.md` first).

## 10. Out of scope (→ Spec 2 — Chat Depth)

- Persistent, multi-conversation chat history (survives restart; named threads)
- Hybrid transport: HTTP-to-node when live (dogfood the OpenAI API) + in-process
  `RelaisEngine.generate` fallback when the node is stopped
- Model switching from inside chat (+ per-turn model/accelerator readout)
- Generation controls: stop mid-generation, regenerate, edit-and-resend, copy message
- Markdown + syntax-highlighted code rendering (may introduce a grotesk body font per
  `DESIGN.md §Typography` "if a prose surface is added later")

## 11. Success criteria

- Launcher shows **one** Relais icon; opening it lands on the **node dashboard**.
- DASHBOARD / CHAT / MODELS are reachable via bottom nav without leaving the app; CONFIGURE and
  BENCHMARK are reachable as sub-screens with working back-navigation.
- Start/stop, endpoints, access key, model summary, and the single primary action work exactly as
  before on DASHBOARD.
- The in-app chat (current behavior) works from the CHAT destination.
- Model selection (allowlist / HF search / manual repo) works from the MODELS destination.
- All absorbed/retained surfaces render under the Relais theme (no Material light-theme leakage).
- Existing `com.ventouxlabs.relais://…` deep links still resolve.
- CI unit-test job is green.
