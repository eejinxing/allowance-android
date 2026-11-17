package com.allowance.core

import com.allowance.core.assistant.Memory
import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.EntryType
import com.allowance.core.ledger.LedgerEntry
import org.junit.Assert.*
import org.junit.Test

/** Memory: looking things up in your own past entries (the retrieval half of RAG). The same cases as the web app's tests/memory.test.mjs. */
class MemoryTest {
    private var n = 0
    private fun entry(note: String, amount: Long, category: String, date: String = "2026-11-01", type: EntryType = EntryType.EXPENSE) =
        LedgerEntry("m${n++}", date, amount, type, category, note)
    private val data = AppData(entries = listOf(
        entry("Chocolate", 400, "FOOD", "2026-10-20"), entry("Chocolate bar", 500, "FOOD", "2026-11-02"), entry("Chocolate", 600, "FOOD", "2026-11-05"),
        entry("Bubble tea", 900, "FOOD"), entry("Green tea", 350, "FOOD"), entry("Grab", 1500, "TRANSPORT"), entry("咖啡", 350, "FOOD"), entry("Phone case", 1800, "SHOPPING"),
        entry("Salary bonus", 5000, "OTHER", type = EntryType.INCOME), entry("", 700, "FOOD"),
    ), swaps = emptyList())

    @Test fun `tokens - Latin words of three letters or more, Chinese in pairs`() {
        assertEquals(listOf("bubble", "spent", "tea"), Memory.tokens("Spent on Bubble Tea!").sorted())
        assertEquals(listOf("买咖", "咖啡"), Memory.tokens("买咖啡").sorted())
        assertEquals(setOf("茶"), Memory.tokens("茶"))
        assertTrue(Memory.tokens("").isEmpty())
    }

    @Test fun `recall finds the same thing you bought before - category, typical price, how often`() {
        val m = Memory.recall(data, "chocolate")!!
        assertEquals("FOOD", m.category)
        assertEquals(500L, m.typicalMinor) // median of 400, 500, 600
        assertEquals(400L, m.minMinor); assertEquals(600L, m.maxMinor)
        assertEquals(3, m.count)
        assertEquals("2026-11-05", m.last)
    }

    @Test fun `only the closest matches count - bubble tea finds bubble tea, not every tea`() {
        val m = Memory.recall(data, "bubble tea")!!
        assertEquals(1, m.count); assertEquals(900L, m.typicalMinor)
    }

    @Test fun `it works in Chinese, inside a longer sentence`() {
        val m = Memory.recall(data, "今天买了咖啡")!!
        assertEquals("FOOD", m.category); assertEquals(350L, m.typicalMinor)
    }

    @Test fun `nothing similar, income, and empty notes give nothing`() {
        assertNull(Memory.recall(data, "sneakers"))
        assertNull(Memory.recall(data, "salary")) // income is not spending
        assertNull(Memory.recall(data, ""))
        assertNull(Memory.recall(AppData(swaps = emptyList()), "chocolate"))
    }

    @Test fun `describe and hint turn a match into one line`() {
        val m = Memory.recall(data, "chocolate")
        assertTrue(Memory.describe(m).contains("usually pay about RM 5.00 for Chocolate (from RM 4.00 to RM 6.00): 3 times, last on 11/05."))
        assertEquals("Chocolate / Chocolate bar -> FOOD, usually 5.00 (3x)", Memory.hint(m))
        assertEquals("", Memory.describe(null)); assertEquals("", Memory.hint(null))
        assertTrue(Memory.describe(Memory.recall(data, "phone case")).contains("1 time, last on 11/01"))
    }
}
