# Relais

**Turn a spare Android phone into an always-on AI appliance.**

Relais runs an LLM directly on your phone's GPU using Google's
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runtime and serves it
over your LAN as a standard **OpenAI-compatible API** — `/v1/chat/completions`,
streaming SSE, API-key auth, mDNS discovery. Point any OpenAI client at the
phone's IP and it answers. Unlike Ollama, llama.cpp-server, or LM Studio — which
need a desktop and can't reach a phone's neural silicon — Relais is **headless by
design**: no chat UI to babysit, it auto-starts, survives Doze, draws a few
watts, and sits on a shelf as private, cloud-free inference infrastructure.

Spare flagship in, OpenAI endpoint out.

> Relais is a fork of Google's [AI Edge Gallery](https://github.com/google-ai-edge/gallery),
> rebuilt around being a network **service** instead of an app. Licensed under
> **AGPL-3.0** (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).

---

## What it is (and isn't)

- **Is:** a headless LAN inference node. One resident model on the phone GPU,
  exposed as an OpenAI-compatible HTTP API, hardened for always-on operation
  (foreground service, wake lock, Doze survival, crash/OOM auto-recovery,
  thermal backpressure, `/metrics`).
- **Isn't:** the fastest way to run a model (a desktop GPU crushes a phone on
  tokens/sec). Relais's value is *always-on, low-power, private, zero-marginal-cost*
  — small-model and agent/automation workloads, not 70B frontier serving.

**Prior art, honestly:** [OlliteRT](https://github.com/NightMean/OlliteRT) does
the same thing on the same runtime. Relais's bet is execution — Doze reliability,
Pixel/Tensor tuning, a real security posture (TLS on the LAN, encrypted key at
rest), and the control/observability surface.

---

## Requirements

- A device LiteRT-LM supports on GPU. Developed and validated on a **Pixel 9 Pro
  Fold** (Tensor G4), Android 12+ (`minSdk 31`).
- For building: **JDK 17+**, Android Studio with **SDK 35**. No NDK required —
  the inference engine ships as a prebuilt AAR.

## Clean-room bring-up

From a fresh clone to a serving node:

```bash
# 1. Clone
git clone https://github.com/entrevoix/relais.git
cd relais/Android/src

# 2. Point the build at your SDK (or set sdk.dir in Android Studio)
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Build + install the debug APK on a connected device
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone:

1. Open the **Relais Node** launcher icon.
2. Tap **START**. On first run it downloads the default open model
   (`litert-community/gemma-4-E4B-it-litert-lm`, ~3.7 GB — **no HuggingFace token
   needed**) and initializes the resident engine.
3. The panel shows status (`LIVE`), the LAN endpoint (`https://<phone-ip>:8443`),
   and your **access key** (tap to copy).

Reach it from any machine on the LAN:

```bash
curl -k https://<phone-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer <access-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b-it","messages":[{"role":"user","content":"reply one word: ping"}]}'
```

> The LAN endpoint is HTTPS with a self-signed cert (`-k` for now). The plaintext
> `http://127.0.0.1:8080` endpoint is **loopback-only** by design — see
> [SECURITY.md](SECURITY.md). On untrusted networks, put the node behind a
> WireGuard/Tailscale overlay.

Discovery: the node advertises `_relais._tcp` over mDNS, so clients can find it by
name after an IP change.

## Works with

Any OpenAI-compatible client — point its base URL at `https://<phone-ip>:8443/v1`
and set the API key:

- **Open WebUI** — add an OpenAI connection.
- **Editors / agents** — anything that speaks the OpenAI API.
- **curl / scripts** — see above.

These are "works with," not dependencies. Relais does not call the cloud and is
not coupled to any gateway.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | none | `{status, ready, thermal_state}` |
| GET | `/metrics` | key | Prometheus text (or JSON via `Accept: application/json`) |
| POST | `/generate` | key | `{text, image_b64?, audio_b64?}` — native multimodal |
| POST | `/v1/chat/completions` | key | OpenAI-compatible, `stream` supported |

## Operating it as an appliance

- **Auto-start on boot:** opt-in in the control panel (off by default).
- **Unattended / force-stop resilience:** the control panel is excluded from the
  recents switcher, so a "clear all recent apps" sweep won't force-stop the node.
  For Doze survival, the panel shows a **POWER** readout — tap **ALLOW UNRESTRICTED**
  to exempt the node from battery optimization. Caveat: a deliberate Settings →
  *Force stop* (or a cleaner that force-stops every package) can't be auto-recovered
  — Android disables a force-stopped app's alarms and boot-receiver until it's
  launched again, so relaunch the node (or reboot with auto-start on) to recover.
- **Observability:** scrape `https://<phone-ip>:8443/metrics` with Prometheus
  (bearer-token auth). Tokens/sec, latency histogram, thermal state, RSS, restart
  count, queue depth.
- **Thermal:** under sustained load the node sheds with `503 + Retry-After` rather
  than melting; see `relais_thermal_status` / `relais_shed_total`.
- **Endurance:** see [docs/soak](docs/soak) for the soak-test harness.

## Security

See [SECURITY.md](SECURITY.md). Short version: trusted-LAN-safe by default
(HTTPS on the LAN, encrypted key at rest, rate limiting); for untrusted networks
use an overlay or cert pinning.

## License

AGPL-3.0. Relais is a network service, and AGPL's network-use clause keeps
modified, network-served versions open. Files derived from Google AI Edge Gallery
retain their Apache-2.0 headers; the work as a whole is AGPL-3.0. See
[LICENSE](LICENSE) and [NOTICE](NOTICE).
