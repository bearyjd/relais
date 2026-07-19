#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# g3-e4b-probe.sh — Tensor G3 (Pixel 8 Pro) datapoint for LiteRT-LM #2566
#
# Reproduces the canonical #2566 path on a third Tensor generation:
#   /health → ready:true, then the FIRST /v1/chat/completions.
# Auto-classifies the outcome into one of:
#   SERVES     → E4B runs on G3 (the new datapoint we want; row: "G3 ✅ serves")
#   SIGSEGV    → E4B *also* crashes on G3 (would mean the bug is NOT G5-only — important!)
#   OOM        → killed by lowmemorykiller (12GB too tight → inconclusive for the bug, not a repro)
#   NODE_DOWN  → node never became ready / unreachable (setup issue, re-run)
#
# This is an EVIDENCE harness for an upstream bug report. It changes nothing on
# the device. Run it from the host with the Pixel 8 Pro attached + unlocked.
#
# PRECONDITIONS (the physical/provisioning bits a script can't do for you):
#   1. Pixel 8 Pro attached, USB-debugging authorized, screen unlocked.
#   2. The Relais full build installed (cc.grepon.relais).
#   3. The default model (gemma-4-E4B-it, OPEN — no HF token) provisioned, i.e.
#      already downloaded. Easiest: open the app once on Wi-Fi and let it pull
#      ~4.4GB, or start the node and wait. The script verifies readiness and
#      bails with guidance if the model isn't there yet.
#   4. The node running (QS tile, or the app's control screen). The script will
#      best-effort start it via the QS tile if it isn't already serving.
#
# USAGE:
#   ANDROID_SERIAL=<serial> .claude/scripts/g3-e4b-probe.sh
#   (omit ANDROID_SERIAL if exactly one device is attached)
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

PKG="cc.grepon.relais"
TILE_FQN="${PKG}/${PKG}.tile.RelaisTileService"
MAIN_ACT="${PKG}/.MainActivity"
HOST_PORT="${HOST_PORT:-18080}"
DEVICE_PORT="${DEVICE_PORT:-8080}"
PROMPT="${PROMPT:-Say hello in one word.}"
MODEL_ID="${MODEL_ID:-litert-community/gemma-4-E4B-it-litert-lm}"   # E4B — the model that SIGSEGVs on G5
READY_TIMEOUT="${READY_TIMEOUT:-180}"   # seconds to wait for /health ready (cold model load)
OUTDIR="${OUTDIR:-/tmp/g3-e4b-probe-$(date +%s)}"

# ── adb helpers ──────────────────────────────────────────────────────────────
if [ -n "${ANDROID_SERIAL:-}" ]; then ADB=(adb -s "$ANDROID_SERIAL"); else ADB=(adb); fi
say() { printf '%s\n' "$*"; }
hr()  { printf '────────────────────────────────────────────────────────\n'; }
fail(){ say "FATAL: $*"; exit 2; }

command -v adb >/dev/null || fail "adb not on PATH"
mkdir -p "$OUTDIR"

# ── 0. device + SoC identity (recorded for the report) ───────────────────────
N=$("${ADB[@]}" get-state 2>/dev/null | grep -c device)
[ "$N" = "1" ] || fail "need exactly one ready device (or set ANDROID_SERIAL). 'adb devices' to check."
"${ADB[@]}" shell 'echo locked=$(dumpsys window 2>/dev/null | grep -m1 mDreamingLockscreen)'
SOC_MODEL=$("${ADB[@]}" shell getprop ro.soc.model | tr -d '\r')
HARDWARE=$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')
DEVICE=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')
ANDROID=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')
TOTAL_RAM=$("${ADB[@]}" shell cat /proc/meminfo | awk '/MemTotal/{printf "%.1f GB", $2/1024/1024}')
{
  say "device:   $DEVICE"
  say "soc:      $SOC_MODEL   (hardware=$HARDWARE)"
  say "android:  $ANDROID"
  say "ram:      $TOTAL_RAM"
} | tee "$OUTDIR/device.txt"
hr

