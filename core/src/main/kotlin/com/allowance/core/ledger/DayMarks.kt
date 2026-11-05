package com.allowance.core.ledger

import java.time.LocalDate
import java.time.YearMonth

/**
 * What one calendar day comes to and how it is marked. [total] is null while the day has no mark and nothing on it.
 * [mode] is "free" (red: nothing spent), "lite" (teal: only the amount, daily bills not counted) or "full" (yellow: daily bills counted, plus the amount).
 */
data class DayInfo(val total: Long?, val mode: String?, val count: Int, val family: Long)

/** Plans, marks, free spending per day and how many expenses make it up, for one month. Fixed bills and fund purchases never count: only what you spend freely does. */
data class MonthDays(
    val plans: Map<String, Long>, val marks: Map<String, String>,
    val spent: Map<String, Long>, val count: Map<String, Int>,
    /** What family paid, shown but never part of your budget. */
    val family: Map<String, Long>,
)

/** The calendar's rules, shared with the web app: every day has a mark you choose, and it is never worked out from the amount. */
object DayMarks {
    val MARK_MODE = mapOf("RED" to "free", "TEAL" to "lite", "YELLOW" to "full")
    val MODE_MARK = MARK_MODE.entries.associate { it.value to it.key }
    /** The order a tap moves the selected day through: no mark, red, teal, yellow, then back to no mark. */
    val NEXT = mapOf("none" to "free", "free" to "lite", "lite" to "full", "full" to "none")

    fun monthState(data: AppData, month: YearMonth): MonthDays {
        val budget = data.budgets.firstOrNull { it.month == month.toString() }
        val inMonth = data.entries.filter { it.date.startsWith(month.toString()) && it.type == EntryType.EXPENSE }
        val mine = inMonth.filter { !it.family }
        return MonthDays(budget?.plans.orEmpty(), budget?.marks.orEmpty(),
            mine.groupBy { it.date }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } },
            mine.groupBy { it.date }.mapValues { (_, rows) -> rows.size },
            inMonth.filter { it.family }.groupBy { it.date }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } })
    }

    /**
     * total = what the day came to (null while it has no mark), mode = its mark. The mark is what you chose, never worked out from the amount:
     * typing 23 on a teal day leaves it teal. A past day's total is what was logged; today and later take their plan, or what has been logged.
     * Days marked before marks existed fall back on their amounts: nothing = red, logged spending = yellow, a plan = teal.
     */
    fun dayInfo(date: LocalDate, today: LocalDate, st: MonthDays): DayInfo {
        val key = date.toString()
        val logged = st.spent[key] ?: 0L
        val plan = st.plans[key]
        val marked = MARK_MODE[st.marks[key]]
        var total: Long? = if (date < today) logged.takeIf { it > 0 } else plan ?: logged.takeIf { it != 0L }
        if (total == null && marked != null) total = 0L
        var mode = marked ?: when {
            total == null -> null
            total == 0L -> "free"
            date < today -> "full"
            else -> "lite"
        }
        if (mode == "free" && (total ?: 0L) > 0) mode = "lite" // red can't have spending on it
        return DayInfo(total, mode, st.count[key] ?: 0, st.family[key] ?: 0L)
    }

    /** A starting amount for a teal day: about a third of the day's suggestion. */
    fun litePick(budget: Long): Long =
        if (budget <= 100) maxOf(1L, budget / 2) else minOf(maxOf(100L, Math.round(budget / 3.0 / 100.0) * 100L), budget - 1)
}
