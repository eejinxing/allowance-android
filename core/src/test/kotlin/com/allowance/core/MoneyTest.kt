package com.allowance.core

import com.allowance.core.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun `decimal input uses exact minor units`() {
        assertEquals(Money(1_250), Money.parse("12.50"))
        assertEquals(Money(30), Money.parse("0.10") + Money.parse("0.20"))
        assertEquals("-0.01", Money(-1).decimal())
    }

    @Test(expected = ArithmeticException::class)
    fun `excess precision is rejected`() { Money.parse("12.501") }

    @Test(expected = ArithmeticException::class)
    fun `overflow is rejected`() { Money(Long.MAX_VALUE) + Money(1) }

    @Test(expected = IllegalArgumentException::class)
    fun `negative expenses are rejected`() {
        Transaction.Expense("x", LocalDate.of(2026, 10, 1), Money(-1), Category.FOOD)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transfer to same account is rejected`() {
        Transaction.Transfer("x", LocalDate.of(2026, 10, 1), Money(100), "cash", "cash")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `all zero weekday plan is rejected`() { WeeklyPlan(0, 0, 0, 0, 0, 0, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun `fund records need a fund ID`() {
        Transaction.Expense("x", LocalDate.of(2026, 10, 1), Money(1), Category.OTHER,
            kind = ExpenseKind.FUND_PURCHASE)
    }
}
