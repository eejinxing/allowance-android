package com.allowance.core.assistant

import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.EntryType
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** What your past entries say about something: its usual category and price. */
data class Recall(
    val category: String, val typicalMinor: Long, val minMinor: Long, val maxMinor: Long,
    val count: Int, val last: String, val names: List<String>,
)

/** "RM 1,234.50", the same as the web app's cash(). */
internal fun cash(minor: Long): String = (if (minor < 0) "-" else "") + "RM " +
    NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(kotlin.math.abs(minor), 2))
internal fun decimal(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

/**
 * Memory: what you usually buy and pay, found by looking things up in your OWN past entries.
 * This is retrieval over your history, the same idea as RAG: fetch the relevant records first, then let them inform the answer
 * (the category for a new entry, "you usually pay about RM 4 for this", and hints handed to the language model).
 * It is a plain word match, not embeddings: with a few hundred entries that is accurate and instant, and it needs no extra model.
 */
object Memory {
    private val Spend = setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT)
    private val Latin = Regex("[a-z]{3,}")
    private val HanRun = Regex("[㐀-鿿]+")

    /** Latin words of 3+ letters, and Chinese characters in pairs (so 咖啡 matches 咖啡, and 买咖啡 still reaches 咖啡). */
    fun tokens(text: String?): Set<String> {
        val t = text.orEmpty().lowercase()
        val out = linkedSetOf<String>()
        Latin.findAll(t).forEach { out += it.value }
        HanRun.findAll(t).forEach { m ->
            val run = m.value
            if (run.length == 1) out += run
            for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
        }
        return out
    }

    private fun median(list: List<Long>): Long {
        val s = list.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else Math.round((s[m - 1] + s[m]) / 2.0)
    }

    /** The past entries that look like [text], summarised, or null. Only the entries sharing the most words count, so "bubble tea" finds your bubble teas, not every "tea". */
    fun recall(data: AppData, text: String?): Recall? {
        val want = tokens(text)
        if (want.isEmpty()) return null
        val scored = data.entries.filter { it.type in Spend && it.note.isNotEmpty() }
            .map { e -> e to tokens(e.note).count { it in want } }.filter { it.second > 0 }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        val rows = scored.filter { it.second == top }.map { it.first }
        val votes = linkedMapOf<String, Int>()
        rows.forEach { votes[it.category] = (votes[it.category] ?: 0) + 1 }
        val category = votes.entries.maxBy { it.value }.key
        val amounts = rows.map { it.amountMinor }
        return Recall(category, median(amounts), amounts.min(), amounts.max(), rows.size, rows.maxOf { it.date },
            rows.map { it.note }.distinct().take(3))
    }

    /** One readable line, or "" when there is nothing to say. */
    fun describe(m: Recall?): String {
        if (m == null) return ""
        val range = if (m.count > 1 && m.minMinor != m.maxMinor) " (from ${cash(m.minMinor)} to ${cash(m.maxMinor)})" else ""
        return "You usually pay about ${cash(m.typicalMinor)} for ${m.names[0]}$range: ${m.count} time${if (m.count == 1) "" else "s"}, " +
            "last on ${m.last.substring(5, 7)}/${m.last.substring(8)}."
    }

    /** The same facts as one line of context for the language model. "" when there are none. */
    fun hint(m: Recall?): String = if (m == null) "" else "${m.names.joinToString(" / ")} -> ${m.category}, usually ${decimal(m.typicalMinor)} (${m.count}x)"
}
