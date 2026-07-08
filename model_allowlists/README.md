# Model allowlists

This directory holds committed **snapshots** of the model allowlist Relais fetches at runtime. The
live allowlist is authoritative — Relais does not read these files directly. They exist so a human
or agent can inspect history and diff versions without hitting the network.

## What's actually happening at runtime

`RelaisModelProvisioner.allowlistUrl()` and `ModelManagerViewModel`'s `getAllowlistUrl()` (both in
`Android/src/app/src/main/java/cc/grepon/relais/`) build a URL from the running app's own version:

```
https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists/{VERSION}.json
```

where `{VERSION}` is `BuildConfig.VERSION_NAME` with dots replaced by underscores (e.g. app version
`1.0.15` → `1_0_15.json`). This is fetched fresh over the network on node boot
(`RelaisModelProvisioner.resolveModel`) and again by the in-app Model Manager
(`ModelManagerViewModel.loadModelAllowlist`); a copy of whatever was last fetched is cached on
device as `model_allowlist.json` under `externalFilesDir` (`MODEL_ALLOWLIST_FILENAME` in
`ModelManagerViewModel.kt`) purely as an offline fallback for that install, not as an update
mechanism.

Per `SPIKE-FINDINGS.md`: **the live upstream allowlist is authoritative; repo snapshots are
stale by definition** — they only need to stay roughly in sync with what Relais's *own* app
version will request, so that (a) diffing across Relais version bumps is possible offline and
(b) `RelaisModelCatalogTest` and friends have realistic fixtures to test against.

**Note on the root `model_allowlist.json`** (repo root, not this directory): it currently holds an
older 4-model Gemma-3n-era snapshot, unrelated to and out of sync with the versioned snapshots
here (verified by diff — no shared entries with `1_0_15.json`'s 9-model Gemma-4 lineup as of this
writing). No `.kt` file references that literal repo-root path; the on-device cache file of the
same name (`MODEL_ALLOWLIST_FILENAME` in `ModelManagerViewModel.kt`) is a *different* file written
at runtime, not this committed one. Treat the root file as stale/vestigial rather than part of the
live update path — don't use it as a diff base.

## What's in each snapshot

Each `<version>.json` has a top-level `models: []` array. Fields that matter for safety/gating
(seen in `model_allowlists/1_0_15.json`):

- `modelId` / `modelFile` / `commitHash` — identify the exact model + weights revision on Hugging
  Face; `RelaisModelProvisioner.resolveModel` matches on `modelId`.
- `sizeInBytes`, `minDeviceMemoryInGb` — download/provisioning gating.
- `llmSupportImage`, `llmSupportAudio` — multimodal capability flags.
- `defaultConfig.accelerators` (e.g. `"gpu,cpu"`) and `defaultConfig.visionAccelerator` — **the
  safety-relevant fields.** Per `SPIKE-FINDINGS.md`'s Tensor-G5 finding, a model × accelerator
  combination can SIGSEGV natively on specific SoCs (the `gemma-4-E4B` / Tensor G5 crash, gated in
  code via `RelaisEngine.kt`'s `isG5Incompatible`/`G5_INCOMPATIBLE_MODEL_IDS`, keyed off
  `RelaisConfig.DEFAULT_MODEL_ID`). A new or changed `accelerators` value on an existing model id is
  not cosmetic — check it against known device-gating bugs before assuming it's safe.
- `taskTypes` / `bestForTaskTypes` / `capabilities` — which app surfaces (chat, agent chat,
  ask-image, ask-audio, prompt lab) a model is offered for.

As observed across the current snapshots (`md5sum model_allowlists/*.json`), several consecutive
versions are byte-identical (e.g. `1_0_13.json` == `1_0_14.json` == `1_0_15.json`;
`1_0_11.json` == `1_0_12.json`) — an app version bump does not necessarily mean the upstream
allowlist changed. Don't assume every new Relais version needs a new snapshot; only add one when
the fetched content actually differs from the previous file.

## Runbook: adding a new snapshot (allowlist bump)

