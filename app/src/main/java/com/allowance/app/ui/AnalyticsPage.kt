package com.allowance.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val CategoryNames = mapOf("FOOD" to "Food & drinks", "TRANSPORT" to "Transport", "SHOPPING" to "Shopping",
    "FUN" to "Entertainment", "LIVING" to "Living", "HEALTH" to "Health", "OTHER" to "Other")
private val SpendTypes = setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE)
private val Gold = Color(0xFFF5C400)
private val FlowColors = listOf(Color(0xFFFFD83D), Color(0xFFF5A524), Ink, Ink, Ink, Ink, Ink)
private val Cream = Color(0xFFFFFDF4)

// ---------- data ----------

private class Period(
    val monthly: Boolean, val dates: List<LocalDate>, val self: List<Long>, val family: List<Long>, val rows: List<LedgerEntry>, val prevTotal: Long,
    val todayIndex: Int, val curve: List<Pair<Float, Long>>, val cut: Float,
)

private fun hourOf(e: LedgerEntry): Double {
    if (e.createdAt == 0L) return 12.0
    val t = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault())
    return t.hour + t.minute / 60.0
}

/** This week (Mon–Sun) or this month, with what was spent each day and a live-tracker curve: at every sample, what went out in the 24 hours before it. */
private fun period(data: AppData, today: LocalDate, monthly: Boolean): Period {
    val start = if (monthly) today.withDayOfMonth(1) else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val len = if (monthly) today.lengthOfMonth() else 7
    val prevStart = if (monthly) start.minusMonths(1) else start.minusDays(7)
    val dates = (0 until len).map { start.plusDays(it.toLong()) }
    val spend = data.entries.filter { it.type in SpendTypes }
    val rows = spend.filter { it.date >= dates.first().toString() && it.date <= dates.last().toString() }
    val prevTotal = spend.filter { it.date >= prevStart.toString() && it.date <= start.minusDays(1).toString() }.sumOf { it.amountMinor }
    val self = dates.map { d -> rows.filter { !it.family && it.date == d.toString() }.sumOf { it.amountMinor } }
    val family = dates.map { d -> rows.filter { it.family && it.date == d.toString() }.sumOf { it.amountMinor } }
    val idx = dates.indexOf(today)
    val marks = rows.map { (dates.indexOf(LocalDate.parse(it.date)) * 24 + hourOf(it)) to it.amountMinor }
    val clock = LocalTime.now()
    val now = if (idx >= 0) idx * 24 + clock.hour + clock.minute / 60.0 else dates.size * 24.0
    val step = if (monthly) 4 else 1
    val hours = generateSequence(0.0) { it + step }.takeWhile { it <= now + .01 }.toMutableList()
    if (hours.last() < now) hours += now
    val curve = hours.map { h -> (h / 24 - .5).toFloat() to marks.sumOf { (m, v) -> if (m <= h && m > h - 24) v else 0L } }
    return Period(monthly, dates, self, family, rows, prevTotal, if (idx >= 0) idx else dates.lastIndex, curve, (now / 24 - .5).toFloat())
}

private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val p = Math.pow(10.0, Math.floor(Math.log10(v))); val f = v / p
    return (if (f <= 1) 1.0 else if (f <= 2) 2.0 else if (f <= 2.5) 2.5 else if (f <= 5) 5.0 else 10.0) * p
}

private fun axisLabel(minor: Double): String {
    val r = minor / 100
    return if (r >= 1000) "${"%.1f".format(Locale.US, r / 1000).removeSuffix(".0")}k" else if (r < 10 && r % 1.0 != 0.0) "%.1f".format(Locale.US, r) else Math.round(r).toString()
}

private val DayLine = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.UK)
private val DayShort = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DayFull = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

// ---------- page ----------

