package com.allowance.core

import com.allowance.core.ledger.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LedgerEngineTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val month = YearMonth.from(today)
    private fun data(): AppData = LedgerEngine.configure(AppData(),
        BudgetSettings("Jin", 120_000, savingsMinor = 20_000, started = today.toString()), emptyList(), today)
        .copy(accounts = listOf(WalletAccount("bank", "Bank", openingMinor = 100_000), WalletAccount("cash", "Cash", "CASH", 10_000)),
            funds = listOf(ShoppingFund("shoes", "Shoes", 10_000)), goals = listOf(Goal("trip", "Trip", 500_000)))
    private fun balance(data: AppData, id: String) = LedgerEngine.accountBalance(data, data.accounts.first { it.id == id })

    @Test fun `setup budgets the whole month even when starting mid-month`() {
        val data = LedgerEngine.configure(AppData(), BudgetSettings("", 310_000, savingsMinor = 31_000),
            listOf(PlannedBill("phone", "Phone", 3_800), PlannedBill("rent", "Rent", 65_000, family = true)), today)
        val budget = data.budgets.single()
        assertEquals("2026-10-01", budget.startDate)
        assertEquals(310_000, budget.incomeMinor)
        assertEquals(31_000, budget.savingsMinor)
        assertEquals(3_800, budget.billsMinor)
    }

    @Test fun `days earlier in the setup month can be logged and count against the budget`() {
        val start = LedgerEngine.configure(AppData(), BudgetSettings("", 310_000, started = today.toString()), emptyList(), today)
        val before = LedgerEngine.result(start, month, today).remaining.minor
        val logged = LedgerEngine.saveEntry(start, LedgerEntry("early", "2026-10-01", 10_000), today)
        assertEquals(before - 10_000, LedgerEngine.result(logged, month, today).remaining.minor)
        assertThrows(IllegalArgumentException::class.java) { LedgerEngine.saveEntry(start, LedgerEntry("old", "2026-09-30", 100), today) }
    }

    @Test fun `a budget saved with a prorated first month is widened to the whole month`() {
        val fresh = LedgerEngine.configure(AppData(), BudgetSettings("", 310_000, savingsMinor = 31_000), emptyList(), today)
        val legacy = fresh.copy(budgets = listOf(fresh.budgets.single().copy(startDate = today.toString(), incomeMinor = 90_000, savingsMinor = 9_000, plans = mapOf("2026-10-25" to 5_000L))))
        val fixed = LedgerEngine.ensureMonths(legacy, today).budgets.single()
        assertEquals("2026-10-01", fixed.startDate)
        assertEquals(310_000, fixed.incomeMinor)
        assertEquals(mapOf("2026-10-25" to 5_000L), fixed.plans)
    }

    @Test fun `frequency conversion uses actual active days`() {
        assertEquals(3_100, LedgerEngine.periodAmount(100, "DAILY", 31))
        assertEquals(31_000, LedgerEngine.periodAmount(7_000, "WEEKLY", 31))
        assertEquals(1_000, LedgerEngine.periodAmount(1_000, "MONTHLY", 9))
    }

    @Test fun `new month settings do not rewrite earlier snapshot`() {
        val original = data()
        val november = today.plusMonths(1)
        val changed = LedgerEngine.configure(original, original.settings!!.copy(incomeMinor = 240_000), emptyList(), november)
        assertEquals(original.budgets.first(), changed.budgets.first { it.month == "2026-10" })
        assertEquals(240_000, changed.budgets.first { it.month == "2026-11" }.incomeMinor)
    }

    @Test fun `transfer moves wallet money without changing budget`() {
        val original = data()
        val next = LedgerEngine.transfer(original, "bank", "cash", 5_000, today)
        assertEquals(95_000, balance(next, "bank")); assertEquals(15_000, balance(next, "cash"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(next, month, today).remaining)
    }

    @Test fun `balance correction records a delta and does not alter allowance`() {
        val original = data()
        val next = LedgerEngine.setBalance(original, "bank", -25_000, today)
        assertEquals(-25_000, balance(next, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(next, month, today).remaining)
        assertEquals(original, LedgerEngine.deleteEntry(next, next.entries.single().id))
    }

    @Test fun `family spending affects neither bank nor personal budget`() {
        val original = data()
        val next = LedgerEngine.saveEntry(original, LedgerEntry("family", today.toString(), 8_000, family = true, accountId = "bank"), today)
        assertEquals(100_000, balance(next, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(next, month, today).remaining)
    }

    @Test fun `scheduled receipt updates account but is not extra income`() {
        val original = data()
        val next = LedgerEngine.saveEntry(original, LedgerEntry("payday", today.toString(), 120_000, EntryType.SCHEDULED_INCOME, accountId = "bank"), today)
        assertEquals(220_000, balance(next, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(next, month, today).remaining)
    }

    @Test fun `fund allocation reserves spending money without moving wallet balance`() {
        val original = data()
        val next = LedgerEngine.fundMove(original, "shoes", 5_000, false, today)
        assertEquals(5_000, LedgerEngine.fundSaved(next, "shoes"))
        assertEquals(100_000, balance(next, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining.minor - 5_000, LedgerEngine.result(next, month, today).remaining.minor)
    }

    @Test fun `fund purchase charges reserved amount once and remainder to budget`() {
        val original = data()
        val saved = LedgerEngine.fundMove(original, "shoes", 5_000, false, today)
        val bought = LedgerEngine.buyFund(saved, "shoes", 8_000, "bank", today)
        assertEquals(92_000, balance(bought, "bank"))
        assertEquals(0, LedgerEngine.fundSaved(bought, "shoes"))
        assertEquals(LedgerEngine.result(original, month, today).remaining.minor - 8_000, LedgerEngine.result(bought, month, today).remaining.minor)
        assertNotNull(bought.funds.single().boughtOn)
    }

    @Test fun `cheaper purchase releases leftover back to spending budget`() {
        val original = data()
        val saved = LedgerEngine.fundMove(original, "shoes", 10_000, false, today)
        val bought = LedgerEngine.buyFund(saved, "shoes", 8_000, "bank", today)
        assertEquals(0, LedgerEngine.fundSaved(bought, "shoes"))
        assertEquals(LedgerEngine.result(original, month, today).remaining.minor - 8_000, LedgerEngine.result(bought, month, today).remaining.minor)
        assertEquals(2_000, bought.entries.first { it.type == EntryType.FUND_RELEASE }.amountMinor)
    }

    @Test fun `deleting a fund purchase restores wallet and consumed reservation`() {
        val saved = LedgerEngine.fundMove(data(), "shoes", 5_000, false, today)
        val bought = LedgerEngine.buyFund(saved, "shoes", 8_000, "bank", today)
        val restored = LedgerEngine.deleteEntry(bought, bought.entries.first { it.type == EntryType.FUND_PURCHASE }.id)
        assertEquals(saved, restored)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot remove allocation that a purchase has consumed`() {
        val saved = LedgerEngine.fundMove(data(), "shoes", 5_000, false, today)
        val bought = LedgerEngine.buyFund(saved, "shoes", 8_000, "bank", today)
        LedgerEngine.deleteEntry(bought, saved.entries.single().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot release more than saved`() { LedgerEngine.fundMove(data(), "shoes", 1, true, today) }

    @Test fun `archiving fund releases reserved balance and preserves history`() {
        val original = data()
        val saved = LedgerEngine.fundMove(original, "shoes", 5_000, false, today)
        val archived = LedgerEngine.archiveFund(saved, "shoes", today)
        assertEquals(0, LedgerEngine.fundSaved(archived, "shoes"))
        assertTrue(archived.funds.single().archived)
        assertEquals(2, archived.entries.size)
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(archived, month, today).remaining)
    }

    @Test fun `goal tracking does not deduct reserved savings twice`() {
        val original = data()
        val saved = LedgerEngine.goalMove(original, "trip", 5_000, false, today)
        val withdrawn = LedgerEngine.goalMove(saved, "trip", 1_000, true, today)
        assertEquals(4_000, LedgerEngine.goalSaved(withdrawn, "trip"))
        assertEquals(100_000, balance(withdrawn, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(withdrawn, month, today).remaining)
    }

    @Test fun `removing archive release makes the funded bucket visible again`() {
        val saved = LedgerEngine.fundMove(data(), "shoes", 5_000, false, today)
        val archived = LedgerEngine.archiveFund(saved, "shoes", today)
        val release = archived.entries.first { it.type == EntryType.FUND_RELEASE }
        val restored = LedgerEngine.deleteEntry(archived, release.id)
        assertEquals(saved, restored)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `purchase leftover cannot be removed while purchase still exists`() {
        val saved = LedgerEngine.fundMove(data(), "shoes", 10_000, false, today)
        val bought = LedgerEngine.buyFund(saved, "shoes", 8_000, "bank", today)
        LedgerEngine.deleteEntry(bought, bought.entries.first { it.type == EntryType.FUND_RELEASE }.id)
    }

    @Test fun `editing transaction adjusts account once`() {
        val original = data()
        val entry = LedgerEntry("lunch", today.toString(), 1_200, accountId = "bank")
        val saved = LedgerEngine.saveEntry(original, entry, today)
        val updated = LedgerEngine.saveEntry(saved, entry.copy(amountMinor = 1_500, accountId = "cash"), today)
        assertEquals(1, updated.entries.size)
        assertEquals(100_000, balance(updated, "bank")); assertEquals(8_500, balance(updated, "cash"))
    }

    @Test fun `backup roundtrip retains every persisted record`() {
        val original = LedgerEngine.buyFund(LedgerEngine.fundMove(data(), "shoes", 5_000, false, today), "shoes", 8_000, "bank", today)
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(original)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid backup is rejected before commit`() { BackupCodec.decode(BackupCodec.encode(data().copy(accounts = emptyList(), entries = listOf(LedgerEntry("x", today.toString(), 10, accountId = "missing"))))) }

    @Test fun `csv quotes newlines and neutralizes spreadsheet formulas`() {
        val original = data().copy(entries = listOf(LedgerEntry("x", today.toString(), 100, note = "=HYPERLINK(\"x\")\nline2")))
        val csv = LedgerEngine.csv(original)
        assertTrue(csv.startsWith("\uFEFFdate,type"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\nline2\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `future transactions are rejected`() {
        LedgerEngine.saveEntry(data(), LedgerEntry("future", today.plusDays(1).toString(), 1), today)
    }

    @Test fun `csv keeps validated negative adjustments numeric`() {
        val adjusted = LedgerEngine.setBalance(data(), "bank", 90_000, today)
        assertTrue(LedgerEngine.csv(adjusted).contains("\"-100.00\""))
        assertFalse(LedgerEngine.csv(adjusted).contains("\"'-100.00\""))
    }

    @Test fun `planned bill payment is categorized as living without charging allowance twice`() {
        val seed = data()
        val original = LedgerEngine.configure(seed, seed.settings!!, listOf(PlannedBill("phone", "Phone", 3_800)), today)
        val next = LedgerEngine.saveEntry(original, LedgerEntry("phone-payment", today.toString(), 3_800,
            EntryType.BILL_PAYMENT, accountId = "bank"), today)
        assertEquals("LIVING", next.entries.single().category)
        assertEquals(96_200, balance(next, "bank"))
        assertEquals(LedgerEngine.result(original, month, today).remaining, LedgerEngine.result(next, month, today).remaining)
    }

    @Test fun `clock moved back a day keeps stored data usable`() {
        val now = LocalDate.now()
        val tomorrow = now.plusDays(1)
        val data = LedgerEngine.configure(AppData(), BudgetSettings("Jin", 120_000, started = tomorrow.toString()), emptyList(), tomorrow)
            .let { it.copy(entries = listOf(LedgerEntry("late", tomorrow.toString(), 500))) }
        LedgerEngine.validate(data)
        // Rendering on the earlier date must not crash. The budget covers the whole month, so within the same month the
        // entry dated tomorrow is simply not spent yet; across a month boundary the later month is shown as of its end.
        val result = LedgerEngine.result(data, YearMonth.from(tomorrow), now)
        assertEquals(if (YearMonth.from(tomorrow) == YearMonth.from(now)) 0 else 500, (result.spendable - result.remaining).minor)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerEngine.validate(data.copy(entries = listOf(LedgerEntry("future", now.plusDays(2).toString(), 500))))
        }
    }

    @Test fun `day plans persist through budget edits and reach the calculator`() {
        val planned = LedgerEngine.setPlan(data(), today.plusDays(2), 5_000, today)
        val edited = LedgerEngine.configure(planned, planned.settings!!.copy(incomeMinor = 150_000), emptyList(), today)
        assertEquals(mapOf(today.plusDays(2).toString() to 5_000L), edited.budgets.first { it.month == month.toString() }.plans)
        assertEquals(5_000L, LedgerEngine.result(edited, month, today).futureAllowances.getValue(today.plusDays(2)).minor)
        val cleared = LedgerEngine.setPlan(edited, today.plusDays(2), null, today)
        assertTrue(cleared.budgets.first { it.month == month.toString() }.plans.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun `past days cannot be planned`() {
        LedgerEngine.setPlan(data(), today.minusDays(1), 1_000, today)
    }

    @Test fun `habit weights follow the weekday you spend most on`() {
        val start = today.minusWeeks(4)
        val fridays = (0 until 4).map { start.plusWeeks(it.toLong()).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)) }
        val base = LedgerEngine.configure(AppData(), BudgetSettings("Jin", 120_000, started = start.toString(), weightMode = "HABIT"), emptyList(), today)
        val data = base.copy(entries = fridays.mapIndexed { i, d -> LedgerEntry("f$i", d.toString(), 4_000) } +
            LedgerEntry("mon", start.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)).toString(), 500))
        val weights = LedgerEngine.habitWeights(data, today)
        assertEquals(weights.max(), weights[4]) // Friday
        assertTrue(weights.all { it in 3..30 })
        assertEquals(List(7) { 10 }, LedgerEngine.habitWeights(base, today))
    }
}
