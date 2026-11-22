#!/usr/bin/env bash
# Builds the full release matrix for every device type Onyx supports.
#
#   ./scripts/build-all-releases.sh
#
# Output: builds/release/<version>/ containing
#   universal (all ABIs), arm64-v8a, armeabi-v7a, x86_64, x86,
#   a Quest 3 copy, an Android 7–9 legacy build (minSdk 24) and the
#   vivo OriginOS build (com.tencent.qqmusic package).
#
# Requires the release keystore path + passwords in local.properties or the
# RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
# RELEASE_KEY_PASSWORD environment variables (see signing/keystore.properties).
#
# NOTE: soxr's CMake passes an unquoted path to cmake_dependent_option, so a
# checkout whose path contains spaces cannot configure. When that happens this
# script clones into a space-free temp directory and builds there.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="$(pwd)"
VERSION="$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
OUT_DIR="builds/release/$VERSION"
mkdir -p "$OUT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-C:/Android/Sdk}"
JV="$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | head -1 || true)"
if [ -n "${JV:-}" ]; then export JAVA_HOME="$JV"; fi

WORK="$SRC"
if [[ "$SRC" == *" "* ]]; then
  WORK="${TMPDIR:-/tmp}/onyx-release-build"
  echo "→ path has spaces; building from $WORK"
  rm -rf "$WORK"
  git clone -q "$SRC" "$WORK"
  cp "$SRC/local.properties" "$WORK/local.properties" 2>/dev/null || true
fi
cd "$WORK"

FAILED=()
build() {
  local name="$1"; shift
  echo
  echo "════════ $name ════════"
  # --no-configuration-cache: the signing key is read from local.properties at
  # configuration time, which the configuration cache does not track. A cached
  # entry stored while the key was absent would otherwise keep rejecting every
  # release build with "No release signing key found".
  if ./gradlew :app:assembleRelease --no-daemon --no-configuration-cache "$@" >/tmp/onyx-build-$name.log 2>&1; then
    local apk
    apk="$(find app/build/outputs/apk -name '*release*.apk' -newermt '-10 minutes' | head -1)"
    if [ -z "$apk" ]; then apk="$(find app/build/intermediates/apk -name '*release*.apk' | head -1)"; fi
    if [ -n "$apk" ] && [ -f "$apk" ]; then
      cp "$apk" "$OUT_DIR/Onyx-Music-$VERSION-$name.apk"
      echo "✓ $name → $(du -h "$OUT_DIR/Onyx-Music-$VERSION-$name.apk" | cut -f1)"
    else
      echo "✗ $name: APK not found"; FAILED+=("$name")
    fi
  else
    echo "✗ $name failed (see /tmp/onyx-build-$name.log)"
    tail -5 /tmp/onyx-build-$name.log
    FAILED+=("$name")
  fi
}

build "universal"
build "arm64-v8a"     -PtargetAbis=arm64-v8a
build "armeabi-v7a"   -PtargetAbis=armeabi-v7a
build "x86_64"        -PtargetAbis=x86_64
build "x86"           -PtargetAbis=x86
build "android7-legacy" -PminSdk=24 -PtargetAbis=arm64-v8a,armeabi-v7a
build "vivo-origin"   -PtargetAbis=arm64-v8a -PvivoOrigin=true

# Quest 3 ships the arm64 build: same bits, named for the headset.
if [ -f "$OUT_DIR/Onyx-Music-$VERSION-arm64-v8a.apk" ]; then
  cp "$OUT_DIR/Onyx-Music-$VERSION-arm64-v8a.apk" "$OUT_DIR/Onyx-Music-$VERSION-quest3.apk"
fi

echo
echo "════════ DONE ════════"
ls -lh "$OUT_DIR"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "FAILED: ${FAILED[*]}"
  exit 1
fi