case "$SOC_MODEL" in
  *G3*) say "✓ Tensor G3 confirmed — this is the new datapoint for #2566." ;;
  *)    say "⚠ SoC is '$SOC_MODEL', not Tensor G3. Recording anyway, but the report row should reflect the real SoC." ;;
esac

# ── 1. app installed? + API key ──────────────────────────────────────────────
"${ADB[@]}" shell pm path "$PKG" >/dev/null 2>&1 || fail "$PKG not installed. Install the full build first."

# Every route except /health requires `Authorization: Bearer <key>` (key = per-install RelaisConfig.apiKey).
# Read it from the app → control screen → "ACCESS KEY" chip (tap to copy), then export RELAIS_API_KEY.
KEY="${RELAIS_API_KEY:-}"
[ -n "$KEY" ] || fail "RELAIS_API_KEY not set. Open the Relais control screen, tap the ACCESS KEY chip to copy the key, then: RELAIS_API_KEY=<key> $0"
AUTH=(-H "Authorization: Bearer ${KEY}")

# ── 2. best-effort start the node via the QS tile, then poll /health ─────────
"${ADB[@]}" forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}" >/dev/null
health() { curl -fsS --max-time 4 "http://127.0.0.1:${HOST_PORT}/health" 2>/dev/null; }

if ! health | grep -q '"ready":true'; then
  say "node not ready — starting it headlessly via the control intent (token + E4B model)…"
  # RelaisControlActivity contract: --es cmd start --es token <apiKey> [--es modelId <id>]
  "${ADB[@]}" shell am start -n "${PKG}/.RelaisControlActivity" \
    --es cmd start --es token "$KEY" --es modelId "$MODEL_ID" >/dev/null 2>&1
  sleep 2
  if ! health | grep -q '"ready":true'; then   # fallback: nudge the QS tile
    "${ADB[@]}" shell cmd statusbar expand-settings >/dev/null 2>&1; sleep 1
    "${ADB[@]}" shell cmd statusbar click-tile "$TILE_FQN" >/dev/null 2>&1
    "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1
  fi
fi

say "waiting up to ${READY_TIMEOUT}s for /health ready (cold E4B load is slow)…"
READY=""
for i in $(seq 1 "$READY_TIMEOUT"); do
  H=$(health)
  if printf '%s' "$H" | grep -q '"ready":true'; then READY="$H"; break; fi
  sleep 1
done
if [ -z "$READY" ]; then
  say "VERDICT: NODE_DOWN — /health never reported ready within ${READY_TIMEOUT}s."
  say "  Start the node from the app's control screen (or check the model finished downloading),"
  say "  then re-run. Last /health: $(health || echo '<unreachable>')"
  exit 1
fi
printf '%s\n' "$READY" > "$OUTDIR/health.json"
say "✓ node ready: $READY"

# confirm the served model is E4B (not the E2B fallback or something else)
MODELS=$(curl -fsS --max-time 5 "${AUTH[@]}" "http://127.0.0.1:${HOST_PORT}/v1/models" 2>/dev/null)
printf '%s\n' "$MODELS" > "$OUTDIR/models.json"
case "$MODELS" in
  *E4B*) say "✓ serving an E4B model." ;;
  *)     say "⚠ /v1/models does not mention E4B — make sure the selected model is gemma-4-E4B-it. Got: $MODELS" ;;
esac
hr

# ── 3. snapshot crash state, fire ONE inference, classify ────────────────────
PID_BEFORE=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')
say "app pid before: ${PID_BEFORE:-<none>}"
"${ADB[@]}" logcat -c 2>/dev/null || true   # clear so the crash buffer is clean
"${ADB[@]}" logcat -b crash -c 2>/dev/null || true

REQ='{"model":"local","messages":[{"role":"user","content":"'"$PROMPT"'"}],"max_tokens":16,"stream":false}'
say "firing first /v1/chat/completions …"
T0=$(date +%s.%N)
RESP=$(curl -sS --max-time 90 -w '\n__HTTP__%{http_code}__TIME__%{time_total}' \
  "${AUTH[@]}" -H 'Content-Type: application/json' -d "$REQ" \
  "http://127.0.0.1:${HOST_PORT}/v1/chat/completions" 2>>"$OUTDIR/curl.err")
