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

**Facts below re-verified against `main` and the published release on 2026-08-05; version and
gate-1 rows re-verified 2026-08-17 (post-#274, v1.0.19 prep).** Anything marked
⚠ is a gate that must clear *before* an upload is worth making.

## Blocked on the operator (account-gated — cannot be automated)

Uploading the AAB and filling the console forms needs the **Play Console** account (`jd@`/VentouxLabs).
Everything transcribable is pre-filled below.

IzzyOnDroid (#123) was **closed as not planned** on 2026-08-05 — reasoning in
[`distribution.md`](distribution.md) §"IzzyOnDroid — NOT PURSUED". Do not re-derive it here.

---

## Gate 1 — GenAI in-app content reporting (DONE, pending two dashboard steps)

Play's [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
requires that apps generating content with AI **"contain in-app user reporting or flagging features
that allow users to report or flag offensive content to developers without needing to exit the
app,"** and that developers use those reports to inform filtering. The policy text states no
on-device carve-out.

Relais generates AI content on three surfaces that ship in `fullPlaysafe`: the in-app chat UI, the
LAN chat/completions API, and image generation (`:imagegen` — see Gate 3, it *is* in this variant).

**Both halves are built.** Local capture (reason picker, on-device persistence, `CONFIGURE › REPORTED
OUTPUT` review screen) shipped in v1.0.18. The opt-in send to the maintainer (`ContentReportDelivery`
→ `report.ventouxlabs.com`, default off, decided per report) ships in this PR, alongside the Data
Safety declaration update it requires. Tracked as **#258**. Remaining before this gate is fully clear:
the two dashboard-only steps `report-worker/README.md` still lists (edge Rate Limiting rule, confirm
*Always Use HTTPS*) — neither is a code or doc change.

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
| `note` — operator's free text | **App activity → Other user-generated content** — not Messages; see Q2 below |
| `reasonId` — which category the operator chose | **App activity → App interactions** |
| `surface` — which in-app surface it came from (`chat` / `gallery_chat`) | **App activity → App interactions** |
| `excerpt`, `note` — both free text, again | Also **Personal info → optional** (declared #267, Q1 below) — free text can carry a name, email or address the user typed, or that a flagged excerpt repeats back from the model. Not a separate field; a second type on the same two rows above. |
| `modelId`, `backend` — what produced the output | Intended as app configuration, so **not** a Play *user* data type — but note the Worker does not enforce that. `reasonId` and `surface` are allowlisted against `REASONS`/`SURFACES`; these two are only length-bounded (`isBoundedOrNull(…, MAX_IDENT)`), so any caller can persist arbitrary text in them. The declaration holds for what *the app* sends; disclose both in the privacy policy, and treat the "configuration" label as an intent, not a validated guarantee |
| `receivedAt` — server timestamp | Part of the record; no separate type |
| the report **key** itself, `report:<receivedAt>:<uuid>` | Not a separate type — the timestamp is already declared above and the UUID is `crypto.randomUUID()`, unlinked to any caller. Listed so the inventory matches the `put()` call rather than only its value |
| `rl:<salted-hash>` — a **second KV key**, written by the rate limiter, not part of the report record. Both halves are data: the **key** is the salted caller identifier, and the **value** is `String(current + 1)` — a count of that caller's requests that got *past the limiter*, which is not the same as accepted reports: it increments before parsing, so malformed and oversized bodies count too, and it is only written when `cf-connecting-ip` is non-empty | **Device or other IDs** (see below) |

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Data types | **Messages → Other in-app messages** (`excerpt` only), **App activity → App interactions** (`reasonId`, `surface`) **and → Other user-generated content** (`note`), **Personal info** (optional — see below), **Device or other IDs** — per the table above |
| Required or optional? | **Optional** for every one — default off, chosen per report |
| Purpose | Report contents and interactions: **App functionality** (content moderation), per the AI-Generated Content policy's "use reports to inform moderation". The identifier: **fraud prevention, security and compliance** |
| Is it shared with third parties? | **No** — it reaches the developer's own endpoint and goes no further |
| Encrypted in transit? | **Yes** — HTTPS to the Worker. ⚠ Enforced by **Cloudflare, not by Worker code**: `index.ts` never inspects the scheme, so this answer is only true while the zone has *Always Use HTTPS* on. Confirm it at deploy time (`report-worker/README.md`) rather than inferring it from the source |
| Can users request deletion? | **Yes**, with a caveat worth getting right — see the row below |
| …what deletion actually means here | Reports expire 180 days after receipt; the identifier expires one hour after that caller's last **counted** request, not one hour after their first (see below). For **ad-hoc deletion by request to the contact email**: the record has no dedicated identity field and stores no link from a report back to its caller, so the operator cannot query "this person's reports" — honoring a request means the requester supplies enough of the report for a manual scan of the KV namespace. Do not imply a lookup capability the schema makes impossible. **And do not overstate the anonymity either:** `excerpt` and `note` are free text (`parseReport` only length-bounds them), so a report *can* contain identifying information the user typed or the model emitted — the absence of an identity **field** is not an absence of identifying **content**. The `rl:<hash>` record is separate, cannot be reached from report content at all, and only ages out on its TTL |

**Scope the Yes precisely — it is narrower than the app, and wider than it first looks.**

An earlier draft of this section said "chat content stays not-collected", which was **wrong in the
same paragraph that declares the report contents**: the flagged excerpt *is* chat content — model
output the operator was reading — so a report transmits it to the developer. Caught by
`/codex review`; a guard written against over-declaring produced an under-declaration on the one type
the payload actually carries.

| | |
|---|---|
| **Collected** (optional) | The report payload — flagged excerpt (**Messages → Other in-app messages**), operator note (**App activity → Other user-generated content**) and both free-text fields again under **Personal info** — plus the model id / backend, **plus the rate-limit identifier below** |
| **Still not collected** | Chat content the operator never reports · prompts · audio in or out · photos · the HF token (user-directed to `huggingface.co`, never to us) |

**#267 is resolved: declare `Personal info`, and `note` is typed as `App activity → Other
user-generated content`, not Messages.** `distribution.md`'s "Personal identifiers / credentials" row
(`:218`) is transcribable now. This block preserves the reasoning that settled it, since the same
research also surfaced a second, separate question the earlier drafts of this block kept running
together with the first — first as a false dichotomy, then by presenting the answer to Q2 as if it
were a third answer to Q1. They were different questions with different confidence (#267):

**Q1 — must `Personal info` be declared? Decided: yes, declare it.** No field parses a name out of a
report, and that fact invites the shortcut of leaving the type undeclared — but a name an operator
types into `note` **is** received, and Q2's type (below) explicitly does not absorb it. Declaring is
the defensible default absent a real client-side redaction guarantee, which does not exist today; a
UI warning alone does not substitute for one. This was a judgement call, made by **JD**.

**Q2 — is `note` typed correctly? Decided: no, it was Messages; it is now `Other user-generated
content`.** Play has a type defined as "user bios, notes, or **open-ended responses**" —
`App activity → Other user-generated content` — which describes a moderation note, while "message to
or from someone" does not. `App activity` was already declared here for `reasonId`/`surface`. Q2
holds independently of how Q1 was decided.

**Why Q2 does not answer Q1** — the tempting shortcut is "the note is `Other user-generated content`,
so personal details typed into it are covered." An earlier draft of this block took exactly that
shortcut. Two reasons it does not hold:

- Play's *"You do not need to declare collection or sharing unless data is **actually collected**
  and/or shared"* says **when** a declaration is owed. It is not a ruling that a name typed into a
  free-text box is uncollected. Once the report reaches us, whatever is in it is collected.
- "Other user-generated content" is defined as content *"not listed here, **or in any other
  section**."* Name, Email address and Address **are** listed elsewhere. So that type is not a
  catch-all that absorbs personal details typed into it — its own definition excludes them.

So Q1 was a judgement call and Q2 was a typing correction, and one being settled did not settle the
other — both had to be decided explicitly, which is why this block keeps them visibly separate rather
than folding Q2's answer into Q1's.

While checking Q2, the exact **Messages** sub-type label in the Console is also worth confirming for
`excerpt`: the Android developer taxonomy says **"Other messages"** where this runbook has been saying
**"Other in-app messages"** — verify against the current Console copy when transcribing, since that is
a label check this doc cannot make authoritatively.

Both land in this PR, alongside the client send path. Reasoning and sources: **#267**.

**The rate-limit identifier counts too, and it is not part of the report.** The Worker derives a
salted SHA-256 of `cf-connecting-ip` and retains it in KV to link a caller's requests, on a one-hour
TTL that renews on each counted request — so it lives until an hour after that caller's last counted
request, not an hour flat (`report-worker/src/index.ts`, `overRateLimit`; the mechanism is spelled
out below). **Not storing the raw IP does not make this
uncollected** — Play treats a stable identifier retained off-device as collection regardless of
reversibility. *(Caught by `/codex review`, which read the Worker source rather than trusting the
doc. I had designed the hash specifically to avoid retaining an IP and then over-claimed what that
bought: the privacy engineering was right, the declaration conclusion drawn from it was not.)*

Declare it as **Device or other IDs → optional**, purpose **fraud prevention, security and
compliance** (it exists solely to rate-limit an unauthenticated endpoint).

**Do not describe the one hour as a retention cap — it is a renewing TTL, and this section has now
stated a local fact as a stronger guarantee than the code provides three rounds running.**
`overRateLimit` calls `put()` with `expirationTtl: RATE_WINDOW_SECONDS` on every request it
**counts**, so each counted request **resets** the hour. A caller submitting under the limit once an
hour keeps the same identifier alive **indefinitely**.

Be exact about which requests renew it: the function returns at `current >= RATE_LIMIT` *before*
`put()`, so a request that is already over the limit does **not** refresh the TTL. The identifier
expires one hour after that caller's last **counted** request — not their last request, and not an
hour after their first. Answer any Play retention question in those terms.

*(Both the original error and the over-correction were caught by `/codex review` reading
`overRateLimit` rather than the sentence describing it. The first draft said "retained one hour"; the
fix for it said "one hour after their last request", which is wrong in the other direction. A claim
about when data expires has to be read off the branch that writes the TTL, not off the constant.)*

**If you would rather not declare it:** delete `overRateLimit` and rely on the Cloudflare edge Rate
Limiting rule instead — platform infrastructure rather than app collection. That is a real code
change to a reviewed Worker and leaves the edge rule as the only defense, which is why the current
decision is to declare rather than remove.

The distinction is **reported vs. unreported**, not chat vs. non-chat. Unreported conversations never
leave the device, which is why the type is declared as *optional* rather than required — but the type
itself must be declared, because a sent report contains it.

**Landed together, in the PR that ships the client send path** — the declaration becoming false is
the single most expensive way to get this wrong, which is why this checklist stayed in the doc rather
than being deleted once satisfied (it had already been incomplete four times before this pass):

- [x] **THIS file's own "Google Play — Data Safety form" table below** — no longer reads `No` / `None`.
- [x] `docs/privacy-policy.md` **and** its `.html` twin (effective date bumped) — cover the rate-limit
      identifier as well as the report contents.
- [x] `docs/distribution.md` — all seven rows: the three overview-table rows (`:206` collect?, `:207`
      encrypted-in-transit justification, `:208` deletion), and the four per-type rows (Messages,
      Personal info, Device IDs, App activity).
- [x] `note` re-typed as **App activity → Other user-generated content** (not Messages) everywhere it
      appears: this file's persistence table, Console answer table, and scope table; `distribution.md`'s
      App activity per-type row.
- [x] The two "no developer endpoint" egress claims corrected: `distribution.md`'s egress inventory now
      lists `report.ventouxlabs.com`, and this file's permission table (`INTERNET`,
      `ACCESS_NETWORK_STATE` row) no longer says "no developer endpoint".
- [x] `report-worker/README.md` checked — it never used Play's type vocabulary (`note` is described
      generically, "the operator's optional note," not as `Messages` or any other declared type), so
      there was nothing to re-type there. Verified by reading the file, not assumed.

The endpoint itself is deployed: `report.ventouxlabs.com` (Cloudflare Worker, custom domain,
`report-worker/README.md`). Gate 1 is done pending the two dashboard-only steps that runbook still
lists (edge Rate Limiting rule, confirming *Always Use HTTPS*) — neither blocks this declaration, both
are already assumed true by the "encrypted in transit: Yes" answer above and should be confirmed before
relying on it in review.

## ⚠ Gate 2 — target API level deadline

`build.gradle.kts` is at **`targetSdk = 35`** (`compileSdk = 35`, `minSdk = 31`, versionCode 37 /
versionName 1.0.19).

Per Google's [target API level requirements](https://developer.android.com/google/play/requirements/target-sdk),
**new apps submitted from 2026-08-31 must target API 36.** targetSdk 35 qualifies for a new
submission **only until 2026-08-30**. An extension to 2026-11-01 can be requested from Play Console.

So there are two viable paths, and the choice is a sequencing decision, not a technical one:

| Path | What it means |
|---|---|
| **Submit before 2026-08-31** | The v1.0.19 AAB is eligible as-is. Gate 1 has landed — **submit v1.0.19 or later, not an earlier AAB**: v1.0.18 lacks the send path and v1.0.17 lacks reporting entirely, so an earlier binary contradicts both the GenAI policy answer and the Data Safety declaration. |
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

> The client send path has shipped (this PR). Every row below is the post-send-path answer — there is
> no longer a separate "today, pre-send-path" table in this runbook.

Transcribe:

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Data collected (sent off-device to the developer) | Report contents (flagged excerpt, operator note, reason, surface — optionally including personal info a user typed) + the rate-limit identifier. See gate 1's persistence table above for the full field-by-field breakdown |
| Data shared (with third parties, by the developer) | **None** — a report reaches the developer's own endpoint and goes no further |
| Is all data encrypted in transit? | **Yes** |
| Way to request data deletion? | **Yes.** On-device data: in-app *Clear data* / uninstall. Sent reports: expire 180 days after receipt; the rate-limit identifier expires one hour after that caller's last **counted** request (a renewing TTL, not a one-hour cap — see gate 1), with ad-hoc deletion by request to the contact email |

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
| `INTERNET`, `ACCESS_NETWORK_STATE` | Model downloads; LAN serving; the opt-in report send (`ContentReportDelivery` → `report.ventouxlabs.com`) | See gate 1 above — the report send is the one developer-bound leg these permissions carry; everything else here remains no developer endpoint |
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
- **AAB:** `app-full-playsafe-release.aab` attached to the **v1.0.19** release once its tag is
  pushed (release.yaml publishes it; appId `com.ventouxlabs.relais`). **Do not upload an earlier
  AAB** — see Gate 2's row above. Enrol in **Play App Signing** on first upload — the release key
  is the *upload* key; keep `distribution.md`'s warning about the sideload key's
  immutable-signature story intact.
- **Changelog:** `fastlane/metadata/android/en-US/changelogs/37.txt` (versionCode 37 = v1.0.19).

---

## Order for the operator

1. **Land Gate 1** (in-app content reporting), **including the opt-in send** — **DONE as of
   v1.0.19**: capture shipped in v1.0.18, the opt-in send merged in #274, and the endpoint is
   deployed at `report.ventouxlabs.com`. The local record alone would not have met the "to
   developers" half. What remains is the dashboard-only work gate 1 lists above.
2. **Decide Gate 2**: submit before 2026-08-31 at targetSdk 35, or bump to 36 first.
3. Record the **Gate 3** FGS video and write the four declarations.
4. Create the app in Play Console, enrol in Play App Signing, upload the AAB.
5. Transcribe Data Safety → content rating → listing → privacy-policy URL.
6. Submit for review, then append what was actually done to `distribution.md` so the *next* release
   needs no rediscovery (that append is #122's acceptance criterion).
