package com.allowance.core.receipt

import com.allowance.core.assistant.jsRegex

/** One row of text as the model read it, and the weakest character's probability. [box] is [y0, y1, x0, x1] in the scaled photo. */
data class ReadRow(val text: String, val conf: Double, val box: IntArray? = null)

/** The amount picked as the receipt's total, which kind of row it came from ("total", "subtotal", "largest"), and that row. */
data class FoundTotal(val cents: Long, val source: String, val text: String, val conf: Double)

/** A row ready for the model: [data] is H x [width] grey values 0..255, row-major. */
class RowTensor(val data: FloatArray, val width: Int)

/**
 * Reading the total off a receipt photo, on the device. No server, no API key, no quota. The arithmetic around the model:
 *   1. [cutRows]:   the photo is cut into rows of text (ink profile; mirrors the web's receiptscan.js and ml/common.py cut_rows)
 *   2. the ONNX model (trained in PyTorch, see the web repo's ml/) reads each row into text; [rowTensor] prepares a row, [decode] reads its output
 *   3. [findTotal]: picks the amount on the TOTAL row, and says how sure it is
 * The model itself runs in the app (onnxruntime-android). Nothing here saves anything: the caller shows the amount for the person to check.
 */
object ReceiptText {
    const val WIDTH = 720
    const val H = 32
    const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ.,:\$£€%#*-/@()& "

