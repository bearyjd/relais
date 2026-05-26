# Relais

A headless on-device LLM node: runs a model on the phone and serves an OpenAI-compatible API over the LAN. Forked from `google-ai-edge/gallery`; the Relais subsystem lives under `Android/src/app/src/main/java/com/google/ai/edge/gallery/relais/`.

## Design System
Always read `DESIGN.md` before making any visual or UI decisions. All font choices, colors,
spacing, the icon, and aesthetic direction are defined there (amber signal-relay on near-black,
monospace, broadcast-beacon mark). Do not deviate without explicit user approval. In QA/review,
flag any UI code that doesn't match `DESIGN.md`.
