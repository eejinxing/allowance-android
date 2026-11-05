package com.allowance.core

import com.allowance.core.ledger.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The rules the web app added after the first port: swaps, paybacks, calendar marks and family-paid bills. Mirrors the web app's tests. */
class SwapsAndMarksTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val month = YearMonth.from(today)
    private fun settings(income: Long, savings: Long = 0, weightMode: String = "EVEN") =
        BudgetSettings("", income, "MONTHLY", savings, "2026-10-01", List(7) { 1 }, weightMode)
    private fun build(income: Long, bills: List<PlannedBill> = emptyList(), savings: Long = 0, swaps: List<Swap>? = null): AppData {
        val base = AppData(settings = settings(income, savings), bills = bills)
        return LedgerEngine.ensureMonths(if (swaps != null) base.copy(swaps = swaps) else base, today)
    }
    private fun spendAllowanceToday(data: AppData): AppData = LedgerEngine.saveEntry(data,
        LedgerEntry("spent", today.toString(), LedgerEngine.result(data, month, today).todayAllowance.minor), today)

    @Test fun `an overspend is trimmed about RM 1 a day, and a purchase that fits needs no payback`() {
        var data = build(30_000, swaps = emptyList())
        val allowance = LedgerEngine.result(data, month, today).todayAllowance.minor
        assertNull(LedgerEngine.payback(data, 500, today))
        data = LedgerEngine.saveEntry(data, LedgerEntry("spent", today.toString(), allowance), today)
        val p = LedgerEngine.payback(data, 800, today)!!
        assertEquals(800, p.over)
        assertEquals(8, p.days.size)
        assertTrue(p.days.all { it.cut == 100L })
        assertEquals(0, p.short)
        assertEquals(allowance + 800, p.total)
    }

    @Test fun `swaps are only used once you tap them, and the icons are still offered`() {
        val data = spendAllowanceToday(build(30_000))
        val p = LedgerEngine.payback(data, 800, today)!!
        assertTrue("nothing is used until it is tapped", p.days.all { it.swaps.isEmpty() })
        assertTrue("but the swap icons are offered on every day", p.options.all { it.cands.isNotEmpty() })
        assertEquals(0, p.short)
        assertEquals(800, p.days.sumOf { it.cut })
        assertTrue(p.days.all { it.now == it.was - it.cut && it.now >= 0 })
    }

    @Test fun `the swaps you pick replace nothing automatic, and the rest is shared out again`() {
        val data = spendAllowanceToday(build(100_000, listOf(PlannedBill("f", "Food", 1_000, "DAILY"))))
        val auto = LedgerEngine.payback(data, 800, today)!!
        assertEquals(5, auto.options.size)
        assertTrue(auto.options[0].cands.all { it.swap.category == "FOOD" || it.swap.category == "TRANSPORT" })
        assertEquals("a Food bill puts the food swaps first", "FOOD", auto.options[0].cands[0].swap.category)
        val bread = auto.options[0].cands[0]
        val day3 = auto.options[2].date
        val picked = LedgerEngine.payback(data, 800, today, mapOf(day3 to listOf(bread.id)))!!
        assertEquals(listOf(bread.id), picked.days.first { it.date == day3 }.swaps.map { it.id })
        assertEquals(maxOf(800L, bread.saving), picked.days.sumOf { it.cut })
        assertEquals(0, picked.short)
        val none = LedgerEngine.payback(data, 800, today, emptyMap())!!
        assertTrue(none.days.all { it.swaps.isEmpty() } && none.days.sumOf { it.cut } == 800L)
    }

    @Test fun `a Breakfast bill lines up with the breakfast swap and is priced against the bill, only when the swap is cheaper`() {
        fun build(billMinor: Long) = spendAllowanceToday(build(100_000, listOf(PlannedBill("b", "Breakfast", billMinor, "DAILY"))))
        // RM 6 breakfast bill: the bread swap (RM 2) is offered first, saving RM 4 (not the generic RM 6).
        val dear = build(600)
        val first = LedgerEngine.payback(dear, 800, today)!!.options[0].cands[0]
        assertEquals("Bread at home", first.swap.cheaperName)
        assertTrue(first.fromBill)
        assertEquals("Breakfast", first.swap.usualName)
        assertEquals(400, first.saving)
        // RM 1.50 breakfast bill: bread (RM 2) isn't cheaper than what you already pay, so it is not offered.
        val cheap = build(150)
        assertTrue(LedgerEngine.payback(cheap, 800, today)!!.options.none { o -> o.cands.any { it.swap.cheaperName == "Bread at home" } })
        // Previewing a swap never changes your data.
        val before = dear.copy()
        LedgerEngine.payback(dear, 800, today, mapOf(LedgerEngine.payback(dear, 800, today)!!.options[0].date to listOf(first.id)))
        assertEquals(before, dear)
        assertEquals(600, dear.bills[0].amountMinor)
    }

    @Test fun `a bill swap takes its saving from the bill, so the day keeps its own money and several bills can each offer one`() {
        val bills = listOf(PlannedBill("b", "Breakfast", 600, "DAILY"), PlannedBill("l", "Lunch", 1_000, "DAILY"))
        val data = spendAllowanceToday(build(100_000, bills))
        val base = LedgerEngine.payback(data, 800, today)!!
        val cands = base.options[0].cands
        val bread = cands.first { it.billId == "b" }; val lunch = cands.first { it.billId == "l" }
        assertTrue("breakfast and lunch each get an icon, even though both are Food", bread.fromBill && lunch.fromBill)
        assertTrue(cands.all { it.fits })
        val day = base.options[0].date
        val on = LedgerEngine.payback(data, 800, today, mapOf(day to listOf(bread.id)))!!
        val d1 = on.days.first { it.date == day }
        assertEquals(400, d1.billSaved)
        assertEquals("the day only gives up its share of the trim, never the bill saving", d1.was - d1.cut, d1.now)
        assertEquals(400, on.days.sumOf { it.cut }) // 800 over, 400 saved from the breakfast bill, 400 trimmed
        assertEquals(0, on.short)
        val both = LedgerEngine.payback(data, 800, today, mapOf(day to listOf(bread.id, lunch.id)))!!
        assertEquals(2, both.days.first { it.date == day }.swaps.size)
        assertEquals(900, both.days.first { it.date == day }.billSaved) // RM 4 from breakfast plus RM 5 from lunch
        // the same bill can't be used twice on one day
        assertEquals(1, LedgerEngine.payback(data, 800, today, mapOf(day to listOf(bread.id, bread.id)))!!.days.first { it.date == day }.swaps.size)
    }

    @Test fun `a bill paid by family is never taken from your budget, even if an old copy of the month still counts it`() {
        val bills = listOf(PlannedBill("rent", "Rent", 90_000, "MONTHLY", family = true))
        var data = LedgerEngine.ensureMonths(AppData(settings = settings(100_000, 30_000), bills = bills), today)
        assertEquals(0, data.budgets.first { it.month == month.toString() }.billsMinor)
        assertEquals(90_000, LedgerEngine.familyBillsMinor(bills, month))
        data = data.copy(budgets = data.budgets.map { it.copy(billsMinor = 90_000) })
        val healed = LedgerEngine.ensureMonths(data, today)
        assertEquals(0, healed.budgets.first { it.month == month.toString() }.billsMinor)
        assertEquals(70_000, LedgerEngine.result(healed, month, today).spendable.minor)
    }

    @Test fun `a daily bill is not charged on red or teal days, and marks stay exactly as set`() {
        val bills = listOf(PlannedBill("food", "Food", 3_000, "DAILY"))
        var data = LedgerEngine.ensureMonths(AppData(settings = settings(100_000, 30_000), bills = bills), today)
        fun stored() = data.budgets.first { it.month == month.toString() }
        assertEquals(31 * 3_000L, LedgerEngine.billsFor(data, stored(), today)) // nothing marked: every day is charged
        data = LedgerEngine.setMark(LedgerEngine.setPlan(data, LocalDate.of(2026, 10, 25), 0, today), LocalDate.of(2026, 10, 25), "RED", today)
        data = LedgerEngine.setMark(LedgerEngine.setPlan(data, LocalDate.of(2026, 10, 26), 2_300, today), LocalDate.of(2026, 10, 26), "TEAL", today) // 23 ringgit typed on a teal day: still teal
        data = LedgerEngine.setMark(LedgerEngine.setPlan(data, LocalDate.of(2026, 10, 27), 2_300, today), LocalDate.of(2026, 10, 27), "YELLOW", today) // yellow: bills counted
        data = LedgerEngine.setMark(data, LocalDate.of(2026, 10, 5), "RED", today) // a day that has gone
        assertEquals("TEAL", stored().marks["2026-10-26"])
        assertEquals(28 * 3_000L, LedgerEngine.billsFor(data, stored(), today)) // red, teal and the past red are not charged; yellow is
        assertEquals(100_000L - 28 * 3_000 - 30_000, LedgerEngine.result(data, month, today).spendable.minor)
        data = LedgerEngine.setMark(data, LocalDate.of(2026, 10, 5), null, today)
        assertEquals(29 * 3_000L, LedgerEngine.billsFor(data, stored(), today))
        assertThrows(IllegalArgumentException::class.java) { LedgerEngine.setMark(data, LocalDate.of(2026, 10, 5), "BLUE", today) }
        assertEquals("YELLOW", BackupCodec.decode(BackupCodec.encode(data)).budgets.first { it.month == month.toString() }.marks["2026-10-27"])
    }

    @Test fun `ensureMonths follows current bills but keeps plans, paybacks and marks`() {
        var data = LedgerEngine.ensureMonths(AppData(settings = settings(100_000, 10_000)), today)
        data = LedgerEngine.setPlan(data, LocalDate.of(2026, 10, 25), 500, today)
        data = LedgerEngine.setMark(data, LocalDate.of(2026, 10, 26), "RED", today)
        data = LedgerEngine.addPayback(data, Payback("p1", today.toString(), "Sneakers", 800,
            listOf(PaybackDay("2026-10-23", 100, listOf(PaybackSwap("Bus", "Train", "", 650))))))
        val bills = listOf(PlannedBill("phone", "Phone", 4_000, "MONTHLY"))
        val changed = LedgerEngine.configure(data, data.settings!!, bills, today).budgets.first { it.month == month.toString() }
        assertEquals(4_000, changed.billsMinor)
        assertEquals(mapOf("2026-10-25" to 500L), changed.plans)
        assertEquals(mapOf("2026-10-26" to "RED"), changed.marks)
        assertEquals(listOf("p1"), changed.paybacks.map { it.id })
    }

    @Test fun `what a day is allowed is its own budget, with or without a plan`() {
        var data = build(100_000)
        val plain = LedgerEngine.suggestedFor(data, LocalDate.of(2026, 10, 28), today)
        data = LedgerEngine.setPlan(data, LocalDate.of(2026, 10, 28), 100, today)
        assertEquals("a plan on the day doesn't change what it would get without one", plain, LedgerEngine.suggestedFor(data, LocalDate.of(2026, 10, 28), today))
        assertEquals(LedgerEngine.result(data, month, today).todayAllowance.minor, LedgerEngine.allowanceOn(data, today, today))
        assertTrue(LedgerEngine.allowanceOn(data, LocalDate.of(2026, 10, 10), today) > 0)
    }

    @Test fun `backups carry swaps, paybacks and marks, and older or web backups still open`() {
        var data = build(100_000)
        data = LedgerEngine.setMark(data, LocalDate.of(2026, 10, 25), "TEAL", today)
        data = LedgerEngine.addPayback(data, Payback("p1", today.toString(), "Sneakers", 800, listOf(PaybackDay("2026-10-23", 100))))
        data = data.copy(swaps = data.swaps + Swap("mine", "FOOD", "", "Dinner out", 1_200, "Cook at home", 400))
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
        // An older backup has no swaps, marks or paybacks: it gets the standard swaps and empty marks.
        val tree = Json.parseToJsonElement(BackupCodec.encode(data)).jsonObject
        val old = JsonObject(tree - "swaps" + ("budgets" to JsonArray(tree.getValue("budgets").jsonArray.map {
            JsonObject(it.jsonObject - "marks" - "paybacks")
        })))
        val decoded = BackupCodec.decode(old.toString())
        assertEquals(LedgerEngine.defaultSwaps(), decoded.swaps)
        assertTrue(decoded.budgets.all { it.marks.isEmpty() && it.paybacks.isEmpty() })
        // A field this version doesn't know is ignored instead of refusing the whole file.
        val future = JsonObject(tree + ("somethingNew" to JsonPrimitive(1)))
        assertEquals(data, BackupCodec.decode(future.toString()))
        // A swap must have two names and a cheaper price.
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(data.copy(swaps = listOf(Swap("bad", "FOOD", "", "A", 100, "B", 100))))) }
    }
}
