#!/usr/bin/env bash
# Dump the full public API of the bundled LiteRT-LM AAR by decompiling its classes.jar.
#
# Use this to (re)generate the inventory in docs/litertlm-native-api.md after a litertlm
# version bump, or whenever you need ground truth instead of trusting a plan's assumptions.
# "Native-API-first": the docs claim is only as current as the last run of this script.
#
# Usage:
#   scripts/dump-litertlm-api.sh                # auto-detect pinned version from libs.versions.toml
#   scripts/dump-litertlm-api.sh 0.11.0         # explicit version
#   scripts/dump-litertlm-api.sh 0.11.0 ToolKt  # one class (substring match), with -c bytecode
#
# Requires: a JDK `javap` on PATH, and the AAR present in the Gradle cache (build once if not).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOML="$REPO_ROOT/Android/src/gradle/libs.versions.toml"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  # Pull the litertlm version line from the version catalog (e.g. `litertlm = "0.11.0"`).
  VERSION="$(grep -iE 'litertlm[^=]*=\s*"' "$TOML" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
fi
[[ -n "$VERSION" ]] || { echo "Could not detect litertlm version; pass it explicitly (e.g. 0.11.0)" >&2; exit 1; }
CLASS_FILTER="${2:-}"

AAR="$(find "$HOME/.gradle/caches/modules-2" -path "*litertlm-android/$VERSION/*litertlm-android-$VERSION.aar" 2>/dev/null | head -1)"
[[ -n "$AAR" ]] || { echo "AAR for litertlm $VERSION not found in the Gradle cache. Build the app once, then retry." >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
( cd "$WORK" && unzip -oq "$AAR" classes.jar && unzip -oq classes.jar 'com/google/ai/edge/litertlm/*' )

echo "# litertlm $VERSION — $AAR"
echo
cd "$WORK"
if [[ -n "$CLASS_FILTER" ]]; then
  # Detailed (bytecode) dump for a single class — useful for understanding dispatch/native calls.
  for c in com/google/ai/edge/litertlm/*"$CLASS_FILTER"*.class; do
    echo "===== ${c%.class} ====="; javap -c -p "$c"
  done
else
  # Curated public-signature dump of every class.
  for c in $(ls com/google/ai/edge/litertlm/*.class | sort); do
    echo "===== ${c%.class} ====="
    javap -p "$c" | grep -vE 'kotlin\.jvm\.internal|DefaultConstructorMarker|\$VALUES|\$ENTRIES|access\$|Intrinsics|static \{\}|^\s*private|component[0-9]+\(\)|copy\$default'
    echo
  done
fi
