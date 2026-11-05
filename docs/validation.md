# Verification

Date: 2026-11-05 (Asia/Kuala_Lumpur).

## Result

| Check | Result |
|---|---|
| Core unit tests (pure Kotlin) | 77 passed, 0 failed |
| On-device tests (Android emulator, API 37, x86_64) | 13 passed, 0 failed |
| Debug APK build | BUILD SUCCESSFUL |
| Android lint | 0 errors, 4 warnings |

Environment: Windows, JDK 24, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36.

## Core tests

- `BudgetCalculatorTest` (20): weekday weights, zero weights, a mid-month start, month end, leap years, overspend and whole-sen conservation.
- `MoneyTest` (7): exact decimal parsing, overflow, negative spending, invalid transfers and incomplete fund data.
- `LedgerEngineTest` (30): monthly snapshots, daily and weekly income, account correction, family-paid spending, scheduled income, fund purchases, releases and deletes, savings, backups and CSV.
- `SwapsAndMarksTest` (10): paybacks, swaps used only when tapped, a bill swap priced against its bill and taken from the bill rather than the day, family-paid bills, daily bills on no-spend days, and backups with swaps, paybacks and marks.
- `DayMarksTest` (7): the calendar's red, teal and yellow marks, and how a tap moves a day through them.
- `WebBackupTest` (3): a JSON backup written by the web app opens here, round-trips unchanged and drives the same pay-back plan.

## On-device tests

- Setup, adding and deleting an entry, rotation, the PIN lock, transfers and fund purchases, and all pages in light and dark mode (`AppFlowTest`).
- Wallet page: fund sheets, account tiles and hiding balances (`WalletPageTest`).
- Room: 20 concurrent writes lose no update, invalid data is not committed, and a cleared database restores from a JSON backup (`RepositoryTest`).
- **Database upgrade**: a hand-built version 1 database opens at version 2 with its data intact and the standard swaps added (`MigrationTest`).
- **Swaps and planning**: adding, editing and deleting a swap; the assistant previewing a swap without recording anything and only "Buy it, pay back" saving a payback; and the calendar moving a day through red, teal, yellow and back (`SwapsAndPlanFlowTest`).

## What was fixed along the way

- A JSON backup from the web app was refused by this app, because the reader rejected unknown fields. It now ignores them.
- The Android screens and rules were brought in line with the web app (swaps, paybacks, calendar marks, family-paid bills).

## Not verified

Physical phones of different brands, API 26 devices, third-party document providers, a hardware keyboard and TalkBack, and very large amounts of data. The pay-back and calendar tests need a few days left in the month, so they skip themselves in the last days.

## Commands

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

The first needs no device. The second needs an emulator or a phone connected.

On Windows, Gradle 8.x sometimes fails with `Could not move temporary workspace`. This is a [known Gradle issue](https://github.com/gradle/gradle/issues/38921). `scripts/build.ps1 -RepairWindowsCache` retries by copying the finished project-local build cache and changes no system security setting.
