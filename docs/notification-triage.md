# Notification Triage (Feature #7)

On-device triage of the phone's own notification stream by the resident Relais model. Strictly
opt-in, default-deny, and **zero network egress of content** — notification text never leaves the
device, is never sent over the LAN endpoint, and is never written to disk.

## What it does

1. A system `NotificationListenerService` (granted via Android **Notification Access**) observes
   posted notifications.
2. Only notifications from **allowlisted** packages are buffered (the allowlist is empty by default —
   nothing is read until the user adds apps). The node's own notifications, ongoing/foreground-service
   notifications, and group-summary rows are skipped.
3. A near-real-time **urgent pass** classifies new notifications and surfaces anything the model marks
   `URGENT` immediately. This pass is **rate-limited** (`TriageRateLimiter`, one classification per
   cooldown window) so a notification storm produces at most one inference, not one per notification.
4. A **periodic digest** (default every 60 min, user-adjustable 15–1440) summarizes everything still
   buffered into a single grouped, low-importance notification.

Each notification is delivered **once**: an item surfaced by the urgent pass is removed from the
buffer, so the digest does not recap it. The digest is the backstop — it also summarizes any items
that arrived too close to a digest run to be individually classified first. Only one digest runs at a
time (the periodic schedule and the manual "Triage now" share a single in-progress guard).

## Privacy posture

| Guarantee | Mechanism |
|-----------|-----------|
| Opt-in | `TriageConfig.enabled` defaults false; one-time consent dialog before first enable |
| Default-deny | `TriageConfig.allowlist` empty by default; `TriageGate` denies any non-allowlisted package |
| No egress | Inference runs through the in-process `RelaisInference` facade only — never ktor/LAN; metrics are counters with **no content labels** |
| No persistence | `NotificationTriageBuffer` is a bounded in-memory ring; content is dropped on process death and after each digest |
| Lockscreen-safe | Both channels are `VISIBILITY_PRIVATE` |
| Kill switch | Revoking Notification Access in Settings unbinds the service (authoritative). Disabling in-app also cancels the worker and clears the buffer |
| Storm-safe | Inference is never inline; the urgent path is debounced and the digest is periodic, both behind engine-ready + thermal gates |

## Threat model

- **Prompt injection** — notification content is attacker-influenced (any allowlisted app can post
  anything). It is fenced in the prompt as DATA, and the urgency parser only accepts the closed
  vocabulary `URGENT|NORMAL|LOW`. There is no parseable token that triggers a device action, tool
  call, or any egress, so injection's worst case is a mislabeled/misleading digest. Unknown/garbled
  replies fail safe to `NORMAL`.
- **Battery / thermal** — both workers return `Result.retry()` (never dropping records) when the node
  is down or `ThermalGovernor.shouldShed()` is true; the digest worker also requires battery-not-low.
- **Self-feedback** — the node's own package is always excluded from triage.

## Components

- `TriageConfig` — opt-in flags, allowlist, interval (clamped 15–1440 min).
- `TriageGate` — pure admission decision (enabled + allowlisted + not own/ongoing/summary).
- `NotificationTriageBuffer` — bounded in-memory ring; dedupe by key; peek-then-remove-on-success.
- `TriageRateLimiter` — CAS debounce for the urgent path (storm safety).
- `TriagePromptBuilder` — pure prompt construction + closed-vocabulary parsing.
- `RelaisNotificationListenerService` — applies the gate, buffers, kicks the workers.
- `TriageUrgentWorker` / `TriageDigestWorker` — engine-/thermal-gated inference passes.
- `TriageNotifications` — `VISIBILITY_PRIVATE` urgent + grouped-digest notifications.
- `TriageControlActivity` — opt-in toggle, consent, allowlist picker, interval, "Triage now".

## Metrics

`relais_triage_digests_total` (digest runs) and `relais_triage_urgent_total` (items surfaced urgent).
Counters only — no notification content, package names, or other labels enter the metrics surface.
