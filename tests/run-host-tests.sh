#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

sources=(
  src/com/xiaopeng/qa/camera/CameraLifecycle.java
  src/com/xiaopeng/qa/camera/RecoveryBudget.java
  src/com/xiaopeng/qa/camera/RelayLifecycle.java
  src/com/xiaopeng/qa/camera/EncoderRetirement.java
)
mapfile -t tests < <(find tests/com -name '*Test.java' -type f | sort)
if (( ${#tests[@]} == 0 )); then
  echo "No host tests found" >&2
  exit 1
fi
if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
else
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -source 8 -target 8 -d "$tmp" \
  "${sources[@]}" "${tests[@]}"
for source in "${tests[@]}"; do
  class=${source#tests/}
  class=${class%.java}
  java -ea -cp "$tmp" "${class//\//.}"
done
echo "PASS: ${#tests[@]} host test programs. Android/Camera2/EGL integration is not exercised."