@Composable
fun InsightsPage(state: AllowanceState, onSettings: () -> Unit = {}, onBack: () -> Unit = {}) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var who by rememberSaveable { mutableStateOf("all") }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    val today = state.today
    val p = remember(state.data, today, monthly) { period(state.data, today, monthly) }
    var selected by rememberSaveable(monthly) { mutableIntStateOf(p.todayIndex) }
    val day = selected.coerceIn(0, p.dates.lastIndex)
    val total = p.self.sum() + p.family.sum()
    val result = LedgerEngine.result(state.data, YearMonth.from(today), today)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tabBg = if (dark) Color.White.copy(alpha = .12f) else Color(0xFFFFF6CC)

    Page(topPadding = 0.dp) {
        item(key = "hero") {
            val heroEnd = if (dark) MaterialTheme.colorScheme.surfaceContainer else Cream
            Column(Modifier.fullBleed().shadow(10.dp, RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp), clip = false, ambientColor = Color(0xFF785A00).copy(alpha = .25f), spotColor = Color(0xFF785A00).copy(alpha = .25f))
                .clip(RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp))
                .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.background, .5f to MaterialTheme.colorScheme.background, 1f to heroEnd))
                .statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    HeroCircle("Back", Modifier.align(Alignment.CenterStart), onBack) { Icon(AppIcons.ChevronLeft, null, Modifier.size(26.dp)) }
                    Text("Analytics", Modifier.align(Alignment.Center), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        HeroCircle("More options", Modifier, { menu = true }) { MoreDots(ink) }
                        DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() }) }
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PeriodTab("This week", !monthly, tabBg) { monthly = false }
                    PeriodTab("This month", monthly, tabBg) { monthly = true }
                }
                Column(Modifier.padding(start = 4.dp, top = 14.dp).staggerIn(0)) {
                    Text("Total spending", fontSize = 14.sp, color = muted)
                    BasicText(cash(rememberCountUp(total)), Modifier.fillMaxWidth().padding(top = 2.dp),
                        style = TextStyle(fontFeatureSettings = "tnum", color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, letterSpacing = (-1.2).sp), maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 42.sp, stepSize = 1.sp))
                    Delta(total, p.prevTotal, monthly, dark)
                }
                if (total == 0L) Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(AppIcons.BarChart, null, Modifier.size(40.dp), tint = ink.copy(alpha = .5f))
                    Text(if (monthly) "No spending this month yet" else "No spending this week yet", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Your daily line appears here as soon as you log an expense.", Modifier.padding(top = 4.dp), fontSize = 14.sp, color = muted, textAlign = TextAlign.Center)
                } else {
                    val values = p.self.indices.map { p.self[it] + p.family[it] }
                    val labels = p.dates.mapIndexed { i, d ->
                        if (monthly) (if (d.dayOfMonth in setOf(1, 8, 15, 22) || i == p.dates.lastIndex) "${d.dayOfMonth}" else "")
                        else d.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH)
                    }
                    LineChart(values, p.curve, p.cut, labels, day, { selected = it }, ink,
                        Modifier.padding(top = 16.dp).fillMaxWidth().height(250.dp).semantics {
                            contentDescription = "Spending chart. ${p.dates[day].format(DayFull)}: you paid ${cash(p.self[day])}, family paid ${cash(p.family[day])}. Tap or drag to change the day."
                        })
                    Text("${p.dates[day].format(DayFull)} · you paid ${cash(p.self[day])}, family paid ${cash(p.family[day])}", Modifier.padding(start = 4.dp, top = 6.dp), fontSize = 13.sp, color = muted)
                }
            }
        }
        item(key = "history") {
            Column(Modifier.staggerIn(1)) {
                Text("History", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
                SoftCard {
                    val days = p.dates.take(p.todayIndex + 1).mapIndexed { i, d -> Triple(i, d, p.self[i] + p.family[i]) }.reversed()
                    val shown = if (monthly) days.filterIndexed { i, row -> row.third > 0 || i == 0 } else days
                    if (shown.isEmpty()) Text("Nothing logged yet.", Modifier.padding(vertical = 16.dp), fontSize = 14.sp, color = muted)
                    shown.forEachIndexed { i, (index, date, sum) ->
                        val hours = LongArray(24).also { arr -> p.rows.filter { it.date == date.toString() }.forEach { e -> arr[(if (e.createdAt == 0L) 12 else hourOf(e).toInt()).coerceIn(0, 23)] += e.amountMinor } }
                        Row(Modifier.fillMaxWidth().pressable(onClickLabel = "Show this day") { selected = index }.padding(top = 13.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(date.format(DayLine).replaceFirst(" ", ", ").replace(",,", ","), fontSize = 13.sp, color = muted)
                                Text(cash(sum).removePrefix("RM "), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.3).sp, style = TextStyle(fontFeatureSettings = "tnum"))
                            }
                            HourBars(hours.toList(), if (date == today) java.time.LocalTime.now().hour else 23, ink, Modifier.padding(bottom = 4.dp))
                        }
                        if (i < shown.lastIndex) HorizontalDivider(color = Color(0xFFF6F1DF).copy(alpha = if (dark) .12f else 1f))
                    }
                }
            }
        }
        item(key = "flow") {
            Column(Modifier.staggerIn(2)) { CategoryFlow(p, who, { who = it; focus = null }, focus, { focus = if (focus == it) null else it }, tabBg, ink, muted, dark) }
        }
        item(key = "caption") {
            Text("Includes everyday spending, bill payments and fund purchases. Transfers and fund top-ups aren’t counted. Budget left this month: ${cash(result.remaining.minor)}",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun HeroCircle(description: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.size(48.dp).semantics { contentDescription = description }.pressable(onClick = onClick).clip(CircleShape)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PeriodTab(label: String, selected: Boolean, selectedBg: Color, onClick: () -> Unit) {
    Box(Modifier.height(38.dp).clip(CircleShape).background(if (selected) selectedBg else Color.Transparent).pressable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "▼ RM 12.00 (−8%) vs last week": more spending is red, less is gold. */
@Composable
private fun Delta(cur: Long, prev: Long, monthly: Boolean, dark: Boolean) {
    val what = if (monthly) "last month" else "last week"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text: String; var color = muted
    if (prev == 0L) text = if (cur > 0) "First ${if (monthly) "month" else "week"} with spending" else "Nothing spent ${if (monthly) "this month" else "this week"} yet"
    else {
        val diff = cur - prev; val pct = Math.round(diff.toDouble() / prev * 100)
        text = "${if (diff > 0) "▲" else if (diff < 0) "▼" else "•"} ${cash(Math.abs(diff))} (${if (diff > 0) "+" else if (diff < 0) "−" else ""}${Math.abs(pct)}%) vs $what"
        color = if (diff > 0) Color(0xFFD9434A) else if (diff < 0) (if (dark) Yellow else Color(0xFFA87C00)) else muted
    }
    Text(text, Modifier.padding(top = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, style = TextStyle(fontFeatureSettings = "tnum"))
}

@Composable
private fun SoftCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Column(modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(24.dp), clip = false, ambientColor = Color(0xFF785A00).copy(alpha = .2f), spotColor = Color(0xFF785A00).copy(alpha = .2f))
        .clip(RoundedCornerShape(24.dp))
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLowest, .45f to MaterialTheme.colorScheme.surfaceContainerLowest, 1f to if (dark) MaterialTheme.colorScheme.surfaceContainer else Cream))
        .padding(horizontal = padding, vertical = 4.dp), content = content)
}

// ---------- line chart ----------

/**
 * Yellow line on a pale card: value labels on the left, a soft band across the middle, a jagged line drawn from closely spaced samples,
 * a gradient fill under it and the time still to come as a lighter panel. Day labels sit under each day; the chosen day gets a dot and a bubble.
 */
@Composable
private fun LineChart(values: List<Long>, curve: List<Pair<Float, Long>>, cut: Float, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, ink: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val n = values.size
    Canvas(modifier.pointerInput(n) {
        val pick = { x: Float ->
            val f = (x - 38.dp.toPx()) / maxOf(1f, size.width - 38.dp.toPx() - 16.dp.toPx())
            onSelect(Math.floor((f * n).toDouble()).toInt().coerceIn(0, n - 1))
        }
        detectTapGestures { pick(it.x) }
    }.pointerInput(n) {
        detectHorizontalDragGestures { change, _ ->
            val f = (change.position.x - 38.dp.toPx()) / maxOf(1f, size.width - 38.dp.toPx() - 16.dp.toPx())
            onSelect(Math.floor((f * n).toDouble()).toInt().coerceIn(0, n - 1))
        }
    }) {
        val w = size.width; val h = size.height
        val left = 38.dp.toPx(); val right = 16.dp.toPx(); val top = 30.dp.toPx(); val base = h - 34.dp.toPx()
        val max = (niceMax(maxOf(1L, curve.maxOf { it.second }) / 100.0) * 100).toFloat()
        fun x(p: Float) = left + (p + .5f) / n * (w - left - right)
        fun y(v: Float) = base - (minOf(v, max) / max) * (base - top)
        val label = TextStyle(fontSize = 12.sp, fontStyle = FontStyle.Italic)
        // band across the middle
        drawRect(Color(0xFFFFD83D).copy(alpha = .07f), Offset(left - 6.dp.toPx(), y(max * .75f)), Size(w - left + 6.dp.toPx(), y(max * .25f) - y(max * .75f)))
        // value axis
        listOf(max, max / 2, 0f).forEach { t ->
            val tl = measurer.measure(axisLabel(t.toDouble()), label.copy(color = ink.copy(alpha = .42f)))
            drawText(tl, topLeft = Offset(2.dp.toPx(), y(t) - tl.size.height / 2f))
        }
        // the time still to come
        val fx = x(cut)
        if (cut < n - .5f) {
            val my = y(max * .5f)
            drawRect(ink.copy(alpha = .035f), Offset(fx, top - 10.dp.toPx()), Size(w - fx, base - top + 10.dp.toPx()))
            drawLine(ink.copy(alpha = .3f), Offset(fx + 6.dp.toPx(), my), Offset(w, my), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 5.dp.toPx())))
            if (w - fx > 92.dp.toPx()) {
                val hrs = Math.round((n - .5f - cut) * 24); val days = hrs / 24
                val l1 = measurer.measure(if (days >= 1) "+$days ${if (days == 1) "day" else "days"}" else "+$hrs h", TextStyle(fontSize = 13.sp, fontStyle = FontStyle.Italic, color = ink.copy(alpha = .38f)))
                val l2 = measurer.measure("Still to come", TextStyle(fontSize = 14.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = .85f)))
                drawText(l1, topLeft = Offset(fx + 10.dp.toPx(), my + 8.dp.toPx())); drawText(l2, topLeft = Offset(fx + 10.dp.toPx(), my + 26.dp.toPx()))
            }
        }
        // line and fill
        val pts = curve.map { (p, v) -> Offset(x(p), y(v.toFloat())) }
        val line = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val area = Path().apply { addPath(line); lineTo(pts.last().x, base); lineTo(pts.first().x, base); close() }
        drawPath(area, Brush.verticalGradient(0f to Color(0xFFFFD83D).copy(alpha = .55f), .55f to Color(0xFFFFE57A).copy(alpha = .22f), 1f to Color(0xFFFFF3B8).copy(alpha = 0f), startY = top, endY = base))
        drawPath(line, Brush.horizontalGradient(0f to Color(0xFFE0A800), .6f to Gold, 1f to Color(0xFFFFD83D), startX = left, endX = w),
            style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // the chosen day: a dot on the curve and a bubble above
        val sel = minOf(selected, n - 1); val sx = x(sel.toFloat())
        val near = curve.minByOrNull { Math.abs(it.first - sel) }
        if (near != null && Math.abs(near.first - sel) <= .5f) {
            val c = Offset(sx, y(near.second.toFloat()))
            drawCircle(Color.White, 6.75.dp.toPx(), c); drawCircle(Gold, 4.25.dp.toPx(), c)
        }
        val bubble = measurer.measure(cash(values[sel]).replace(".00", ""), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White))
        val bw = bubble.size.width + 20.dp.toPx(); val bx = (sx - bw / 2).coerceIn(left - 4.dp.toPx(), w - bw - 2.dp.toPx())
        drawRoundRect(Ink, Offset(bx, 0f), Size(bw, 22.dp.toPx()), CornerRadius(11.dp.toPx()))
        drawText(bubble, topLeft = Offset(bx + (bw - bubble.size.width) / 2, (22.dp.toPx() - bubble.size.height) / 2))
        // day labels
        labels.forEachIndexed { i, t ->
            if (t.isEmpty()) return@forEachIndexed
            val tl = measurer.measure(t, TextStyle(fontSize = 12.5.sp, fontStyle = FontStyle.Italic, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Medium, color = ink.copy(alpha = if (i == sel) 1f else .55f)))
            drawText(tl, topLeft = Offset(x(i.toFloat()) - tl.size.width / 2f, h - 8.dp.toPx() - tl.size.height * .8f))
        }
    }
}

