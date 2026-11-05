# The data and calculation core

This file keeps the design notes for the first version of the core. The app has since grown Room, DataStore, a ViewModel and the full set of screens; see [architecture.md](architecture.md) for the current structure.

## Why two modules

`app` draws the Android screens. `core` holds the data types and the calculations. The core never reads the phone's clock, never queries a database and never touches the screen, so the budget can be tested without an emulator.

## Money

`Money(1250)` is MYR 12.50. A `@JvmInline value class` wraps a `Long` in a meaningful type, so a plain number cannot be mistaken for an amount. `Money.parse("12.50")` parses with `BigDecimal` and never goes through a `Double`. More than two decimals is rejected, and arithmetic overflow raises an error.

Only MYR is supported. Supporting another currency later would mean handling both its decimal places and exchange rates. Every transaction belongs to one MYR budget, and currencies cannot be mixed.

## Transaction: income, spending, transfers

A `sealed interface` limits transactions to the declared kinds, so a `when` can check that none is forgotten.

| Kind | Effect on the spendable budget |
|---|---|
| Ordinary self-paid spending | Deducted |
| Paid by family | Recorded, not deducted |
| Fixed bill payment | Already set aside by the budget snapshot, not deducted again |
| Fund top-up | Deducted, but does not mean a real account paid |
| The part of a purchase paid from a fund | Already deducted at top-up, not deducted again |
| The part of a purchase above the fund | Recorded as ordinary spending |
| Extra income / fund release | Added to the spendable budget |
| Scheduled income arriving | Already in the budget snapshot, not added again |
| Transfer between accounts | Does not affect the budget |

Real wallet balances are calculated apart from the budget. A fund top-up is a reservation and is not a bank debit; only the purchase affects the paying account. A fund purchase, its leftover release and the status update are saved together in one Room transaction.

## BudgetSnapshot: the monthly budget

A snapshot keeps that month's income, fixed bills and savings reserve, already converted. It never reads the current settings, so changing November's settings does not recalculate October.

## BudgetCalculator: the daily budget

1. Take only transactions from the start date to today, leaving out future dates and other months.
2. Spendable for the period = snapshot income + extra income - fixed bills set aside - savings reserve.
3. To allocate = spendable - what was used before today.
4. Share the amount over the remaining days by weekday weight to get today's allowance.
5. Today's remainder = today's allowance - what was used today.
6. What is left after today's spending is shared over the future days, which shows the carried-over allowances.

Weekday weights run Monday to Sunday. All 1s means an even split and 0 means that day gets no allowance. If every remaining day is 0, the result carries `unallocated` instead of dividing by zero. Even when today's weight is zero, spending today still reduces the remaining pool.

When an amount does not divide evenly, whole sen are shared first and the rest go to the days with the largest fractional remainders, the earlier date winning a tie. For example, 100 sen over three days is 34, 33, 33. A negative budget stays negative; an overspend is never hidden as zero.

`futureAllowances` is a projection that assumes no new transactions or setting changes. The last day of the month has no future allowance, and the leftover pool stays visible in `remaining`.

## Deliberate differences from the original HTML

- No floating-point money.
- Future income and future spending are left out on purpose, so money not yet received can't be spent in advance.
- The daily amount is split in whole sen, so it can differ by one sen from the HTML's rounded display.
- Months are isolated by snapshots; history is never recalculated from the current settings.
- Duplicate transaction IDs are rejected, so the same transaction is never added twice.

## What the tests cover

Ordinary bookkeeping, budget reservations, family-paid spending, bills and funds not being deducted twice, transfers, extra income, future dates, a negative budget, weights and zero weights, a mid-month start, month end, leap years, whole-sen conservation and invalid input.
