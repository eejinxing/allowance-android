# Allowance (Android)

An offline budgeting app for Android, written in Kotlin and Jetpack Compose.

It turns your income, fixed bills and savings into a daily allowance, tracks what you spend, and helps you pay back an overspend with cheaper swaps (the bus instead of the train, bread at home instead of a café breakfast). Everything stays on your phone. There is no account, no cloud and no API key.

A web version of the same app lives in its own repository: [allowance-web](https://github.com/eejinxing/allowance-web). Backups are JSON and work in both.

## The budget assistant

The AI tab works like the web app's, in English, 中文 or simple Malay, typed or spoken: `Spent RM 3 on coffee` or `昨天打车花了18块` records it (with Undo), `Sneakers RM 120` checks it against your budget and shows the pay-back chart, `how much did I spend on food this month` adds it up, `save for a Japan trip RM 5000` makes a goal, `undo` / `撤销` takes back the last record.

- **Built-in rules** (`core/.../assistant/Nlu.kt`): always on, offline, the same rules and test cases as the web app. An amount must be written in your words; a number that counts something else ("3 km", "room 305") is never read as money.
- **Memory** (`Memory.kt`): looks up your own past entries to pick a category and say "you usually pay about RM 4 for this".
- **Receipt photos**: the camera button reads the total on the phone with the same ONNX model as the web app (`app/src/main/assets/receipt-reader.onnx`, run by ONNX Runtime). It only fills in an entry for you to check; nothing is saved from a photo by itself.
- **Ollama on your computer**, optional (Settings, On-device AI): a free local model reads loose sentences, and a vision model (`qwen2.5vl:3b`) can read receipts the phone couldn't. Start Ollama with `OLLAMA_HOST=0.0.0.0` and enter the computer's address. A record is only written when the rules and the model agree (`Reader.combine`); amounts and dates always come from your words. This is the app's only network use, and only to the address you enter.

The web app can also download a small model into the browser; the Android app doesn't have an in-phone model yet.

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