/** A strip of 24 hourly slots for one day, like a step counter: a short dash for an hour with no spending, a yellow bar where money went out. */
@Composable
private fun HourBars(hours: List<Long>, upTo: Int, ink: Color, modifier: Modifier = Modifier) {
    val max = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(modifier.size((24 * 7.6 - 1.6).dp, 44.dp)) {
        val w = 6.dp.toPx(); val gap = 1.6.dp.toPx(); val h = size.height
        hours.forEachIndexed { i, v ->
            val bx = i * (w + gap)
            if (v == 0L) drawRoundRect(if (i <= upTo) Color(0xFFE8B800).copy(alpha = .45f) else ink.copy(alpha = .1f), Offset(bx, h - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(1.2.dp.toPx()))
            else { val bh = maxOf(5.dp.toPx(), v.toFloat() / max * h); drawRoundRect(Gold, Offset(bx, h - bh), Size(w, bh), CornerRadius(1.8.dp.toPx())) }
        }
    }
}

// ---------- category flow ----------

private class FlowCat(val cat: String, val amount: Long, val color: Color, val bright: Boolean, var y: Float = 0f, var h: Float = 0f)
private class FlowItem(val cat: String, val name: String, val sub: String)

@Composable
private fun CategoryFlow(p: Period, who: String, onWho: (String) -> Unit, focus: String?, onFocus: (String) -> Unit, tabBg: Color, ink: Color, muted: Color, dark: Boolean) {
    val list = p.rows.filter { who == "all" || (who == "family") == it.family }
    val totals = list.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
    val order = totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    val pays = list.sortedByDescending { it.amountMinor }
    val maxRows = 8
    val picked = order.mapNotNull { (k, _) -> pays.firstOrNull { it.category == k } }.toMutableList()
    for (e in pays) { if (picked.size >= maxOf(maxRows, order.size)) break; if (e !in picked) picked += e }
    val rank = order.mapIndexed { i, (k, _) -> k to i }.toMap()
    picked.sortWith(compareBy<LedgerEntry> { rank[it.category] ?: 0 }.thenByDescending { it.amountMinor })
    val colorOf = order.mapIndexed { i, (k, _) -> k to FlowColors[minOf(i, FlowColors.lastIndex)] }.toMap()
    val items = picked.map { e -> FlowItem(e.category, (e.note.trim().ifBlank { CategoryNames[e.category] ?: "Other" }).take(22), "${cash(e.amountMinor)} · ${LocalDate.parse(e.date).format(DayShort)}") }
    val cats = order.filter { (k, _) -> items.any { it.cat == k } }.mapIndexed { i, (k, v) -> FlowCat(k, v, colorOf.getValue(k), i < 2) }
    val more = pays.size - picked.size
    val scope = if (p.monthly) "this month" else "this week"

    SoftCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text("Spending by category", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
            Text("${order.size} ${if (order.size == 1) "category" else "categories"} · ${list.size} ${if (list.size == 1) "payment" else "payments"}", fontSize = 12.sp, color = muted)
        }
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("all" to "All", "self" to "Self-paid", "family" to "Family-paid").forEach { (k, t) -> PeriodTab(t, who == k, tabBg) { onWho(k) } }
        }
        if (items.isEmpty()) Text("No spending ${if (who == "family") "paid by family " else if (who == "self") "paid by you " else ""}$scope yet.", Modifier.padding(start = 4.dp, top = 2.dp, bottom = 16.dp), fontSize = 14.sp, color = muted)
        else {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                order.forEach { (k, v) ->
                    val on = focus == k
                    Row(Modifier.height(34.dp).clip(CircleShape).background(if (on) Color(0xFFFFF1B0).copy(alpha = if (dark) .3f else 1f) else if (dark) Color.White.copy(alpha = .08f) else Color(0xFFFFFBEA))
                        .pressable(onClickLabel = "Focus ${CategoryNames[k]}") { onFocus(k) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf.getValue(k)))
                        Text(CategoryNames[k] ?: "Other", fontSize = 13.sp, color = muted); Text(cash(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            FlowChart(cats, items, focus, onFocus, ink)
            if (more > 0) Text("+$more smaller ${if (more == 1) "payment" else "payments"} not shown", Modifier.padding(start = 4.dp, top = 10.dp), fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Flow chart, like a process map: one block per category on the left whose height follows how much was spent there, and one row per
 * payment on the right. Every payment is a ribbon of the same width: straight out of its block, one bend, straight to the right edge.
 * The two biggest categories are solid colour; the rest are frosted glass laid over them. Tap a block to focus a category.
 */
@Composable
private fun FlowChart(cats: List<FlowCat>, items: List<FlowItem>, focus: String?, onFocus: (String) -> Unit, ink: Color) {
    val rowH = 64f; val ribbon = 44f; val nodeW = 40f; val gap = 8f; val minNode = 48f
    val total = maxOf(items.size * rowH, cats.size * (minNode + gap))
    val avail = total - gap * (cats.size - 1)
    val sum = cats.sumOf { it.amount }.coerceAtLeast(1L).toFloat()
    var hs = cats.map { maxOf(minNode, it.amount / sum * avail) }
    for (pass in 0 until 4) {
        val over = hs.sum() - avail; if (over <= .5f) break
        val flex = hs.sumOf { if (it > minNode) (it - minNode).toDouble() else 0.0 }.toFloat().coerceAtLeast(1f)
        hs = hs.map { if (it > minNode) maxOf(minNode, it - over * ((it - minNode) / flex)) else it }
    }
    var yy = 0f
    cats.forEachIndexed { i, c -> c.y = yy; c.h = hs[i]; yy += hs[i] + gap }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth().height(total.dp).semantics { contentDescription = "Spending by category: each category joined to its payments" }) {
        Canvas(Modifier.fillMaxSize().pointerInput(cats, items) {
            detectTapGestures { tap ->
                val d = density
                if (tap.x <= nodeW * d) cats.firstOrNull { tap.y >= it.y * d && tap.y <= (it.y + it.h) * d }?.let { onFocus(it.cat) }
            }
        }) {
            val d = density; val W = size.width
            val rowY = { i: Int -> (i * rowH + rowH / 2 + (total - items.size * rowH) / 2) * d }
            val x0 = (nodeW - 2) * d; val xs1 = W * .2f; val xs2 = W * .46f; val xm = (xs1 + xs2) / 2
            val fade = { cat: String -> if (focus == null || focus == cat) 1f else .14f }
            fun ribbonPath(ya: Float, yb: Float) = Path().apply { moveTo(x0, ya); lineTo(xs1, ya); cubicTo(xm, ya, xm, yb, xs2, yb); lineTo(W, yb) }
            for (pass in 0..1) cats.filter { (pass == 0) == it.bright }.forEach { c ->
                val mine = items.withIndex().filter { it.value.cat == c.cat }
                val step = if (mine.size > 1) minOf(ribbon, (c.h - ribbon) / (mine.size - 1)) else 0f
                val first = c.y + c.h / 2 - step * (mine.size - 1) / 2
                val brush = if (c.bright) Brush.horizontalGradient(0f to c.color.copy(alpha = .9f), .5f to c.color.copy(alpha = .75f), 1f to c.color, startX = 0f, endX = W)
                    else Brush.horizontalGradient(0f to Color(0xFFEDE7D3).copy(alpha = .9f), .5f to Color(0xFFF6F2E6).copy(alpha = .62f), 1f to Color(0xFFEFEADB).copy(alpha = .8f), startX = 0f, endX = W)
                mine.forEachIndexed { j, (i, _) ->
                    val path = ribbonPath((first + j * step) * d, rowY(i))
                    drawPath(path, brush, alpha = fade(c.cat), style = Stroke(ribbon * d))
                    if (!c.bright) translate(0f, (-ribbon / 2 + 1) * d) { drawPath(path, Color.White.copy(alpha = .7f * fade(c.cat)), style = Stroke(1.2f * d)) }
                }
            }
            cats.forEach { c ->
                val a = fade(c.cat)
                drawRoundRect((if (c.bright) c.color else Color(0xFFEFEADB)).copy(alpha = a), Offset(0f, c.y * d), Size(nodeW * d, c.h * d), CornerRadius(minOf(14f, c.h / 2) * d))
                drawRoundRect(Color.White.copy(alpha = .35f * a), Offset(1 * d, (c.y + 1) * d), Size((nodeW - 2) * d, minOf(c.h - 2, 18f) * d), CornerRadius(minOf(13f, c.h / 2) * d))
            }
            items.forEachIndexed { i, it ->
                val a = fade(it.cat); val yb = rowY(i)
                val name = measurer.measure(it.name, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink.copy(alpha = a)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = measurer.measure(it.sub, TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = .6f * a)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                drawText(name, topLeft = Offset(xs2 + 12 * d, yb - 3 * d - name.size.height * .78f)); drawText(sub, topLeft = Offset(xs2 + 12 * d, yb + 13 * d - sub.size.height * .78f))
            }
        }
        cats.forEach { c ->
            Icon(categoryIcon(c.cat), null, Modifier.offset(x = (nodeW / 2 - 9).dp, y = (c.y + c.h / 2 - 9).dp).size(18.dp), tint = Ink.copy(alpha = if (focus == null || focus == c.cat) 1f else .2f))
        }
    }
}
