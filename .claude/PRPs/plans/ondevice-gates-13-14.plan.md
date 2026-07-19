# Plan: Get through the owed on-device gates #13 (share→OCR→inference→result) and #14 (batch→worker-through-LLM→signed HTTPS webhook)

## Summary
Both features (#13 image-share OCR, #14 async batch + signed/SSRF-guarded webhooks) are **already merged**; what's owed is the **end-to-end on-device validation** that was deferred because the harnesses are fiddly (Android-16 content-URI for #13; a reachable, guard-passing webhook receiver for #14). This plan delivers two instrumented probes + an operator runbook that close both gates on `rango` (Pixel 10 / G5).

## User Story
As a **Relais maintainer**, I want **the #13 and #14 end-to-end paths exercised on real hardware**, so that **I can stop carrying them as "deferred on-device gates" and trust the share-OCR and batch-webhook flows in production.**

## Problem → Solution
- #13: OCR engine PROVEN (`ImageOcrProbe`), share text-plumbing proven, but the **image content-URI → trampoline → service → OCR → inference → result** chain was never run on-device → **a `ShareImageProbe` that creates a MediaStore image with known text, fires the real SEND intent, and asserts the inference result reaches the clipboard** + an operator logcat/notification check.
- #14: `BatchProbe` proved SSRF→400 / submit→202 / status→200, but **the worker running a job through the LLM** and **a real over-the-wire signed HTTPS webhook delivery** were never run → **a `BatchE2eProbe` that submits a job, polls until `completed` with a real LLM answer**, plus a **public-HTTPS-receiver runbook** (webhook.site) that captures the signed POST and verifies the HMAC.

## Metadata
- **Complexity**: Medium (test-only; ~2 new probe files + a host receiver script + a runbook; no production code changes expected)
- **Source PRD**: N/A (free-form via `/prp-plan`)
- **PRD Phase**: N/A — closes deferred gates from PRs #54 (#14) and #55 (#13)
- **Estimated Files**: 2 new androidTest probes (+ 1 optional host script), 0 production files
- **Branch dependency**: must be implemented on a branch that already has the **flavor split (#56)** merged — the #13 probe lives in `src/androidTestFull/` (full-flavor only, imports ML Kit), which only exists once #56 lands. Sequence: merge #56 → #58 → #57, then branch `feat/ondevice-gates-13-14` off `main`.

---

## UX Design
Internal/validation — no user-facing UX change. (The #13 *feature* UX is share-an-image → notification; this plan only verifies it.)

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| #13 gate | "deferred — correct-by-design" | exercised e2e on rango | ShareImageProbe + logcat/notification |
| #14 gate | "deferred — worker-LLM + real delivery" | exercised e2e on rango | BatchE2eProbe + webhook.site receiver |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/share/RelaisShareActivity.kt` | 45–141 | Trampoline: `readImageUris()` filters `scheme=="content"` (l.117), `MAX_SHARE_IMAGES=8`, cold-start guard `shouldRunShare`/`shouldStartImageShare` on `RelaisInference.isReady()` + `RelaisConfig.shareEnabled()`, hand-off via `RelaisShareService.imageIntent(...)` with `FLAG_GRANT_READ_URI_PERMISSION` |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/share/RelaisShareService.kt` | 90–254 | OCR call (l.142 `ImageTextRecognizer.recognize`), caption-prefix (l.143–146), inference (l.158 `RelaisInference.completeText`), **result delivery: clipboard full (l.170, 212–216) + notification capped (l.171, 218–240)**; `TAG="RelaisShareService"`; re-asserts `isReady` at l.132 |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt` | 150–270 | EXACT keys/setters: `apiKey()` (l.158), **`shareEnabled()` / its setter + backing key (share is DEFAULT-OFF — must flip)**, `webhookHmacSecret()` (l.245), `webhookAllowlist()` (l.257) + `setWebhookAllowlist`, `setModelId`, `setHfToken` |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 532–585 | `POST /v1/batch` (shape `{messages[],webhook?,temperature?,top_p?,seed?}` → 202 `{job_id,status}`; `WebhookGuard.check` at submit) + `GET /v1/batch/{id}` (status + result); auth `authorized()` l.224–228 (constant-time Bearer) |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/worker/BatchWorker.kt` | 44–120 | `doWork()`: stale reaper (5min), atomic `claim` CAS, **`RelaisEngine.generate(...)` at l.96–100 (the worker-through-LLM)**, `finish()`, `deliverWebhook()`; `MAX_PER_RUN=3`; `TAG="RelaisBatch"`; re-kick on overflow |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/batch/WebhookGuard.kt` | 37–86 | SSRF policy: blocks loopback/wildcard/link-local/private(RFC1918)/multicast/IPv6-ULA **unless host ∈ allowlist**; HTTPS required for public IPs; resolves + returns vetted IPs for pinning |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/batch/WebhookDelivery.kt` | 46–145 | Pinned-IP raw socket → TLS layered via `createSocket(raw, host, port, true)` (SNI=hostname) → `startHandshake()` (PKIX chain) → `HostnameVerifier.verify` → POST `X-Relais-Signature` header, `Connection: close`, no redirects; CONNECT 15s / READ 30s |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/batch/WebhookSigner.kt` | 24–37 | `HEADER="X-Relais-Signature"`; `header(payload,secret)="sha256="+hex(HmacSHA256(secret, payload))`; payload = the JSON envelope `{job_id,status,result}` |
| P1 | `Android/src/app/src/androidTestFull/java/cc/grepon/relais/ImageOcrProbe.kt` | all | The OCR probe pattern to mirror: `assumeTrue(... args.getString("RELAIS_PROBE")=="1")`, renders text to a Bitmap, asserts recognition. Lives in `androidTestFull` (full flavor only). |
| P1 | `Android/src/app/src/androidTest/java/cc/grepon/relais/BatchProbe.kt` | 40–75 | The batch probe pattern: spins a local `RelaisHttpServer(..., tls=false, bindAddr="127.0.0.1")`, posts via raw socket, asserts status lines. Mirror its HTTP helper + `assumeTrue` gate. |
| P2 | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisNodeTest.kt` | all | androidTest idiom: `@RunWith(AndroidJUnit4)`, `targetContext`, `assumeTrue` gating, warming the engine via `RelaisEngine.generate(...)` before asserting `isReady`. |
| P2 | `Android/src/app/src/main/java/cc/grepon/relais/common/Utils.kt` | 329–354 | `decodeSampledBitmapFromUri` (the decode the OCR path uses) — informs the test image size/format. |

## External Documentation
| Topic | Source | Key Takeaway |
|---|---|---|
| Android 16 scoped storage MediaStore insert | developer.android.com/training/data-storage/shared/media | Use `contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)` → returns a `content://` URI owned by the app UID; write bytes via `openOutputStream`. No `_data` column on A16. |
| Clipboard read restriction (A10+) | developer.android.com/develop/ui/views/touch-and-input/copy-paste | Background/non-focused apps can't read the clipboard. An instrumentation test reading `ClipboardManager.primaryClip` is **unreliable** → use logcat/notification as the authoritative observable; treat clipboard assert as best-effort. |
| webhook.site (public HTTPS receiver) | webhook.site | Free unique HTTPS endpoint that records POST body + headers — exercises the real TLS path (valid cert, public IP) so the SSRF guard passes with no allowlist. Read the captured `X-Relais-Signature`. |
| adb reverse | developer.android.com/tools/adb#forwardports | `adb reverse tcp:9999 tcp:9999` maps device `127.0.0.1:9999` → host `:9999` (hermetic HTTP fallback for #14; requires allowlisting `localhost` to bypass the SSRF loopback block). |

> KEY_INSIGHT: The SSRF guard is the gate for #14 delivery. APPLIES_TO: webhook receiver choice. GOTCHA: a LAN/loopback receiver is BLOCKED unless its host is in `webhookAllowlist`; the cleanest *real-HTTPS* test is a public receiver (webhook.site) which needs no allowlist and validates the TLS/SNI/chain path.

---

## Patterns to Mirror

### PROBE_GATE_AND_STRUCTURE
```kotlin
// SOURCE: ImageOcrProbe.kt / BatchProbe.kt
@RunWith(AndroidJUnit4::class)
class XxxProbe {
  private val args get() = InstrumentationRegistry.getArguments()
  @Test fun something() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run",
      args.getString("RELAIS_PROBE") == "1")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // ...
  }
}
```

### RAW_HTTP_HELPER (for talking to the resident node from a probe)
```kotlin
// SOURCE: BatchProbe.kt — raw-socket HTTP against 127.0.0.1:<port>; mirror for curl-like calls.
// For an e2e against the REAL resident node, prefer driving via the already-running server on
// 127.0.0.1:8080 (loopback, cleartext) with Authorization: Bearer <apiKey>.
```

### ENGINE_WARM_BEFORE_ISREADY
```kotlin
// SOURCE: RelaisNodeTest.kt / MultiTurnReplayProbe.kt
// isReady() is only true after a real decode. Warm it:
RelaisEngine.generate(context, RelaisRequest(text = "hi"))   // blocks until a token decodes
```

### PREF_FLIP (enable a default-off feature on-device)
```kotlin
// SOURCE: SessionGateProbe.kt pattern (session_memory_enabled). Regular prefs file = "relais".
// Prefer the RelaisConfig setter if one exists (e.g. RelaisConfig.setShareEnabled(ctx, true));
// else: context.getSharedPreferences("relais", MODE_PRIVATE).edit().putBoolean(<KEY>, true).commit()
```

### NODE_CONTROL (start/stop, token-gated)
```bash
# SOURCE: RelaisControlActivity.kt:101-119
adb -s <serial> shell am start -n com.ventouxlabs.relais/cc.grepon.relais.RelaisControlActivity \
  --es cmd start --es token <apiKey> [--es modelId <allowlistId>]
```

---

## Files to Change
| File | Action | Justification |
|---|---|---|
| `Android/src/app/src/androidTestFull/java/cc/grepon/relais/ShareImageProbe.kt` | CREATE | #13 e2e: MediaStore image → SEND intent → assert result (full flavor; needs ML Kit) |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/BatchE2eProbe.kt` | CREATE | #14 worker-through-LLM: submit job vs the resident node → poll status → assert real LLM result |
| `.claude/scripts/webhook-receiver.py` (or inline in runbook) | CREATE (throwaway, untracked) | #14 delivery: host HTTPS/HTTP receiver that records the POST + verifies HMAC |
| `docs/` or `SPIKE-FINDINGS.md` | UPDATE | Record the gate-closure evidence (optional) |

## NOT Building
- **No production code changes** — these are validation harnesses. If a gate *fails*, that's a separate bug fix, not this plan.
- **No CI wiring** — androidTest isn't in CI (per the repo); these stay manual/on-device probes.
- **No GrapheneOS-specific clipboard/notification work** — rango is stock/GMS; the GrapheneOS share gates remain separate.
- **No self-signed-TLS webhook receiver** — testing the device-trusts-a-custom-CA path is out of scope; use a public valid-cert receiver for the TLS path.
- **No change to the SSRF policy** — use the existing `webhookAllowlist` hook for the hermetic fallback.

---

## Step-by-Step Tasks

### Task 1: Read RelaisConfig for the exact share-enabled + webhook keys/setters
- **ACTION**: Open `RelaisConfig.kt`; find the share-enabled accessor (`shareEnabled`) + its setter/backing key, and confirm `setWebhookAllowlist`, `webhookHmacSecret`, `apiKey` signatures.
- **VALIDATE**: You can name the exact call to (a) enable share, (b) set the webhook allowlist, (c) read the HMAC secret, from a probe. No guessing in later tasks.

### Task 2 (#13): Write `ShareImageProbe` (androidTestFull)
- **ACTION**: New instrumented probe, full flavor.
- **IMPLEMENT**:
  1. `assumeTrue(RELAIS_PROBE==1)`; `targetContext`.
  2. Enable share (Task 1 setter) and warm the engine (`RelaisEngine.generate(ctx, RelaisRequest(text="hi"))`) so `RelaisInference.isReady()` is true.
  3. Render a Bitmap with known text (mirror `ImageOcrProbe`: e.g. "INVOICE TOTAL 4242").
  4. Insert into MediaStore: `val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply { put(DISPLAY_NAME,"relais_ocr_test.png"); put(MIME_TYPE,"image/png") })`; write PNG bytes via `resolver.openOutputStream(uri)`.
  5. Fire the real trampoline: `Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK).setClassName(ctx,"cc.grepon.relais.share.RelaisShareActivity")`; `ctx.startActivity(intent)`.
  6. Observe the result: poll for up to ~60s. PRIMARY assert = the resident node served an inference after the share (the share calls `RelaisInference.completeText`, which is in-process — assert via a side effect you can read: the **clipboard** `ClipboardManager.primaryClip` becoming non-empty with the result, run on the main thread via `instrumentation.runOnMainSync {}`). If clipboard read returns null (A10 restriction), FALL BACK to the manual logcat check in Task 4 and assert only "no crash + share activity launched".
- **MIRROR**: `ImageOcrProbe` (gate + bitmap), `RelaisNodeTest` (warm engine).
- **IMPORTS**: `android.provider.MediaStore`, `android.content.ContentValues`, `android.content.ClipboardManager`, `androidx.test.platform.app.InstrumentationRegistry`.
- **GOTCHA**: share is DEFAULT-OFF → must enable or the trampoline silently no-ops (`shouldRunShare` returns false). `isReady()` is false until the engine decodes once → warm first or the trampoline skips. `file://` URIs are rejected (l.117) — MUST use the MediaStore `content://`. The result is best observed via logcat (`RelaisShareService` + `RelaisOcr`), not metrics (no `/share` counter).
- **VALIDATE**: `adb -s <serial> shell am instrument -w -e RELAIS_PROBE 1 -e class cc.grepon.relais.ShareImageProbe cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner` → OK; concurrently `adb logcat -s RelaisShareService:* RelaisOcr:* RelaisEngine:*` shows: OCR text recognized → inference runs → "result" logged + a "Relais · result" notification appears on the device.

### Task 3 (#14): Write `BatchE2eProbe` (androidTest) — worker-through-LLM
- **ACTION**: New instrumented probe (shared androidTest — no ML Kit needed).
- **IMPLEMENT**:
  1. `assumeTrue(RELAIS_PROBE==1)`; warm the engine; `key = RelaisConfig.apiKey(ctx)`.
  2. POST to the **resident** node loopback `http://127.0.0.1:8080/v1/batch` with `Authorization: Bearer $key` and body `{"messages":[{"role":"user","content":"Reply with exactly: batch-ok"}],"temperature":0}` (no webhook) → assert 202 + capture `job_id`.
  3. Poll `GET http://127.0.0.1:8080/v1/batch/{job_id}` every ~3s up to ~90s until `status=="completed"`.
  4. Assert the `result.choices[0].message.content` is a real LLM answer (non-empty; ideally contains "batch-ok").
- **MIRROR**: `BatchProbe` HTTP helper; `RelaisNodeTest` warm pattern. (Talk to the REAL resident server, not a fresh in-test server, so the real `BatchWorker` + engine run.)
- **IMPORTS**: as `BatchProbe`.
- **GOTCHA**: `BatchWorker` is WorkManager-driven (auto-kicked on submit). On G5 the engine must serve **E2B** (E4B SIGSEGVs — ensure the resident node's model is the E2B default). The worker honors thermal backpressure (`ThermalGovernor.shouldTruncate`) — if the G5 headroom-shed quirk fires, the job may `finish` as `failed`/`length`; assert `status in {completed}` and surface a clear message if thermally truncated.
- **VALIDATE**: probe OK; `GET /v1/batch/{id}` shows `completed` with a coherent `result`. Cross-check `adb logcat -s RelaisBatch:*` shows the claim→generate→finish transitions.

### Task 4 (#14): Real signed HTTPS webhook delivery — public-receiver runbook
- **ACTION**: Drive a batch job WITH a webhook at a public HTTPS receiver and verify the signed POST arrives.
- **IMPLEMENT** (operator steps, no code if using webhook.site):
  1. Open a fresh `https://webhook.site/#!/<uuid>` → copy its unique URL (public IP + valid TLS → passes the SSRF guard, no allowlist).
  2. Read the HMAC secret via a throwaway probe that logs `RelaisConfig.webhookHmacSecret(ctx)` (mirror the api-key dump technique; delete after).
  3. Submit: `curl -k -H "Authorization: Bearer $key" -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"Reply: hook-ok"}],"temperature":0,"webhook":"https://webhook.site/<uuid>"}' https://127.0.0.1:8443/v1/batch` (via `adb forward tcp:8443 tcp:8443`).
  4. Wait for the worker to run + deliver; webhook.site shows the incoming POST with header `X-Relais-Signature: sha256=<hex>` and body `{"job_id","status":"completed","result":{...}}`.
  5. Verify HMAC offline: `python3 -c "import hmac,hashlib; print('sha256='+hmac.new(b'<secret>', open('body.json','rb').read(), hashlib.sha256).hexdigest())"` → must equal the received header.
  6. Confirm `/metrics` shows `relais_webhook_delivered_total` incremented (scrape `https://127.0.0.1:8443/metrics` with the Bearer key).
- **ALTERNATIVE (hermetic, HTTP not TLS)**: `.claude/scripts/webhook-receiver.py` on the host logging body+headers; `adb reverse tcp:9999 tcp:9999`; allowlist `localhost` via `RelaisConfig.setWebhookAllowlist(ctx, setOf("localhost"))` (probe); webhook `http://localhost:9999/hook`. Tests signing + SSRF-bypass + delivery plumbing, but NOT the TLS/SNI/chain path — use only if the device lacks internet.
- **GOTCHA**: the body the HMAC is computed over is the EXACT JSON envelope string `WebhookDelivery` sends (`{job_id,status,result}`) — verify against the raw received bytes, not a re-serialized copy. Webhook.site needs device internet; the hermetic path needs the allowlist (loopback is blocked otherwise).
- **VALIDATE**: webhook.site received the POST; HMAC verifies; `relais_webhook_delivered_total` +1.

### Task 5: Capture evidence + clean up
- **ACTION**: Record outcomes (probe outputs, webhook.site screenshot/log, metrics deltas) in the session handoff / `SPIKE-FINDINGS.md`. Delete the throwaway secret-dumper probe + host receiver. Restore rango: stop node, remove forward/reverse, `svc power stayon false`.
- **VALIDATE**: no secret-logging probe left installed; device clean.

---

## Testing Strategy

### Probes
| Probe | Drives | Asserts | Edge case |
|---|---|---|---|
| `ShareImageProbe` | MediaStore image → SEND → trampoline → service | clipboard/logcat shows OCR'd text → inference result | share-disabled (must enable), engine-cold (must warm), multi-image SEND_MULTIPLE |
| `BatchE2eProbe` | `POST /v1/batch` → worker → engine | `GET` → `completed` + real `result` | thermal truncation on G5, queue cap (429), stale-running reaper |
| webhook runbook | batch job + public HTTPS webhook | signed POST received + HMAC verifies + metric +1 | SSRF block (private→400), bad-secret HMAC mismatch, non-2xx receiver → `failed_total` |

### Edge Cases Checklist
- [ ] #13: share default-off → trampoline no-ops (enable first)
- [ ] #13: engine cold → trampoline skips (warm first)
- [ ] #13: `file://` URI rejected; only `content://` works
- [ ] #13: caption (EXTRA_TEXT) + image both present → caption prefixed to OCR
- [ ] #14: private-IP webhook → 400 at submit (regression of the proven SSRF block)
- [ ] #14: worker through LLM on G5 serves E2B (not E4B → SIGSEGV)
- [ ] #14: HMAC over exact envelope bytes
- [ ] #14: receiver non-2xx → `relais_webhook_failed_total`

---

## Validation Commands

### Build the probes (must be on a branch with #56 merged; full + its androidTest)
```bash
cd Android/src
# one flavor per invocation (2GB heap OOMs on both at once)
./gradlew :app:assembleFullDebug :app:assembleFullDebugAndroidTest -x lint --max-workers=2
```
EXPECT: BUILD SUCCESSFUL; `app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk` present.

### Install + node up (rango, G5 → E2B)
```bash
ADB="/home/user/Android/Sdk/platform-tools/adb -s 57211FDCG0023C"; KEY=<apiKey>
$ADB install -r app/build/outputs/apk/full/debug/app-full-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
$ADB shell am start -n com.ventouxlabs.relais/cc.grepon.relais.RelaisControlActivity --es cmd start --es token $KEY
$ADB forward tcp:8443 tcp:8443   # for curl; loopback 8080 reachable in-process for probes
```
EXPECT: `/health` → `{"ready":true}` after a warm decode.

### #13
```bash
$ADB shell am instrument -w -e RELAIS_PROBE 1 -e class cc.grepon.relais.ShareImageProbe \
  cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
# in another shell: $ADB logcat -s RelaisShareService:* RelaisOcr:* RelaisEngine:*
```
EXPECT: OK; logcat shows OCR text → inference → "result"; a "Relais · result" notification on device.

### #14 worker-through-LLM
```bash
$ADB shell am instrument -w -e RELAIS_PROBE 1 -e class cc.grepon.relais.BatchE2eProbe \
  cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
```
EXPECT: OK; job reaches `completed` with a coherent result.

### #14 webhook delivery (public receiver)
```bash
curl -k -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Reply: hook-ok"}],"temperature":0,"webhook":"https://webhook.site/<uuid>"}' \
  https://127.0.0.1:8443/v1/batch
# then check webhook.site for the POST + X-Relais-Signature; verify HMAC; then:
curl -k -H "Authorization: Bearer $KEY" https://127.0.0.1:8443/metrics | grep relais_webhook
```
EXPECT: 202; webhook.site shows the signed POST; `relais_webhook_delivered_total` +1.

### Manual validation
- [ ] #13: a real notification appears with the model's answer to the OCR'd text
- [ ] #14: webhook.site shows `{job_id,status:completed,result}` with a valid `X-Relais-Signature`

---

## Acceptance Criteria
- [ ] `ShareImageProbe` runs e2e on rango: image-share → OCR → inference → result observed (clipboard or logcat+notification)
- [ ] `BatchE2eProbe` runs e2e: submit → worker-through-LLM → `completed` + real result
- [ ] A real signed HTTPS webhook POST is received + HMAC verified + metric incremented
- [ ] SSRF block still returns 400 for a private-IP webhook (no regression)
- [ ] Evidence captured; throwaway secret-dumper + receiver removed; rango restored

## Completion Checklist
- [ ] Probes follow `assumeTrue(RELAIS_PROBE)` gate + androidTest idioms
- [ ] #13 probe in `androidTestFull` (not shared androidTest — it needs ML Kit)
- [ ] No production code changed (validation-only)
- [ ] No secret left in logs/probes after the run
- [ ] Handoff/SPIKE-FINDINGS updated with gate-closure evidence

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Clipboard unreadable from the test (A10+) → #13 assert weak | High | Med | Authoritative observable = logcat + on-device notification; clipboard assert is best-effort only |
| Android-16 MediaStore insert quirks (scoped storage) | Med | Med | Use `EXTERNAL_CONTENT_URI` insert + `openOutputStream` (no `_data`); same-uid grant via `FLAG_GRANT_READ_URI_PERMISSION` |
| G5 thermal shed truncates the batch LLM run | Med | Low | Assert `completed`; if thermally truncated, report it (known G5 `getThermalHeadroom` quirk), retry on cooler headroom |
| webhook.site unavailable / device offline | Low | Med | Hermetic fallback: host receiver + `adb reverse` + `localhost` allowlist (HTTP path; no TLS) |
| Probes require #56 merged (androidTestFull source set) | High | Low | Sequence after the 3 PRs merge; branch off main |
| E4B selected on G5 → SIGSEGV mid-batch | Low | High | Ensure resident model is E2B (G5 default, PR #19); verify `/v1/models` / `/health` before running |

## Notes
- Both gates are **validation**, not features — a failure is a new bug, not a plan defect.
- Device facts: rango = Pixel 10 Pro Fold / G5 / `57211FDCG0023C`; serves **E2B** (E4B SIGSEGVs on G5 — LiteRT-LM #2566). Node start is token-gated via `RelaisControlActivity`. API key + webhook secret live in EncryptedSharedPreferences (read via a throwaway probe).
- This session already PROVED: #13 OCR engine (`ImageOcrProbe` OK) and #14 SSRF→400 / submit→202 / status→200 (`BatchProbe` OK). This plan closes the remaining halves: #13 full content-URI e2e, #14 worker-through-LLM + real signed HTTPS delivery.
- Implement on `feat/ondevice-gates-13-14` off `main` **after** #56/#58/#57 merge (so `androidTestFull` exists and the AboutLibraries/branding state is settled).
```