Do this when Relais's app version advances to a version whose allowlist hasn't been snapshotted
yet, or when you need an offline copy of a change you saw live.

1. **Fetch the new upstream JSON.** Use the same URL the app would build for the target version:
   ```bash
   curl -s "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists/<version>.json" \
     -o model_allowlists/<version>.json
   ```
   (`<version>` = the app's `VERSION_NAME` with dots as underscores, e.g. `1_0_16`.)

2. **Diff against the prior snapshot** and read the diff, don't skim it:
   ```bash
   diff <(python3 -m json.tool model_allowlists/<prev>.json) <(python3 -m json.tool model_allowlists/<version>.json)
   ```
   If the diff is empty, you don't need a new file — see the note above. If it's non-empty, look
   specifically for:
   - a changed or added `accelerators` / `visionAccelerator` value on any model id currently
     referenced from code (`RelaisConfig.DEFAULT_MODEL_ID`,
     `RelaisModelProvisioner.G5_DEFAULT_REF`) — cross-check against `SPIKE-FINDINGS.md` for any
     known device × model incompatibility before treating it as safe;
   - a new model entry that could become a default or G5-substitute candidate;
   - a removed model entry that `RelaisConfig.DEFAULT_MODEL_ID` or any other pinned id/ref in code
     depends on — a removal from the live allowlist breaks `resolveModel`'s allowlist path (the
     persisted-`RelaisModelRef` fast path still works for anyone already provisioned, per
     `RelaisModelProvisioner.resolveModel`).

3. **Add the file** under `model_allowlists/<version>.json` (this directory). No other file needs
   to change for the snapshot itself — `allowlistUrl()`/`getAllowlistUrl()` derive the filename
   from `BuildConfig.VERSION_NAME` at build time, so bumping the app's own version (in
   `Android/src/build.gradle.kts`) is what actually changes which snapshot the running app
   requests; this directory is documentation/history, not a lookup table the app reads.

4. **Re-run the tests that exercise allowlist-adjacent logic**, all under
   `Android/src/app/src/test/java/cc/grepon/relais/`:
   - `Pixel10DefaultModelTest` — the G5-default-substitution decision (`deviceDefaultRef`) is
     independent of the live allowlist content but must still pass; re-run to catch any regression
     if you touched `RelaisModelProvisioner.kt` while at it.
   - `RelaisModelCatalogTest` — model-catalog / `isNodeRunnable` matching logic.
   - `StaleModelPathTest` — the mid-provision modelId-drift guard (`shouldPersistPath`).
   - `RelaisModelRefProvisionTest` — `RelaisModelRef` JSON round-trip and provisioning.
   - `RelaisEngineConfigTest` — the G5-incompatibility gate (`isG5Incompatible`) and encoder-based
     multimodal/text-only config fallback; re-run this any time an `accelerators` value changed for
     a model id referenced in `RelaisEngine.kt`.

   From `Android/src`:
   ```bash
   ./gradlew testFullOpenDebugUnitTest --tests "cc.grepon.relais.Pixel10DefaultModelTest" \
     --tests "cc.grepon.relais.RelaisModelCatalogTest" \
     --tests "cc.grepon.relais.StaleModelPathTest" \
     --tests "cc.grepon.relais.RelaisModelRefProvisionTest" \
     --tests "cc.grepon.relais.RelaisEngineConfigTest"
   ```
   (Or run the full CI unit-test job — see `CLAUDE.md`'s build/test table — if you touched
   provisioning/engine code, not just the snapshot file.)

5. **If a device-gating fact changed** (a model now safe/unsafe on a specific SoC), update
   `SPIKE-FINDINGS.md` too — that file, not this one, is the source of truth for settled
   device/backend facts, and code (`RelaisEngine.kt`'s `G5_INCOMPATIBLE_MODEL_IDS`) should follow
   it, not the other way around.

**Acceptance:** the new snapshot is added, the diff against the prior version has been read (not
just fetched), any accelerator/device-gating change has been cross-checked against
`SPIKE-FINDINGS.md`, and the five tests above are green.
