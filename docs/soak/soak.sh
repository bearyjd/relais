#!/usr/bin/env bash
#
# Relais multi-hour soak (Gate 3 endurance proof).
#
# Drives a sustained request loop against a running node and logs tok/s, thermal
# state, RSS, and errors to a CSV. Warn-and-continue: a failed request is logged
# and the loop carries on, so one bad request never ends the soak.
#
# Usage:
#   ./soak.sh <host> <api-key> [duration-min] [interval-sec]
# Example:
#   ./soak.sh 192.168.1.50 0123abcd... 360 20
#
# Pass criteria (evaluate against the CSV + the device):
#   - zero unhandled crashes; /health stays reachable throughout
#   - relais_restarts_total does not increase (init count stable)
#   - memory_rss_bytes shows no upward trend (no leak)
#   - latency degrades gracefully under heat (no timeout cliff); thermal sheds
#     (503) recover rather than crashing
set -uo pipefail

HOST="${1:?usage: soak.sh <host> <api-key> [duration-min] [interval-sec]}"
KEY="${2:?missing api key}"
DURATION_MIN="${3:-360}"   # default 6h
INTERVAL_SEC="${4:-20}"
BASE="https://${HOST}:8443"
PROMPT='Write one sentence about relay stations.'

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
metric() { # metric() <name> -> value from /metrics, or empty
  printf '%s' "$1" | grep -m1 "^$2 " | awk '{print $2}'
}

OUT="soak-$(date -u +%Y%m%dT%H%M%SZ).csv"
echo "ts,http_status,latency_ms,decode_tok_s,thermal_status,rss_bytes,engine_ready,restarts,note" >"$OUT"
echo "Soak -> $BASE for ${DURATION_MIN}m every ${INTERVAL_SEC}s; logging to $OUT"

end=$(( $(date +%s) + DURATION_MIN * 60 ))
reqs=0; errs=0; sheds=0
while [ "$(date +%s)" -lt "$end" ]; do
  reqs=$((reqs + 1))
  body=$(printf '{"model":"gemma-4-e4b-it","messages":[{"role":"user","content":"%s"}]}' "$PROMPT")
  start_ns=$(date +%s%N)
  http=$(curl -k -s -o /tmp/soak_resp.json -w '%{http_code}' \
    --max-time 180 -H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json" \
    -d "$body" "${BASE}/v1/chat/completions" 2>/dev/null || echo "000")
  latency_ms=$(( ($(date +%s%N) - start_ns) / 1000000 ))

  prom=$(curl -k -s --max-time 10 -H "Authorization: Bearer ${KEY}" "${BASE}/metrics" 2>/dev/null || true)
  tok=$(metric "$prom" relais_decode_tokens_per_second)
  thermal=$(metric "$prom" relais_thermal_status)
  rss=$(metric "$prom" relais_memory_rss_bytes)
  ready=$(metric "$prom" relais_engine_ready)
  restarts=$(metric "$prom" relais_restarts_total)

  note=""
  case "$http" in
    200) ;;
    503) sheds=$((sheds + 1)); note="thermal-shed" ;;
    000) errs=$((errs + 1)); note="connect-fail" ;;
    *)   errs=$((errs + 1)); note="http-$http" ;;
  esac

  echo "$(ts),${http},${latency_ms},${tok:-},${thermal:-},${rss:-},${ready:-},${restarts:-},${note}" >>"$OUT"
  printf '\r[%d reqs | %d err | %d shed] last=%sms http=%s tok/s=%s thermal=%s   ' \
    "$reqs" "$errs" "$sheds" "$latency_ms" "$http" "${tok:-?}" "${thermal:-?}"
  sleep "$INTERVAL_SEC"
done

echo
echo "Done. reqs=$reqs errors=$errs sheds=$sheds -> $OUT"
echo "Check: restarts column flat? rss_bytes flat? no run of connect-fail? sheds recovered to 200?"
