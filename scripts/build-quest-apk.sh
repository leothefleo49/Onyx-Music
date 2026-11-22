#!/usr/bin/env bash
# Builds a sideloadable APK for Meta Quest 3 (Horizon OS 2D panel).
#
#   ./scripts/build-quest-apk.sh
#
# Produces builds/Onyx-Quest3-<version>.apk, signed with the debug key so it
# installs without a Play/Store listing:
#   adb install -r builds/Onyx-Quest3-<version>.apk
# On the headset, open the APK from Files (or install over adb) and launch it
# from "Unknown Sources" / App Library. The panel is resizable and landscape
# aware; controllers act as a pointer.
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-C:/Android/Sdk}"
JV="$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | head -1 || true)"
if [ -n "${JV:-}" ]; then export JAVA_HOME="$JV"; fi

VERSION="$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
OUT="builds/Onyx-Quest3-${VERSION}.apk"

./gradlew :app:assembleDebug --no-daemon
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
mkdir -p builds
cp "$APK" "$OUT"

echo
echo "Quest 3 APK: $OUT"
echo "Install with: adb install -r \"$OUT\""
echo "Or copy it to the headset and open it from Files."
