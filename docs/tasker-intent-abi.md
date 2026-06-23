# Tasker / Automate intent ABI

Relais exposes an **exported, API-key-gated** Android intent ABI so automation apps (Tasker,
Automate) and `adb` can send a prompt to the on-device node and capture the response. It complements
the existing `--es cmd start|stop` lifecycle control on `RelaisControlActivity` (see the control
panel) with a structured prompt → response path.

It is implemented by `RelaisTaskerActivity` (a transparent trampoline that authenticates and gates,
then finishes immediately), the `RelaisAutomationService` (a short-lived foreground service that runs
the decode off the activity's lifecycle so a 30–120s inference survives a cross-app launch — issue
#44), and the pure `RelaisIntentAbi` (action + extra-key constants, request parsing, result building).
It is a **consumer** of the already-resident engine — it never cold-starts the node.

## INFER action

Launch the activity with the action:

```
com.ventouxlabs.relais.action.INFER
```

### Input extras

| Extra            | Type             | Required | Default     | Notes |
|------------------|------------------|----------|-------------|-------|
| `prompt`         | String           | **yes**  | —           | The user prompt. Trimmed; capped at **16,000** chars. Missing/blank → request rejected (no inference). |
| `token`          | String           | **yes**  | —           | The node's API key (constant-time compared). Missing/blank → request rejected. **Never echoed in the result.** |
| `system`         | String           | no       | none        | System prompt override. Capped at **8,192** chars. Blank → ignored. Takes precedence over `template_id`. |
| `template_id`    | String           | no       | none        | A Relais prompt-template id whose system prompt is used when `system` is not set. Unknown id → no system prompt. |
| `timeout_ms`     | long *or* String | no       | `60000`     | Decode timeout. **Clamped to `[1000, 120000]` ms.** Tasker can only set Strings, so a String is accepted and parsed (garbage → default). A real `long` extra wins over a String. |
| `result_package` | String           | no       | none        | If set, a RESULT broadcast is sent **targeted to this package** (see below). Blank → ignored. |
| `request_id`     | String           | no       | none        | Opaque correlation id echoed back in the result so you can match a response to its request. |

### Clamps and caps (summary)

- `timeout_ms` is always clamped to **1000–120000 ms** (1s–120s). Out-of-band or non-numeric values
  are corrected; a missing/garbage value falls back to **60000 ms**.
- `prompt` is capped at **16,000** chars, `system` at **8,192** chars (on-device context windows are
  finite).

## RESULT delivery

The outcome is delivered **two ways**:

1. **Activity result** — for callers that use `startActivityForResult`. The result code is
   `RESULT_OK` on success and `RESULT_CANCELED` on any failure/rejection; the result `Intent` carries
   the result extras below.
2. **Targeted RESULT broadcast** — sent **only when `result_package` is set**, with action
   `com.ventouxlabs.relais.action.INFER_RESULT`, **always targeted to that package** via
   `Intent.setPackage(result_package)`. Relais never sends a global/implicit broadcast for the result
   — that would leak the model output to every installed app. If you want the broadcast, set
   `result_package` to your automation app's package.

### Result extras

| Extra        | Type    | When            | Notes |
|--------------|---------|-----------------|-------|
| `ok`         | Boolean | always          | `true` on success, `false` otherwise. |
| `response`   | String  | success         | The model's answer text. |
| `error`      | String  | failure         | One of the error codes below. |
| `request_id` | String  | if you sent one | Echoes the input `request_id` for correlation. |

The result **never** contains the `token` or any other secret.

### Error codes

| `error`               | Meaning |
|-----------------------|---------|
| `unauthorized`        | Missing or wrong `token`. No inference was run. |
| `node_not_running`    | The node/engine is not resident. The ABI never cold-starts it — start the node first. |
| `thermal_backpressure`| The device is shedding under thermal load. Retry later. |
| `busy`                | Another ABI inference is already running (single-flight). Retry. |
| `timeout`             | The decode exceeded `timeout_ms`. |
| `unavailable`         | The OS rejected the foreground-service handoff (e.g. a cross-app launch from a backgrounded app under stricter FGS rules). The request did not run — retry with the launching app foregrounded. |
| `inference_failed`    | The decode threw. An **opaque, stable** code: the internal exception message is never broadcast to a third-party package (it is logged on-device only). |

## Tasker example

**Task → Send Intent**

- **Action:** `com.ventouxlabs.relais.action.INFER`
- **Cat:** `Default`
- **Extras** (one per line, `key:value`):
  - `prompt:Summarize the following: %clip`
  - `token:YOUR_API_KEY` (copy it from the Relais control panel — "ACCESS KEY")
  - `timeout_ms:60000`
  - `result_package:net.dinglisch.android.taskerm`
  - `request_id:%PRAND`
- **Target:** `Activity`

**Capture the result** — add an Intent Received profile (or a paired receiver task):

- **Profile → Event → System → Intent Received**
  - **Action:** `com.ventouxlabs.relais.action.INFER_RESULT`
- In the task, read the extras Tasker exposes from the broadcast:
  - `%ok` → `true`/`false`
  - `%response` → the answer (on success)
  - `%error` → the error code (on failure)
  - `%request_id` → matches what you sent

> The targeted broadcast only reaches your app when you pass `result_package` set to your automation
> app's package (Tasker is `net.dinglisch.android.taskerm`). Without it, use
> `startActivityForResult`-style capture instead.

## adb example

```
adb shell am start -W \
  -a com.ventouxlabs.relais.action.INFER \
  -n cc.grepon.relais/.automation.RelaisTaskerActivity \
  --es prompt "Say hello in one word." \
  --es token "YOUR_API_KEY" \
  --es timeout_ms "30000"
```

The activity is transparent and finishes immediately; the answer is delivered asynchronously via the
result broadcast (the decode runs in the foreground service, not the activity).
(`adb` can't easily read an activity-result, so adb is mainly useful for triggering a run plus a
`result_package` broadcast into a receiver app.)

## Security model

- **API key required.** Every request is gated by a **constant-time** comparison of the `token`
  extra against the node's API key (the same comparison the HTTP `/generate` path uses). A
  missing/wrong token runs **no** inference and returns `unauthorized`.
- **The token is never echoed.** The RESULT intent carries only `ok`, `response`/`error`, and your
  `request_id` — never the token or any secret.
- **RESULT broadcasts are package-targeted, never global.** The broadcast is sent only when you set
  `result_package`, and is always scoped to that package, so the model output is never broadcast to
  other apps.
- **`result_package` is caller-chosen, trusted to the API key.** The node delivers the output to
  whatever package an **authenticated** caller names. Possessing the API key already grants full
  inference, so directing where the answer goes is part of that same trust — the key is the security
  boundary. Pass only a `result_package` you control.
- **Device-safety gates are honored.** The ABI respects thermal shedding (`thermal_backpressure`),
  refuses to cold-start the engine (`node_not_running`), and serializes decodes with a single-flight
  latch (`busy`) — it cannot be used to bypass the protections the HTTP path enforces.
- **No back-stack/recents footprint.** The activity is `noHistory` + `excludeFromRecents`, so a
  prompt never lingers in recents or the back stack.

> On-device validation (rango / Pixel 10 / Tensor G5, gemma-4-E2B). **In-process** (`TaskerIntentProbe`):
> cold→`node_not_running`, bad-token→no broadcast, warm→`ok` delivered with no token echo, and
> single-flight→exactly one `ok` + one `busy` (both delivered). **Cross-app** (fired from a separate
> package/UID into a distinct receiver app): the foreground-service decode runs to completion across a
> cross-app launch — no FGS rejection, no crash, and `/metrics` `…endpoint="/automation",status="200"`
> increments — the targeted RESULT broadcast reaches the other app, an unauthenticated fire elicits no
> broadcast, and no result ever echoes the token. (Capturing a clean cross-app `ok=true` in one line was
> gated by the G5 thermal-headroom shed during the run — the safety gate working as intended; the
> success-delivery path itself is covered in-process.) The pure ABI (parse + result-build) is covered by
> JVM unit tests.
