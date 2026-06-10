# Security policy & threat model

Relais is a network service: it binds a port on a phone and answers LLM requests
over the LAN. This document states what it defends against, what it does not, and
how to report issues.

## Reporting a vulnerability

Email **security@grepon.cc** with details and a proof of concept if you have one.
Please do not open a public issue for an unpatched vulnerability.

## Network posture (default)

- **HTTPS on the LAN, HTTP on loopback.** The node serves the LAN only over TLS
  (`https://<phone-ip>:8443`). The plaintext HTTP listener is bound to
  `127.0.0.1:8080` and is reachable only from the device itself (the in-app
  control panel and on-device tooling). The bearer API key therefore never
  crosses the network in cleartext.
- **Bearer-token auth on everything except `/health`.** A 32-hex-char key is
  generated per install, stored in `EncryptedSharedPreferences` (Keystore-wrapped),
  shown in the Relais Node control screen, and compared in constant time.
- **Per-IP rate limiting** (30 req / 60 s) with bounded, self-evicting state.
- **Body and header caps**, a per-read socket timeout, and a bounded worker pool
  to resist slow-client and oversized-request abuse.
- **Thermal backpressure**: under sustained heat the node returns `503` +
  `Retry-After` instead of running the device into a throttle cliff.
- **No cloud egress for inference.** Inference is fully on-device. (Telemetry is
  being removed; see "Known limitations".)

## What Relais assumes

Relais is designed for a **trusted LAN** (your home or a private VLAN). With the
default posture it is safe to run there. For **untrusted networks** (cafe Wi-Fi,
shared hotspots, guest VLANs) you should additionally use one of:

- a private overlay network (WireGuard / Tailscale) and treat the node as
  reachable only over it; or
- certificate pinning (below) so a man-in-the-middle cannot impersonate the node.

## Known limitations (tracked)

- **Self-signed TLS cert, no pinning yet.** The LAN cert is self-signed
  (`CN=relais-node`); clients currently connect with `curl -k` / verification
  disabled. A man-in-the-middle on the same L2 who also spoofs the mDNS
  advertisement could impersonate the node and harvest the key. **Mitigation in
  progress**: trust-on-first-use pinning with an in-app fingerprint and an
  optional mTLS "hardened" mode. Until then, prefer an overlay network on
  untrusted segments, or pin the cert out-of-band (`--cacert`).
- **mDNS discovery is unauthenticated** (link-local `_relais._tcp`); pair with
  cert pinning before trusting discovery on a shared network.
- **Bundled analytics.** The upstream fork still compiles Firebase Analytics/FCM;
  removal is tracked for the OSS release so the "no cloud" property holds for the
  whole app, not just inference.
- **Upgrade-in-place key remnant.** Upgrading from a pre-hardening build that stored
  the API key in plaintext may leave a recoverable remnant in on-disk SharedPreferences
  until it is overwritten. Fresh installs are unaffected. A key-rotation action is
  tracked; until then, a clean reinstall clears any remnant.

## Reproducing the hardening checks

`./gradlew :app:assembleDebug` builds the app; the `secret-scan` and
`license-lint` GitHub workflows gate secrets and license-header integrity on
every push.
