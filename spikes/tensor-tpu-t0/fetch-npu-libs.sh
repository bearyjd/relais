#!/usr/bin/env bash
# Fetches the Google Tensor NPU dispatcher out of the LiteRT v2.1.1 release zip into jniLibs.
# PINNED to v2.1.1 — the only public release whose dispatcher exists and matches the runtime;
# 2.1.2–2.1.5 ship no dispatcher (LiteRT #7787). See docs/tensor-tpu-spike-plan.md (2026-07-09).
set -euo pipefail
cd "$(dirname "$0")"

ZIP_URL="https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries.zip"
SO_PATH="google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
DEST="app/src/main/jniLibs/arm64-v8a"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading $ZIP_URL ..."
curl -fsSL -o "$tmp/npu.zip" "$ZIP_URL"

mkdir -p "$DEST"
unzip -q -o -j "$tmp/npu.zip" "$SO_PATH" -d "$DEST"

test -s "$DEST/libLiteRtDispatch_GoogleTensor.so"
echo "OK: $DEST/libLiteRtDispatch_GoogleTensor.so ($(stat -c%s "$DEST/libLiteRtDispatch_GoogleTensor.so") bytes)"
