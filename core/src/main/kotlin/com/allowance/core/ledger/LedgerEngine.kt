package com.allowance.core.ledger

import com.allowance.core.calculator.BudgetCalculator
import com.allowance.core.calculator.BudgetResult
import com.allowance.core.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.math.roundToInt

/** All mutations are immutable. Repository commits the returned aggregate atomically. */
object LedgerEngine {
    fun id(): String = UUID.randomUUID().toString()
    private fun sum(values: Iterable<Long>) = values.fold(0L, Math::addExact)
    private fun scaled(amount: Long, numerator: Int, denominator: Int): Long =
        BigDecimal.valueOf(amount).multiply(numerator.toBigDecimal())
            .divide(denominator.toBigDecimal(), 0, RoundingMode.HALF_UP).longValueExact()

    fun periodAmount(amount: Long, frequency: String, days: Int): Long = when (frequency) {
        "DAILY" -> Math.multiplyExact(amount, days.toLong())
        "WEEKLY" -> scaled(amount, days, 7)
        "MONTHLY" -> amount
        else -> error("Invalid income or bill frequency")
    }

    /** The first day your budget covers: the 1st of the month you set it up in, so earlier days that month can still be logged. */
    fun budgetFrom(settings: BudgetSettings): LocalDate = YearMonth.from(LocalDate.parse(settings.started)).atDay(1)

    /** A month's budget always covers the whole month, even the month you started in. */
    fun makeBudget(settings: BudgetSettings, bills: List<PlannedBill>, month: YearMonth): MonthBudget {
        val start = month.atDay(1)
        val activeDays = month.lengthOfMonth()
        val income = if (settings.incomeFrequency == "MONTHLY")
            scaled(settings.incomeMinor, activeDays, month.lengthOfMonth())
        else periodAmount(settings.incomeMinor, settings.incomeFrequency, activeDays)
        // Monthly bills are reserved in full, matching the source HTML's policy.
        val fixed = sum(bills.filterNot { it.family }.map { periodAmount(it.amountMinor, it.frequency, activeDays) })
        return MonthBudget(month.toString(), start.toString(), income, fixed,
            scaled(settings.savingsMinor, activeDays, month.lengthOfMonth()), settings.weights.toList())
    }

    fun ensureMonths(data: AppData, today: LocalDate): AppData {
        val settings = data.settings ?: return data
        var month = YearMonth.from(LocalDate.parse(settings.started))
        val end = YearMonth.from(today)
        val budgets = data.budgets.toMutableList()
        while (month <= end) {
            if (budgets.none { it.month == month.toString() }) budgets += makeBudget(settings, data.bills, month)
            month = month.plusMonths(1)
        }
        // This month and the coming ones always follow your current income, bills (family-paid ones excluded) and savings, so a stale copy can't linger.
        // Past months keep what they had. Plans, paybacks and marks are kept.
        val now = YearMonth.from(today).toString()
        val fresh = budgets.map { b ->
            if (b.month >= now) makeBudget(settings, data.bills, YearMonth.parse(b.month)).copy(weights = b.weights, plans = b.plans, paybacks = b.paybacks, marks = b.marks) else b
        }
        return data.copy(budgets = fresh.sortedBy { it.month })
    }

    // ---------- calendar marks and daily bills ----------
    /** How a day is marked on the calendar. RED: nothing spent. TEAL: only what you spent, daily bills not counted. YELLOW: daily bills counted, plus what you spent. */
    val MARKS = setOf("RED", "TEAL", "YELLOW")

    /**
     * Days of [month] on which a daily bill is not charged: the days marked red or teal. (A day with only a plan counts the same way:
     * a plan of 0 as red, any other plan as teal.) Only this month and later months count; a month that is over keeps its bills.
     */
    fun noSpendDays(data: AppData, month: YearMonth, today: LocalDate): Int {
        if (month < YearMonth.from(today)) return 0
        val budget = data.budgets.firstOrNull { it.month == month.toString() }
        val plans = budget?.plans.orEmpty(); val marks = budget?.marks.orEmpty()
        val from = budgetFrom(requireNotNull(data.settings))
        return (1..month.lengthOfMonth()).map { month.atDay(it) }.count { day ->
            if (day < from) return@count false
            val key = day.toString()
            val mark = marks[key] ?: plans[key]?.let { if (it == 0L) "RED" else "TEAL" }
            mark == "RED" || mark == "TEAL"
        }
    }

    /** What you pay per day in daily bills (family-paid ones excluded). */
    fun dailyBillsMinor(bills: List<PlannedBill>): Long = sum(bills.filter { !it.family && it.frequency == "DAILY" }.map { it.amountMinor })

