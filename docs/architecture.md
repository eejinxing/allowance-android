# Architecture

## How a change flows

`MainActivity` creates `AllowanceViewModel`, and the screens subscribe to its state with `collectAsStateWithLifecycle`. A user action calls the ViewModel. The repository reads the latest data inside one SQLite transaction, hands it to `LedgerEngine` to produce the new state, validates it, and commits it in one go. Room's invalidation notice makes the screens read the state again.

`core` has no Android dependency: money, the budget calculator, the bookkeeping rules and the backup format. `app` holds the Compose screens, Room, DataStore and the Android file pickers.

## Main files

| File | Job |
|---|---|
| `core/.../model/Money.kt` | Money as a `Long` number of sen, exact parsing and arithmetic |
| `core/.../calculator/BudgetCalculator.kt` | Weekday weights, today's allowance, carry-over, whole-sen allocation |
| `core/.../ledger/AppData.kt` | The versioned, backup-able data model and the JSON codec |
| `core/.../ledger/LedgerEngine.kt` | Entries, accounts, transfers, funds, savings, monthly snapshots, daily bills and no-spend days, swaps and pay-back plans, validation |
| `core/.../ledger/PaybackPlan.kt` | Result types of a pay-back plan: each day's allowance, the swaps used, the saving taken from a bill |
| `core/.../ledger/DayMarks.kt` | What each calendar day comes to and how it is marked (red, teal, yellow). Shared rules with the web app |
| `app/.../data/AllowanceDatabase.kt` | Nine Room tables: Profile, Bills, Accounts, Entries, Goals, Funds, Wishes, Budgets, Swaps (schema version 2) |
| `app/.../data/AllowanceRepository.kt` | Atomic reads and writes, and the Room flow |
| `app/.../data/AppPreferences.kt` | DataStore: theme, PIN hash, attempt counter and lockout |
| `app/.../AllowanceViewModel.kt` | Screen state, mutually exclusive actions, day rollover and messages |
| `app/.../ui/AllowanceApp.kt` | Theme, six tabs (Home, Analytics, AI, Goals, Wallet, Swaps; History and Planning open from Home), settings, app lock, file import and export |
| `app/.../ui/SwapsPage.kt` | The Swaps page (dark tiles), the swap editor, the list of paybacks |
| `app/.../ui/PlanPage.kt` | Planning: the Budget tab (bill by bill) and the Calendar tab (red, teal and yellow marks) |
| `app/.../ui/AssistantPage.kt` | The budget assistant: "can I afford this", the pay-back chart and the swap icons |
| `app/.../ui/Editors.kt` | Setup, entry, account, goal, fund and transfer forms |
| `app/.../ui/Components.kt` | Shared cards, date picker, categories, amount and input widgets |

In the table, `...` stands for `src/main/kotlin/com/allowance/core` or `src/main/java/com/allowance/app` in each module.

## How data is stored

Room has one table per collection, with an index on the entry date. Each row's payload is Kotlin Serialization JSON, so it is shared with the backup format. Queries and grouping happen in the in-memory model in `core`. Every change commits the whole aggregate in one SQLite transaction, which favours simplicity and consistency for a personal budget. It is not a paging design for huge transaction counts: if that is ever needed, split the payloads into columns and use incremental DAOs while keeping `LedgerEngine`.

There is no destructive migration. Room exports its schema to `app/schemas/`: version 1 has eight tables, and version 2 adds the `swaps` table (`MIGRATION_1_2` creates it and inserts the standard swaps). Calendar marks (`marks`) and paybacks (`paybacks`) live inside each month's budget JSON, which only gained fields with defaults, so no table change was needed. The PIN and the theme are kept in DataStore and are not part of a backup.

JSON backups use the same format as the web app (`swaps`, and each month's `paybacks` and `marks`, are included). Unknown fields are ignored on read, so the two apps can import each other's backups. An older backup that lacks these fields gets defaults.

## Money rules

- All amounts are MYR sen. A `Double` is never used for money.
- Budgets cover the whole month. Even if you set up mid-month, every day from the 1st counts as a budget day and earlier days can still be logged (`LedgerEngine.budgetFrom`). Daily and weekly amounts convert using the real days of the month, rounding HALF_UP.
- A budget snapshot is kept per month. Changing settings only updates the current month and later ones; past months stay as they were. If the app is opened after months away, the missing months are filled in with the settings saved at that time.
- "Scheduled income" only updates the wallet, so income is not counted twice. "Extra income" raises the spendable budget.
- Family-paid spending never reduces your budget or an account. A bill payment that was already set aside only updates the account. A fixed bill paid by family is never taken from your budget either. This month and later months are rebuilt from your current income, bills and savings every time data loads (plans, paybacks and marks are kept); past months are left alone.
- Daily bills are not charged on days marked red or teal (or days with only a plan). Yellow days and unmarked days are charged (`noSpendDays`, `billsFor`).
- Calendar marks are chosen by you: tap a day to select it, tap again to move it through no mark, red, teal, yellow. The amount is separate: for a past day it is the expense you logged, for today and later it is a plan.
- A swap is a cheaper way to do something you already do. When you overspend, no swap is used by default: the overspend is only trimmed evenly over the following days (about RM 1 a day), and a swap is used only once you tap it. A swap that lines up with a fixed bill by name (a Breakfast bill and the breakfast swap) is priced against that bill and is offered only if it is really cheaper. Its saving comes from the bill, so it does not use the day's own money, and one bill can be used once a day. Any other swap is deducted from that day and can never be more than the day has. Previewing a swap changes no data; "Buy it, pay back" records the purchase, lowers the following days and writes a payback record in one step.
- A transfer is one entry with two accounts and does not change the budget. A balance correction leaves its own delta entry.
- A fund top-up uses budget but does not move a real account. A purchase takes the full amount from the account, and only the part above what was set aside is taken from the budget again. Any leftover is released automatically.
- A savings goal tracks money already saved. It does not take the monthly savings reserve a second time, and it does not move an account.
- Deleting an entry recalculates balances. Money already used by a later fund or goal action cannot be deleted. Deleting a fund purchase restores the fund and the paying account; the leftover release is its own entry.

## PIN and files

The PIN uses a random salt and PBKDF2-HMAC-SHA256 (210,000 iterations), and the hashes are compared with a constant-time API. After five wrong tries there is a 30-second wait, with the count kept in DataStore. Leaving the app locks it; rotating the screen does not. The PIN guards the entrance and the database has no extra encryption. Release builds block screenshots; debug builds keep them for testing.

Importing JSON checks size, version, IDs, account references, amounts and fund balances, and only replaces the data atomically after you confirm. Backups do not include the PIN. CSV export protects spreadsheet formula prefixes and handles quotes and line breaks correctly.

## Outside services

There is no sign-in, no cloud sync and no online AI service, and no API key is needed. All data stays on the device. The budget assistant reads sentences with built-in rules and reads receipt photos with an on-device model. The only network use is optional: Settings can connect it to an Ollama server you run yourself, at the address you enter. Adding cloud sync or an online AI later would need separate authentication and a backend.
