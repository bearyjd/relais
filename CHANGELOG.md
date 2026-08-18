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

## [1.0.20] - 2026-08-18

versionCode 38. The app now targets Android 16 (API 36), and an opt-in report
that fails to reach the developer is retried instead of silently lost.

### Added

- Opt-in report sends are durable. The choice is recorded on the report itself
  (schema v7), a failed delivery is retried in the background, and
  `CONFIGURE > REPORTED OUTPUT` shows each report's send status with a SEND
  action for anything still undelivered. A report the user did not opt to send
  is never transmitted — by the retry worker or by that screen.
- Download stop reasons are logged. The app previously had no way to tell a
  stalled download from one the system had throttled.

### Changed

- Targets Android 16 (API 36); `compileSdk` 36, Robolectric 4.16. Verified on a
  Pixel 10 Pro Fold: the unfolded 852dp layout renders identically to 1.0.19,
  which already filled the same window.
- The report dialog's consent caption now names every field a send carries.
  It previously omitted the chat surface, which the Data Safety form declares.

### Fixed

- A download resuming after the system stopped its worker is no longer counted
  as a new download. It previously overwrote the recorded start time — so an
  interrupted download reported a *faster* duration than an uninterrupted one —
  logged a duplicate start event, and left the progress bar frozen at its last
  value with no explanation. A dropped network was enough to trigger this.
- Report delivery distinguishes a rate-limited attempt from a server error and
  from a rejected payload. A rate-limited attempt no longer consumes the retry
  budget, so being throttled can never be what permanently fails a report.
- The on-device probe suite compiles again, and CI now compiles it so it cannot
  silently break.

### Security

- No change to what the app transmits. The set of fields a sent report carries
  is unchanged from 1.0.19; only its delivery is now durable.

## [1.0.19] - 2026-08-17

versionCode 37. The report a user files against AI output can now — per report,
and only on request — also be sent to the developer, completing the "to
developers" half of Play's AI-Generated Content policy that 1.0.18's local-only
capture deliberately deferred. This flips the Data Safety declaration: the app
now declares optional collection.

### Added

- An ALSO SEND TO DEVELOPER toggle in the report dialog: default off, decided
  per report. When checked, that report — the flagged excerpt, the reason
  picked, the optional note, and the model, backend and chat surface it came
  from — is POSTed over HTTPS to the deployed receiver
  (`report.ventouxlabs.com`); when unchecked, nothing transmits, exactly as
  before. The "saved" notice appears immediately and updates when the send
  resolves. A failed send is reported, not retried — retry is tracked as #273.
  (#274)

### Changed

- Data Safety declarations and the privacy policy now describe the optional
  collection the send path creates: Messages (excerpt), App activity (reason,
  surface, note), Personal info (free text can carry it), Device or other IDs
  (the receiver's salted rate-limit hash). (#274, #267)

### Security

- The send is HTTPS-only with redirects disabled and carries no credentials —
  the receiver is effectively open by design and defends structurally: hard
  body caps before parsing, an allowlist schema, per-IP rate limiting on a
  salted non-reversible hash. (#274)

## [1.0.18] - 2026-08-08

versionCode 36. Adds in-app reporting for AI-generated output, which is what
Google Play's AI-Generated Content policy asks for. Reports stay on the device:
there is no delivery path yet, and the Data Safety declaration is unchanged
because nothing is transmitted.

### Added

- Report AI output from inside the app, on both chat surfaces: pick one of six
  reasons, add an optional note, and the report is written to the device. A
  confirmation says plainly that it stays there. (#260)
- A review screen in the control panel listing the reports saved on this
  device. (#260)
- `report-worker`, a Cloudflare Worker that receives reports — **not deployed,
  and the app has no send path to it**. It is here so the delivery half can be
  reviewed and tested before anything transmits. (#262, #263, #268)

### Fixed

- The report Worker could not start at all: the Workers runtime rejects a
  non-function named export from the entry module, and four size constants were
  exported from it. Unit tests and `wrangler deploy --dry-run` both pass on the
  broken shape, because neither boots the runtime. (#268)

### Security

- Nothing in this release transmits a report. The receiver, when deployed, rate
  limits per caller on a salted, non-reversible hash of the caller IP; the raw
  address is never stored. (#262)

## [1.0.17] - 2026-08-04

versionCode 35. *(Backfilled 2026-08-08 — 1.0.16 and 1.0.17 shipped without an
entry here, which this file's own opening line promises not to happen.)*

### Fixed

- The compatibility gate now parses a download URL's authority with
  `java.net.URI` instead of slicing the string, so an unusual host spelling —
  odd casing, an explicit port, a userinfo prefix — can no longer read as
  "unidentifiable" and let a model already measured as incompatible start a
  multi-gigabyte download that was always going to fail. (#243)

### Changed

- Refusal messages read the same wherever they appear, and the API explains an
  unloadable model rather than reporting it as missing. (#243)

## [1.0.16] - 2026-08-04

versionCode 34. *(Backfilled 2026-08-08 — see the note under 1.0.17.)*

### Fixed

- A long model ID no longer pushes the chat actions out of view. (#241)

### Changed

- Verified chat sharing, Markdown export, and audio attachment flows; added
  on-device multimodal HTTP coverage. The Pixel 10 image-generation CPU
  fallback is preserved while Vulkan driver retests stay isolated. (#241)

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
