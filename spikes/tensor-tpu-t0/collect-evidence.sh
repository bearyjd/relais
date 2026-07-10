#!/usr/bin/env bash
# T-2 hardware-evidence sampler (docs/tensor-tpu-spike-plan.md, Gate T-2).
# Samples the SoC's dedicated TPU thermal sensor + battery draw once per second.
# Run it in one terminal while a TPU inference loops, then again during the
# forced-GPU negative control. TPU execution shows as a rising TPU temperature
# that the GPU run does not produce.
#
# Usage: ./collect-evidence.sh [label] [seconds]   (default: 120s)
# Output: CSV on stdout — tee it into a file per run.
set -euo pipefail

SERIAL="${RELAIS_SERIAL:-57211FDCG0023C}"
LABEL="${1:-run}"
DURATION="${2:-120}"

echo "ts,label,tpu_temp_c,battery_ua"
for ((i = 0; i < DURATION; i++)); do
  tpu=$(adb -s "$SERIAL" shell dumpsys thermalservice 2>/dev/null |
    tr -d '\r' | grep -m1 'mName=TPU' | grep -oE 'mValue=[0-9.]+' | cut -d= -f2 || echo "")
  ua=$(adb -s "$SERIAL" shell cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -d '\r' || echo "")
  echo "$(date +%s),$LABEL,${tpu:-NA},${ua:-NA}"
  sleep 1
done
