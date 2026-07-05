# Changelog

All notable changes to Relais are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to semantic-ish versioning: the `versionName` tracks
user-visible releases, and every release also bumps the Android `versionCode`.

## [Unreleased]

### Added

### Changed

### Fixed

### Security

## [1.0.15] - 2026-07-04

First public release (versionCode 33). Relais is a headless on-device LLM
node: it runs a model on a spare Android phone's GPU/neural silicon via
Google LiteRT-LM and serves an OpenAI-compatible API over the LAN. Because
this is the first tagged release, essentially the entire surface lands under
"Added" — the baseline node first, then the work that shipped on top of it.

### Added

**Core node (baseline feature set)**

- OpenAI-compatible `POST /v1/chat/completions` with SSE streaming, so
  existing OpenAI clients and SDKs work against the phone unchanged.
- Native `POST /generate` endpoint for multimodal input: text, image, and
  audio.
- `GET /health` for liveness checks and a Prometheus-format `GET /metrics`
  endpoint for scraping node telemetry.
- Bearer-token authentication with the API key encrypted at rest; HTTPS
  served on the LAN.
- Zero-config discovery: the node advertises itself as `_relais._tcp` over
  mDNS.
- Thermal backpressure: when the device runs hot, the API sheds load with
  `503` + `Retry-After` instead of throttling silently or crashing.
- Crash/OOM auto-recovery for the inference engine, plus Doze survival and
  auto-start so the node keeps serving unattended.
- Model selector with a curated model list and HuggingFace search. Default
  model is gemma-4-E4B-it (~3.7 GB), downloadable without an access token.

**Image generation (experimental, `full` build)**

- On-device image generation via an sd.cpp backend. The image endpoint
  reports its state honestly as it warms up: `501` (not provisioned) →
  `503` (loading) → `200` (serving). (#83)
- Image-gen model provisioner backed by a SHA-pinned public model registry,
  so downloaded weights are integrity-checked against known hashes. (#82)
- Image generation runs exclusively behind a drain-the-gate admission lock:
  in-flight LLM requests drain before a render starts, so image generation
  never contends with the resident LLM for the GPU. (#84)

**Release engineering**

- Tag-triggered CI release pipeline producing a Play Store AAB plus
  IzzyOnDroid and GrapheneOS APKs, with release signing performed in CI
  from hardware-backed secrets. This release (1.0.15) is the first one cut
  through it. (#85)

### Security

- Closed an SSRF DNS-rebinding TOCTOU in the agentchat skill fetch by
  pinning the vetted IP for the connection, so a hostname can no longer
  re-resolve to an internal address between validation and use. (#81)

---

## Release notes — how we write them

Template for a new release entry:

```markdown
## [X.Y.Z] - YYYY-MM-DD

One or two sentences of context if the release has a theme; omit otherwise.

### Added
- New capability, stated as what the user can now do. (#PR)

### Changed
- Behavior that differs from the previous release, and why. (#PR)

### Fixed
- The user-visible symptom that no longer happens. (#PR)

### Security
- What was vulnerable, what an attacker could have done, what changed. (#PR)
```

Style rules:

1. **Honest first.** Say what actually shipped, including caveats
   (experimental, build-flavor-only, known limits). No hype, no emoji.
2. **Lead with the user-facing benefit**, then the mechanism. "Existing
   OpenAI clients work unchanged" before "SSE chunk framing".
3. **Group by Added / Changed / Fixed / Security** — one entry per change,
   written as a human-readable sentence, not a raw commit subject.
4. **Link the PR** in parentheses at the end of each entry so every claim
   is traceable to a diff.
5. **Keep the store-facing short form separate.** The Play/Izzy changelog
   for each release lives in
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and must
   stay ≤ 500 characters for Play; distill from this file, don't diverge
   from it.
