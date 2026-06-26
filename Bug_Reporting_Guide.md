# Reporting a Relais Bug

Relais is a headless on-device LLM node, so the most useful diagnostics are **node-specific** (which
SoC, which model, the node's own logs) on top of a standard Android bug report. Please include the
"Essentials" below in every issue.

> **Security issues:** do **not** file publicly. Follow [`SECURITY.md`](SECURITY.md).
> **Always redact your API key** before sharing logs or screenshots — it appears on the control panel
> and in `/v1/clientconfig` output.

## Essentials (put these in the issue)

- **Device + SoC** — model and Tensor generation matters a lot (e.g. Pixel 10 / **Tensor G5** has a
  known E4B first-inference crash). Find it under Settings → About phone.
- **OS** — Android version / build (GrapheneOS vs stock).
- **Build channel / variant** — which APK: `com.ventouxlabs.relais` (Play), `.izzy` (IzzyOnDroid), or
  `.degoogled` (GrapheneOS/GitHub). `adb shell pm list packages | grep ventouxlabs` if unsure.
- **Model in use** — e.g. `gemma-4-E4B-it` / `E2B`, and whether it was downloaded or staged.
- **What happened** — exact request (curl/SDK), expected vs actual, repro steps.

## Node diagnostics

```bash
# Health + readiness + thermal (no auth)
adb -s <serial> forward tcp:8443 tcp:8443
curl -k https://localhost:8443/health

# Metrics snapshot (Prometheus text, bearer-gated)
curl -k -H "Authorization: Bearer <key>" https://localhost:8443/metrics
```

### Logs (the most useful artifact)

Capture logcat while reproducing. All node tags start with `Relais`:

```bash
adb -s <serial> logcat -c          # clear, then reproduce the bug
adb -s <serial> logcat -d | grep -iE "relais|litert" > relais-log.txt
```

Key tags: `RelaisNodeService`, `RelaisModelProvisioner`, `RelaisEngine`, `RelaisWatchdog`,
`BatchWorker`. Native inference crashes show as a `SIGSEGV` in `liblitert*`/`libsdcpp` frames — include
the full native stack.

## Full Android bug report (optional, for hard cases)

On-device: Settings → Developer options → **Take bug report** → **Full report**, then share the `.zip`.

Via adb:
```bash
adb devices                                  # get the serial
adb -s <serial> bugreport ./relais-bugreport # writes a .zip
```
The `bugreport-*.txt` inside holds the full logcat + `dumpsys`. **Scrub the API key** before attaching.

## Where to file

- **Relais issues** → this repository's GitHub issues, with the Essentials above.
- **Native inference crashes/hangs** (litertlm `SIGSEGV`, GPU deadlock) are often **upstream** — check
  the [`google-ai-edge/LiteRT-LM`](https://github.com/google-ai-edge/LiteRT-LM) issues first (the G5
  E4B crash is tracked as LiteRT-LM #2566).
