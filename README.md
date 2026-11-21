# Onyx Music

**Onyx Music** (`com.leothefleo49.onyxmusic`) is a high-craft audiophile Android music player built with Kotlin, Jetpack Compose, Media3/ExoPlayer, C++20/Oboe native audio DSP, and Liquid Glass shaders.

Onyx Music is derived from [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) and preserves full GNU General Public License v3.0 (GPL-3.0) licensing, commit history, and upstream attribution (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)).

## Core Highlights

- **Audiophile Audio Engine:** 24-bit Hi-Res Lossless FLAC addon support, Bit-Perfect USB DAC output, 15-band Parametric EQ + AutoEQ headphone presets, ReplayGain/LUFS normalization, DSD/soxr HQ resampling, and tempo/pitch control.
- **Visual Craft & Lyrics:** Material 3 Expressive + Liquid Glass UI with granular per-surface glass controls, Apple Music-style word-by-word synced lyrics, romanization/translation, Spotify Canvas & animated backgrounds, and custom/layered wave progress bars.
- **3-Tier Automix & Manual DJ Remixing:** Classic Automix, improved beat/loudness-matched Automix, manual per-transition DJ controls, and curated deep transition mix building.
- **Unified Multi-Source Library & Discovery:** YouTube Music sync, bulk Spotify import, expanded streaming & self-hosted connections, unified library with optional source filters/badges, and upgraded YouTube Music-grade search.
- **Spotify Silent Connect Mirroring & Built-In Scrobbler/Stats Hub:** Silent Spotify Connect mirroring for Wrapped accuracy, system-wide scrobbling + Last.fm/ListenBrainz sync, and a stats.fm-style listening insights dashboard.
- **Multi-Device Cloud Sync & In-App Auto-Updater:** Cross-device library/settings sync and self-contained GitHub Releases in-app updates.

## Build Requirements

- **JDK:** JDK 17 (for unit tests via Gradle Java toolchain) / JDK 21 (Gradle daemon)
- **Android SDK:** `platforms;android-37.0` (`compileSdk = 37`), `targetSdk = 35`, `minSdk = 29` (or `-PminSdk=24` for legacy builds)
- **Build Tools:** `build-tools;37.0.0` (and `35.0.0`)
- **Native Toolchain:** Android NDK `27.3.13750724` (or compatible NDK r27+) and CMake `3.22.1`
- **Local Build:**
  ```bash
  ./gradlew :app:assembleDebug
  ```

## License & Attribution

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Special thanks to [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) and all upstream open-source contributors whose foundation makes Onyx Music possible.
