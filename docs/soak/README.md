# Soak testing (Gate 3 endurance)

Gate 3's promise is "runs unattended on a desk for a week." Endurance can't be
proven by CI — emulators have no thermal and a multi-hour run is too slow/flaky
for a pipeline. So Gate 3 closes by **two complementary checks**:

1. **Deterministic CI proxy** (in-repo instrumented tests): drives load via a
   thermal-injection seam to assert the governor's shed/cool-down/truncate paths
   and bounded memory (incl. the rate-limiter and metrics maps) — runs every CI.
2. **Documented real-device soak** (this directory): a multi-hour run on the
   target phone whose captured artifact (CSV + logcat) is committed here. This
   mechanizes the "evidence, never assumption" discipline from `SPIKE-FINDINGS.md`.

## Pre-staging the model (skip the first-run download)

A fresh install otherwise downloads the ~3.66 GB model from HuggingFace on first
start. To skip that, side-load the model into the node's app files dir: the node
adopts a pre-staged copy at `…/files/relais/<model>.litertlm` instead of
downloading (`RelaisModelProvisioner.ensureModel` fast path; gated to the default
model id).

```bash
PKG=cc.grepon.relais
DIR=/sdcard/Android/data/$PKG/files/relais
adb shell mkdir -p "$DIR"
adb push gemma-4-E4B-it.litertlm "$DIR/"
# REQUIRED: a file pushed by adb is owned by `shell` with no "others" permission,
# so the app (a different uid) reads it as 0 bytes and re-downloads anyway. Make it
# app-readable:
adb shell chmod 0755 "$DIR"
adb shell chmod 0644 "$DIR/gemma-4-E4B-it.litertlm"
```

On the next start, logcat shows `Adopting pre-staged model at default location`
(not `AGDownloadWorker`). The adopted path is then persisted, so later boots skip
even the allowlist fetch.

## Running the real-device soak

```bash
# from a host on the same LAN as the node
./soak.sh <phone-ip> <api-key> 360 20      # 6 hours, one request every 20s
adb logcat -v time > soak-logcat.txt &      # capture device-side logs in parallel
```

`soak.sh` writes `soak-<timestamp>.csv` with, per request: HTTP status, latency,
decode tok/s, thermal status, RSS, engine-ready, restart count, and a note
(`thermal-shed` / `connect-fail` / `http-<code>`). It is warn-and-continue: a bad
request is logged and the loop carries on.

## Pass criteria

Evaluate the CSV (and the device) against:

| Check | Pass condition |
|---|---|
| Stability | zero unhandled crashes; `/health` reachable throughout |
| Resident engine | `restarts` column flat (no unintended re-init) |
| No leak | `rss_bytes` shows no upward trend over the run |
| Graceful thermal | latency degrades smoothly; `503` sheds **recover** to `200` (no timeout cliff, no crash) |
| Recovery | any induced crash returns to `/health` 200 within the watchdog window |

## What to commit

Commit the run artifact for the release evidence trail:

```
docs/soak/
  soak-YYYYMMDDThhmmssZ.csv     # the run
  soak-YYYYMMDDThhmmssZ.notes.md # device, model, ambient temp, verdict
```
