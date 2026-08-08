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
Tracked as **#258**; do not submit before it lands.

### The requirement has two halves, and only one is easy

The sentence quoted above imposes **both** "without needing to exit the app" **and** "to
**developers**". An in-app dialog that records the report on-device satisfies the first and, on its
own, **fails the second** — VentouxLabs never learns anything, and since the operator running Relais
is usually also the user, a purely local record is self-reporting. *(Raised by `/codex review` on
#259 after an earlier draft of this section presented local-only storage as sufficient. It was not.)*

**Decision: a local record plus an opt-in send, defaulting to off.**

| Half of the requirement | How it is met |
|---|---|
| "without needing to exit the app" | The report is captured by an in-app dialog. No `mailto:`, no browser — a mail hand-off arguably *is* exiting the app. |
| "to developers" | Each report offers an explicit, per-report **send to the maintainer**, chosen by the operator. |
| "use reports to inform moderation" | Reports are reviewable in-app (`CONFIGURE › REPORTED OUTPUT`), so the operator can act on them whether or not one is sent. |

### ⚠ The send path FLIPS the Data Safety answer to "Yes". Default-off does not preserve it.

An earlier draft of this section said keeping the send **default-off, per-report and user-initiated**
lets the baseline stay "collects nothing". **That is wrong, and it would have produced a false
declaration.** Corrected by `/codex review`, which is worth quoting because the distinction is easy
to get backwards:

> Sending a report to VentouxLabs transmits it off-device to the **first-party developer**, which
> Google defines as data collection; default-off only makes that collection *optional*. The
> user-initiated exception is for **sharing to a third party**, not first-party collection.

That is the trap: Play's user-initiated carve-out is about *sharing*, and everywhere else in this
runbook Relais relies on it correctly (HF search queries, webhooks — all user-directed traffic to
**third parties**, with no developer intermediary). A report sent to VentouxLabs is the one case
where **we are the recipient**, so the carve-out does not apply and optional collection is still
collection.

**Transcribe this once the send path ships — not before, since nothing transmits today:**

**Derive the declaration from what the Worker actually persists, not from "the report".** Three
review rounds each found this table under-declaring, because it was written from the *idea* of a
report rather than from the KV write. The record is `{...report, receivedAt}` —
`report-worker/src/index.ts` — which is **seven fields**, plus a second key the rate limiter writes:

| Field actually stored | Play data type to declare |
|---|---|
| `excerpt` — the flagged model output | **Messages → Other in-app messages** |
| `note` — operator's free text | **Messages → Other in-app messages** |
| `reasonId` — which category the operator chose | **App activity → App interactions** |
| `surface` — which in-app surface it came from (`chat` / `gallery_chat`) | **App activity → App interactions** |
| `modelId`, `backend` — what produced the output | Intended as app configuration, so **not** a Play *user* data type — but note the Worker does not enforce that. `reasonId` and `surface` are allowlisted against `REASONS`/`SURFACES`; these two are only length-bounded (`isBoundedOrNull(…, MAX_IDENT)`), so any caller can persist arbitrary text in them. The declaration holds for what *the app* sends; disclose both in the privacy policy, and treat the "configuration" label as an intent, not a validated guarantee |
| `receivedAt` — server timestamp | Part of the record; no separate type |
| the report **key** itself, `report:<receivedAt>:<uuid>` | Not a separate type — the timestamp is already declared above and the UUID is `crypto.randomUUID()`, unlinked to any caller. Listed so the inventory matches the `put()` call rather than only its value |
| `rl:<salted-hash>` — a **second KV key**, written by the rate limiter, not part of the report record. Both halves are data: the **key** is the salted caller identifier, and the **value** is `String(current + 1)` — a count of that caller's requests that got *past the limiter*, which is not the same as accepted reports: it increments before parsing, so malformed and oversized bodies count too, and it is only written when `cf-connecting-ip` is non-empty | **Device or other IDs** (see below) |

| Console question | Answer once delivery ships |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Data types | **Messages → Other in-app messages**, **App activity → App interactions**, **Device or other IDs** — all three, per the table above |
| Required or optional? | **Optional** for every one — default off, chosen per report |
| Purpose | Report contents and interactions: **App functionality** (content moderation), per the AI-Generated Content policy's "use reports to inform moderation". The identifier: **fraud prevention, security and compliance** |
| Is it shared with third parties? | **No** — it reaches the developer's own endpoint and goes no further |
| Encrypted in transit? | **Yes** — HTTPS to the Worker |
| Can users request deletion? | **Yes** — reports expire 180 days after receipt; the identifier expires one hour after that caller's **last** request, **not** one hour after their first (see below). Ad-hoc deletion by request to the contact email |

**Scope the Yes precisely — it is narrower than the app, and wider than it first looks.**

An earlier draft of this section said "chat content stays not-collected", which was **wrong in the
same paragraph that declares the report contents**: the flagged excerpt *is* chat content — model
output the operator was reading — so a report transmits it to the developer. Caught by
`/codex review`; a guard written against over-declaring produced an under-declaration on the one type
the payload actually carries.

| | |
|---|---|
| **Collected** (optional) | The report payload — flagged excerpt and operator note, both **Messages → Other in-app messages** — plus the model id / backend, **plus the rate-limit identifier below** |
| **Still not collected** | Chat content the operator never reports · prompts · audio in or out · photos · the HF token (user-directed to `huggingface.co`, never to us) |

**The rate-limit identifier counts too, and it is not part of the report.** The Worker derives a
salted SHA-256 of `cf-connecting-ip` and retains it in KV for an hour to link a caller's requests
(`report-worker/src/index.ts`, `overRateLimit`). **Not storing the raw IP does not make this
uncollected** — Play treats a stable identifier retained off-device as collection regardless of
reversibility. *(Caught by `/codex review`, which read the Worker source rather than trusting the
doc. I had designed the hash specifically to avoid retaining an IP and then over-claimed what that
bought: the privacy engineering was right, the declaration conclusion drawn from it was not.)*

Declare it as **Device or other IDs → optional**, purpose **fraud prevention, security and
compliance** (it exists solely to rate-limit an unauthenticated endpoint).

**Do not describe the one hour as a retention cap — it is a sliding window, and this is the third
time this section has stated a local fact as a stronger guarantee than the code provides.**
`overRateLimit` calls `put()` with `expirationTtl: RATE_WINDOW_SECONDS` on *every* request it does
not reject, so each new request **resets** the hour. A caller submitting under the limit once an hour
keeps the same identifier alive **indefinitely**; it expires an hour after their last request, not an
hour after their first. Answer any Play retention question in those terms. *(Caught by
`/codex review`, reading `overRateLimit` rather than the sentence describing it — the same check that
caught the two rounds before.)*

**If you would rather not declare it:** delete `overRateLimit` and rely on the Cloudflare edge Rate
Limiting rule instead — platform infrastructure rather than app collection. That is a real code
change to a reviewed Worker and leaves the edge rule as the only defense, which is why the current
decision is to declare rather than remove.

The distinction is **reported vs. unreported**, not chat vs. non-chat. Unreported conversations never
leave the device, which is why the type is declared as *optional* rather than required — but the type
itself must be declared, because a sent report contains it.

**Land these together, in the same PR as the client send path** — the declaration becoming false is
the single most expensive way to get this wrong:

- **THIS file's own "Google Play — Data Safety form" table below** — it still reads `No` / `None` and
  is the table an operator actually transcribes. Listing every *other* document and forgetting the
  primary one in the same runbook is how the stale answer reaches the console. *(Missed in the first
  draft of this list; caught by `/codex review`.)*
- `docs/privacy-policy.md` **and** its `.html` twin (bump the effective date) — must cover the
  rate-limit identifier as well as the report contents
- `docs/distribution.md` — **five** rows, not one, across its two tables. Each carries a marker
  pointing here; **re-grep before trusting the line numbers**, which have already gone stale once:

  | Row | Where | What it says today |
  |---|---|---|
  | §"Play Data Safety form" overview | `:206` | "No" |
  | Deletion request | `:208` | "Data not collected — nothing exists server-side" |
  | **Messages** per-type | `:216` | "not collected" |
  | **Device IDs** per-type | `:219` | "Not read, not transmitted", which the rate-limit hash contradicts |
  | **App activity** per-type | `:220` | "Not collected today" — `reasonId`/`surface` |

  *(This list has been wrong twice. The first draft named two rows; the second named three and
  omitted **App activity** — the row gate 1's own declaration had just created — while quoting line
  numbers that inserting that row had already invalidated. Derive the list by grepping the per-type
  table, not from memory, and re-derive the line numbers in the same pass.)*
- **The two "no developer endpoint" egress claims**, which are separate from every row above and
  were missed by the first *three* drafts of this list:
  - `docs/distribution.md` §"Egress inventory backing the 'No'" — it calls itself **"complete, from
    source sweep 2026-07-07"** and does not list a VentouxLabs endpoint, because none existed. The
    send path adds one, and a self-described complete inventory that omits it is worse than one that
    never claimed completeness.
  - **THIS file's** permission table — the `INTERNET`, `ACCESS_NETWORK_STATE` row reads
    *"None — no developer endpoint"* in its Data Safety consequence column.
- `report-worker/README.md` — same correction

**Open and blocking #258:** *where* an opt-in send delivers to. There is no VentouxLabs endpoint
today, and standing one up is a real commitment for a project whose pitch is no cloud. Resolve this
before the send path is built; the local record and review screen are already done and are a
prerequisite for any delivery design.

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
form".

> ⚠ **This table is the answer sheet only while #258's report send is UNBUILT.** The moment the
> client send path ships, **three** of the rows below become **false** — the first two *and the
> deletion row*, which is the one most easily missed because "no server-side data exists" reads like
> a property of the app rather than a claim the Worker's 180-day retention falsifies — and gate 1's
> table replaces them.
> Two tables in one runbook is a trap — the stale one looks like the answer sheet — so **whichever PR
> ships the send path must edit THIS table**, not just the ones listed in gate 1.

Transcribe (today, pre-send-path):

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** → **Yes** once the send path ships (gate 1) |
| Data collected (sent off-device to the developer) | **None** → report contents + the rate-limit identifier (gate 1) |
| Data shared (with third parties, by the developer) | **None** — unchanged; a report reaches the developer's own endpoint and goes no further |
| Is all data encrypted in transit? | **Yes** |
| Way to request data deletion? | **Data not collected** (n/a) → **Yes** once the send path ships (gate 1). Today: all data is on-device; in-app *Clear data* / uninstall removes it, and no server-side data exists. After: reports expire after 180 days and the rate-limit identifier after one hour, with ad-hoc deletion by request to the contact email |

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

`POST /v1/audio/speech` (on-device Piper/sherpa-onnx TTS) was reviewed against the form and **adds no
collected data type of its own.** (Phrased as a statement about TTS, not about the form as a whole —
the report send above does change the form's overall answer, and this section must not be read as
contradicting it.) Synthesis runs entirely on-device; the
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

1. **Land Gate 1** (in-app content reporting), **including the opt-in send** — the local record
   alone does not meet the "to developers" half. Nothing below is worth doing first: a GenAI app
   without this is rejectable on a policy Google enforces at review. Decide the delivery endpoint
   before building the send path.
2. **Decide Gate 2**: submit before 2026-08-31 at targetSdk 35, or bump to 36 first.
3. Record the **Gate 3** FGS video and write the four declarations.
4. Create the app in Play Console, enrol in Play App Signing, upload the AAB.
5. Transcribe Data Safety → content rating → listing → privacy-policy URL.
6. Submit for review, then append what was actually done to `distribution.md` so the *next* release
   needs no rediscovery (that append is #122's acceptance criterion).
