#!/usr/bin/env bash
#
# Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
#
# compile-tensor-g5-model.sh — AOT-compile a HuggingFace Gemma-4 model (base OR a
# "heretic"/abliterated finetune) into a Google-Tensor-G5 `.litertlm` that Relais's
# TPU lane can serve.
#
# WHY THIS EXISTS
#   litert-community publishes G5-AOT builds for E2B and 1B but NOT E4B, and never
#   for uncensored finetunes. This runs the public `litert-torch` AOT toolchain
#   yourself. Verified 2026-07-12 (Relais spike): the `--aot_backend=GOOGLE` path is
#   NOT allowlist-gated, and the toolchain works once you shim a transformers version
#   skew (baked in below). The only hard requirement is RAM.
#
# REQUIREMENTS
#   - Linux, Python 3.11 (torch/litert-torch wheels lag newest Python).
#   - ~64 GB+ RAM DEDICATED. E2B peaked past a 24 GB cap on a shared 62 GB host and
#     swap-thrashed; E4B needs more. Do NOT run this on a memory-loaded workstation.
#   - ~40 GB free disk (HF source download + int4 output).
#   - `uv` (preferred) or `python3.11 -m venv` + pip.
#   - HuggingFace: base google/gemma-4-* and the huihui-ai / llmfan46 heretic repos
#     are UNGATED (no token needed). Gated repos need `huggingface-cli login` first.
#
# USAGE
#   scripts/compile-tensor-g5-model.sh <HF_MODEL_ID> [OUT_DIR] [CACHE_LEN] [QUANT] [LARGE_MODEL]
#
#   HF_MODEL_ID   e.g. google/gemma-4-E4B-it
#                      huihui-ai/Huihui-gemma-4-E4B-it-abliterated   (uncensored)
#                      llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic
#   OUT_DIR       output dir (default: ./tensor-g5-out)
#   CACHE_LEN     KV cache length; becomes the ekv<N> filename marker (default: 1280)
#   QUANT         litert-torch quantization recipe (default: weight_only_wi4_afp32 = int4)
#   LARGE_MODEL   true|false — google_tensor_enable_large_model_support; REQUIRED true
#                 for E4B-class, harmless-to-omit for E2B (default: true)
#
# OUTPUT
#   A file named  <slug>_<quanttag>_ekv<CACHE_LEN>_Google_Tensor_G5.litertlm
#   The `_Google_Tensor_G5` marker is MANDATORY — Relais's RelaisTpuLane keys the TPU
#   lane on it (RelaisTpuLane.kt: isTpuCompiledModel → filename contains "google_tensor").
#   The `ekv<N>` marker must match CACHE_LEN (AOT KV is fixed at compile time).
#
set -euo pipefail

MODEL="${1:?usage: $0 <HF_MODEL_ID> [OUT_DIR] [CACHE_LEN] [QUANT] [LARGE_MODEL]}"
OUT_DIR="${2:-$(pwd)/tensor-g5-out}"
CACHE_LEN="${3:-1280}"
QUANT="${4:-weight_only_wi4_afp32}"
LARGE_MODEL="${5:-true}"
SOC="Tensor_G5"
LITERT_TORCH_VERSION="0.9.1"   # spike-verified; bump deliberately + re-verify

