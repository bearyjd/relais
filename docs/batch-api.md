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

## Webhooks — SSRF guard + HMAC signature

If `webhook` is set, the node POSTs the result there when the job finishes:

```json
{ "job_id": "…", "status": "completed", "result": { /* chat.completion */ } }
```

- **SSRF guard** (checked at submit AND delivery — TOCTOU): the URL must be **https**, and its host
  must NOT resolve to a loopback / private / link-local / unique-local / multicast address (checked for
  *every* resolved address — DNS-rebinding defence). Redirects are **not** followed. An operator can
  allowlist a trusted internal host (`RelaisConfig.setWebhookAllowlist`) to permit a **private-IP https**
  endpoint. (Note: plain `http` is additionally blocked by Android's cleartext-traffic policy, so in
  practice webhooks must be https even when allowlisted.)
- **Signature:** the request carries `X-Relais-Signature: sha256=<hex>` = `HMAC-SHA256(secret, body)`
  over the exact raw body. The secret is `RelaisConfig.webhookHmacSecret` (generated once, stored
  encrypted). Verify it on your receiver to confirm the delivery came from this node.

Delivery is best-effort (no retries in v1). Bearer-authed like every endpoint.
