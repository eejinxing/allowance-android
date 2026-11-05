package com.allowance.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

enum class Category { FOOD, TRANSPORT, SHOPPING, FUN, LIVING, HEALTH, OTHER }
enum class AccountType { BANK, EWALLET, CASH, CREDIT }
enum class Frequency { DAILY, WEEKLY, MONTHLY }
enum class Payer { SELF, FAMILY }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val openingBalance: Money,
    val openingDate: LocalDate,
)

data class Bill(
    val id: String,
    val name: String,
    val amount: Money,
    val frequency: Frequency,
    val payer: Payer,
) {
    init { require(amount >= Money.ZERO) }
}

/** Separate kinds prevent planned bills and fund purchases being charged twice. */
enum class ExpenseKind { SPENDING, BILL_PAYMENT, FUND_CONTRIBUTION, FUND_PURCHASE }
enum class IncomeKind { EXTRA, FUND_RELEASE, SCHEDULED }

sealed interface Transaction {
    val id: String
    val date: LocalDate
    val amount: Money

    data class Expense(
        override val id: String,
        override val date: LocalDate,
        override val amount: Money,
        val category: Category,
        val payer: Payer = Payer.SELF,
        val kind: ExpenseKind = ExpenseKind.SPENDING,
        val accountId: String? = null,
        val fundId: String? = null,
        val note: String = "",
    ) : Transaction {
        init {
            require(amount > Money.ZERO)
            require(kind !in setOf(ExpenseKind.FUND_CONTRIBUTION, ExpenseKind.FUND_PURCHASE) || !fundId.isNullOrBlank())
        }
    }

    data class Income(
        override val id: String,
        override val date: LocalDate,
        override val amount: Money,
        val kind: IncomeKind = IncomeKind.EXTRA,
        val accountId: String? = null,
        val note: String = "",
    ) : Transaction {
        init { require(amount > Money.ZERO) }
    }

    data class Transfer(
        override val id: String,
        override val date: LocalDate,
        override val amount: Money,
        val fromAccountId: String,
        val toAccountId: String,
    ) : Transaction {
        init {
            require(amount > Money.ZERO)
            require(fromAccountId.isNotBlank() && toAccountId.isNotBlank())
            require(fromAccountId != toAccountId)
        }
    }
}

/** Immutable weekday weights: Monday through Sunday; zero is a no-spend day. */
data class WeeklyPlan(
    val monday: Int = 1, val tuesday: Int = 1, val wednesday: Int = 1,
    val thursday: Int = 1, val friday: Int = 1, val saturday: Int = 1, val sunday: Int = 1,
) {
    private fun values() = listOf(monday, tuesday, wednesday, thursday, friday, saturday, sunday)
    init {
        require(values().all { it >= 0 })
        require(values().any { it > 0 })
    }
    fun weight(day: DayOfWeek): Int = values()[day.value - 1]
}

/** Persist one per month so later setting changes do not rewrite past budgets. */
data class BudgetSnapshot(
    val month: YearMonth,
    val startDate: LocalDate,
    val income: Money,
    val fixedBills: Money,
    val savingsGoal: Money,
    val weeklyPlan: WeeklyPlan = WeeklyPlan(),
    /** Amounts the user planned for specific dates; RM 0 marks a no-spend day. */
    val plans: Map<LocalDate, Money> = emptyMap(),
    val currencyCode: String = "MYR",
) {
    init {
        require(YearMonth.from(startDate) == month)
        require(income >= Money.ZERO && fixedBills >= Money.ZERO && savingsGoal >= Money.ZERO)
        require(plans.all { (date, amount) -> YearMonth.from(date) == month && amount >= Money.ZERO })
        require(currencyCode == "MYR") { "First version supports MYR (two decimal places)." }
    }
}

data class SavingsGoal(val id: String, val name: String, val target: Money, val saved: Money) {
    init { require(target > Money.ZERO && saved >= Money.ZERO) }
}

data class PurchaseFund(
    val id: String, val name: String, val price: Money,
    val saved: Money, val deadline: LocalDate? = null, val purchased: Boolean = false,
) {
    init { require(price > Money.ZERO && saved >= Money.ZERO) }
}