log() { printf '\033[1;33m[compile-g5]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[compile-g5] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---- prereqs ---------------------------------------------------------------
command -v python3.11 >/dev/null 2>&1 || die "python3.11 not found (torch wheels need <=3.12/3.13)."
ram_gb=$(awk '/MemTotal/{printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0)
avail_gb=$(awk '/MemAvailable/{printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0)
log "RAM total=${ram_gb}GB available=${avail_gb}GB"
[ "${avail_gb:-0}" -lt 48 ] && log "WARNING: <48 GB available — E4B compile will likely thrash/OOM. 64 GB+ recommended."

VENV="${VENV_DIR:-$(pwd)/.tensor-g5-venv}"
if [ ! -d "$VENV" ]; then
  log "creating venv at $VENV (litert-torch==$LITERT_TORCH_VERSION)"
  if command -v uv >/dev/null 2>&1; then
    uv venv --python 3.11 "$VENV"
    # shellcheck disable=SC1091
    source "$VENV/bin/activate"
    uv pip install "litert-torch==$LITERT_TORCH_VERSION" hf-transfer
  else
    python3.11 -m venv "$VENV"
    # shellcheck disable=SC1091
    source "$VENV/bin/activate"
    pip install --upgrade pip
    pip install "litert-torch==$LITERT_TORCH_VERSION" hf-transfer
  fi
else
  # shellcheck disable=SC1091
  source "$VENV/bin/activate"
fi

mkdir -p "$OUT_DIR"
export HF_HUB_ENABLE_HF_TRANSFER=1

# ---- shim + export runner --------------------------------------------------
# litert-torch 0.9.1 vs current transformers: the Cache-layer ABC re-requires
# get_max_length, which litert's Gemma4 layer doesn't override (it has the newer
# get_max_cache_shape — same value). Alias them. Semantically identical; verified to
# clear the crash and run the export end-to-end.
SHIM="$(mktemp --suffix=.py)"
trap 'rm -f "$SHIM"' EXIT
cat > "$SHIM" <<'PYEOF'
import sys, json

def _get_max_length(self):
    return self.get_max_cache_shape()

from litert_torch.generative.export_hf.core import cache as core_cache
from litert_torch.generative.export_hf.model_ext.gemma4 import cache as g4

_targets = [core_cache.LiteRTLMCacheLayer] + [
    getattr(g4, n) for n in dir(g4) if isinstance(getattr(g4, n), type)
]
for cls in _targets:
    cur = getattr(cls, "get_max_length", None)
    if cur is None or getattr(cur, "__isabstractmethod__", False):
        cls.get_max_length = _get_max_length
    if getattr(cls, "__abstractmethods__", frozenset()):
        cls.__abstractmethods__ = frozenset(
            m for m in cls.__abstractmethods__ if m != "get_max_length"
        )
print("[shim] get_max_length patched:", [c.__name__ for c in _targets], flush=True)

model, out_dir, cache_len, quant, large = sys.argv[1:6]
argv = [
    "litert-torch", "export_hf", model, out_dir,
    "--externalize_embedder",
    f"--quantization_recipe={quant}",
    f"--cache_length={cache_len}",
    "--aot_backend=GOOGLE",
    "--aot_soc_model=Tensor_G5",
]
if large.lower() == "true":
    argv.append('--aot_compilation_config_dict=' +
                json.dumps({"google_tensor_enable_large_model_support": True}))

import fire
from litert_torch.cli import CLI
sys.argv = argv
fire.Fire(CLI())
PYEOF

log "compiling $MODEL -> $SOC (cache_len=$CACHE_LEN quant=$QUANT large=$LARGE_MODEL)"
log "this is long (source download + load + quantize + AOT); watch RAM."
python "$SHIM" "$MODEL" "$OUT_DIR" "$CACHE_LEN" "$QUANT" "$LARGE_MODEL"

# ---- rename to Relais's TPU-lane convention --------------------------------
produced="$(find "$OUT_DIR" -maxdepth 2 -name '*.litertlm' -newermt '-1 hour' | head -1 || true)"
[ -n "$produced" ] || die "no .litertlm produced — check the log above (OOM? backend error?)."

slug="$(basename "$MODEL" | tr '/:' '__')"
qtag="$(printf '%s' "$QUANT" | grep -oE 'wi[0-9]+' | head -1 || echo q)"
final="$OUT_DIR/${slug}_${qtag}_ekv${CACHE_LEN}_Google_Tensor_G5.litertlm"
mv -f "$produced" "$final"
sz=$(du -h "$final" | cut -f1)
log "DONE -> $final ($sz)"

cat <<EOF

Next: stage on rango (Pixel 10 / Tensor G5) and select it.
  APP=com.ventouxlabs.relais.izzy
  DST=/sdcard/Android/data/\$APP/files/bench
  adb push "$final" "\$DST/$(basename "$final")"
  # then point the node at it (non-default model_id so the provisioner default
  # doesn't override; filename already carries the _Google_Tensor_G5 marker so the
  # TPU lane is selected). Verify: engine log backend=TPU_LITERTLM + TPU power rail active.
EOF
