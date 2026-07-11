#!/usr/bin/env bash
# (Re)generates the committed libLiteRtDispatch_GoogleTensor.so (Tensor G5 TPU dispatcher) under
# app/src/main/jniLibs so Backend.NPU(nativeLibraryDir) can reach the TPU on Pixel 10. The .so is a
# TRACKED vendored binary (it must ship in release APKs); run this only to refresh it after a
# dispatcher version bump, then commit the result.
#
# SOURCE: the google-ai-edge/litert-samples sample_app_tpu tree (pinned commit), which ships the
# dispatcher build that pairs with litertlm 0.12.x. Do NOT use the LiteRT v2.1.1 release zip's
# dispatcher (409,920 B): it SIGABRTs in liblitertlm_jni on Backend.NPU (rango, 2026-07-10) —
# only this newer build (330,960 B) initializes. SHA-256-pinned below.
# See docs/tensor-tpu-spike-plan.md (T-3 RESULT).
set -euo pipefail
cd "$(dirname "$0")/.."

COMMIT="f500335f045414d174908544970126e80bdbc6b4"
URL="https://raw.githubusercontent.com/google-ai-edge/litert-samples/$COMMIT/compiled_model_api/google/sample_app_tpu/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
SHA256="95a0771c9b45f2e74dbf91a45a8ba89de2ce717916bbb5ba936efcedaf4abe68"
DEST="Android/src/app/src/main/jniLibs/arm64-v8a"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading dispatcher (litert-samples @ ${COMMIT:0:12}) ..."
curl -fsSL -o "$tmp/dispatch.so" "$URL"
echo "$SHA256  $tmp/dispatch.so" | sha256sum -c - >/dev/null

mkdir -p "$DEST"
install -m 0644 "$tmp/dispatch.so" "$DEST/libLiteRtDispatch_GoogleTensor.so"
echo "OK: $DEST/libLiteRtDispatch_GoogleTensor.so ($(stat -c%s "$DEST/libLiteRtDispatch_GoogleTensor.so") bytes, sha256-verified)"
