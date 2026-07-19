#!/usr/bin/env python3
"""Throwaway local webhook receiver for the #14 hermetic delivery gate.

Use this only when the device has no internet (so webhook.site is unreachable). It records the incoming
signed POST and verifies the HMAC over the EXACT received bytes — the same bytes WebhookDelivery signed.

Setup (see .claude/PRPs/plans/ondevice-gates-13-14.plan.md Task 4 "ALTERNATIVE"):
  1. Read the node's HMAC secret via a throwaway probe (RelaisConfig.webhookHmacSecret) and export it:
       export RELAIS_WEBHOOK_SECRET=<secret>
  2. Run this receiver on the host:    python3 .claude/scripts/webhook-receiver.py 9999
  3. Map the device loopback to the host:   adb -s <serial> reverse tcp:9999 tcp:9999
  4. Allowlist localhost so the SSRF guard permits the loopback receiver (a probe calling
     RelaisConfig.setWebhookAllowlist(ctx, setOf("localhost"))).
  5. Submit a batch job with  "webhook":"http://localhost:9999/hook"  and watch this log.

NOTE: this exercises signing + SSRF-bypass + delivery plumbing but NOT the TLS/SNI/chain path. Prefer the
public webhook.site receiver (valid cert, public IP, no allowlist) when the device has internet.
"""
import hashlib
import hmac
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ.get("RELAIS_WEBHOOK_SECRET", "").encode()
HEADER = "X-Relais-Signature"  # WebhookSigner.HEADER


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 (http.server API)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        received = self.headers.get(HEADER, "")

        print(f"\n--- POST {self.path} ---")
        for key, value in self.headers.items():
            print(f"  {key}: {value}")
        print(f"  body ({len(body)} bytes): {body.decode('utf-8', 'replace')}")

        if SECRET:
            expected = "sha256=" + hmac.new(SECRET, body, hashlib.sha256).hexdigest()
            ok = hmac.compare_digest(expected, received)
            print(f"  HMAC: {'OK — signature matches' if ok else 'MISMATCH'}")
            if not ok:
                print(f"    expected: {expected}")
                print(f"    received: {received}")
        else:
            print("  HMAC: skipped (set RELAIS_WEBHOOK_SECRET to verify)")

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *_args):  # silence the default per-request stderr line
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    print(f"webhook receiver on 0.0.0.0:{port} (HMAC verify: {'on' if SECRET else 'off'})")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
