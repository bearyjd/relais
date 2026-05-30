# PR Review: #1 — Relais on-device LLM node

**Reviewed**: 2026-05-25
**Branch**: relais/model-download → main
**Decision**: COMMENT (1 HIGH + MEDIUMs — threat-model-dependent, owner's call; not a hard block)

## Summary
Lands the full Relais node (15 commits). The model-download + TLS work added this session is solid and verified on-device. The network-facing surface has one HIGH worth fixing (exported control activity) and a few MEDIUMs typical of a hand-rolled HTTP server. Most findings predate this session but land on `main` here, so they're in scope.

## Findings

### CRITICAL
None.

### HIGH
- **Exported control activity, no permission guard** — `AndroidManifest.xml:126-131` + `RelaisControlActivity.kt:48-53`. `RelaisControlActivity` is `exported="true"` with no `android:permission`, and honors `--es cmd start|stop`. Any installed app can start/stop the node (load a 3.6 GB model, bind the LAN endpoint, interrupt inference) with no user interaction. Intentional for adb/automation, but unguarded. Fix: signature-level `android:permission`, or `exported="false"` + a narrower automation path.

### MEDIUM
- **No socket read timeout + unbounded thread pool** — `RelaisHttpServer.kt` (`start`/`handle`, `Executors.newCachedThreadPool`). No `setSoTimeout`; a handful of slow/idle connections (slowloris) tie up threads indefinitely → DoS. Auth doesn't help (headers read before auth, no timeout). Fix: `client.soTimeout = N`, bound the pool.
- **Non-constant-time API key compare** — `RelaisHttpServer.kt:308` `token == apiKey`. Timing side-channel on the bearer token. Fix: `MessageDigest.isEqual(...)`.
- **Unbounded per-request body allocation** — `readBody` allocates `CharArray(contentLength)` up to 32 MB (64 MB as chars) × unbounded threads → memory pressure under concurrent authed requests. Tie to the pool/timeout fix.
- **Image decode bounds (AICore path)** — `RelaisAicore.kt:87` `BitmapFactory.decodeByteArray` on untrusted bytes, no null-check/size bound (decompression-bomb OOM, NPE on null). NOTE: gated OFF on this Pixel 9 (`aicoreAvailable=false`), so unreachable here; real on a Pixel 10. The GPU path hands raw bytes to litertlm and catches per-request.

### LOW
- mDNS TXT advertises `model`/`https`/`api` unencrypted on the LAN (`RelaisDiscovery.kt:43-46`) — fingerprinting. By-design for zero-config discovery; informational.
- NsdManager `register`/`unregister` use non-atomic `var`s — race on IP-change could orphan a registration.
- Watchdog reschedules before re-checking `shouldRun` — fires once more after stop.

### Inherited (not introduced by this PR)
- `READ_CALENDAR` and other stock-Gallery dangerous permissions ride in the same process as the LAN service. Worth a least-privilege pass on the fork eventually; out of scope for this PR.

## Validation Results

| Check | Result |
|---|---|
| Type check | Pass (Kotlin compile, main + androidTest) |
| Lint | Skipped (none wired) |
| Tests | Partial — `g_provisionDownloadsModel` passed on-device (Pixel 9); G1–G4 need device/NPU |
| Build | Pass — `assembleDebug` + `assembleDebugAndroidTest` |
| On-device | TLS 1.3 handshake + 200 on `https :8443`; provisioning download→engine verified |

## Files Reviewed
Full subsystem (19 files). Deep: RelaisHttpServer, RelaisModelProvisioner, RelaisEngine, RelaisConfig, RelaisNodeService, RelaisControlActivity, RelaisNodeTest. Security scan: RelaisDiscovery, RelaisWatchdog, RelaisBootReceiver, RelaisAicore, AndroidManifest.

## Recommendation
The one I'd fix before relying on this on a shared network: **H1** (guard the exported activity). M1/M2 are cheap hardening. The rest are notes. None block the model-download/TLS work this PR set out to do.
