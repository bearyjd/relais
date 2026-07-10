#!/usr/bin/env bash
# Fetches libLiteRtDispatch_GoogleTensor.so (Tensor G5 TPU dispatcher) into the app's
# debug-only jniLibs so Backend.NPU(nativeLibraryDir) can reach the TPU on Pixel 10.
# PINNED to LiteRT v2.1.1 — the only public release whose dispatcher exists and matches
# the runtime; 2.1.2–2.1.5 ship none (LiteRT #7787). See docs/tensor-tpu-spike-plan.md.
set -euo pipefail
cd "$(dirname "$0")/.."

ZIP_URL="https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries.zip"
SO_PATH="google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
DEST="Android/src/app/src/debug/jniLibs/arm64-v8a"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading $ZIP_URL ..."
curl -fsSL -o "$tmp/npu.zip" "$ZIP_URL"

mkdir -p "$DEST"
unzip -q -o -j "$tmp/npu.zip" "$SO_PATH" -d "$DEST"

test -s "$DEST/libLiteRtDispatch_GoogleTensor.so"
echo "OK: $DEST/libLiteRtDispatch_GoogleTensor.so ($(stat -c%s "$DEST/libLiteRtDispatch_GoogleTensor.so") bytes)"