    /** This month's fixed bills with daily ones charged only on days that aren't no-spend days. */
    fun billsFor(data: AppData, budget: MonthBudget, today: LocalDate): Long =
        maxOf(0L, budget.billsMinor - dailyBillsMinor(data.bills) * noSpendDays(data, YearMonth.parse(budget.month), today))

    /** What family pays towards your fixed bills in a month. It is shown, but never taken from your budget. */
    fun familyBillsMinor(bills: List<PlannedBill>, month: YearMonth): Long =
        sum(bills.filter { it.family }.map { periodAmount(it.amountMinor, it.frequency, month.lengthOfMonth()) })

    /** Sets or clears a day's mark (RED, TEAL or YELLOW). The amount for the day is separate: a plan for coming days, spending you logged for past ones. */
    fun setMark(data: AppData, date: LocalDate, mark: String?, today: LocalDate): AppData {
        require(mark == null || mark in MARKS) { "Unknown mark" }
        val month = YearMonth.from(date)
        val ensured = ensureMonths(data, maxOf(today, date))
        val budget = ensured.budgets.firstOrNull { it.month == month.toString() }
            ?: makeBudget(requireNotNull(ensured.settings), ensured.bills, month)
        val marks = if (mark == null) budget.marks - date.toString() else budget.marks + (date.toString() to mark)
        return ensured.copy(budgets = ensured.budgets.filterNot { it.month == month.toString() } + budget.copy(marks = marks)).also(::validate)
    }

    fun configure(data: AppData, settings: BudgetSettings, bills: List<PlannedBill>, today: LocalDate): AppData {
        val started = data.settings?.started ?: today.toString()
        val next = settings.copy(started = started)
        val month = YearMonth.from(today)
        val old = data.budgets.firstOrNull { it.month == month.toString() }
        return ensureMonths(data.copy(settings = next, bills = bills,
            budgets = data.budgets.filterNot { it.month == month.toString() } + makeBudget(next, bills, month)
                .copy(plans = old?.plans.orEmpty(), paybacks = old?.paybacks.orEmpty(), marks = old?.marks.orEmpty())), today)
            .also(::validate)
    }

    private fun storedOrNew(data: AppData, month: YearMonth): MonthBudget =
        data.budgets.firstOrNull { it.month == month.toString() } ?: makeBudget(requireNotNull(data.settings), data.bills, month)

    /** The weekday weights a month is calculated with. The current month follows the chosen mode; Evenly means truly even, whatever older weights were saved. */
    private fun weightsFor(data: AppData, month: YearMonth, today: LocalDate, stored: MonthBudget): List<Int> = when {
        month != YearMonth.from(today) -> stored.weights
        data.settings?.weightMode == "HABIT" -> habitWeights(data, today)
        else -> List(7) { 1 }
    }

    /** A stored month as the calculator sees it: bills are what is charged after daily bills skip the no-spend days. */
    private fun snapshotOf(data: AppData, stored: MonthBudget, w: List<Int>, today: LocalDate) = BudgetSnapshot(
        YearMonth.parse(stored.month), LocalDate.parse(stored.startDate), Money(stored.incomeMinor),
        Money(billsFor(data, stored, today)), Money(stored.savingsMinor), WeeklyPlan(w[0], w[1], w[2], w[3], w[4], w[5], w[6]),
        stored.plans.map { (date, minor) -> LocalDate.parse(date) to Money(minor) }.toMap())

    private fun transactionsOf(data: AppData): List<Transaction> = data.entries.flatMap { entry ->
        val d = LocalDate.parse(entry.date)
        val amount = Money(entry.amountMinor)
        when (entry.type) {
            EntryType.EXPENSE -> listOf(Transaction.Expense(entry.id, d, amount, Category.valueOf(entry.category),
                if (entry.family) Payer.FAMILY else Payer.SELF))
            EntryType.BILL_PAYMENT -> listOf(Transaction.Expense(entry.id, d, amount, Category.LIVING, kind = ExpenseKind.BILL_PAYMENT))
            EntryType.FUND_ALLOCATION -> listOf(Transaction.Expense(entry.id, d, amount, Category.OTHER,
                kind = ExpenseKind.FUND_CONTRIBUTION, fundId = entry.targetId))
            EntryType.FUND_PURCHASE -> if (entry.amountMinor > entry.reservedMinor)
                listOf(Transaction.Expense(entry.id, d, Money(entry.amountMinor - entry.reservedMinor), Category.SHOPPING))
                else emptyList()
            EntryType.FUND_RELEASE -> listOf(Transaction.Income(entry.id, d, amount, IncomeKind.FUND_RELEASE))
            EntryType.INCOME -> listOf(Transaction.Income(entry.id, d, amount))
            else -> emptyList()
        }
    }

