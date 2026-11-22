# Builds

Release **1.1.0** (versionCode `2`) — Wave 2 feature release.

All release APKs are signed with the project release key
(`signing/onyx-release.jks`; credentials in `local.properties` /
`signing/keystore.properties`). Back that keystore up: updating an installed
Onyx requires the same key, otherwise the user must uninstall and lose data.

## Artifacts

Build everything with:

```bash
./scripts/build-all-releases.sh      # -> builds/release/1.1.0/
```

| File | Device type | Notes |
|---|---|---|
| `Onyx-Music-1.1.0-universal.apk` | any Android device | all four ABIs in one file (largest) |
| `Onyx-Music-1.1.0-arm64-v8a.apk` | modern phones/tablets, Quest 3 | recommended for most devices |
| `Onyx-Music-1.1.0-armeabi-v7a.apk` | older 32-bit phones | |
| `Onyx-Music-1.1.0-x86_64.apk` | emulators, Chromebooks, x86 tablets | |
| `Onyx-Music-1.1.0-x86.apk` | old 32-bit x86 emulators | |
| `Onyx-Music-1.1.0-quest3.apk` | Meta Quest 3 / Horizon OS | resizable 2D panel; same bits as arm64 |
| `Onyx-Music-1.1.0-android7-legacy.apk` | Android 7–9 (`minSdk 24`) | arm64 + armeabi-v7a |
| `Onyx-Music-1.1.0-vivo-origin.apk` | vivo OriginOS | separate package id (`com.tencent.qqmusic`) for the island |

Debug artifacts (`Onyx-Music-debug-arm64.apk` here, built earlier) are
debug-signed and cannot be installed over a release-signed copy — uninstall
the other signature first.

## Toolchain

Gradle 9.3.1, OpenJDK 17, Android compileSdk 37, targetSdk 35,
minSdk 29 (24 for the legacy build), NDK `28.2.13676358`, CMake `3.22.1`.

## Known build constraint

The bundled soxr CMake project passes an unquoted path to
`cmake_dependent_option`, so a checkout under a directory whose path contains
spaces cannot configure its native build. Build from a space-free path (the
release and Quest scripts clone to a temp directory automatically).