    // ---------- 1. rows ----------
    /** Mean over a (2r+1) square around each pixel, edges averaging the pixels that exist. */
    private fun boxMean(a: IntArray, w: Int, h: Int, r: Int): FloatArray {
        val sw = w + 1; val s = DoubleArray((h + 1) * sw)
        for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += a[y * w + x]; s[(y + 1) * sw + x + 1] = s[y * sw + x + 1] + row } }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
                out[y * w + x] = ((s[y1 * sw + x1] - s[y0 * sw + x1] - s[y1 * sw + x0] + s[y0 * sw + x0]) / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }

    /** [gray]: w*h values 0..255 (already [WIDTH] wide). Returns the boxes [y0, y1, x0, x1] of the text rows, top to bottom. */
    fun cutRows(gray: IntArray, w: Int, h: Int): List<IntArray> {
        val mean = boxMean(gray, w, h, 12)
        val ink = ByteArray(w * h); val prof = DoubleArray(h)
        for (y in 0 until h) { var n = 0; for (x in 0 until w) if (gray[y * w + x] < mean[y * w + x].toDouble() - 14) { ink[y * w + x] = 1; n++ }; prof[y] = n.toDouble() }
        val k = DoubleArray(h) { y -> ((if (y > 0) prof[y - 1] else 0.0) + prof[y] + (if (y < h - 1) prof[y + 1] else 0.0)) / 3 }
        val thr = maxOf(2.0, 0.006 * w)
        val segs = mutableListOf<IntArray>()
        var y = 0
        while (y < h) { if (k[y] > thr) { val y0 = y; while (y < h && k[y] > thr) y++; segs += intArrayOf(y0, y) } else y++ }
        val merged = mutableListOf<IntArray>()
        for (s in segs) { if (merged.isNotEmpty() && s[0] - merged.last()[1] <= 2) merged.last()[1] = s[1] else merged += intArrayOf(s[0], s[1]) }

        // Lines packed so tightly that they touch: cut where the ink count dips well below the text on both sides.
        // A tall single line (a big heading) has no such dip, so it stays whole.
        fun valleys(a: Int, b: Int): List<IntArray> {
            val cuts = mutableListOf<Int>()
            for (yy in a + 8 until b - 8) {
                var lo = Double.POSITIVE_INFINITY; for (i in maxOf(0, yy - 2) until minOf(h, yy + 3)) lo = minOf(lo, k[i])
                if (k[yy] > lo) continue
                var pl = 0.0; var pr = 0.0
                for (i in maxOf(a, yy - 26) until yy) pl = maxOf(pl, k[i])
                for (i in yy + 1 until minOf(b, yy + 27)) pr = maxOf(pr, k[i])
                if (k[yy] < 0.4 * minOf(pl, pr)) {
                    if (cuts.isNotEmpty() && yy - cuts.last() < 8) { if (k[yy] < k[cuts.last()]) cuts[cuts.size - 1] = yy } else cuts += yy
                }
            }
            val edges = listOf(a) + cuts + b
            return (0 until edges.size - 1).filter { edges[it + 1] - edges[it] >= 8 }.map { intArrayOf(edges[it], edges[it + 1]) }
        }
        // A barcode is far denser in ink than any line of text: judged on the whole stack, before it could be cut into text-sized slices.
        fun barcode(a: Int, b: Int): Boolean {
            if (b - a <= 30) return false
            var first = -1; var last = -1; var n = 0L
            for (x in 0 until w) { var c = 0; for (yy in a until b) c += ink[yy * w + x]; if (c > 0) { if (first < 0) first = x; last = x }; n += c }
            return first >= 0 && n.toDouble() / ((b - a).toLong() * (last - first + 1)) > 0.33
        }
        val out = mutableListOf<IntArray>()
        for (seg in merged) { val a = seg[0]; val b = seg[1]; if (b - a >= 8 && !barcode(a, b)) out += if (b - a > 38) valleys(a, b) else listOf(intArrayOf(a, b)) }
        val boxes = mutableListOf<IntArray>()
        for (seg in out) {
            val a = seg[0]; val b = seg[1]
            if (b - a > 90) continue                                       // a barcode or a picture, not text
            var first = -1; var last = -1
            for (x in 0 until w) { var c = 0; for (yy in a until b) c += ink[yy * w + x]; if (c > 0) { if (first < 0) first = x; last = x } }
            if (first < 0 || last - first + 1 < 3) continue
            val x0 = maxOf(0, first - 6); val x1 = minOf(w, last + 7)
            if (x1 - x0 < 10) continue
            boxes += intArrayOf(maxOf(0, a - 3), minOf(h, b + 3), x0, x1)
        }
        return boxes
    }

    /** Crop [box] = [y0,y1,x0,x1] out of [gray] and resize it to height [H] (area average when shrinking, bilinear when growing). */
    fun rowTensor(gray: IntArray, w: Int, box: IntArray): RowTensor {
        val (y0, y1, x0) = box; val x1 = box[3]; val ch = y1 - y0; val cw = x1 - x0
        val nw = minOf(1024, maxOf(16, Math.round(cw.toDouble() * H / ch).toInt()))
        val out = FloatArray(H * nw)
        val sy = ch.toDouble() / H; val sx = cw.toDouble() / nw
        for (oy in 0 until H) for (ox in 0 until nw) {
            val v: Double
            if (sy > 1 || sx > 1) {                                        // area average over the source footprint
                val ya = y0 + oy * sy; val yb = ya + sy; val xa = x0 + ox * sx; val xb = xa + sx
                var sum = 0.0; var wt = 0.0
                for (yy in Math.floor(ya).toInt() until Math.ceil(yb).toInt()) {
                    val wy = minOf(yy + 1.0, yb) - maxOf(yy.toDouble(), ya)
                    for (xx in Math.floor(xa).toInt() until Math.ceil(xb).toInt()) {
                        val wx = minOf(xx + 1.0, xb) - maxOf(xx.toDouble(), xa)
                        sum += gray[minOf(yy, y1 - 1) * w + minOf(xx, x1 - 1)] * wy * wx; wt += wy * wx
                    }
                }
                v = sum / wt
            } else {                                                       // bilinear
                val fy = minOf(maxOf(y0 + (oy + 0.5) * sy - 0.5, y0.toDouble()), (y1 - 1).toDouble())
                val fx = minOf(maxOf(x0 + (ox + 0.5) * sx - 0.5, x0.toDouble()), (x1 - 1).toDouble())
                val iy = Math.floor(fy).toInt(); val ix = Math.floor(fx).toInt(); val jy = minOf(iy + 1, y1 - 1); val jx = minOf(ix + 1, x1 - 1)
                val dy = fy - iy; val dx = fx - ix
                v = (gray[iy * w + ix] * (1 - dx) + gray[iy * w + jx] * dx) * (1 - dy) + (gray[jy * w + ix] * (1 - dx) + gray[jy * w + jx] * dx) * dy
            }
            out[oy * nw + ox] = v.toFloat()
        }
        return RowTensor(out, nw)
    }

    /** Greedy CTC: scores (T x C, row-major) → (text, conf). conf is the weakest kept character's probability. */
    fun decode(scores: FloatArray, t: Int, c: Int): Pair<String, Double> {
        var prev = -1; val text = StringBuilder(); var conf = 1.0; var kept = 0
        for (i in 0 until t) {
            var best = 0; var mx = Double.NEGATIVE_INFINITY
            for (j in 0 until c) { val v = scores[i * c + j].toDouble(); if (v > mx) { mx = v; best = j } }
            if (best != prev && best != 0) {
                var z = 0.0; for (j in 0 until c) z += Math.exp(scores[i * c + j] - mx)
                conf = minOf(conf, 1 / z); text.append(CHARS[best - 1]); kept++
            }
            prev = best
        }
        return text.toString() to if (kept > 0) conf else 0.0
    }

    // ---------- 3. which row is the total ----------
    private val Money = Regex("""(?:RM|\$|£|€)?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})(?!\d)""")
    private val Total = jsRegex("""\b(TOTAL|AMOUNT DUE|BALANCE DUE|JUMLAH|GRAND|NETT|AMOUNT PAID|AMOUNT)\b""")
    private val NotTotal = Regex("""SUB[ -]?TOTAL|TOTAL ITEMS|ITEM COUNT|TOTAL QTY|SAVINGS|POINTS|DISCOUNT""")
    private val Sub = jsRegex("""SUB[ -]?TOTAL|\bSUBTOT""")
    private val NotPayable = jsRegex("""CASH|CHANGE|TEND|DEBIT|CREDIT|VISA|MASTER|CARD|WALLET|\bTAX\b|GST|SST|VAT|ROUNDING|SERVICE|DISCOUNT|SAVINGS|POINTS|\bQR\b""")
    private val BareAmount = jsRegex("""^AMOUNT\b(?! (DUE|PAID))""")
    private val WholeNumber = Regex("""(?:^|[^\d:.,])(\d{1,6})\s*$""")
    private val Spaces = Regex("\\s+")

    /** The amounts in one row of text, as whole cents. "1,234.50" → 123450; "12,50" → 1250. */
    fun amountsIn(text: String): List<Long> = Money.findAll(text).mapNotNull { m ->
        val c = m.groupValues[1].replace(",", "").toLong() * 100 + m.groupValues[2].toLong()
        c.takeIf { it > 0 && it < 1_000_000_000L }
    }.toList()

    /**
     * Rows top to bottom → the total, or null. The TOTAL row wins; a row that says SUBTOTAL only counts when there is no TOTAL (some receipts
     * print nothing else); as a last resort the biggest amount that is not cash, change, tax or a card line, marked "largest" so the caller can warn.
     */
    fun findTotal(rows: List<ReadRow>): FoundTotal? {
        val r = rows.map { it to it.text.uppercase().replace(Spaces, " ") }
        fun tier(pick: (String) -> Boolean, source: String): FoundTotal? {
            val hits = r.mapNotNull { (row, up) -> if (pick(up)) amountsIn(up).lastOrNull()?.let { FoundTotal(it, source, row.text, row.conf) } else null }
            if (hits.isEmpty()) return null
            val count = hits.groupingBy { it.cents }.eachCount()
            return hits.maxBy { count.getValue(it.cents) } // the value printed most often; ties keep the first
        }
        fun whole(up: String) = Total.containsMatchIn(up) && !NotTotal.containsMatchIn(up) && "SUB" !in up
        (tier({ whole(it) && !BareAmount.containsMatchIn(it) }, "total") ?: tier(::whole, "total"))?.let { return it }
        tier({ Sub.containsMatchIn(it) }, "subtotal")?.let { return it }
        // whole-number totals ("TOTAL: 557") are only trusted on a row that says TOTAL
        for ((row, up) in r) if (whole(up)) {
            val m = WholeNumber.find(up)
            if (m != null && m.groupValues[1].toLong() > 0) return FoundTotal(m.groupValues[1].toLong() * 100, "total", row.text, row.conf)
        }
        var big: FoundTotal? = null
        for ((row, up) in r) { if (NotPayable.containsMatchIn(up)) continue; for (c in amountsIn(up)) if (big == null || c > big.cents) big = FoundTotal(c, "largest", row.text, row.conf) }
        return big
    }
}
