<p align="center">
  <img src="docs/icon/app-icon.png" alt="Burn-in Tool app icon" width="144">
</p>

<h1 align="center">Burn-in Tool</h1>

<p align="center">
  An Android headphone burn-in tool · helps a new headphone's diaphragm settle faster with scientifically designed sound signals · fully offline, no account, free and open source
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg" alt="Requires Android 8.0 or later">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg" alt="Kotlin + Jetpack Compose">
</p>

<p align="center"><a href="README.md">简体中文</a> · <b>English</b></p>

---

## Features

### Two burn-in routes

- **Plan burn-in**: Standard 4-phase · 120 hours (white noise warm-up → pink noise activation → pink noise steady endurance → white/pink noise rotating every 30 minutes), or a custom 4-phase plan (total 24–240 hours, automatically split across the four phases at a 10/10/60/20 ratio).
- **Free burn-in**: pick any sound source, with 2/8/16/24/48/72 hour presets or a custom 1–999 hours.
- Start / pause / resume / stop; an unfinished plan session keeps a checkpoint, so you can resume from where you left off.

### Sound sources

- 7 synthesized sources: white noise, pink noise, 300 Hz sine wave, 150 Hz square wave, low-frequency sweep (100–200 Hz), wide sweep (100 Hz–10 kHz), mixed burn (white + pink noise).
- Custom local music: import audio files through the system file picker, play them on a loop, and remove them whenever you like.

### Progress tracking

- A summary of cumulative burn-in time and session count.
- A paged history list (20 per page, loading more as you scroll) showing each session's plan, planned duration and actual progress.
- Clear all history in one tap (with a confirmation dialog, unrecoverable).

### Background and foreground playback

- A foreground service keeps playback alive, and a persistent notification shows a remaining-time countdown with pause / resume / stop actions.
- Audio focus handling: playback resumes automatically after a brief interruption such as an incoming call, and pauses immediately when the headphones are unplugged.
- Progress is persisted every 60 seconds, so reopening the app after exiting restores the in-progress screen; a "Burn-in complete" system notification is posted when the planned duration is reached.

### Screen and appearance

- **Keep screen on**: the playback screen stays awake.
- **Anti screen-off**: the screen stays awake while playing and drops to minimum brightness once the system screen-off timeout passes, so that background playback is not interrupted on devices that stop it when the screen turns off.
- Light and dark themes: follow system / force light / force dark.
- Android 12+ dynamic color, plus 6 preset palettes.
- Simplified Chinese / English: detected from the system language by default, and switchable manually in Settings with immediate effect.

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- Room (progress persistence) + DataStore (preferences)
- Foreground service + notification media controls
- Android 8.0 minimum (minSdk 26), target/compileSdk 36, JVM target 17

## Building

Requirements: JDK 17 and the Android SDK (API 36).

1. **JDK 17**: read from the `JAVA_HOME` environment variable by default; you can also point at it from the project root's `gradle.properties`:
   ```properties
   org.gradle.java.home=path/to/jdk-17
   ```
2. **Android SDK**: write it into the project root's `local.properties` (already covered by .gitignore):
   ```properties
   sdk.dir=path/to/android-sdk
   ```
   Opening the project in Android Studio generates this file for you.
3. Build:
   ```bash
   ./gradlew assembleDebug        # Debug build (one APK each for armeabi-v7a / arm64-v8a)
   ./gradlew assembleRelease      # Release build (minify + resource shrinking, split by ABI)
   ./gradlew :app:testDebugUnitTest  # Unit tests
   ```
4. **Release signing (optional)**: place `key.properties` and your keystore in the project root:
   ```properties
   storePassword=...
   keyPassword=...
   keyAlias=...
   storeFile=path/to/keystore.jks
   ```
   The build does not fail when `key.properties` is missing; the release is simply produced unsigned.

> The repo lists Aliyun Maven mirrors ahead of the official ones in `settings.gradle.kts` to smooth over network flakiness in mainland China; when a mirror is unreachable it falls back to the official google / mavenCentral repositories.

## Build artifacts

APKs are split by ABI (`armeabi-v7a` / `arm64-v8a`, no universal APK) and named:

```
open-burnin-tool-v<version>-<abi>-<debug|release>.apk
```

for example `open-burnin-tool-v1.5.0-arm64-v8a-release.apk` and `open-burnin-tool-v1.5.0-armeabi-v7a-debug.apk`.

## Project layout

```
app/src/main/java/com/github/gbandszxc/obt/
├── data/       # Room persistence, repositories and settings
├── domain/     # Burn-in plans and progress logic
├── playback/   # Foreground service, synthesized-source player, playback control
└── ui/         # Compose screens (Burn-in / History / Settings / theme)
docs/           # Product definition (PRODUCT.md) and design system (DESIGN.md)
```

## License

[MIT](LICENSE)
