# Developing on Windows

Open the project root (the folder that contains `settings.gradle.kts`) in Android Studio or VS Code.

You need a JDK 17 or newer (the project builds with JDK 21 and runs on 24), the Android SDK with Platform 36, and Gradle, which comes with the wrapper (8.14.3). Android Studio writes `local.properties` with your SDK path; it is ignored by git.

## Command line

```powershell
# Core tests only. No Android SDK is needed.
.\gradlew.bat -PcoreOnly :core:test

# Build the debug APK, then run the on-device tests with an emulator or phone connected.
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest

# Static checks.
.\gradlew.bat :app:lintDebug
```

The first run downloads Gradle and the Maven dependencies, so it needs a network.

## Android Studio

Run the `app` configuration on an emulator, for example Medium Phone API 37.0. To see the Compose previews, open `app/src/debug/java/com/allowance/app/ui/AllowancePreviews.kt` and use Split or Design (you may need Build & Refresh the first time). Use the emulator for the full app.

## Emulator shows "offline" or "already running"

Stop the virtual phone in Device Manager and start it again with Cold Boot. Choose Wipe Data only if you really want to erase that device's data.

## Gradle cache errors on Windows

Gradle 8.x can fail on Windows with "Could not move temporary workspace". `scripts/build.ps1 -RepairWindowsCache` retries by copying the already finished project-local build cache; it changes no system security setting. `scripts/dev.ps1` has helper actions for building and running on an emulator.
