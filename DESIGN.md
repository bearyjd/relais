# Design System — Relais

## Product Context
- **What this is:** A headless, on-device LLM node. It runs a model on hardware you own and broadcasts an OpenAI-compatible API over the LAN.
- **Who it's for:** Builders running their own local/offline AI infrastructure.
- **Space/industry:** Local-first / self-hosted AI inference, developer infrastructure.
- **Project type:** Android app — a model browser tile ("Relais") + a node control tile ("Relais Node").

## Brand Thesis
"Your own relay station for AI." Relais is a relay: sovereign, local-first, always-on. Infrastructure you operate, not a cloud you rent. A node with a light on, transmitting on your network.

The one thing to remember: an amber relay light on a black panel.

## Aesthetic Direction
- **Direction:** Industrial signal-panel (rack-equipment status light meets ham-radio beacon, refined).
- **Decoration level:** Minimal. Type, status, and one accent do the work.
- **Mood:** Powered-on, technical, sovereign. Reads as a live machine you control.

## Logo / Mark
- **Mark:** Amber broadcast beacon — a node dot with two concentric arcs radiating upward — on a charcoal field.
- **Files:** `app/src/main/res/drawable/relais_icon_foreground.xml` (amber glyph) over a charcoal adaptive-icon background in `mipmap-anydpi-v26/ic_launcher.xml`. Shared by both launcher tiles.
- **Wordmark:** `RELAIS`, uppercase, monospace, bold, letter-spacing ~5sp, amber.

## Color
- **Approach:** Restrained — one loud accent on near-black.
- **Accent (signal amber):** `#FFB000` — live/transmitting. The only bright color; used for the wordmark, the live status, the primary action, and the mark.
- **Background (charcoal):** `#0B0B0D`
- **Surface / panel:** `#16171A`
- **Hairline / divider:** `#2A2B30`
- **Text (paper):** `#EDEAE3`
- **Muted text / labels:** `#8A8780`
- **Stop / destructive:** `#FF5247` (used only for Stop)
- **Status mapping:** LIVE = full amber + pulsing dot; STARTING = amber 60%; OFFLINE = muted.
- **Mode:** Dark only. This is infra tooling; a light theme is out of scope.

## Typography
- **Wordmark / labels / readouts / buttons:** Monospace (`FontFamily.Monospace`, bundled — no font download). The machine voice; also keeps endpoints/keys/model ids aligned.
- **Body / prose:** same monospace for now (control-panel consistency). If a prose surface is added later, pair with a clean grotesk for body.
- **Scale (control screen):** wordmark 22sp / status 13sp / readout label 12sp / value 13-14sp / caption 11sp.

## Spacing & Layout
- **Density:** Comfortable. Readouts as label-left / value-right rows separated by hairline dividers.
- **Insets:** Always apply `systemBarsPadding()` — content must clear the status/nav bars.
- **Border radius:** Buttons 6dp; text fields use M3 default. Keep it crisp, not bubbly.

## Motion
- **Approach:** Minimal-functional, one signature.
- **Signature:** The status dot pulses amber (alpha 0.3→1.0, ~900ms, reverse) only while the node is LIVE. A beacon heartbeat. Nothing else animates.

## Application
- **App label:** "Relais" (was "Edge Gallery"). Control tile: "Relais Node".
- **Icon:** both launcher tiles share the amber-beacon mark.
- **Control screen:** `RelaisControlActivity` — the canonical expression of this system.

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-05-25 | Initial brand identity: amber signal-relay on near-black, monospace, broadcast-beacon mark | Created via /design-consultation. Amber-on-black breaks from the blue/purple AI-SaaS norm and says "live, transmitting, sovereign" — the relay thesis. Verified on-device (Pixel 9 Pro Fold). |
