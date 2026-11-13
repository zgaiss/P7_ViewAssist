#!/usr/bin/env bash
# Compile resources and Java against a real installed Android SDK. No signing,
# installation, vehicle access, or distributable application APK is performed.
set -euo pipefail
cd "$(dirname "$0")/.."

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" || ! -d "$sdk" ]]; then
  echo "NOT RUN: set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK." >&2
  exit 2
fi
android_jar="${ANDROID_JAR:-$sdk/platforms/android-34/android.jar}"
if [[ ! -f "$android_jar" ]]; then
  echo "NOT RUN: Android API 34 platform jar is absent: $android_jar" >&2
  exit 2
fi
aapt="${AAPT2:-}"
if [[ -z "$aapt" ]]; then
  aapt=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -1)
fi
if [[ -z "$aapt" || ! -x "$aapt" ]]; then
  echo "NOT RUN: install Android SDK build-tools or set AAPT2." >&2
  exit 2
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/generated" "$tmp/classes"
"$aapt" compile --dir res -o "$tmp/resources.zip"
"$aapt" link -o "$tmp/resources.ap_" --manifest AndroidManifest.xml \
  -I "$android_jar" --java "$tmp/generated" "$tmp/resources.zip"
find src "$tmp/generated" -name '*.java' -type f | sort > "$tmp/sources"
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$android_jar" \
    -d "$tmp/classes" @"$tmp/sources"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 \
    -source 8 -target 8 -bootclasspath "$android_jar" -d "$tmp/classes" @"$tmp/sources"
fi
echo "PASS: Android resources and Java API compilation. No device/GL runtime validation."
