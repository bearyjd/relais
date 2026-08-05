<!--
  Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
  Store-submission paperwork for Google Play (#122). This is the answer sheet an operator
  transcribes into the Play Console forms; it does not itself submit anything (Play Console access
  is account-gated — see "Blocked on the operator" below).

  Code-level justification for the Data Safety answers lives in distribution.md §"Play Data Safety
  form" — this file is the transcription sheet, not the derivation. Keep them consistent.
-->

# Relais Play-submission runbook (#122)

Companion to [`distribution.md`](distribution.md) (signing + release pipeline + the code-level
derivation of every Data Safety answer). That doc builds and signs the artifacts; this one is the
**listing + policy paperwork** to get them accepted.

**Facts below re-verified against `main` and the published release on 2026-08-05.** Anything marked
⚠ is a gate that must clear *before* an upload is worth making.

## Blocked on the operator (account-gated — cannot be automated)

Uploading the AAB and filling the console forms needs the **Play Console** account (`jd@`/VentouxLabs).
Everything transcribable is pre-filled below.

IzzyOnDroid (#123) was **closed as not planned** on 2026-08-05 — reasoning in
[`distribution.md`](distribution.md) §"IzzyOnDroid — NOT PURSUED". Do not re-derive it here.

---

## ⚠ Gate 1 — GenAI in-app content reporting (BLOCKER, code not paperwork)

Play's [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
requires that apps generating content with AI **"contain in-app user reporting or flagging features
that allow users to report or flag offensive content to developers without needing to exit the
app,"** and that developers use those reports to inform filtering. The policy text states no
on-device carve-out.

Relais generates AI content on three surfaces that ship in `fullPlaysafe`: the in-app chat UI, the
LAN chat/completions API, and image generation (`:imagegen` — see Gate 3, it *is* in this variant).

**As of `main` there is no report/flag affordance anywhere in the app** (verified: no such string,
no such handler). This is the one item on this page that cannot be transcribed — it has to be built.
Tracked as **#258**; do not submit before it lands. The design question it opens: Relais has no
developer server, so "report to developers" has to resolve to something local (an in-app dialog that
records the report on-device, optionally offering an export) rather than a `mailto:` — a mail handoff
arguably *is* "exiting the app."

## ⚠ Gate 2 — target API level deadline

`build.gradle.kts` is at **`targetSdk = 35`** (`compileSdk = 35`, `minSdk = 31`, versionCode 35 /
versionName 1.0.17).

Per Google's [target API level requirements](https://developer.android.com/google/play/requirements/target-sdk),
**new apps submitted from 2026-08-31 must target API 36.** targetSdk 35 qualifies for a new
submission **only until 2026-08-30**. An extension to 2026-11-01 can be requested from Play Console.

So there are two viable paths, and the choice is a sequencing decision, not a technical one:

| Path | What it means |
|---|---|
| **Submit before 2026-08-31** | The current v1.0.17 AAB is eligible as-is. Requires Gate 1 to land first. |
| **Bump to targetSdk 36 first** | Removes the deadline pressure entirely. Tracked as a deferred sub-project in `.claude/PRPs/plans/relais-release-pipeline.plan.md` — no open issue yet. Note `build.gradle.kts:284` already flags a dependency whose 14.x fix wants `compileSdk 36`. |

*Not verified here:* whether an app already **in review** on 2026-08-31 is judged against the old
level, and what the first post-deadline *update* must target. Confirm in Console before relying on
either.

## ⚠ Gate 3 — foreground-service declaration

Play requires an FGS declaration in **App content → Foreground service permissions** for apps
targeting Android 14+: per declared type, a functionality description, the user impact if the work
is deferred, and **a link to a video demonstrating the feature** (screen recording of the operator
starting the node is sufficient — this is a deliverable, not a form field).

`fullPlaysafe` merges **four** `dataSync` services, not one — `distribution.md:244` names only the
first:

| Service | Purpose to declare |
|---|---|
| `RelaisNodeService` | Keeps the local inference server resident while the operator's LAN devices use it. Started only by explicit operator action (START / opt-in boot-start). |
| `RelaisShareService` | Runs a single share-sheet inference off the UI lifecycle so a 30–120 s decode survives the trampoline finishing. |
| `RelaisAutomationService` | Runs a Tasker/intent-triggered request, same lifecycle reason. |
| `SystemForegroundService` (WorkManager) | Multi-GB model downloads. |

Google's guidance prefers a **user-initiated data transfer job** over `dataSync` for *network
transfers* specifically, which describes the WorkManager download case. It is guidance, not a
prohibition, and `dataSync` remains correct for the other three (local processing on explicit user
request). Expect a reviewer question on the download path; no code change is required to answer it.

---

## Google Play — Data Safety form

Derivation and per-data-type reviewer notes: [`distribution.md`](distribution.md) §"Play Data Safety
form". Transcribe:

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Data collected (sent off-device to the developer) | **None** |
| Data shared (with third parties, by the developer) | **None** |
| Is all data encrypted in transit? | **Yes** |
| Way to request data deletion? | **Data not collected** (n/a) — all data is on-device; in-app *Clear data* / uninstall removes it, and no server-side data exists |

> Reviewer-note nuance to keep on file (not entered in the form): the app *does* transmit data the
> **user directs** — a typed model-search query and optional HF token to `huggingface.co`, and
> webhook payloads to a user-chosen URL. Play scopes "collection" to the **developer**; user-directed
> third-party traffic with no developer intermediary is disclosed in the privacy policy
> (§"When the app talks to the internet") rather than declared as developer collection.

### Permissions in the merged `fullPlaysafe` manifest → what each one answers for

The release workflow's permission gate (`release.yaml:76-95`) enforces that `READ_CALENDAR`,
`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are **absent**,
along with the notification-listener component. What remains, and the reviewer answer for each:

| Permission | Why it ships | Data Safety consequence |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Model downloads; LAN serving | None — no developer endpoint |
| `FOREGROUND_SERVICE`, `..._DATA_SYNC` | The four services in Gate 3 | None |
| `RECORD_AUDIO` | On-device transcription (`/v1/audio/transcriptions`) | **Not collected** — audio never leaves the device. TTS is *output* and needs no permission |
| `CAMERA` | On-device vision / OCR capture | **Not collected** — no upload path exists |
| `POST_NOTIFICATIONS` | Node status + result notifications | None |
| `WAKE_LOCK` | Keeps inference alive across screen-off | None |
| `NFC` | Tap-to-trigger workflows | None |
| `RECEIVE_BOOT_COMPLETED` | Opt-in boot-start of the node | None |
| `com.google.android.apps.aicore.service.BIND_SERVICE` | AICore/Gemini Nano, `full` dist only | None — on-device |

### Audio-output modality (#212)

`POST /v1/audio/speech` (on-device Piper/sherpa-onnx TTS) was reviewed against the form and **the
answers above are unchanged — still "collects nothing."** Synthesis runs entirely on-device; the
WAV/PCM returns over the LAN socket. It added one network host (the voice bundle downloads from
`github.com/k2-fsa/sherpa-onnx`, SHA-256-pinned), now disclosed in the privacy policy
§"When the app talks to the internet" item 1. "Audio files → Voice or sound recordings" does **not**
apply: that type covers recordings collected from the user and sent to the developer.

**Privacy-policy URL for the console:** **https://bearyjd.github.io/relais/privacy-policy.html**
— ✅ verified live 2026-08-05 (HTTP 200, byte-identical to `docs/privacy-policy.html`, effective
date 2026-07-26).

## Google Play — Content rating (IARC questionnaire)

Category: **Utility / Productivity / Tools**. Full mapping in `distribution.md` §"Play content
rating". Violence, sexual content, profanity, controlled substances, gambling, user-to-user
communication, shared UGC, in-app purchases: **No** to all.

**Declare AI-generated content: Yes** — on-device LLM chat **and** image generation. Image gen is
live in this variant: `ImageGenRegistration` is split on the `dist` dimension, so `fullPlaysafe`
registers the real sd.cpp generator and ships the `:imagegen` service; the endpoint's 501 is a
*runtime* gate on whether a model bundle is provisioned, not a build-time removal. Mitigations to
declare: fully local processing, operator-only access behind a device-generated bearer key, Gemma
models under Google's Gemma Terms + Prohibited Use Policy (linked in-app at `ai.google.dev/gemma`).

Expected result: Everyone / PEGI 3 tier, though the generative-AI question set may return higher in
some locales.

## Google Play — store-listing checklist

- **Title / short / full description / screenshots / icon:** `fastlane/metadata/android/en-US/`
  — title 29 chars, short-desc 77/80, full-desc 2286/4000, 3 phone screenshots, icon. Reuse verbatim.
- **App category:** Tools. **Tags:** developer tools, productivity.
- **Contact email:** `bryn@ventouxadvisoryco.com` (matches the privacy policy).
- **Target audience:** 18+ / developers — not directed at children (privacy policy §"Children").
- **Ads:** declare **No ads**.
- **AAB:** `app-full-playsafe-release.aab`, **77,969,674 bytes**, attached to the published
  [v1.0.17 release](https://github.com/bearyjd/relais/releases/tag/v1.0.17) (appId
  `com.ventouxlabs.relais`). Enrol in **Play App Signing** on first upload — the release key is the
  *upload* key; keep `distribution.md`'s warning about the sideload key's immutable-signature story
  intact.
- **Changelog:** `fastlane/metadata/android/en-US/changelogs/35.txt` (versionCode 35 = v1.0.17).

---

## Order for the operator

1. **Land Gate 1** (in-app content reporting). Nothing below is worth doing first — a GenAI app
   without it is rejectable on a policy Google enforces at review.
2. **Decide Gate 2**: submit before 2026-08-31 at targetSdk 35, or bump to 36 first.
3. Record the **Gate 3** FGS video and write the four declarations.
4. Create the app in Play Console, enrol in Play App Signing, upload the AAB.
5. Transcribe Data Safety → content rating → listing → privacy-policy URL.
6. Submit for review, then append what was actually done to `distribution.md` so the *next* release
   needs no rediscovery (that append is #122's acceptance criterion).
