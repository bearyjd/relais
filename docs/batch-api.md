# Async batch + signed webhooks (`/v1/batch`)

Queue a chat completion to run **off the request path**, poll for the result, and optionally have the
node **POST the signed result to a webhook**. Useful for long generations a client doesn't want to hold
a connection open for.

## Submit — `POST /v1/batch`

Body is a chat-completions request (v1 supports a **text** `messages` body — a `user` turn + optional
`system`; multimodal/tools batch is a follow-up), with an optional `webhook`:

```json
{ "messages": [{"role":"user","content":"Summarize War and Peace."}], "webhook": "https://you.example.com/hook" }
```
→ `202 { "object": "batch.job", "job_id": "…", "status": "queued" }`. A drain worker picks it up.

`400` if `messages` is missing / the body exceeds 256 KB / the webhook is rejected by the SSRF guard;
`429` if more than 256 jobs are already queued.

## Poll — `GET /v1/batch/{job_id}`

→ `200 { "object":"batch.job", "job_id", "status", "created", "result"? }`. `status` is
`queued | running | completed | failed`; `result` (the OpenAI completion envelope, or `{error}`)
appears once the job finishes. Results are kept ~7 days, then pruned.

A drain worker claims jobs one at a time (an atomic queued→running flip, so two drains never double-run a
job) and is bounded per run to stay under the platform's background-execution limit; leftover jobs are
picked up by the next run. A job left `running` by a worker that was killed mid-flight (process death,
reboot) is reaped to `failed` on the next drain — a client never polls `running` forever.

## Webhooks — SSRF guard + HMAC signature

If `webhook` is set, the node POSTs the result there when the job finishes:

```json
{ "job_id": "…", "status": "completed", "result": { /* chat.completion */ } }
```

- **SSRF guard:** the URL must be **https**, and its host must NOT resolve to a loopback / private /
  link-local / unique-local / multicast address (checked for *every* resolved address). The guard
  resolves the host once and the delivery layer **connects to that exact pinned IP** — it does not let
  the socket re-resolve the name, which is what actually closes the DNS-rebinding TOCTOU (a name that
  flips to a private IP between resolve and connect never gets a second lookup). For https the original
  hostname is still used for TLS SNI + certificate verification, so SNI vhosts work and cert checks stay
  honest. Redirects are **not** followed (a 3xx could point at a private IP). An operator can allowlist a
  trusted internal host (`RelaisConfig.setWebhookAllowlist`) to permit a **private-IP** endpoint; the
  allowlist bypasses the scheme + private-IP checks but the address is still pinned. An allowlisted host
  may be plain `http` (the pinned delivery uses a raw socket, so cleartext to an operator-trusted
  internal endpoint is permitted) — non-allowlisted webhooks are always https.
- **Signature:** the request carries `X-Relais-Signature: sha256=<hex>` = `HMAC-SHA256(secret, body)`
  over the exact raw body. The secret is `RelaisConfig.webhookHmacSecret` (generated once, stored
  encrypted). Verify it on your receiver to confirm the delivery came from this node.

Delivery is best-effort (no retries in v1) — a job is still marked `completed` even if its webhook fails.
Delivery outcomes are counted in metrics (`relais_webhook_delivered_total` / `relais_webhook_failed_total`)
so a silently-failing receiver is visible to operators. Bearer-authed like every endpoint.
