<p align="center">
  <img src="docs/icon/app-icon.png" alt="Burn-in Tool app icon" width="144">
</p>

<h1 align="center">Burn-in Tool</h1>

<p align="center">
  An Android headphone burn-in tool. Play noise, frequency sweeps or your own music to help a new headphone's diaphragm settle faster.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg" alt="Requires Android 8.0 or later">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg" alt="Kotlin + Jetpack Compose">
</p>

<p align="center"><a href="README.md">简体中文</a> · <b>English</b></p>

---

## Screenshots

| Plan burn-in | Free burn-in |
| :---: | :---: |
| <img src="docs/screenshots/home_plan_en.jpg" width="320" alt="Burn-in Tool UI · English · Plan burn-in"> | <img src="docs/screenshots/home_free_en.jpg" width="320" alt="Burn-in Tool UI · English · Free burn-in"> |

<p align="center"><sub>Top row: English UI · Bottom row: Simplified Chinese UI. Left column: Plan burn-in · Right column: Free burn-in.</sub></p>

## Features and manual

Burn-in Tool offers two routes: plan burn-in and free burn-in. Plan burn-in walks a fixed four-phase sequence for a long session; free burn-in lets you choose the source and duration yourself and stop at any time. Both can play the built-in synthesized sources or music you import from your device. Progress is recorded as you go, and playback continues when the app is in the background.

For how each feature works, its settings and answers to common questions, see the user manual: [docs/MANUAL_EN.md](docs/MANUAL_EN.md). 

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- Room (progress persistence) + DataStore (preferences)
- Foreground service + notification media controls
- Android 8.0 minimum (minSdk 26), target/compileSdk 36, JVM target 17

## Building

Requirements: JDK 17 and the Android SDK (API 36).

1. JDK 17 is read from the `JAVA_HOME` environment variable by default; you can also point at it from the project root's `gradle.properties`:
   ```properties
   org.gradle.java.home=path/to/jdk-17
   ```
2. The Android SDK path goes in the project root's `local.properties` (already covered by .gitignore):
   ```properties
   sdk.dir=path/to/android-sdk
   ```
   Opening the project in Android Studio generates this file for you.
3. Build:
   ```bash
   ./gradlew assembleDebug        # Debug build (one APK each for armeabi-v7a and arm64-v8a)
   ./gradlew assembleRelease      # Release build (minify + resource shrinking, split by ABI)
   ./gradlew :app:testDebugUnitTest  # Unit tests
   ```
4. Release signing is optional. Place `key.properties` and your keystore in the project root:
   ```properties
   storePassword=...
   keyPassword=...
   keyAlias=...
   storeFile=path/to/keystore.jks
   ```
   The build does not fail when `key.properties` is missing; the release is simply produced unsigned.

The repo lists Aliyun Maven mirrors ahead of the official ones in `settings.gradle.kts` to smooth over network flakiness in mainland China. The mirrors are only prepended for local builds; CI uses the official repositories directly. When a local mirror is unreachable it still falls back to the official google and mavenCentral repositories.

## Build artifacts and releases

APKs are split by ABI into `armeabi-v7a` and `arm64-v8a` builds; there is no universal APK. Output files are named:

```
open-burnin-tool-v<version>-<abi>-<debug|release>.apk
```

for example `open-burnin-tool-v1.5.0-arm64-v8a-release.apk` and `open-burnin-tool-v1.5.0-armeabi-v7a-debug.apk`.

Releases are published manually; there is no automated release workflow:

1. Bump `appVersionName` at the top of `app/build.gradle.kts` and the `versionCode` in `defaultConfig`;
2. run `./gradlew assembleRelease` (artifacts land in `app/build/outputs/apk/release/`);
3. create a `v<version>` GitHub Release named after `appVersionName` and upload both ABI release APKs as its assets.

In-app updates fetch the public GitHub Release pages anonymously, with no GitHub API and no token, and download from those release assets, matched by ABI. A release must therefore satisfy the following:

- The release tag must equal `appVersionName`. The app matches on `-v<version>-` in the asset name; a mismatch finds nothing and reports "Version x.y.z is available, but no package matches this device's architecture."
- The asset name must stay `open-burnin-tool-v<version>-<abi>-release.apk`. Matching also depends on the `-release` and ABI segments.
- `versionCode` must be incremented, or the system installer refuses to overwrite the installed version.
- Build with the same signing key as the installed app, or the installer reports a signature conflict.

The existing CI workflow `.github/workflows/build-apk.yml` (triggered by pushes to main) only uploads an Actions artifact and does not create a Release.

## Project layout

```
app/src/main/java/com/github/gbandszxc/obt/
├── data/       # Room persistence, repositories and settings
├── domain/     # Burn-in plans and progress logic
├── locale/     # In-app language (process-wide locale state and Context wrapping, AppLocale.kt)
├── playback/   # Foreground service, synthesized-source player, playback control
├── update/     # In-app updates (check, download, install)
└── ui/         # Compose screens (Burn-in, History, Settings, theme; update dialogs in ui/update/)
docs/           # Product definition, design system and user manual (PRODUCT.md, DESIGN.md, MANUAL.md)
docs/screenshots/  # README screenshots
```

## License

[MIT](LICENSE)
