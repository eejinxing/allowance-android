package com.allowance.core

import com.allowance.core.calculator.BudgetCalculator
import com.allowance.core.model.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class BudgetCalculatorTest {
    private val month = YearMonth.of(2026, 10)
    private val today = month.atDay(22) // Ten days left, including today.
    private val calculator = BudgetCalculator()
    private fun budget(
        income: Money = Money(30_000), fixed: Money = Money.ZERO,
        goal: Money = Money.ZERO, plan: WeeklyPlan = WeeklyPlan(),
    ) = BudgetSnapshot(month, month.atDay(1), income, fixed, goal, plan)

    private fun expense(
        id: String = "lunch", amount: Money = Money(1_200), date: LocalDate = today,
        payer: Payer = Payer.SELF, kind: ExpenseKind = ExpenseKind.SPENDING,
    ) = Transaction.Expense(id, date, amount, Category.FOOD, payer, kind,
        fundId = if (kind in setOf(ExpenseKind.FUND_CONTRIBUTION, ExpenseKind.FUND_PURCHASE)) "shoes" else null)

    @Test fun `300 ringgit over ten days gives 30 today and 18 after lunch`() {
        val result = calculator.calculate(budget(), listOf(expense()), today)
        assertEquals(Money(3_000), result.todayAllowance)
        assertEquals(Money(1_800), result.todayRemaining)
        assertEquals(Money(28_800), result.remaining)
        assertEquals(result.remaining, result.futureAllowances.values.fold(Money.ZERO, Money::plus))
    }

    @Test fun `prior spending and reserves reduce the pool`() {
        val result = calculator.calculate(budget(Money(100_000), Money(20_000), Money(20_000)),
            listOf(expense(amount = Money(30_000), date = today.minusDays(1))), today)
        assertEquals(Money(60_000), result.spendable)
        assertEquals(Money(3_000), result.todayAllowance)
    }

    @Test fun `family bills fund purchases transfers and scheduled receipts do not double count`() {
        val entries = listOf(
            expense(id = "family", payer = Payer.FAMILY),
            expense(id = "bill", kind = ExpenseKind.BILL_PAYMENT),
            expense(id = "purchase", kind = ExpenseKind.FUND_PURCHASE),
            Transaction.Transfer("transfer", today, Money(5_000), "bank", "cash"),
            Transaction.Income("scheduled", today, Money(30_000), IncomeKind.SCHEDULED),
        )
        val result = calculator.calculate(budget(), entries, today)
        assertEquals(Money(30_000), result.remaining)
        assertEquals(Money.ZERO, result.spentToday)
    }

    @Test fun `fund contribution reduces allowance and release adds it back`() {
        val result = calculator.calculate(budget(), listOf(
            expense(kind = ExpenseKind.FUND_CONTRIBUTION, amount = Money(1_000)),
            Transaction.Income("release", today, Money(400), IncomeKind.FUND_RELEASE),
        ), today)
        assertEquals(Money(29_400), result.remaining)
        assertEquals(Money(1_000), result.spentToday)
    }

    @Test fun `only received extra income counts`() {
        val result = calculator.calculate(budget(), listOf(
            Transaction.Income("received", today, Money(5_000)),
            Transaction.Income("future", today.plusDays(1), Money(8_000)),
            expense(id = "tomorrow", date = today.plusDays(1)),
            expense(id = "last-month", date = month.minusMonths(1).atEndOfMonth()),
        ), today)
        assertEquals(Money(35_000), result.spendable)
        assertEquals(Money.ZERO, result.spentToday)
    }

    @Test fun `weighted days and zero spending days conserve every sen`() {
        val plan = WeeklyPlan(monday = 0, tuesday = 0, wednesday = 0, thursday = 0,
            friday = 1, saturday = 2, sunday = 0)
        val result = calculator.calculate(budget(Money(101), plan = plan), emptyList(), today)
        assertEquals(Money.ZERO, result.todayAllowance)
        assertEquals(Money(101), result.futureAllowances.values.fold(Money.ZERO, Money::plus))
        assertEquals(Money(17), result.futureAllowances.getValue(month.atDay(23)))
        assertEquals(Money(34), result.futureAllowances.getValue(month.atDay(24)))
    }

    @Test fun `negative pool is distributed without clamping debt away`() {
        val result = calculator.calculate(budget(Money.ZERO, goal = Money(101)), emptyList(), today)
        assertEquals(Money(-11), result.todayAllowance)
        assertEquals(Money(-101), result.futureAllowances.values.fold(Money.ZERO, Money::plus))
    }

    @Test fun `overspending tightens future allowances`() {
        val result = calculator.calculate(budget(), listOf(expense(amount = Money(5_000))), today)
        assertEquals(Money(-2_000), result.todayRemaining)
        assertEquals(Money(25_000), result.futureAllowances.values.fold(Money.ZERO, Money::plus))
    }

    @Test fun `last day leaves money unallocated after today`() {
        val result = calculator.calculate(budget(), listOf(expense(date = month.atEndOfMonth())), month.atEndOfMonth())
        assertEquals(Money(28_800), result.todayRemaining)
        assertTrue(result.futureAllowances.isEmpty())
    }

    @Test fun `remaining dates can all have zero weight`() {
        val result = calculator.calculate(budget(plan = WeeklyPlan(
            monday = 1, tuesday = 0, wednesday = 0, thursday = 0,
            friday = 0, saturday = 0, sunday = 0,
        )), emptyList(), month.atEndOfMonth())
        assertEquals(Money.ZERO, result.todayAllowance)
        assertEquals(Money(30_000), result.unallocated)
    }

    @Test fun `partial month ignores earlier records`() {
        val snapshot = budget().copy(startDate = today)
        val result = calculator.calculate(snapshot, listOf(expense(date = today.minusDays(1))), today)
        assertEquals(Money.ZERO, result.spentBeforeToday)
    }

    @Test fun `spending on a zero weight day still reduces the pool`() {
        val snapshot = budget(plan = WeeklyPlan(
            monday = 1, tuesday = 0, wednesday = 0, thursday = 0,
            friday = 0, saturday = 0, sunday = 0,
        ))
        val end = month.atEndOfMonth()
        val result = calculator.calculate(snapshot, listOf(expense(date = end)), end)
        assertEquals(Money(-1_200), result.todayRemaining)
        assertEquals(Money(28_800), result.unallocated)
    }

    @Test fun `leap day is a valid final day`() {
        val leap = BudgetSnapshot(YearMonth.of(2028, 2), LocalDate.of(2028, 2, 1), Money(100), Money.ZERO, Money.ZERO)
        val result = calculator.calculate(leap, emptyList(), LocalDate.of(2028, 2, 29))
        assertEquals(Money(100), result.todayAllowance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `date outside snapshot is rejected`() {
        calculator.calculate(budget(), emptyList(), month.plusMonths(1).atDay(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate records are rejected`() {
        calculator.calculate(budget(), listOf(expense(), expense()), today)
    }

    @Test fun `allocation conserves pools for many amounts and dates`() {
        for (day in 1..31) for (minor in listOf(-10_001L, -101L, -1L, 0L, 1L, 101L, 10_001L)) {
            val snapshot = if (minor >= 0) budget(Money(minor)) else budget(Money.ZERO, goal = Money(-minor))
            val date = month.atDay(day)
            val result = calculator.calculate(snapshot, emptyList(), date)
            // Future projection rolls all of today's unused allowance forward.
            val projected = if (day == 31) result.todayRemaining
                else result.futureAllowances.values.fold(Money.ZERO, Money::plus)
            assertEquals(Money(minor), projected)
        }
    }

    @Test fun `a planned day gets exactly its plan and the rest spreads over other days`() {
        // RM 300 over ten days; RM 100 planned for Saturday the 24th leaves RM 200 for the other nine.
        val plans = mapOf(month.atDay(24) to Money(10_000))
        val result = calculator.calculate(budget().copy(plans = plans), emptyList(), today)
        assertEquals(Money(10_000), result.futureAllowances.getValue(month.atDay(24)))
        assertEquals(Money(30_000), result.futureAllowances.values.fold(Money.ZERO, Money::plus))
        assertEquals(Money(2_223), result.todayAllowance) // 20 000 / 9, remainder going to the earliest days
    }

    @Test fun `a zero plan is a no-spend day`() {
        val result = calculator.calculate(budget().copy(plans = mapOf(month.atDay(25) to Money.ZERO)), emptyList(), today)
        assertEquals(Money.ZERO, result.futureAllowances.getValue(month.atDay(25)))
        assertEquals(Money(30_000), result.futureAllowances.values.fold(Money.ZERO, Money::plus))
    }

    @Test fun `plans larger than the money left are scaled down and nothing else gets an allowance`() {
        val plans = mapOf(month.atDay(23) to Money(40_000), month.atDay(24) to Money(20_000))
        val result = calculator.calculate(budget().copy(plans = plans), emptyList(), today)
        assertEquals(Money(20_000), result.futureAllowances.getValue(month.atDay(23)))
        assertEquals(Money(10_000), result.futureAllowances.getValue(month.atDay(24)))
        assertEquals(Money.ZERO, result.todayAllowance)
    }

    @Test fun `day shares split what is left without counting today twice`() {
        val result = calculator.calculate(budget(), listOf(expense()), today)
        assertEquals(Money(3_000), result.dayShares.getValue(today))
        assertEquals(result.remaining + result.spentToday, result.dayShares.values.fold(Money.ZERO, Money::plus))
        val over = calculator.calculate(budget(), listOf(expense(amount = Money(5_000))), today)
        assertEquals(over.remaining, over.dayShares.filterKeys { it > today }.values.fold(Money.ZERO, Money::plus))
    }
}
