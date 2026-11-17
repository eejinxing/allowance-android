package com.allowance.core

import com.allowance.core.receipt.ReadRow
import com.allowance.core.receipt.ReceiptText
import org.junit.Assert.*
import org.junit.Test

/** The total-finding rules of the on-device receipt reader, on rows of text as the model reads them. The same cases as the web app's tests/receiptscan.test.mjs. */
class ReceiptTextTest {
    private fun rows(vararg t: String) = t.map { ReadRow(it, 0.9) }

    @Test fun `amountsIn reads money in the usual shapes and ignores other numbers`() {
        assertEquals(listOf(9959L), ReceiptText.amountsIn("TOTAL $99.59"))
        assertEquals(listOf(123450L), ReceiptText.amountsIn("GRAND TOTAL RM 1,234.50"))
        assertEquals(listOf(1250L), ReceiptText.amountsIn("JUMLAH 12,50"))
        assertEquals(listOf(1440L), ReceiptText.amountsIn("TOTAL £14.40"))
        assertEquals(emptyList<Long>(), ReceiptText.amountsIn("TEL: 813 932-0562 TC# 0432 2121"))
        assertEquals(emptyList<Long>(), ReceiptText.amountsIn("TOTAL: 32:13"))        // a duration, not money
        assertEquals(listOf(26L), ReceiptText.amountsIn("TAX 1 7.000 % 0.26"))        // 7.000 is not 7.00
    }

    @Test fun `the TOTAL row wins over subtotal, cash and change`() {
        val t = ReceiptText.findTotal(rows("BREAD 2.88 N", "SUBTOTAL 46.04", "TAX 1 7.000 % 0.26", "TOTAL 46.30", "DEBIT TEND 50.00", "CHANGE DUE 3.70"))!!
        assertEquals(4630L, t.cents); assertEquals("total", t.source)
    }

    @Test fun `the value printed on several TOTAL rows is the one chosen`() {
        assertEquals(4630L, ReceiptText.findTotal(rows("TOTAL 46.30", "ACCOUNT : 5259", "46.30 TOTAL PURCHASE", "TOTAL SAVINGS 1.20"))!!.cents)
    }

    @Test fun `TOTAL ITEMS 4 and ITEM COUNT are not the total`() {
        assertEquals(1440L, ReceiptText.findTotal(rows("ITEM COUNT: 10", "TOTAL ITEMS 4", "TOTAL £14.40", "CREDIT £14.40"))!!.cents)
    }

    @Test fun `a receipt that prints only SUB TOTAL is read from that row`() {
        val t = ReceiptText.findTotal(rows("Lorem ipsum $9.20", "SUB TOTAL $107.60", "CASH $200.00", "CHANGE $92.40"))!!
        assertEquals(10760L, t.cents); assertEquals("subtotal", t.source)
    }

    @Test fun `with no total row at all, the biggest amount that is not cash or change is offered, and marked as a guess`() {
        val t = ReceiptText.findTotal(rows("NASI LEMAK 7.50", "TEH TARIK 2.50", "CASH 20.00", "CHANGE 10.00"))!!
        assertEquals(750L, t.cents); assertEquals("largest", t.source)
    }

    @Test fun `a whole-number total is read only from a row that says TOTAL`() {
        assertEquals(55700L, ReceiptText.findTotal(rows("ITEM COUNT: 10", "TOTAL: 557"))?.cents)
        assertNull(ReceiptText.findTotal(rows("ORDER #0001", "CARD #: **** 2021")))
    }

    @Test fun `no money, no answer`() {
        assertNull(ReceiptText.findTotal(rows("THANK YOU", "TOTAL: 32:13")))
        assertNull(ReceiptText.findTotal(emptyList()))
    }

    @Test fun `decode is greedy CTC - repeats collapse only across a blank, and confidence is the weakest kept character`() {
        val c = ReceiptText.CHARS.length + 1; val t = 6
        val s = FloatArray(t * c) { -9f }
        fun at(step: Int, cls: Int) { s[step * c + cls] = 9f }
        fun id(ch: Char) = ReceiptText.CHARS.indexOf(ch) + 1
        at(0, id('4')); at(1, id('4')); at(2, 0); at(3, id('4')); at(4, id('.')); at(5, 0)
        val (text, conf) = ReceiptText.decode(s, t, c)
        assertEquals("44.", text); assertTrue(conf > 0.99)
    }

    @Test fun `cutRows finds the rows of dark text on a light page and skips a barcode`() {
        val w = 720; val h = 260; val g = IntArray(w * h) { 235 }
        fun bar(y0: Int, y1: Int, x0: Int, x1: Int) { for (y in y0 until y1) for (x in x0 until x1) if ((x shr 1) % 2 == 0) g[y * w + x] = 30 }
        bar(20, 40, 40, 400); bar(70, 90, 40, 300); bar(120, 140, 300, 680)                                 // three text-like rows
        for (y in 170 until 235) for (x in 100 until 600) if ((x / 3) % 2 == 0) g[y * w + x] = 20             // a barcode, 65 high
        val b = ReceiptText.cutRows(g, w, h)
        assertEquals(3, b.size)
        assertTrue(b[0][0] <= 20 && b[0][1] >= 40 && b[0][2] <= 40 && b[0][3] >= 400)
    }

    @Test fun `rowTensor makes a row 32 high, keeping its shape`() {
        val w = 720; val g = IntArray(w * 100) { 200 }
        val r = ReceiptText.rowTensor(g, w, intArrayOf(10, 74, 0, 640))   // 64 high, 640 wide → 32 x 320
        assertEquals(320, r.width); assertEquals(32 * 320, r.data.size); assertEquals(200f, r.data[0], 0.001f)
    }
}
