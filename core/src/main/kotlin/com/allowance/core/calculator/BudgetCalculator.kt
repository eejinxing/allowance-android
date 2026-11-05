package com.allowance.core.calculator

import com.allowance.core.model.*
import java.math.BigInteger
import java.time.LocalDate

data class BudgetResult(
    val spendable: Money,
    val spentBeforeToday: Money,
    val spentToday: Money,
    val remaining: Money,
    val todayAllowance: Money,
    val todayRemaining: Money,
    val futureAllowances: Map<LocalDate, Money>,
    /** Money left after today's spending when every remaining date has zero weight. */
    val unallocated: Money,
    /**
     * Each day's share of what is left, today through month end, summing to [remaining] plus today's
     * spending. Unlike [futureAllowances] it does not assume today's unspent share rolls forward,
     * so it is what a month calendar should show.
     */
    val dayShares: Map<LocalDate, Money> = emptyMap(),
)

class BudgetCalculator {
    /** Pure function: caller provides today's date, making midnight and tests explicit. */
    fun calculate(
        budget: BudgetSnapshot,
        transactions: List<Transaction>,
        today: LocalDate,
    ): BudgetResult {
        require(today >= budget.startDate && today <= budget.month.atEndOfMonth())
        require(transactions.map { it.id }.distinct().size == transactions.size) { "Duplicate transaction IDs" }
        // Future dated entries do not affect today's available money.
        val entries = transactions.filter { it.date >= budget.startDate && it.date <= today }
        val extra = entries.filterIsInstance<Transaction.Income>()
            .filter { it.kind != IncomeKind.SCHEDULED }.fold(Money.ZERO) { total, it -> total + it.amount }
        val expenses = entries.filterIsInstance<Transaction.Expense>().filter {
            it.payer == Payer.SELF && it.kind in setOf(ExpenseKind.SPENDING, ExpenseKind.FUND_CONTRIBUTION)
        }
        val before = expenses.filter { it.date < today }.fold(Money.ZERO) { sum, it -> sum + it.amount }
        val spentToday = expenses.filter { it.date == today }.fold(Money.ZERO) { sum, it -> sum + it.amount }
        val spendable = budget.income + extra - budget.fixedBills - budget.savingsGoal
        val pool = spendable - before
        val dates = generateSequence(today) { it.plusDays(1) }
            .takeWhile { it <= budget.month.atEndOfMonth() }.toList()
        val daily = distribute(budget, pool, dates).getValue(today)
        // Roll today's unspent (or overspent) money into the next days.
        val future = distribute(budget, pool - spentToday, dates.drop(1))
        val unallocated = pool - spentToday - future.values.fold(Money.ZERO) { sum, it -> sum + it }
        return BudgetResult(
            spendable, before, spentToday, pool - spentToday, daily, daily - spentToday,
            future, unallocated,
            mapOf(today to daily) + distribute(budget, pool - maxOf(daily, spentToday), dates.drop(1)),
        )
    }

    /**
     * Planned dates get exactly what was planned; whatever is left is spread over the other dates
     * by weekday weight. When the money can't cover the plans, or no unplanned day can take the
     * rest, the pool is shared out in proportion to the plans instead, so every sen stays assigned.
     */
    private fun distribute(budget: BudgetSnapshot, pool: Money, dates: List<LocalDate>): Map<LocalDate, Money> {
        val planned = dates.filter { it in budget.plans }.associateWith { budget.plans.getValue(it).minor }
        val free = dates.filterNot { it in budget.plans }.associateWith { budget.weeklyPlan.weight(it.dayOfWeek).toLong() }
        val planTotal = planned.values.fold(0L, Math::addExact)
        val freeTotal = free.values.sum()
        val shares = when {
            pool.minor >= planTotal && freeTotal > 0 -> planned.mapValues { Money(it.value) } + allocate(pool - Money(planTotal), free)
            planTotal > 0 -> allocate(pool, planned) + free.mapValues { Money.ZERO }
            freeTotal > 0 -> allocate(pool, free) + planned.mapValues { Money.ZERO }
            else -> dates.associateWith { Money.ZERO }
        }
        return dates.associateWith { shares.getValue(it) }
    }

    /** Largest remainder allocation conserves every sen, including negative pools. */
    private fun allocate(pool: Money, weights: Map<LocalDate, Long>): Map<LocalDate, Money> {
        val total = weights.values.fold(BigInteger.ZERO) { sum, weight -> sum + weight.toBigInteger() }
        if (total == BigInteger.ZERO) return weights.mapValues { Money.ZERO }
        val magnitude = pool.minor.toBigInteger().abs()
        val shares = weights.map { (date, weight) ->
            val (whole, remainder) = (magnitude * weight.toBigInteger()).divideAndRemainder(total)
            Triple(date, whole, remainder)
        }
        val remainderCount = (magnitude - shares.fold(BigInteger.ZERO) { sum, it -> sum + it.second }).intValueExact()
        val bonuses = shares.sortedWith(compareByDescending<Triple<LocalDate, BigInteger, BigInteger>> { it.third }
            .thenBy { it.first }).take(remainderCount).map { it.first }.toSet()
        return shares.associate { (date, whole, _) ->
            val value = whole + if (date in bonuses) BigInteger.ONE else BigInteger.ZERO
            date to Money((if (pool.minor < 0) -value else value).longValueExact())
        }
    }
}
