<!--
  Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
  Deploy runbook for the Relais AI-content report receiver (#258).
-->

# Relais report receiver

Receives **opt-in** AI-content reports from Relais and stores them for the maintainer to review.
This exists to satisfy the *"to developers"* half of Play's
[AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
— see [`docs/store-submission.md`](../docs/store-submission.md) gate 1 for why a local-only record
does not.

**Nothing sends here by default.** The app records reports on-device; transmission is a per-report
action the operator chooses. That is what keeps the baseline Data Safety answer ("collects nothing")
true, and it is why the default must stay off.

## What it stores, and what it does not

| | |
|---|---|
| **Stored** | reason id, the reported excerpt, the operator's optional note, the model id and backend that produced it, which surface it came from, and a server timestamp |
| **Not stored** | **the caller's IP address.** Rate limiting keys on a salted SHA-256 of the IP, which is not reversible and expires with the window |
| **Retention** | 180 days, then dropped automatically by KV TTL |

Both facts above are load-bearing for the privacy policy. If you change what is stored, update
`docs/privacy-policy.md`, its `.html` twin, and `docs/distribution.md` §"Play Data Safety form" in
the same change.

## Threat model

Relais is AGPL and its source is public, so **any credential shipped in the APK is public too**.
There is no useful client authentication: this endpoint is effectively open. The defenses are
therefore structural, not secret-based — a hard body cap enforced before parsing, an allowlist schema
that drops unknown fields, per-IP rate limiting, and fixed response strings that never echo input.

Read the header comment in `src/index.ts` before changing any of that.

## Deploy

Requires a Cloudflare account with Workers and KV. Run from this directory.

```bash
npm install
npm run typecheck && npm test        # both must pass before deploying

npx wrangler login

# 1. Create the KV namespace.
#    The `[[kv_namespaces]]` block in wrangler.toml is COMMENTED OUT so this command can run —
#    wrangler rejects an empty binding id while parsing the config, before running anything, which
#    would otherwise make this step fail on the config it is meant to fill in.
npx wrangler kv namespace create REPORTS

# 1b. Uncomment the `[[kv_namespaces]]` block in wrangler.toml and paste the printed id.
#     Do NOT commit the id — it would point every fork's deploy at your storage.

# 2. Set the rate-limit salt. Any long random string; rotating it only resets in-flight windows.
openssl rand -hex 32 | npx wrangler secret put RATE_LIMIT_SALT

# 3. Deploy.
npm run deploy
```

Then map a route (`report.ventouxlabs.com/report`, or a path on an existing zone) in the Cloudflare
dashboard, and **add a Rate Limiting rule at the edge as well** — the in-Worker limiter is a fixed
window and is deliberately not exact under concurrency; the edge rule is what absorbs a real flood
before it reaches Worker invocations.

## Verify a deploy

```bash
# Expect 202
curl -si https://<your-route>/report -H 'content-type: application/json' \
  -d '{"reasonId":"other","surface":"chat","excerpt":"test","note":null,"modelId":null,"backend":null}' | head -1

# Expect 400 — reason id outside the allowlist
curl -si https://<your-route>/report -H 'content-type: application/json' \
  -d '{"reasonId":"nope","surface":"chat","excerpt":"test"}' | head -1

# Expect 405
curl -si https://<your-route>/report | head -1
```

## Read the reports

```bash
npx wrangler kv key list --binding REPORTS --prefix 'report:'
npx wrangler kv key get --binding REPORTS '<key>'
```

## After it is live

The client send path (#258 step 2) needs the deployed URL. It is **not** built yet — do not point
the app at an endpoint that has not been verified with the curl checks above, and land the privacy
policy and Data Safety updates in the same PR as the client path, not after it.
