<!--
  Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
  Store-submission paperwork for Google Play (#122) and IzzyOnDroid (#123). This is the answer
  sheet an operator transcribes into the Play Console forms and the Izzy RFP tracker; it does not
  itself submit anything (both require account access — see "Blocked on the operator" below).
-->

# Relais store-submission runbook (#122 Play · #123 IzzyOnDroid)

Companion to [`distribution.md`](distribution.md) (signing + release pipeline). That doc builds and
signs the artifacts; this one is the **listing + policy paperwork** to get them accepted. Every
answer below is derived from the shipped [`privacy-policy.md`](privacy-policy.md) and the
`fastlane/metadata/` listing already in the repo.

## Blocked on the operator (account-gated — cannot be automated)
- **Play (#122):** uploading the AAB and filling the console forms needs the **Play Console** account
  (`jd@`/VentouxLabs). The answers are pre-filled below — transcribe them.
- **Izzy (#123):** filing the RFP needs a **GitLab** account, **and** a *published* GitHub Release
  carrying the `fullOpen` APK must exist first (Izzy tracks GitHub releases). There is no published
  release yet — cut one per `distribution.md` §"Cutting a release" before filing.

---

## Google Play — Data Safety form

Grounded in the privacy policy: Relais has **no developer server, no analytics/telemetry, no crash
reporting, no ads, no account system**. All inference is on-device; the only network egress is
user-initiated (model downloads from Hugging Face / `dl.google.com`, the curated-catalog + app-update
check, and any webhook/skill URL the user configures). None of that is developer-side collection.

**Does your app collect or share any of the required user data types? → No.**

| Console question | Answer |
|---|---|
| Data collected (sent off-device to the developer) | **None** |
| Data shared (with third parties, by the developer) | **None** |
| Is all data encrypted in transit? | **Yes** — LAN API is HTTPS; all downloads/checks are HTTPS |
| Do you provide a way to request data deletion? | **Yes** — all data is on-device; in-app *Clear data* / uninstall removes it. No server-side data exists to delete |
| Data collected/processed ephemerally only | N/A (no collection) |

> Reviewer-note nuance to keep on file (not entered in the form): the app *does* transmit data the
> **user directs** — a typed model-search query and (optional) HF token to `huggingface.co`, and
> webhook payloads to a user-chosen URL. Play's Data Safety scopes "collection" to the **developer**;
> user-directed third-party traffic with no developer intermediary is disclosed in the privacy policy
> (§"When the app talks to the internet") rather than declared as developer collection. If a reviewer
> pushes back, point them at that section.

Privacy-policy URL for the console: **https://bearyjd.github.io/relais/privacy-policy.html**
(the hosted copy of `docs/privacy-policy.html`; confirm GitHub Pages serves it before submitting).

## Google Play — Content rating (IARC questionnaire)
Category: **Utility / Productivity / Tools** (developer infrastructure; no social features, no UGC
sharing surface, no in-app purchases).

| Questionnaire topic | Answer |
|---|---|
| Violence / scary content | No |
| Sexual / nudity | No |
| Profanity / crude humor | No |
| Controlled substances (drugs/alcohol/tobacco) | No |
| Gambling (simulated or real) | No |
| User-to-user communication / shares user location | No |
| User-generated content shared with others | No — the app serves a **private, LAN-only** API the user hosts; no content is shared through a developer service |
| In-app purchases | No |

Expected result: **Everyone / PEGI 3 / USK 0** (utility, no objectionable content).

## Google Play — store-listing checklist
- **Title / short / full description / screenshots / icon:** already in `fastlane/metadata/android/en-US/`
  (title 29 chars, short-desc 77/80, full-desc 2324/4000, 3 phone screenshots, icon). Reuse verbatim.
- **App category:** Tools. **Tags:** developer tools, productivity.
- **Contact email:** `bryn@ventouxadvisoryco.com` (matches privacy policy).
- **Target audience:** 18+ / developers (not directed at children — privacy policy §"Children").
- **Ads:** declare **No ads**.
- **AAB:** `bundleFullPlaysafeRelease` (appId `com.ventouxlabs.relais`), enrol in **Play App Signing**
  (the release key is the upload key — `distribution.md`).
- **Changelog:** `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (currently `33.txt`).

---

## IzzyOnDroid — RFP (#123)

**Prerequisites (verify before filing):**
1. A **published GitHub Release** whose assets include the `fullOpen` APK
   (`com.ventouxlabs.relais.izzy`). — *not yet done.*
2. `fastlane/metadata/android/en-US/` present in the repo. — **done.**
3. Signature stable across releases (one release key, never rotated). — **done** (`distribution.md`).
4. Anti-feature disclosed: **NonFreeDep** (bundled proprietary `litertlm`/`litert` native blobs).

**File at:** https://gitlab.com/IzzyOnDroid/repo/-/issues (choose the *Request for Packaging* template).

Ready-to-paste RFP body:

```
### App name
Relais — On-Device LLM Node

### Source code
https://github.com/bearyjd/relais

### Upstream / releases (APK source Izzy should track)
https://github.com/bearyjd/relais/releases  (asset: app-full-open-release.apk)

### Application ID
com.ventouxlabs.relais.izzy   (IzzyOnDroid `fullOpen` flavor)

### License
AGPL-3.0-or-later (forked from google-ai-edge/gallery, Apache-2.0)

### Short description
Turn a spare phone into an always-on, OpenAI-compatible LLM server. No cloud.

### Anti-features
NonFreeDep — bundles Google's proprietary prebuilt LiteRT-LM / LiteRT native
libraries (the on-device inference runtime). All app code is AGPL-3.0; only these
vendored native blobs are non-free, which is why this targets IzzyOnDroid rather
than the F-Droid main repo.

### Metadata
Fastlane metadata is in-repo at fastlane/metadata/android/en-US/ (title, short/full
description, 3 screenshots, icon, changelogs keyed by versionCode).

### Signing
Single release key, never rotated (in-place updates for sideload users). APKs are
apksigner-verified in CI on every tagged release.

### Notes
Headless app: no launcher chat UI to babysit — it runs an OpenAI-compatible API on
the LAN. Builds are reproducible from the tagged source via the standard Gradle
task (assembleFullOpenRelease); the release workflow attaches the signed APK to the
GitHub Release Izzy tracks.
```

---

## Suggested order for the operator
1. Cut + **publish** a GitHub Release (unblocks Izzy's prerequisite #1 and gives Play a versioned build).
2. **Izzy (#123):** paste the RFP body above into a new GitLab RFP issue.
3. **Play (#122):** upload the AAB, transcribe the Data Safety + content-rating answers, set the
   privacy-policy URL, submit for review.
