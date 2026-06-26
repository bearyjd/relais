# Relais — Operations Runbook

Operating a **running** Relais node. Build & config → [`../DEVELOPMENT.md`](../DEVELOPMENT.md).
Architecture → [`CODEMAPS/`](CODEMAPS/). Per-endpoint API references → the `*-api.md` files in this folder.

## Deploy / install
1. Pick a channel/variant (see the flavor table in `DEVELOPMENT.md`). Sideload the APK or
   `./gradlew :app:install<Variant>Debug`.
2. Provision a model — default `gemma-4-E4B-it`; **Tensor G5 must use E2B** (see Known issues). Either
   download in-app, or stage files directly into the app's external files dir:
   `/storage/emulated/0/Android/data/<appId>/files/…` (the provisioner adopts a staged model on start).
3. Set the API key + start the node (control ABI below, or the Quick Settings tile / control activity).

## Control ABI (headless start/stop)
```
adb -s <serial> shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity \
  --es cmd start --es token <api-key>
```
- `<appId>` follows the channel: `com.ventouxlabs.relais` (Play) / `.izzy` (IzzyOnDroid) / `.degoogled` (GrapheneOS/GitHub). The activity class FQN stays in the `cc.grepon.relais` namespace.
- Stop: `--es cmd start` → `--es cmd stop`. Optional `--es modelId <allowlistId>` to switch model.
- The API key lives in `EncryptedSharedPreferences` — read it off the control-panel "ACCESS KEY" field.

## Health & monitoring
- `GET /health` (no auth) → `{status, ready, thermal_state}`.
- `GET /metrics` → Prometheus text (or JSON via `Accept: application/json`). Dashboards/alerts:
  [`relais-grafana-dashboard.json`](relais-grafana-dashboard.json), [`relais-alerts.md`](relais-alerts.md).
- Reach a node: `adb -s <serial> forward tcp:8443 tcp:8443` → `curl -k https://localhost:8443/health`.
- LAN discovery: mDNS `_relais._tcp`; HTTPS `0.0.0.0:8443` (bearer), loopback HTTP `127.0.0.1:8080`.

## Common issues
| Symptom | Cause | Action |
|---|---|---|
| First-inference SIGSEGV on **Tensor G5** with E4B | upstream LiteRT-LM #2566 (G5-specific) | Pin **E2B** on G5 (the default is already gated); don't re-file |
| `503` + `Retry-After` | thermal shed or model still provisioning | Back off; let the device cool; check `/health` `ready` |
| `429` + `Retry-After` | admission queue full (cap 16) | Retry after the header delay |
| `POST /v1/images/generations` → `501` | image-gen backend unregistered (#16) | Expected; works on G3 (~5 min cold), **deadlocks on G5** — don't attempt on G5 |
| `401 unauthorized` | missing/wrong bearer token | Pass the node's API key (constant-time compared) |
| Node won't start after a dev branch switch | stale Hilt codegen | `./gradlew :app:clean` |
| CI build fails on GMS / permission gate | `degoogled` dexed a GMS class, or `playsafe` kept a restricted perm | Re-check the offending flavor's deps / `src/playsafe/AndroidManifest.xml` |

## Rollback
- **App:** sideload the prior APK for the **same channel** (same `applicationId` = in-place update).
  Different channels have different `applicationId`s and install side-by-side.
- **Database:** Room `relais.db` is at **v4** with **additive, non-destructive** migrations and **no
  down-migrations** — to roll back to an older schema you must uninstall (clears staged models too).

## Escalation
- Native inference crashes/hangs → upstream **LiteRT-LM** issues (G5 E4B = #2566, open).
- Production node = a separate device from the destructive-test spares; do destructive/model-swap work on a spare.