CURL_RC=$?
T1=$(date +%s.%N)
printf '%s\n' "$RESP" > "$OUTDIR/response.txt"
HTTP_CODE=$(printf '%s' "$RESP" | sed -n 's/.*__HTTP__\([0-9]*\)__TIME__.*/\1/p')
LAT=$(printf '%s' "$RESP" | sed -n 's/.*__TIME__\([0-9.]*\)$/\1/p')

# gather post-mortem signals regardless of outcome
sleep 2
PID_AFTER=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')
"${ADB[@]}" logcat -b crash -d > "$OUTDIR/logcat-crash.txt" 2>/dev/null || true
"${ADB[@]}" logcat -d -t 4000 > "$OUTDIR/logcat-main.txt" 2>/dev/null || true

CRASH_LOG="$OUTDIR/logcat-crash.txt"
grep -qiE 'signal 11 \(SIGSEGV\)|liblitertlm_jni\.so|F DEBUG' "$CRASH_LOG" 2>/dev/null && SIGSEGV=1 || SIGSEGV=0
grep -qiE 'lowmemorykiller|lmkd|Out of memory|oom-killer|Cmdline.*'"$PKG" "$OUTDIR/logcat-main.txt" 2>/dev/null && OOM=1 || OOM=0
PROC_DIED=0; [ -n "$PID_BEFORE" ] && [ "$PID_AFTER" != "$PID_BEFORE" ] && PROC_DIED=1

hr
say "http_code=$HTTP_CODE  curl_rc=$CURL_RC  latency=${LAT}s  pid_after=${PID_AFTER:-<gone>}  sigsegv=$SIGSEGV  oom=$OOM  proc_died=$PROC_DIED"
hr

# ── 4. verdict + ready-to-paste matrix row ───────────────────────────────────
ROW_PREFIX="| **gemma-4-E4B-it.litertlm** | Tensor G3 (Pixel 8 Pro, ${TOTAL_RAM}) |"
if [ "$HTTP_CODE" = "200" ] && printf '%s' "$RESP" | grep -q '"content"'; then
  TEXT=$(printf '%s' "$RESP" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p' | head -c 120)
  say "VERDICT: ✅ SERVES — E4B ran on Tensor G3. first-inference latency ${LAT}s, reply: \"$TEXT\""
  say "matrix row:  $ROW_PREFIX ✅ serves (first inference ${LAT}s) |"
  say ">>> This is the new G3 datapoint: add it to the #2566 report/nudge."
elif [ "$SIGSEGV" = "1" ]; then
  say "VERDICT: ❌ SIGSEGV — E4B *also* crashes on Tensor G3 (native fault in liblitertlm_jni.so)."
  say "matrix row:  $ROW_PREFIX ❌ SIGSEGV (1st inference) |"
  say ">>> IMPORTANT: this means the bug is NOT G5-specific — it's E4B on Tensor G3 too."
  say "    Pull the full tombstone for the report:  adb -s \$SERIAL shell 'cat /data/tombstones/* 2>/dev/null'"
elif [ "$HTTP_CODE" = "401" ]; then
  say "VERDICT: ✗ 401 UNAUTHORIZED — RELAIS_API_KEY doesn't match this install. Re-copy it from the ACCESS KEY chip and re-run."
elif [ "$OOM" = "1" ] || { [ "$PROC_DIED" = "1" ] && [ "$SIGSEGV" = "0" ]; }; then
  say "VERDICT: ⚠ OOM / process killed (no SIGSEGV signature) — 12GB likely too tight for E4B here."
  say "  INCONCLUSIVE for #2566 (this is a memory limit, not the G5 codegen bug). Don't report as a repro."
  say "  Evidence: $OUTDIR/logcat-main.txt (grep lowmemorykiller/lmkd)."
else
  say "VERDICT: ? UNCLEAR — http=$HTTP_CODE, no SIGSEGV/OOM signature. Inspect $OUTDIR/ and re-run."
fi
hr
say "evidence bundle: $OUTDIR"
ls -1 "$OUTDIR"