    fun result(data: AppData, month: YearMonth, today: LocalDate): BudgetResult {
        val stored = storedOrNew(data, month)
        val snapshot = snapshotOf(data, stored, weightsFor(data, month, today, stored), today)
        // A clock moved back across time zones can put today before the stored start date.
        val date = (if (month == YearMonth.from(today)) today else month.atEndOfMonth())
            .coerceAtLeast(snapshot.startDate)
        return BudgetCalculator().calculate(snapshot, transactionsOf(data), date)
    }

    /** What a day today or later would get if it had no plan of its own: the amount offered when a no-spend day becomes a spend limit. */
    fun suggestedFor(data: AppData, date: LocalDate, today: LocalDate): Long {
        val month = YearMonth.from(date)
        val free = data.copy(budgets = data.budgets.map { if (it.month == month.toString()) it.copy(plans = it.plans - date.toString()) else it })
        if (month == YearMonth.from(today)) return result(free, month, today).dayShares[date]?.minor ?: 0L
        // A later month starts fresh, so look at it from its first day.
        val weights = if (data.settings?.weightMode == "HABIT") habitWeights(data, today) else List(7) { 1 }
        val snapshot = snapshotOf(free, storedOrNew(free, month), weights, today)
        return BudgetCalculator().calculate(snapshot, transactionsOf(data), month.atDay(1)).dayShares[date]?.minor ?: 0L
    }

    /**
     * A day's own budget: what it gets with no plan of its own. For today and later that is the suggestion; for a day already
     * gone it is what that day was allowed, given everything spent before it.
     */
    fun allowanceOn(data: AppData, date: LocalDate, today: LocalDate): Long {
        if (date >= today) return suggestedFor(data, date, today)
        val month = YearMonth.from(date)
        val stored = storedOrNew(data, month)
        val snapshot = snapshotOf(data, stored, weightsFor(data, month, today, stored), today)
        return BudgetCalculator().calculate(snapshot, transactionsOf(data), date.coerceAtLeast(snapshot.startDate)).todayAllowance.minor
    }

    /** How many weeks of history the weekday pattern looks back over. */
    const val HABIT_WEEKS = 8

