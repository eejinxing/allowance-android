# Allowance for Android

Allowance shows you how much you can spend today after bills and savings. Record what you spend and see what's left. If a purchase puts you over budget, the app can spread the extra cost over the next few days and suggest cheaper swaps, like taking the bus instead of the train.

The app is built with Kotlin and Jetpack Compose. Your budget is stored on your phone, and you can use it offline without an account. To move your budget between Android and the [web version](https://github.com/eejinxing/allowance-web), export a JSON backup from Settings and restore it on the other version.

## The budget assistant

Open the **AI** tab and try one of these messages.

- `Spent RM 3 on coffee` records an expense. Tap **Undo** if you need to take it back.
- `Can I afford sneakers RM 120?` shows how the purchase would affect your budget.
- `How much did I spend on food this month?` adds up your food expenses.
- `Save for a Japan trip RM 5000` creates a savings goal.

The built-in rules handle English, Chinese, and basic Malay. The assistant can use past expenses to suggest a category or show what you usually pay for an item. You can also tap the microphone to use Android's speech recognition. The app sends the transcript as soon as recognition finishes.

Tap the camera to take a receipt photo or choose one from your phone. Allowance looks for the total on the phone. If it finds one, tap **Check and save** to review the entry before saving it.

### Connect Ollama (optional)

If you already run Ollama on your computer, you can connect it under **Settings → On-device AI**. Start Ollama with `OLLAMA_HOST=0.0.0.0`, then enter your computer's local address in the app. A text model helps interpret more ways of phrasing a request. A vision model such as `qwen2.5vl:3b` can try receipts the phone couldn't read. When both the rules and a model return an answer, they have to agree before an expense is recorded from a message.

## Run the app

Open the project in Android Studio, install Android SDK Platform 36, and let Gradle sync. Run the `app` configuration on a phone or emulator with Android 8.0 (API 26) or later. The Gradle wrapper uses JDK 21 for its daemon. The first build needs an internet connection to download Gradle and its dependencies.

## Build and test

```bash
./gradlew -PcoreOnly :core:test            # Run core tests without the Android SDK
./gradlew :app:assembleDebug               # Build the debug APK
./gradlew :app:connectedDebugAndroidTest   # Run tests on a connected phone or emulator
```

On Windows, use `.\gradlew.bat` in PowerShell.

## Project notes

- [Architecture and money rules](docs/architecture.md)
- [Test coverage and results](docs/validation.md)
- [Developing on Windows](docs/development-windows.md)
