# Allowance (Android)

An offline budgeting app for Android, written in Kotlin and Jetpack Compose.

It turns your income, fixed bills and savings into a daily allowance, tracks what you spend, and helps you pay back an overspend with cheaper swaps (the bus instead of the train, bread at home instead of a café breakfast). Everything stays on your phone. There is no account, no cloud and no API key.

A web version of the same app lives in its own repository: [allowance-web](https://github.com/eejinxing/allowance-web). Backups are JSON and work in both.

## What you need

- Android Studio (or a JDK 17+) and the Android SDK Platform 36
- An Android 8.0+ (API 26) phone or emulator

## Run it

1. Open this folder in Android Studio and let Gradle sync.
2. Run the `app` configuration on a phone or an emulator.

## Check it

```bash
./gradlew -PcoreOnly :core:test            # core unit tests, no Android SDK needed
./gradlew :app:assembleDebug               # build the debug APK
./gradlew :app:connectedDebugAndroidTest   # on-device tests, with an emulator or phone connected
```

On Windows use `gradlew.bat`. The first build needs a network to download Gradle and the dependencies.

## More

- [Architecture and money rules](docs/architecture.md)
- [How it was verified](docs/validation.md)
- [Developing on Windows](docs/development-windows.md)