    /**
     * Average self-paid spending per weekday (Monday first) over the last [HABIT_WEEKS] weeks before
     * [today], counting only weekdays inside that window and on or after the budget start.
     */
    fun weekdayAverages(data: AppData, today: LocalDate): List<Long> {
        val start = maxOf(today.minusWeeks(HABIT_WEEKS.toLong()), data.settings?.let(::budgetFrom) ?: today)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it < today }.toList()
        val spent = data.entries.filter { !it.family && it.type in setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE) }
            .groupBy { LocalDate.parse(it.date) }.mapValues { (_, rows) -> sum(rows.map { it.amountMinor }) }
        return (1..7).map { weekday ->
            val matching = days.filter { it.dayOfWeek.value == weekday }
            if (matching.isEmpty()) 0L else sum(matching.map { spent[it] ?: 0L }) / matching.size
        }
    }

    /**
     * Weekday weights that follow real habits: each day is weighted by its share of average spending,
     * kept between 3 and 30 so a quiet day still gets something. Falls back to even weights until
     * there is spending history to learn from.
     */
    fun habitWeights(data: AppData, today: LocalDate): List<Int> {
        val averages = weekdayAverages(data, today)
        val mean = averages.sum() / 7.0
        if (mean <= 0.0) return List(7) { 10 }
        return averages.map { (it / mean * 10).roundToInt().coerceIn(3, 30) }
    }

    /** Sets (or with null clears) the planned spend for [date]. Only today and later days can be planned. */
    fun setPlan(data: AppData, date: LocalDate, minor: Long?, today: LocalDate): AppData {
        require(date >= today) { "You can only plan today or a later day" }
        require(minor == null || minor in 0..100_000_000_000L) { "Enter an amount of zero or more" }
        val month = YearMonth.from(date)
        val ensured = ensureMonths(data, maxOf(today, date))
        val budget = ensured.budgets.firstOrNull { it.month == month.toString() }
            ?: makeBudget(requireNotNull(ensured.settings), ensured.bills, month)
        val plans = if (minor == null) budget.plans - date.toString() else budget.plans + (date.toString() to minor)
        return ensured.copy(budgets = ensured.budgets.filterNot { it.month == month.toString() } + budget.copy(plans = plans))
            .also(::validate)
    }

    // ---------- cheaper swaps ----------
    fun swapSaving(s: Swap): Long = s.usualMinor - s.cheaperMinor

    fun defaultSwaps(): List<Swap> = listOf(
        Swap("seed-train-bus", "TRANSPORT", "🚌", "Train", 1000, "Bus", 350),
        Swap("seed-ride-train", "TRANSPORT", "🚆", "Grab ride", 1500, "Train", 500),
        Swap("seed-breakfast-bread", "FOOD", "🍞", "Café breakfast", 800, "Bread at home", 200),
        Swap("seed-lunch-pack", "FOOD", "🍱", "Lunch out", 1200, "Packed lunch", 500),
        Swap("seed-tea-water", "FOOD", "💧", "Bubble tea", 900, "Water from home", 0),
        Swap("seed-coffee-home", "FOOD", "☕", "Café coffee", 1000, "Coffee at home", 200),
    )

    private val transportWords = Regex("\\b(bus|train|mrt|lrt|grab|ride|taxi|cab|car|bike|petrol|parking|toll|ferry|flight|transport)\\b", RegexOption.IGNORE_CASE)
    private val shoppingWords = Regex("\\b(shirt|shoe|shoes|clothes|bag|shop|shopee|lazada|mall|shopping)\\b", RegexOption.IGNORE_CASE)
    private val funWords = Regex("\\b(movie|cinema|game|games|karaoke|bowling|concert|netflix|spotify|fun)\\b", RegexOption.IGNORE_CASE)

    /** Which category a name sounds like (a bill called Food, a swap called Bus). Used to line swaps up with the bills they could replace. */
    fun guessCategory(text: String): String = when {
        transportWords.containsMatchIn(text) -> "TRANSPORT"
        shoppingWords.containsMatchIn(text) -> "SHOPPING"
        funWords.containsMatchIn(text) -> "FUN"
        else -> "FOOD"
    }

    private val swapStop = setOf("out", "the", "and", "for", "home", "from", "with", "instead")
    private fun nameWords(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in swapStop }

    /** What a bill costs per day, to set against a cheaper option: a daily bill as it is, a weekly one ÷ 7, a monthly one spread over the month. */
    fun billPerDay(bill: PlannedBill, month: YearMonth): Long = when (bill.frequency) {
        "DAILY" -> bill.amountMinor
        "WEEKLY" -> Math.round(bill.amountMinor / 7.0)
        else -> Math.round(bill.amountMinor / month.lengthOfMonth().toDouble())
    }

    /**
     * A swap as it applies to your fixed bills: a Breakfast bill lines up with the breakfast swap and is priced against what that bill really costs.
     * Returns the swap with the bill's name and price as "usual" (so its saving is bill minus cheaper), the swap itself when no bill is like it,
     * and null when a bill is like it but the cheaper option isn't actually cheaper than the bill.
     */
    fun swapForBills(swap: Swap, bills: List<PlannedBill>, month: YearMonth): SwapChoice? {
        val words = nameWords(swap.usualName)
        val hits = bills.filter { !it.family && nameWords(it.name).any { w -> w in words } }
        if (hits.isEmpty()) return SwapChoice(swap)
        val best = hits.map { it to billPerDay(it, month) }.filter { it.second > swap.cheaperMinor }.maxByOrNull { it.second } ?: return null
        return SwapChoice(swap.copy(usualName = best.first.name, usualMinor = best.second), fromBill = true, billId = best.first.id)
    }

    /** Spreads [rest] evenly over [days], each taking at most its room; a day that runs out drops out and the others cover it. Returns the cuts and what is left. */
    private fun spread(rest: Long, days: List<String>, room: Map<String, Long>): Pair<Map<String, Long>, Long> {
        val cuts = days.associateWith { 0L }.toMutableMap(); var left = rest
        var open = days.filter { room.getValue(it) > 0 }
        while (left > 0 && open.isNotEmpty()) {
            val share = left / open.size; var extra = left - share * open.size
            for (d in open) {
                val give = minOf(share + if (extra > 0) 1 else 0, room.getValue(d) - cuts.getValue(d)); if (extra > 0) extra--
                cuts[d] = cuts.getValue(d) + give; left -= give
            }
            open = open.filter { cuts.getValue(it) < room.getValue(it) }
        }
        return cuts to left
    }

    /**
     * Buying [amount] more today than today allows: a gentle way to pay the overspend back over the next days. Null when it fits today.
     * Nothing is used until you tap it: with no [pick] the overspend is only trimmed evenly, about RM 1 a day.
     * [pick] (date to swap ids) is the swaps you tapped. A swap that matches one of your fixed bills (breakfast) takes its saving from that bill, so it
     * counts toward the overspend without using up the day's own money (one per bill a day, and it always fits); any other swap (the bus for the train)
     * is deducted from that day, never two from the same category and never more than the day has. What is left is trimmed evenly over the days.
     * Days that already have a plan are left alone. The options are the next five days with the swaps that could go on each.
     */
    fun payback(data: AppData, amount: Long, today: LocalDate, pick: Map<String, List<String>>? = null): PaybackPlan? {
        val month = YearMonth.from(today)
        val r = result(data, month, today)
        val over = amount - maxOf(r.todayRemaining.minor, 0L)
        if (over <= 0) return null
        val plans = data.budgets.firstOrNull { it.month == month.toString() }?.plans.orEmpty()
        fun was(d: String) = maxOf(r.dayShares[LocalDate.parse(d)]?.minor ?: 0L, 0L)
        val usable = r.futureAllowances.keys.sorted().map { it.toString() }.filter { it !in plans && was(it) > 0 }

        // Swaps for the kinds of bills you have come first, then the categories you spend most on.
        val since = today.minusWeeks(HABIT_WEEKS.toLong()).toString()
        val spendTypes = setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE)
        val byCat = data.entries.filter { !it.family && it.type in spendTypes && it.date >= since }
            .groupBy { it.category }.mapValues { (_, rows) -> sum(rows.map { it.amountMinor }) }
        val billCats = data.bills.filterNot { it.family }.map { guessCategory(it.name) }.toSet()
        // A swap that matches one of your bills (breakfast to breakfast) goes first and is priced against that bill; only if it is really cheaper.
        val pool = data.swaps.mapNotNull { swapForBills(it, data.bills, month) }.filter { it.saving > 0 }
            .sortedWith(compareByDescending<SwapChoice> { it.fromBill }.thenByDescending { it.swap.category in billCats }
                .thenByDescending { byCat[it.swap.category] ?: 0L }.thenByDescending { it.saving })
        val byId = pool.associateBy { it.id }

        val swapsOn = linkedMapOf<String, List<SwapChoice>>(); var rest = over
        for (date in usable) {
            var room = was(date); val got = mutableListOf<SwapChoice>()
            for (id in pick?.get(date).orEmpty()) {
                val s = byId[id] ?: continue
                if (s.fromBill) { if (got.none { it.billId == s.billId }) got += s }
                else if (s.saving <= room && got.none { !it.fromBill && it.swap.category == s.swap.category }) { got += s; room -= s.saving }
            }
            if (got.isNotEmpty()) { swapsOn[date] = got; rest -= got.sumOf { it.saving } }
        }
        rest = maxOf(rest, 0L)
        val swapDays = swapsOn.size
        val swapSpan = swapsOn.keys.maxOfOrNull { usable.indexOf(it) + 1 } ?: 0
        fun sumOn(date: String, fromBill: Boolean) = swapsOn[date].orEmpty().filter { it.fromBill == fromBill }.sumOf { it.saving }
        // The leftover goes evenly over the swap days plus enough following days to keep it to about RM 1 each (3 to 14 days when there are no swaps).
        val span = minOf(usable.size, 14, maxOf(swapSpan, if (rest > 0) maxOf(if (swapDays > 0) 1 else 3, ((rest + 99) / 100).toInt()) else 0))
        val days = usable.take(span)
        val trim = spread(rest, days, days.associateWith { was(it) - sumOn(it, false) })
        val out = days.map { date ->
            val cut = sumOn(date, false) + trim.first.getValue(date)
            PaybackDayPlan(date, was(date), swapsOn[date].orEmpty(), trim.first.getValue(date), cut, sumOn(date, true), was(date) - cut)
        }.filter { it.cut > 0 || it.billSaved > 0 }
        // The icons offered under each day: one for each of your bills that has a cheaper swap (breakfast, dinner), then another kind (transport) if there is room.
        val cands = pool.filterIndexed { i, s -> s.fromBill && pool.indexOfFirst { it.fromBill && it.billId == s.billId } == i }.take(3).toMutableList()
        for (s in pool) if (cands.size < 2 && !s.fromBill && cands.none { it.swap.category == s.swap.category }) cands += s
        val options = usable.take(5).map { date ->
            val o = out.firstOrNull { it.date == date }; val cut = o?.cut ?: 0L
            PaybackOption(date, was(date), cut, was(date) - cut, o?.billSaved ?: 0L, swapsOn[date].orEmpty(),
                cands.map { it.copy(fits = it.fromBill || it.saving <= was(date)) })
        }
        return PaybackPlan(over, maxOf(r.todayAllowance.minor, 0L), r.spentToday.minor + amount, out, options, over - trim.second, trim.second)
    }

    /** Remembers a payback in the month it was made, so the swaps can be shown again on those days later. */
    fun addPayback(data: AppData, record: Payback): AppData {
        val month = record.date.take(7)
        if (data.budgets.none { it.month == month }) return data
        return data.copy(budgets = data.budgets.map { if (it.month == month) it.copy(paybacks = it.paybacks + record) else it })
    }

    fun accountBalance(data: AppData, account: WalletAccount): Long = data.entries.fold(account.openingMinor) { balance, e ->
        val delta = when {
            e.type == EntryType.TRANSFER && e.toAccountId == account.id -> e.amountMinor
            e.accountId != account.id -> 0L
            e.type in setOf(EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.ADJUSTMENT) -> e.amountMinor
            e.type == EntryType.EXPENSE && !e.family -> -e.amountMinor
            e.type in setOf(EntryType.BILL_PAYMENT, EntryType.TRANSFER, EntryType.FUND_PURCHASE) -> -e.amountMinor
            else -> 0L
        }
        Math.addExact(balance, delta)
    }

    fun fundSaved(data: AppData, fundId: String): Long = sum(data.entries.filter { it.targetId == fundId }.map {
        when (it.type) {
            EntryType.FUND_ALLOCATION -> it.amountMinor
            EntryType.FUND_RELEASE -> -it.amountMinor
            EntryType.FUND_PURCHASE -> -it.reservedMinor
            else -> 0L
        }
    })

    fun goalSaved(data: AppData, goalId: String): Long = sum(data.entries.filter { it.targetId == goalId }.map {
        when (it.type) {
            EntryType.GOAL_DEPOSIT -> it.amountMinor
            EntryType.GOAL_WITHDRAWAL -> -it.amountMinor
            else -> 0L
        }
    })

    fun saveEntry(data: AppData, entry: LedgerEntry, today: LocalDate): AppData {
        require(entry.type in setOf(EntryType.EXPENSE, EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.BILL_PAYMENT))
        require(entry.amountMinor > 0) { "Enter an amount greater than zero" }
        val date = LocalDate.parse(entry.date)
        require(date <= today) { "You can’t record a future transaction" }
        require(date >= budgetFrom(requireNotNull(data.settings))) { "That date is before your budget started" }
        val previous = data.entries.find { it.id == entry.id }
        require(previous == null || previous.type in setOf(EntryType.EXPENSE, EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.BILL_PAYMENT))
        val saved = if (entry.type == EntryType.BILL_PAYMENT) entry.copy(category = Category.LIVING.name) else entry
        return data.copy(entries = data.entries.filterNot { it.id == saved.id } + saved).also(::validate)
    }

    fun deleteEntry(data: AppData, id: String): AppData {
        val entry = data.entries.first { it.id == id }
        require(entry.type != EntryType.FUND_RELEASE || data.funds.none { it.id == entry.targetId && it.boughtOn != null }) {
            "Delete this fund’s purchase entry before changing its leftover"
        }
        var next = data.copy(entries = data.entries.filterNot { it.id == id }, funds = data.funds.map {
            if (entry.type == EntryType.FUND_PURCHASE && it.id == entry.targetId) it.copy(boughtOn = null) else it
        })
        next = next.copy(funds = next.funds.map {
            if (it.archived && it.boughtOn == null && fundSaved(next, it.id) > 0) it.copy(archived = false) else it
        })
        return next.also(::validate)
    }

    fun transfer(data: AppData, from: String, to: String, amount: Long, date: LocalDate): AppData {
        require(from != to) { "Choose two different accounts" }
        require(amount > 0) { "Enter an amount greater than zero" }
        require(data.accounts.any { it.id == from && !it.archived } && data.accounts.any { it.id == to && !it.archived })
        return data.copy(entries = data.entries + LedgerEntry(id(), date.toString(), amount, EntryType.TRANSFER,
            note = "Account transfer", accountId = from, toAccountId = to)).also(::validate)
    }

    fun setBalance(data: AppData, accountId: String, balance: Long, date: LocalDate): AppData {
        val account = data.accounts.first { it.id == accountId }
        val delta = Math.subtractExact(balance, accountBalance(data, account))
        if (delta == 0L) return data
        return data.copy(entries = data.entries + LedgerEntry(id(), date.toString(), delta,
            EntryType.ADJUSTMENT, note = "Balance adjustment", accountId = accountId)).also(::validate)
    }

    fun fundMove(data: AppData, fundId: String, amount: Long, release: Boolean, date: LocalDate): AppData {
        val fund = data.funds.first { it.id == fundId }
        require(!fund.archived && fund.boughtOn == null)
        require(amount > 0) { "Enter an amount greater than zero" }
        require(!release || fundSaved(data, fundId) >= amount) { "Not enough in this fund" }
        return data.copy(entries = data.entries + LedgerEntry(id(), date.toString(), amount,
            if (release) EntryType.FUND_RELEASE else EntryType.FUND_ALLOCATION,
            note = (if (release) "Released: " else "Set aside: ") + fund.name, targetId = fundId)).also(::validate)
    }

    fun buyFund(data: AppData, fundId: String, amount: Long, accountId: String?, date: LocalDate): AppData {
        val fund = data.funds.first { it.id == fundId }
        require(!fund.archived && fund.boughtOn == null)
        require(amount > 0) { "Enter an amount greater than zero" }
        val saved = fundSaved(data, fundId)
        val used = minOf(saved, amount)
        var next = data.copy(entries = data.entries + LedgerEntry(id(), date.toString(), amount,
            EntryType.FUND_PURCHASE, category = "SHOPPING", note = fund.name,
            accountId = accountId, targetId = fundId, reservedMinor = used))
        // The leftover release can be removed only after the purchase is removed.
        if (saved > amount) next = next.copy(entries = next.entries + LedgerEntry(id(), date.toString(), saved - amount,
            EntryType.FUND_RELEASE, note = "Leftover from ${fund.name}", targetId = fundId))
        return next.copy(funds = next.funds.map { if (it.id == fundId) it.copy(boughtOn = date.toString()) else it }).also(::validate)
    }

    fun archiveFund(data: AppData, fundId: String, date: LocalDate): AppData {
        val saved = fundSaved(data, fundId)
        val released = if (saved > 0) fundMove(data, fundId, saved, true, date) else data
        return released.copy(funds = released.funds.map { if (it.id == fundId) it.copy(archived = true) else it })
    }

    fun goalMove(data: AppData, goalId: String, amount: Long, withdraw: Boolean, date: LocalDate): AppData {
        require(data.goals.any { it.id == goalId && !it.archived })
        require(amount > 0) { "Enter an amount greater than zero" }
        require(!withdraw || goalSaved(data, goalId) >= amount) { "Not enough saved in this goal" }
        return data.copy(entries = data.entries + LedgerEntry(id(), date.toString(), amount,
            if (withdraw) EntryType.GOAL_WITHDRAWAL else EntryType.GOAL_DEPOSIT,
            note = if (withdraw) "Taken from goal" else "Saved to goal", targetId = goalId)).also(::validate)
    }

    fun csv(data: AppData): String {
        fun cell(value: String, protect: Boolean): String {
            // Quoted CSV is still interpreted as a formula by spreadsheet software.
            val safe = if (protect && value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val rows = data.entries.sortedWith(compareBy<LedgerEntry> { it.date }.thenBy { it.createdAt }).map { e ->
            listOf(e.date, e.type.name, Money(e.amountMinor).decimal(), "MYR", e.category, e.note,
                data.accounts.find { it.id == e.accountId }?.name.orEmpty(),
                data.accounts.find { it.id == e.toAccountId }?.name.orEmpty(), e.family.toString())
                .mapIndexed { index, value -> cell(value, protect = index != 2) }.joinToString(",")
        }
        return "\uFEFFdate,type,amount,currency,category,note,account,to_account,family_paid\r\n" + rows.joinToString("\r\n")
    }

    fun validate(data: AppData) {
        require(data.version == 1) { "This backup version isn’t supported" }
        val s = data.settings
        if (s == null) {
            require(data.entries.isEmpty() && data.accounts.isEmpty() && data.budgets.isEmpty() && data.funds.isEmpty() && data.goals.isEmpty() && data.bills.isEmpty() && data.wishes.isEmpty())
            return
        }
        val maxAmount = 100_000_000_000L
        // One day of slack keeps stored data valid after travelling to an earlier time zone;
        // saveEntry still rejects new entries dated after the caller's today.
        val latest = LocalDate.now().plusDays(1)
        require(s.incomeMinor > 0 && s.incomeMinor <= 100_000_000_00L && s.savingsMinor in 0..maxAmount)
        require(s.incomeFrequency in setOf("DAILY", "WEEKLY", "MONTHLY"))
        require(s.weightMode in setOf("EVEN", "HABIT"))
        require(LocalDate.parse(s.started).year in 2000..2100)
        require(LocalDate.parse(s.started) <= latest) { "Your budget can’t start in the future" }
        fun weights(w: List<Int>) = require(w.size == 7 && w.all { it in 0..100 } && w.any { it > 0 }) { "Give at least one day of the week an allowance" }
        weights(s.weights)
        require(data.entries.size <= 50_000 && data.accounts.size <= 1_000 && data.bills.size <= 1_000 &&
            data.funds.size <= 1_000 && data.goals.size <= 1_000 && data.wishes.size <= 5_000 && data.budgets.size <= 2_000) { "Too many records for this version" }
        fun ids(values: List<String>) = require(values.all { it.isNotBlank() } && values.toSet().size == values.size) { "Invalid or duplicate record ID" }
        ids(data.entries.map { it.id }); ids(data.accounts.map { it.id }); ids(data.funds.map { it.id })
        ids(data.goals.map { it.id }); ids(data.wishes.map { it.id }); ids(data.bills.map { it.id }); ids(data.budgets.map { it.month })
        data.bills.forEach { require(it.name.isNotBlank() && it.amountMinor in 1..maxAmount && it.frequency in setOf("DAILY", "WEEKLY", "MONTHLY")) }
        data.accounts.forEach { require(it.name.isNotBlank() && it.openingMinor in -maxAmount..maxAmount && it.type in setOf("BANK", "EWALLET", "CASH", "CREDIT")) }
        data.budgets.forEach {
            require(YearMonth.from(LocalDate.parse(it.startDate)).toString() == it.month)
            require(it.incomeMinor in 0..maxAmount * 31_000 && it.billsMinor in 0..maxAmount * 31_000 && it.savingsMinor in 0..maxAmount * 31_000); weights(it.weights)
            it.plans.forEach { (date, minor) -> require(YearMonth.from(LocalDate.parse(date)).toString() == it.month && minor in 0..maxAmount) { "Invalid day plan" } }
            it.marks.forEach { (date, mark) -> require(YearMonth.from(LocalDate.parse(date)).toString() == it.month && mark in MARKS) { "Invalid day mark" } }
            it.paybacks.forEach { p ->
                require(YearMonth.from(LocalDate.parse(p.date)).toString() == it.month && p.amountMinor > 0) { "Invalid payback" }
                p.days.forEach { x -> require(YearMonth.from(LocalDate.parse(x.date)).toString() == it.month && x.cutMinor >= 0) { "Invalid payback day" } }
            }
        }
        ids(data.swaps.map { it.id })
        require(data.swaps.size <= 500) { "Too many swaps" }
        data.swaps.forEach { w ->
            require(w.category in Category.entries.map { it.name } && w.usualName.isNotBlank() && w.cheaperName.isNotBlank() &&
                w.cheaperMinor >= 0 && w.usualMinor > w.cheaperMinor && w.usualMinor <= maxAmount && w.icon.length <= 12) { "A swap needs two names and a cheaper price" }
        }
        data.entries.forEach { e ->
            require(LocalDate.parse(e.date) >= budgetFrom(s))
            require(LocalDate.parse(e.date) <= latest) { "Transaction dates can’t be in the future" }
            require(e.amountMinor > 0 || e.type == EntryType.ADJUSTMENT) { "Transaction amounts must be greater than zero" }
            require(e.amountMinor in -maxAmount..maxAmount) { "Transaction amount is out of range" }
            require(!e.family || e.type == EntryType.EXPENSE)
            require(e.accountId == null || data.accounts.any { it.id == e.accountId })
            require(e.toAccountId == null || data.accounts.any { it.id == e.toAccountId })
            require(e.category in Category.entries.map { it.name })
            require(e.reservedMinor >= 0 && (e.type != EntryType.FUND_PURCHASE || e.reservedMinor <= e.amountMinor))
            if (e.type == EntryType.TRANSFER) require(e.accountId != null && e.toAccountId != null && e.accountId != e.toAccountId)
            if (e.type in setOf(EntryType.FUND_ALLOCATION, EntryType.FUND_RELEASE, EntryType.FUND_PURCHASE))
                require(data.funds.any { it.id == e.targetId })
            if (e.type in setOf(EntryType.GOAL_DEPOSIT, EntryType.GOAL_WITHDRAWAL)) require(data.goals.any { it.id == e.targetId })
        }
        data.funds.forEach {
            require(it.name.isNotBlank() && it.priceMinor in 1..maxAmount)
            if (it.deadline.isNotEmpty()) LocalDate.parse(it.deadline)
            require(fundSaved(data, it.id) >= 0) { "Not enough in the fund. Handle the later releases or purchases first" }
            require(data.entries.count { e -> e.targetId == it.id && e.type == EntryType.FUND_PURCHASE } <= 1)
            require((it.boughtOn != null) == data.entries.any { e -> e.targetId == it.id && e.type == EntryType.FUND_PURCHASE })
            if (it.boughtOn != null) LocalDate.parse(it.boughtOn)
        }
        data.goals.forEach {
            require(it.name.isNotBlank() && it.targetMinor in 1..maxAmount)
            if (it.deadline.isNotEmpty()) LocalDate.parse(it.deadline)
            require(goalSaved(data, it.id) >= 0) { "Not enough saved. Handle the later withdrawals first" }
        }
        data.wishes.forEach { require(it.name.isNotBlank() && it.priceMinor in 1..maxAmount); LocalDate.parse(it.date) }
        data.accounts.forEach { accountBalance(data, it) }
        data.budgets.forEach { Math.subtractExact(Math.subtractExact(it.incomeMinor, it.billsMinor), it.savingsMinor) }
    }
}
