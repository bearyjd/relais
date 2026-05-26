# Local Review: Relais node self-provisioning model download

**Reviewed**: 2026-05-25
**Branch**: relais/model-download (uncommitted)
**Decision**: APPROVE (HIGH + MEDIUM findings fixed during review)

## Summary
Adds a headless `RelaisModelProvisioner` that resolves the configured model from Gallery's allowlist
and downloads it via the existing `DownloadWorker` if absent. Implementation is sound and reuses the
existing stack cleanly. Review found one HIGH robustness bug (offline restart) and one MEDIUM
(unbounded wait); both were fixed and the build re-verified green.

## Findings

### CRITICAL
None.

### HIGH
- **Offline/restart with model present would fail** — `RelaisModelProvisioner.kt`.
  `ensureModel()` always called `resolveModel()` (an allowlist HTTP GET) before checking the disk,
  and the in-memory `cachedPath` did not survive a process restart. A rebooted / watchdog-restarted
  node whose model is already downloaded would throw at startup while offline, despite nothing
  needing download.
  **FIXED**: persist the provisioned path in `RelaisConfig.modelPath`; `ensureModel()` now
  short-circuits on a persisted path whose file exists (no network), and `cachedPathOrDefault()`
  consults the persisted path too.

### MEDIUM
- **Unbounded `while(true)` download wait** — `RelaisModelProvisioner.download()`. No stall guard;
  a hung worker (dead socket, stuck constraint) would block the `relais-init` thread forever.
  **FIXED**: stall timeout (`STALL_TIMEOUT_MS = 120s`) on *no byte growth* (not total size, so slow
  multi-GB downloads still proceed); cancels the work and throws a clear error on stall.
- **HF token stored in plaintext** — `RelaisConfig.hfToken` lives in plain `SharedPreferences` and is
  copied into the WorkManager DB as `Data`. NOT fixed (accepted): app-private storage (`MODE_PRIVATE`),
  identical to the existing `apiKey` handling and to Gallery's own `downloadModel`. Noted for future
  hardening (EncryptedSharedPreferences) but out of scope and not a regression.

### LOW
- `resolveModel()` error messages include the allowlist URL — informational only, no secret leak
  (the Bearer token is set by `DownloadWorker`, never echoed).
- `g_provisionDownloadsModel` performs a live network fetch; it fails (not skips) when offline with
  no model. Acceptable for an opt-in on-device provisioning test.

## Validation Results

| Check | Result |
|---|---|
| Type check | Pass (Kotlin compile of main + androidTest) |
| Lint | Skipped (no ktlint/detekt task wired; build warnings pre-existing) |
| Tests | Skipped — instrumented androidTest, requires device + network + model |
| Build | Pass — `:app:assembleDebug :app:assembleDebugAndroidTest` BUILD SUCCESSFUL |

## Files Reviewed
- `relais/RelaisModelProvisioner.kt` — Added
- `relais/RelaisConfig.kt` — Modified
- `relais/RelaisEngine.kt` — Modified
- `relais/RelaisNodeService.kt` — Modified
- `androidTest/.../RelaisNodeTest.kt` — Modified
- (docs/plan move excluded from review)
