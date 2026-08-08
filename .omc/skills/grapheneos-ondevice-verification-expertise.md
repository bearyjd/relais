---
name: grapheneos-ondevice-verification-expertise
description: How to prove which hardware (TPU/GPU/CPU) actually executed work on a GrapheneOS Pixel, and the adb pitfalls (stale sensors, FUSE perms, foldable taps) that produce false negatives
triggers:
  - power.rails.tpu
  - perfetto android.power
  - thermalservice cached
  - NPU silent fallback
  - adb push unreadable EACCES
  - chmod no-op sdcard
  - screencap multiple displays
  - tap wrong app fold
  - flat zero power rail
---

# GrapheneOS on-device hardware verification (rango / Pixel 10 Pro Fold)

## The Insight

On GrapheneOS, every *conventional* channel for proving hardware execution is broken or lies,
but one is authoritative: **per-rail energy counters via `perfetto` (`android.power` data source,
`collect_power_rails: true`)**. `power.rails.tpu` and `power.S2S_VDD_GPU_uws` are monotonic µWs
counters — the slope is milliwatts. An accelerator that is truly executing draws a sustained,
unmistakable signature (TPU: 0 mW idle → ~350–530 mW during LLM decode). A backend badge, an
engine log line, or a "correct answer" proves nothing; the rail cannot be faked.

## Why This Matters

The 2026-07-09 TPU spike produced three consecutive *false* negatives (flat-zero rails) before the
real positive — each caused by an environment pitfall, not the hardware. Without knowing these,
you'll conclude "TPU doesn't work" when actually your measurement never saw the workload.

## Recognition Pattern / The Pitfalls

1. **All rails flat + CPU near-idle (~20 mW) during a supposed workload** → you are measuring the
   wrong thing, not observing a fallback. Check `dumpsys activity activities | grep ResumedActivity`
   and `ps -A | grep <pkg>` FIRST: is the app process alive and foregrounded *on this device*?
   (Two of our false negatives: generation had already finished; user was holding a different phone.)
2. **`dumpsys thermalservice` TPU sensor is a trap**: it reports a cached value (a constant
   41.000004 in our case) plus a live value of `-3.4028235E38` (= unavailable). A never-changing
   temperature is a stale cache, not a cool chip. Don't build evidence on it.
3. **sysfs/dmesg are sealed**: `/sys/class/edgetpu/*` attributes, `/sys/class/thermal/*`, iio ODPM
   rails, and `dmesg` are all SELinux-hidden from adb shell on GrapheneOS. Skip them; go straight
   to perfetto (traced runs privileged and works from shell).
4. **`adb push` staging silently breaks apps**: files pushed to `/sdcard/Android/data/<pkg>/files`
   are owned by `shell` and the app uid CANNOT read them — and on current GrapheneOS **`chmod` is a
   FUSE no-op** (mode changes appear to succeed but change nothing; on dirs it errors). The old
   `chmod -R a+rX` workaround is dead. Fix: stream into app-owned storage via
   `adb exec-out cat <file> | adb shell "run-as <pkg> sh -c 'cat > cache/<name>'"` (debug builds).
5. **Foldable screencap/tap over adb is unreliable**: `screencap -p` to stdout is corrupted by a
   "Multiple displays" warning — write to a file on-device with `-d <physical-display-id>` (list
   via `dumpsys SurfaceFlinger --display-id`) and pull. `input tap` coordinates drift across
   fold/display states; verify `ResumedActivity` after EVERY tap and never chain blind taps —
   ours opened unrelated apps twice. When taps drift twice, stop and hand the interaction to the
   human holding the device.

## The Approach

To prove "workload X ran on accelerator Y":
1. Confirm the workload is *live right now* (foreground + process + user says tokens are flowing).
2. Start the trace FIRST, then trigger the workload inside the window (a small on-device LLM can
   finish before a 30 s trace even starts).
3. `perfetto -c - --txt` with `android.power`/`collect_power_rails`, 500 ms poll, 20–45 s.
4. Read with the `perfetto` pip package (TraceProcessor): slope of the target rail (mW) + all
   sibling rails.
5. Require BOTH: target rail sustained-high during the run AND a negative control (same workload
   forced onto the other backend → target rail stays flat).

## Example

`spikes/tensor-tpu-t0/collect-evidence.sh` + the trace config pattern in the 2026-07-09 session;
result recorded in `docs/tensor-tpu-spike-plan.md` (T-2 RESULT).
