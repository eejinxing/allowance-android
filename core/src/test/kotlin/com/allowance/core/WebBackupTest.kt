package com.allowance.core

import com.allowance.core.ledger.*
import org.junit.Assert.*
import org.junit.Test

/** A backup written by the web app opens here, and one written here is read by the web app (same JSON). */
class WebBackupTest {
    // Exactly what the web app's engine produces for a budget with a daily Breakfast and Dinner bill and an extra swap of the user's own.
    private val fromWeb = """{"version":1,"settings":{"nickname":"Test","incomeMinor":85000,"incomeFrequency":"MONTHLY","savingsMinor":0,"started":"2026-10-01","weights":[1,1,1,1,1,1,1],"weightMode":"EVEN"},"bills":[{"id":"b1","name":"Breakfast","amountMinor":1000,"frequency":"DAILY","family":false},{"id":"b2","name":"Dinner","amountMinor":1200,"frequency":"DAILY","family":false}],"accounts":[],"entries":[],"goals":[],"funds":[],"wishes":[],"budgets":[{"month":"2026-11","startDate":"2026-11-01","incomeMinor":85000,"billsMinor":66000,"savingsMinor":0,"weights":[1,1,1,1,1,1,1],"plans":{"2026-11-20":0},"paybacks":[{"id":"p1","date":"2026-11-05","name":"Sneakers","amountMinor":3000,"days":[{"date":"2026-11-06","cutMinor":100,"swaps":[{"name":"Bread at home","usual":"Breakfast","icon":"🍞","savingMinor":800}]}]}],"marks":{"2026-11-20":"RED"}}],"swaps":[{"id":"seed-train-bus","category":"TRANSPORT","icon":"🚌","usualName":"Train","usualMinor":1000,"cheaperName":"Bus","cheaperMinor":350},{"id":"seed-breakfast-bread","category":"FOOD","icon":"🍞","usualName":"Café breakfast","usualMinor":800,"cheaperName":"Bread at home","cheaperMinor":200},{"id":"dinner-cook","category":"FOOD","icon":"🍲","usualName":"Dinner out","usualMinor":1200,"cheaperName":"Cook at home","cheaperMinor":400}]}"""

    @Test fun `a backup from the web app decodes with its swaps, paybacks and marks`() {
        val data = BackupCodec.decode(fromWeb)
        assertEquals(3, data.swaps.size)
        assertEquals("Cook at home", data.swaps.last().cheaperName)
        val november = data.budgets.single()
        assertEquals(mapOf("2026-11-20" to "RED"), november.marks)
        assertEquals(mapOf("2026-11-20" to 0L), november.plans)
        val payback = november.paybacks.single()
        assertEquals("Sneakers", payback.name)
        assertEquals(PaybackSwap("Bread at home", "Breakfast", "🍞", 800), payback.days.single().swaps.single())
    }

    @Test fun `what the web app wrote survives a round trip through this app unchanged`() {
        val data = BackupCodec.decode(fromWeb)
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }

    @Test fun `the swaps in a web backup drive the payback exactly like the web app`() {
        val today = java.time.LocalDate.of(2026, 11, 5)
        var data = LedgerEngine.ensureMonths(BackupCodec.decode(fromWeb), today)
        data = LedgerEngine.saveEntry(data, LedgerEntry("spent", today.toString(), LedgerEngine.result(data, java.time.YearMonth.of(2026, 11), today).todayAllowance.minor), today)
        val plan = LedgerEngine.payback(data, 3_000, today)!!
        val cands = plan.options[0].cands
        // Breakfast RM 10 against bread RM 2, Dinner RM 12 against cooking RM 4: RM 8 each, both from the bills, neither used until tapped.
        assertEquals(setOf("Bread at home", "Cook at home"), cands.filter { it.fromBill }.map { it.swap.cheaperName }.toSet())
        assertTrue(cands.filter { it.fromBill }.all { it.saving == 800L && it.fits })
        assertTrue(plan.days.all { it.swaps.isEmpty() })
    }
}
