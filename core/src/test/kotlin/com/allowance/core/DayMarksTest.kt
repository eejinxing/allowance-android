package com.allowance.core

import com.allowance.core.ledger.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The calendar marks: chosen by you, never worked out from the amount. Mirrors the web app's calendar. */
class DayMarksTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)
    private fun days(plans: Map<String, Long> = emptyMap(), marks: Map<String, String> = emptyMap(), spent: Map<String, Long> = emptyMap(),
        count: Map<String, Int> = emptyMap(), family: Map<String, Long> = emptyMap()) = MonthDays(plans, marks, spent, count, family)

    @Test fun `a day with nothing on it and no mark has no mark`() {
        assertEquals(DayInfo(null, null, 0, 0), DayMarks.dayInfo(yesterday, today, days()))
        assertEquals(DayInfo(null, null, 0, 0), DayMarks.dayInfo(tomorrow, today, days()))
    }

    @Test fun `without a mark, the amounts decide for days marked before marks existed`() {
        assertEquals("full", DayMarks.dayInfo(yesterday, today, days(spent = mapOf(yesterday.toString() to 8_340L))).mode)
        assertEquals("free", DayMarks.dayInfo(tomorrow, today, days(plans = mapOf(tomorrow.toString() to 0L))).mode)
        assertEquals("lite", DayMarks.dayInfo(tomorrow, today, days(plans = mapOf(tomorrow.toString() to 3_000L))).mode)
    }

    @Test fun `a mark you chose wins over the amount, and a marked day with nothing on it comes to zero`() {
        val teal = DayMarks.dayInfo(yesterday, today, days(marks = mapOf(yesterday.toString() to "TEAL"), spent = mapOf(yesterday.toString() to 2_300L)))
        assertEquals(DayInfo(2_300L, "lite", 0, 0), teal)
        val red = DayMarks.dayInfo(yesterday, today, days(marks = mapOf(yesterday.toString() to "RED")))
        assertEquals(0L, red.total); assertEquals("free", red.mode)
        assertEquals("full", DayMarks.dayInfo(yesterday, today, days(marks = mapOf(yesterday.toString() to "YELLOW"))).mode)
    }

    @Test fun `a red day can't have spending on it`() {
        val info = DayMarks.dayInfo(yesterday, today, days(marks = mapOf(yesterday.toString() to "RED"), spent = mapOf(yesterday.toString() to 500L)))
        assertEquals("lite", info.mode)
    }

    @Test fun `family spending is shown but kept apart, and several expenses are counted`() {
        val entries = listOf(
            LedgerEntry("a", yesterday.toString(), 500), LedgerEntry("b", yesterday.toString(), 700),
            LedgerEntry("f", yesterday.toString(), 9_000, family = true),
        )
        val data = AppData(settings = BudgetSettings("", 100_000, started = "2026-11-01"), entries = entries)
        val info = DayMarks.dayInfo(yesterday, today, DayMarks.monthState(data, YearMonth.of(2026, 11)))
        assertEquals(1_200L, info.total); assertEquals(2, info.count); assertEquals(9_000L, info.family)
    }

    @Test fun `a tap moves the selected day red, teal, yellow, then back to no mark`() {
        var mode = "none"; val seen = mutableListOf<String>()
        repeat(4) { mode = DayMarks.NEXT.getValue(mode); seen += mode }
        assertEquals(listOf("free", "lite", "full", "none"), seen)
        assertEquals("RED", DayMarks.MODE_MARK["free"]); assertEquals("TEAL", DayMarks.MODE_MARK["lite"]); assertEquals("YELLOW", DayMarks.MODE_MARK["full"])
    }

    @Test fun `a starting amount for a teal day is about a third of the suggestion`() {
        assertEquals(25L, DayMarks.litePick(50))
        assertEquals(50L, DayMarks.litePick(100))
        assertEquals(1L, DayMarks.litePick(1))
        assertEquals(200L, DayMarks.litePick(600))
        assertEquals(100L, DayMarks.litePick(150))
        assertEquals(1_000L, DayMarks.litePick(3_000))
    }
}
