# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FileTransformer is an Android application (package: `com.nothing.filetransformer`) that enables LAN file transfer between devices. It runs an embedded HTTP server on one device, allowing other devices on the same local network to upload files via a web browser.

## Tech Stack

- **Language:** Kotlin, Java 11 bytecode target
- **Build:** Gradle 9.3.1 with Kotlin DSL version catalogs (`gradle/libs.versions.toml`)
- **Android:** AGP 9.1.1, compileSdk 36 (Android 16 "Baklava", minorApiLevel 1), minSdk 24, targetSdk 36
- **UI:** Material Components (Material 1.13.0) with AppCompat 1.7.1, DayNight theme, ViewBinding
- **HTTP Server:** NanoHTTPd 2.3.1 (embedded, lightweight)
- **Storage:** MediaStore (Downloads) + SAF DocumentFile (custom directories)
- **Preferences:** Jetpack DataStore
- **Concurrency:** Kotlin Coroutines
- **Testing:** JUnit 4.13.2 (unit), AndroidX Test + Espresso 3.7.0 (instrumented)
- **JDK:** 21 (via Foojay toolchain resolver)

## Architecture

Four-layer single-module architecture (`com.nothing.filetransformer`):

```
UI (MainActivity) → Service (ServerForegroundService) → Server (FileTransferServer) → Storage (FileRepository)
```

### Package structure
```
com.nothing.filetransformer
├── MainActivity.kt                    # Single Activity — server controls, IP display, save-location picker
├── FileTransformerApp.kt              # Application subclass — registers notification channel
├── server/
│   ├── FileTransferServer.kt          # NanoHTTPd subclass — GET / (web UI), POST /upload (multipart)
│   └── UploadHandler.kt               # Multipart parsing, filename sanitization, delegates to FileRepository
├── service/
│   ├── ServerForegroundService.kt     # Foreground service — owns server lifecycle, exposes StateFlow<ServerState>
│   └── NotificationHelper.kt          # Notification channel + foreground notification builder
├── network/
│   └── NetworkUtils.kt                # LAN IPv4 detection via NetworkInterface
└── storage/
    ├── FileRepository.kt              # MediaStore.Downloads (default) + SAF DocumentFile (custom dir)
    └── PreferencesManager.kt          # DataStore — save_location_type + custom_tree_uri
```

Static resources: `app/src/main/assets/web/upload.html` — self-contained web upload page (inline CSS/JS, no CDN).

### Key design decisions
- **NanoHTTPd** chosen over Ktor/raw ServerSocket — 50 KiB, zero deps, built-in multipart parsing.
- **Foreground service** (`dataSync` type) — required by Android 8+ to keep the server alive when backgrounded.
- **Scoped storage only** — MediaStore.Downloads (no runtime permission needed on API 29+) + SAF for custom dirs (persistent URI permission via `takePersistableUriPermission`).
- **No DI framework** — manual constructor injection; ~7 source files, Hilt/Dagger would be over-engineering.
- **`usesCleartextTraffic=true`** in the manifest — required for HTTP (not HTTPS) on newer Android versions.

## Commands

```bash
# Build
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (minification disabled)

# Testing
./gradlew testDebugUnitTest      # Unit tests (JVM, no device needed)
./gradlew connectedDebugAndroidTest  # Instrumented tests (requires emulator/device)

# Lint
./gradlew lint                   # Android lint checks

# Install on device
./gradlew installDebug           # Install debug APK on connected device/emulator

# Clean
./gradlew clean
```

## Key Configuration

- **Version catalog:** `gradle/libs.versions.toml` — all dependency and plugin versions. Use `libs.<alias>` syntax in build files.
- **Gradle properties:** `gradle.properties` — JVM args (2048m heap), Kotlin code style set to `official`.
- **ProGuard:** `app/proguard-rules.pro` — all commented out; release builds have minification disabled.
- **Manifest permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS.
