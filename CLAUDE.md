# Relais

A headless on-device LLM node: runs a model on the phone and serves an OpenAI-compatible API over the LAN. Forked from `google-ai-edge/gallery`; the Relais subsystem lives under `Android/src/app/src/main/java/cc/grepon/relais/`.

## Native-API-first (LiteRT-LM)
Relais runs on the bundled `com.google.ai.edge.litertlm` AAR. **Before designing any fallback,
prompt-injection scheme, output scraper, or new SDK integration, read
[`docs/litertlm-native-api.md`](docs/litertlm-native-api.md)** — the curated inventory of what the
native API already does (multi-turn seeding, native tool-calling, constrained decoding, channels,
custom templates, raw prefill/decode, real benchmark metrics, …). If a plan or comment claims a
capability is "not available," **verify against the AAR before believing it** — feature plans in this
repo have repeatedly been wrong about this. Regenerate the inventory after any litertlm version bump
with `scripts/dump-litertlm-api.sh`, and re-run the on-device probes
(`Android/src/app/src/androidTest/java/cc/grepon/relais/*Probe.kt`) to re-verify behavior claims.

## Design System
Always read `DESIGN.md` before making any visual or UI decisions. All font choices, colors,
spacing, the icon, and aesthetic direction are defined there (amber signal-relay on near-black,
monospace, broadcast-beacon mark). Do not deviate without explicit user approval. In QA/review,
flag any UI code that doesn't match `DESIGN.md`.
