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
#
# NOTE: the bundled soxr CMake project passes an unquoted path to
# cmake_dependent_option, so a checkout whose path contains spaces cannot
# configure. This script builds a space-free clone in a temp directory and
# copies the APK back, so the normal checkout location can be used as-is.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="$(pwd)"

export ANDROID_HOME="${ANDROID_HOME:-C:/Android/Sdk}"
JDK17="$(ls -d "/c/Program Files/Microsoft/jdk-17"* 2>/dev/null | head -1 || true)"
[ -z "$JDK17" ] && JDK17="$(ls -d "$HOME"/.jdks/jdk-17* 2>/dev/null | head -1 || true)"
if [ -n "${JDK17:-}" ]; then
  export JAVA_HOME="$JDK17"
  TOOLCHAIN_ARGS="-Porg.gradle.java.installations.paths=$JDK17"
else
  JV="$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | head -1 || true)"
  [ -n "${JV:-}" ] && export JAVA_HOME="$JV"
  TOOLCHAIN_ARGS=""
fi

VERSION="$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
mkdir -p builds
OUT="$SRC/builds/Onyx-Quest3-${VERSION}.apk"

if [[ "$SRC" == *" "* ]]; then
  echo "Path contains spaces; building from a space-free clone."
  BUILD_DIR="${TMPDIR:-/tmp}/onyx-quest-build"
  rm -rf "$BUILD_DIR"
  git clone -q "$SRC" "$BUILD_DIR"
  # No local.properties needed: AGP falls back to ANDROID_HOME.
fi

BUILD_DIR="${BUILD_DIR:-$SRC}"
cd "$BUILD_DIR"
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a --no-daemon

APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
cp "$APK" "$OUT"

echo
echo "Quest 3 APK: $OUT"
echo "Install with: adb install -r \"$OUT\""
echo "Or copy it to the headset and open it from Files."
