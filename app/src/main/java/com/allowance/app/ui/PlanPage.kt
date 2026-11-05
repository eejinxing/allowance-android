package com.allowance.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.app.AllowanceViewModel
import com.allowance.core.ledger.*
import com.allowance.core.model.Money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val SpendTypes = setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE)
private val DayTitle = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

/** How many months ahead the calendar can be paged. */
private const val MonthsAhead = 2L

private val CalendarInk = Color(0xFF151514)
private val NoSpendRed = Color(0xFFFF5A5F)
private val OutRed = Color(0xFFE5484D)

/** "RM 12.50" → "12.5", "RM 1,250.00" → "1,250": short enough for a small bar label. */
private fun compact(minor: Long): String = cash(minor).removePrefix("RM ").removeSuffix(".00").let { if (it.contains('.')) it.trimEnd('0') else it }

private val TealMark = Color(0xFF3DE0B6)

/**
 * The mark under a calendar day, and it is what you chose, never worked out from the amount. Free: a red dash, nothing spent, daily bills not counted.
 * Lite: a teal dash, only the amount at the top, daily bills not counted. Full: a breathing yellow dot, daily bills counted plus the amount.
 */
internal enum class DayMark { None, Free, Lite, Full }
private fun markOf(mode: String?) = when (mode) { "free" -> DayMark.Free; "lite" -> DayMark.Lite; "full" -> DayMark.Full; else -> DayMark.None }

/**
 * Planning: a Budget tab (where the money comes from, your weekday pattern) and a Calendar tab. In the calendar a breathing yellow dot
 * under a day means you spent, a red dash means a no-spend day. Tap a day to select it, tap it again (or its amount) to enter spending
 * for a past day or a plan for a coming one.
 */
@Composable
fun PlanPage(state: AllowanceState, model: AllowanceViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val data = state.data
    val settings = data.settings!!
    val today = state.today
    val thisMonth = YearMonth.from(today)
    var tab by rememberSaveable { mutableStateOf("cal") }
    var viewText by rememberSaveable { mutableStateOf(thisMonth.toString()) }
    var selText by rememberSaveable { mutableStateOf(today.toString()) }
    val view = YearMonth.parse(viewText)
    val sel = LocalDate.parse(selText)
    val editDay: (LocalDate) -> Unit = { date -> open(if (date > today) "plan:$date" else "dayspend:$date") }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf<RemoveAsk?>(null) }

    // Set what a day comes to: a number, or null for "no mark". A past day is logged spending (one expense, replaced in place) with a
    // no-spend mark for zero; today and later take a plan. Taking away something you logged asks first.
    fun applyDay(date: LocalDate, minor: Long?, mark: String?, confirmed: Boolean = false) {
        if (date >= today) { model.change("") { d -> LedgerEngine.setMark(LedgerEngine.setPlan(d, date, minor, today), date, mark, today) }; return }
        val mine = data.entries.filter { it.date == date.toString() && it.type == EntryType.EXPENSE && !it.family }
        if (mine.size > 1) { model.notify("${mine.size} expenses on this day. Change them one by one in Activity."); return }
        val clearing = (minor ?: 0L) == 0L
        if (clearing && mine.isNotEmpty() && !confirmed) { removeAsk = RemoveAsk(date, minor, mark, mine[0].amountMinor); return }
        model.change("") { d ->
            var out = d
            if (clearing && mine.isNotEmpty()) out = LedgerEngine.deleteEntry(out, mine[0].id)
            if (!clearing) out = LedgerEngine.saveEntry(out, mine.firstOrNull()?.copy(amountMinor = minor!!)
                ?: LedgerEntry(LedgerEngine.id(), date.toString(), minor!!, EntryType.EXPENSE, "OTHER"), today)
            LedgerEngine.setMark(out, date, mark, today)
        }
    }
    // Tapping the selected day moves it along: no mark, red, teal, yellow, no mark again, for any day.
    fun cycle(date: LocalDate) {
        val now = DayMarks.dayInfo(date, today, DayMarks.monthState(data, YearMonth.from(date)))
        if (now.count > 1 && date < today) { model.notify("${now.count} expenses on this day. Change them one by one in Activity."); return }
        val budget = maxOf(runCatching { LedgerEngine.allowanceOn(data, date, today) }.getOrDefault(0L), 0L)
        val next = DayMarks.NEXT.getValue(now.mode ?: "none")
        applyDay(date, when (next) { "none" -> null; "free" -> 0L; "full" -> if (budget > 0) budget else 100L; else -> DayMarks.litePick(budget) }, DayMarks.MODE_MARK[next])
    }

    Page(topPadding = 0.dp) {
        item(key = "top") { PlanTop { open("plan:${if (sel >= today) sel else today}") } }
        item(key = "tabs") { PlanTabs(tab) { tab = it } }
        if (tab == "budget") {
            val budget = data.budgets.firstOrNull { it.month == thisMonth.toString() } ?: LedgerEngine.makeBudget(settings, data.bills, thisMonth)
            val result = LedgerEngine.result(data, thisMonth, today)
            val spentSoFar = result.spentBeforeToday.minor + result.spentToday.minor
            val daysLeft = thisMonth.lengthOfMonth() - today.dayOfMonth + 1
            val averages = LedgerEngine.weekdayAverages(data, today)
            val habit = settings.weightMode == "HABIT"
            item(key = "money") {
                Surface(Modifier.fillMaxWidth().staggerIn(0), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Left for $daysLeft days", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BasicText(cash(rememberCountUp(result.remaining.minor + result.spentToday.minor)), Modifier.fillMaxWidth(),
                                    style = TextStyle(fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-.8).sp),
                                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 32.sp, stepSize = 1.sp))
                            }
                            OutlinedButton(onClick = { open("budget:") }, Modifier.height(44.dp)) { Icon(AppIcons.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val days = DayMarks.monthState(data, thisMonth)
                        val skipped = LedgerEngine.noSpendDays(data, thisMonth, today)
                        val family = LedgerEngine.familyBillsMinor(data.bills, thisMonth)
                        val from = LedgerEngine.budgetFrom(settings)
                        // What your marked days add up to: red costs nothing, teal only what you typed (instead of the daily bill), yellow the daily bill plus what you typed.
                        val tally = mutableMapOf("free" to (0 to 0L), "lite" to (0 to 0L), "full" to (0 to 0L))
                        for (n in 1..thisMonth.lengthOfMonth()) {
                            val date = thisMonth.atDay(n); if (date < from) continue
                            val info = DayMarks.dayInfo(date, today, days)
                            info.mode?.let { m -> val (count, sum) = tally.getValue(m); tally[m] = (count + 1) to (sum + (info.total ?: 0L)) }
                        }
                        val planned = days.plans.filter { it.key >= today.toString() }.values.sum()
                        fun dayText(n: Int) = "$n ${if (n == 1) "day" else "days"}"
                        MoneyLine("Income", budget.incomeMinor, "+")
                        MoneyLine("Fixed bills (yours)", LedgerEngine.billsFor(data, budget, today), "−")
                        BillLines(data.bills.filter { !it.family }, thisMonth, skipped)
                        if (skipped > 0 && LedgerEngine.dailyBillsMinor(data.bills) > 0) SmallNote("Daily bills aren’t charged on the $skipped days marked red or teal in Calendar.")
                        tally.getValue("free").takeIf { it.first > 0 }?.let { SubLine("Red · ${dayText(it.first)}", "no cost") }
                        tally.getValue("lite").takeIf { it.first > 0 }?.let { SubLine("Teal · ${dayText(it.first)}, instead of the daily bill", cash(it.second)) }
                        tally.getValue("full").takeIf { it.first > 0 }?.let { SubLine("Yellow · ${dayText(it.first)}, extra on top of the bill", cash(it.second)) }
                        if (planned > 0) MoneyLine("Planned for coming days", planned, "−")
                        MoneyLine("Saved for your goals", budget.savingsMinor, "−")
                        MoneyLine("Spent so far", spentSoFar, "−")
                        if (family > 0) {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text("Bills paid by family", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                                Text(cash(family), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                            }
                            BillLines(data.bills.filter { it.family }, thisMonth, 0)
                            SmallNote("Shown for your total only. It isn’t taken from your budget.")
                        }
                        Text("Every day you log, the rest of the month re-balances around what’s left.", Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "week") {
                WeekCard(averages, habit, Modifier.staggerIn(1)) { mode ->
                    model.change(if (mode == "HABIT") "Now following your habits" else "Spreading evenly") {
                        LedgerEngine.configure(it, settings.copy(weightMode = mode, weights = List(7) { 1 }), it.bills, today)
                    }
                }
            }
        } else {
            val stored = data.budgets.firstOrNull { it.month == view.toString() }
            val st = DayMarks.monthState(data, view)
            val started = LedgerEngine.budgetFrom(settings) // the whole month you set up in counts, so earlier days can still be logged
            // What the selected day gets with no plan of its own, and the swaps a payback put on it.
            val suggested = if (YearMonth.from(sel) == view && sel >= started) runCatching { LedgerEngine.allowanceOn(data, sel, today) }.getOrDefault(0L) else 0L
            val swaps = stored?.paybacks.orEmpty().flatMap { p -> p.days.filter { it.date == sel.toString() }.flatMap { it.swaps } }
            item(key = "calendar") {
                PlanCalendar(view, today, started, { DayMarks.dayInfo(it, today, st) }, suggested, swaps, sel,
                    earliest = YearMonth.from(started), latest = thisMonth.plusMonths(MonthsAhead),
                    onMonth = { delta -> viewText = view.plusMonths(delta.toLong()).toString() },
                    onTap = { date -> if (date == sel) cycle(date) else selText = date.toString() },
                    onEdit = editDay, onInfo = { showInfo = true }, modifier = Modifier.staggerIn(1))
            }
            val upcoming = data.budgets.flatMap { b -> b.plans.entries.filter { it.key >= today.toString() }.map { it.key to it.value } }
                .sortedBy { it.first }.take(8)
            item(key = "upcoming-title") { SubTitle("Upcoming plans") }
            if (upcoming.isEmpty()) item(key = "upcoming-none") {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(22.dp)) {
                    Text("No plans yet. Pick a coming day and enter how much you plan to spend, or 0 for a no-spend day.", Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else items(upcoming, key = { "plan-${it.first}" }) { (dateText, minor) ->
                val date = LocalDate.parse(dateText)
                PlanRow(if (minor == 0L) AppIcons.Check else AppIcons.CalendarMonth, if (minor == 0L) "No-spend day" else "Planned spend", listOf(date.format(DayTitle)),
                    if (minor == 0L) "No spend" else cash(minor), MaterialTheme.colorScheme.onSurface,
                    "${date.format(DayTitle)}, ${if (minor == 0L) "no-spend day" else "planned ${cash(minor)}"}. Tap to edit") { editDay(date) }
            }
            val activity = data.entries.filter { !it.family && it.type in SpendTypes && it.date.startsWith(view.toString()) }
                .sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.createdAt }).take(8)
            if (activity.isNotEmpty()) {
                item(key = "activity-title") { SubTitle("Activity") }
                items(activity, key = { "act-${it.id}" }) { e ->
                    val date = LocalDate.parse(e.date)
                    val category = Categories[e.category] ?: e.category
                    PlanRow(AppIcons.Coin, e.note.trim().ifBlank { category }, listOf(category, date.format(DayTitle)), "−${cash(e.amountMinor)}", OutRed,
                        "${date.format(DayTitle)}, spent ${cash(e.amountMinor)}. Tap to edit") { editDay(date) }
                }
            }
        }
    }
    if (showInfo) MarkInfoSheet { showInfo = false }
    removeAsk?.let { ask ->
        ConfirmDialog("Remove ${cash(ask.amount)} from ${ask.date.format(DayTitle)}?", "The expense you logged that day will be deleted.", { removeAsk = null }) {
            removeAsk = null; applyDay(ask.date, ask.minor, ask.mark, confirmed = true)
        }
    }
}

/** A tap that would clear an expense you logged, waiting for a yes. */
private class RemoveAsk(val date: LocalDate, val minor: Long?, val mark: String?, val amount: Long)

/** The explanation of the marks, in a small sheet instead of a legend on the calendar. */
@Composable
private fun MarkInfoSheet(onDismiss: () -> Unit) {
    EditorSheet("What the marks mean", false, onDismiss, onSave = onDismiss, saveLabel = "Done") {
        @Composable fun Row3(mark: DayMark, title: String, text: String) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(CalendarInk), contentAlignment = Alignment.Center) { MarkShape(mark, 0) }
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row3(DayMark.Free, "No spend", "Nothing spent. Daily bills aren’t counted that day.")
        Row3(DayMark.Lite, "Only this much", "Just the amount at the top. Daily bills aren’t counted that day.")
        Row3(DayMark.Full, "Budget + extra", "Daily bills are counted, plus the amount at the top.")
        Row3(DayMark.None, "No mark", "Nothing set for this day yet.")
        Text("Tap a day to select it. Tap it again to switch its mark, and tap the amount to type it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanTop(onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Planning", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Surface(Modifier.height(44.dp).pressable(onClickLabel = "Plan a day", onClick = onNew), shape = CircleShape, color = Color.Transparent,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppIcons.AddBold, null, Modifier.size(16.dp)); Text("Plan a day", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PlanTabs(tab: String, onTab: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Segment("Budget", tab == "budget", Modifier.weight(1f)) { onTab("budget") }
        Segment("Calendar", tab == "cal", Modifier.weight(1f)) { onTab("cal") }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, Modifier.padding(start = 2.dp, top = 8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One list row: a round black icon, a title with small lines under it, an amount and a chevron. */
@Composable
private fun PlanRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, lines: List<String>, amount: String, amountColor: Color, description: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }.pressable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            Text(amount, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = amountColor, maxLines = 1)
            Icon(AppIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
        }
    }
}

@Composable
private fun MoneyLine(label: String, minor: Long, sign: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$sign ${cash(minor)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A smaller, indented line under a total, so it is clear what the total is made of. */
@Composable
private fun SubLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
    }
}

@Composable
private fun SmallNote(text: String) {
    Text(text, Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val PerPeriod = mapOf("DAILY" to "a day", "WEEKLY" to "a week", "MONTHLY" to "a month")

/** One small line per bill: what it costs each time, and what that comes to this month (daily bills skip the no-spend days). */
@Composable
private fun BillLines(bills: List<PlannedBill>, month: YearMonth, skipped: Int) {
    bills.forEach { b ->
        val daily = b.frequency == "DAILY" && skipped > 0
        val days = month.lengthOfMonth() - if (daily) skipped else 0
        SubLine("${b.name} · ${cash(b.amountMinor)} ${PerPeriod[b.frequency].orEmpty()}${if (daily) " × $days days" else ""}", cash(LedgerEngine.periodAmount(b.amountMinor, b.frequency, days)))
    }
}

/** Average spend per weekday as bars, plus the choice of spreading money evenly or by those habits. */
@Composable
private fun WeekCard(averages: List<Long>, habit: Boolean, modifier: Modifier, onMode: (String) -> Unit) {
    val max = averages.max().coerceAtLeast(1)
    val top = averages.indexOf(averages.max())
    val hasHistory = averages.any { it > 0 }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Your week", style = MaterialTheme.typography.titleMedium)
            Text(if (hasHistory) "You spend most on ${DayOfWeek.of(top + 1).getDisplayName(DateTextStyle.FULL, Locale.ENGLISH)}s, about ${cash(averages[top])}."
                else "Log a few days of spending and your weekly pattern shows up here.",
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().height(132.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                averages.forEachIndexed { i, avg ->
                    val fill = rememberGrow(if (hasHistory) avg.toFloat() / max else .08f, 700, i * 40)
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        if (hasHistory && avg > 0) Text(compact(avg), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().weight(1f, fill = false).fillMaxHeight(fill.coerceAtLeast(.04f))
                            .clip(RoundedCornerShape(8.dp)).background(if (hasHistory && i == top) Yellow else MaterialTheme.colorScheme.surfaceContainerHighest))
                        Text(DayOfWeek.of(i + 1).getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH), Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = if (hasHistory && i == top) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text("Spread my money", Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp)) {
                Segment("Evenly", !habit, Modifier.weight(1f)) { if (habit) onMode("EVEN") }
                Segment("Like my habits", habit, Modifier.weight(1f)) { if (!habit) onMode("HABIT") }
            }
            Text(if (habit) "Days you usually spend more on get a bigger share, updated as you log."
                else "Every day without a plan gets the same share.", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent, label = "segment")
    Box(modifier.height(44.dp).clip(CircleShape).background(bg).pressable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- calendar ----------

/** The dark month card: month arrows, the selected day's amount, and a grid where each day carries its mark. */
@Composable
private fun PlanCalendar(month: YearMonth, today: LocalDate, started: LocalDate, info: (LocalDate) -> DayInfo, suggested: Long, swaps: List<PaybackSwap>,
    sel: LocalDate, earliest: YearMonth, latest: YearMonth, onMonth: (Int) -> Unit, onTap: (LocalDate) -> Unit, onEdit: (LocalDate) -> Unit, onInfo: () -> Unit, modifier: Modifier) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val total = (lead + month.lengthOfMonth() + 6) / 7 * 7
    val cells = List(total) { first.plusDays((it - lead).toLong()) }
    Surface(modifier.fillMaxWidth(), color = CalendarInk, contentColor = Color.White, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonth(-1) }, enabled = month > earliest, modifier = Modifier.size(44.dp)) {
                    Icon(AppIcons.ChevronLeft, "Previous month", Modifier.size(26.dp), tint = Color.White.copy(alpha = if (month > earliest) 1f else .25f))
                }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                IconButton(onClick = { onMonth(1) }, enabled = month < latest, modifier = Modifier.size(44.dp)) {
                    Icon(AppIcons.ChevronRight, "Next month", Modifier.size(26.dp), tint = Color.White.copy(alpha = if (month < latest) 1f else .25f))
                }
                IconButton(onClick = onInfo, modifier = Modifier.size(44.dp)) {
                    Box(Modifier.size(26.dp).border(1.5.dp, Color.White.copy(alpha = .55f), CircleShape).semantics { contentDescription = "What the marks mean" }, contentAlignment = Alignment.Center) {
                        Text("i", color = Color.White.copy(alpha = .8f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val selActive = YearMonth.from(sel) == month && sel >= started
            SelectedDay(sel, month, today, started, if (selActive) info(sel) else null, suggested, swaps) { onEdit(sel) }
            Row(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, Modifier.weight(1f).padding(vertical = 4.dp), textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .4f))
                }
            }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { date ->
                            val inMonth = YearMonth.from(date) == month
                            val active = inMonth && date >= started
                            val day = if (active) info(date) else null
                            val mark = markOf(day?.mode)
                            val hint = when (day?.mode) {
                                "free" -> "no spend"
                                "lite" -> "only ${cash(day.total ?: 0L)}"
                                "full" -> "budget used, ${cash(day.total ?: 0L)} in all"
                                else -> "not marked"
                            }
                            CalendarDay(date, active, inMonth, date == today, date == sel, mark, "${date.format(DayTitle)}, $hint", Modifier.weight(1f)) { onTap(date) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The picked day at the top of the calendar: what the day comes to (tap it to type an amount), what is suggested for it, and the swaps a payback put on it.
 * [info] is null for a day outside the month or before the budget started.
 */
@Composable
private fun SelectedDay(date: LocalDate, month: YearMonth, today: LocalDate, started: LocalDate, info: DayInfo?, suggested: Long, swaps: List<PaybackSwap>, onEdit: () -> Unit) {
    val off = info == null
    val name = date.format(DayTitle)
    // Several expenses on a past day are changed one by one from the list that opens; the chip says so.
    val locked = info != null && info.count > 1 && date < today
    val amount = if (info == null) "—" else cash(info.total ?: 0L)
    val chip = when {
        YearMonth.from(date) != month -> "Pick a day"
        date < started -> "Before your budget started"
        locked -> "${info?.count} expenses, see Activity"
        suggested < 0 -> "No budget left this month"
        else -> "Suggested ${cash(suggested)}"
    }
    val (chipBg, chipInk) = when (info?.mode) {
        "full" -> Yellow.copy(alpha = .18f) to Yellow
        "lite" -> TealMark.copy(alpha = .18f) to TealMark
        "free" -> NoSpendRed.copy(alpha = .18f) to NoSpendRed
        else -> Color.White.copy(alpha = .10f) to Color.White.copy(alpha = .75f)
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(20.dp))
        .then(if (off) Modifier else Modifier.semantics(mergeDescendants = true) { contentDescription = "$name, $amount, $chip. Tap to edit" }.pressable(onClick = onEdit))
        .padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!off) Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .6f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(amount, style = TextStyle(fontFeatureSettings = "tnum", color = Color.White.copy(alpha = if (off) .35f else 1f), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
                maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 38.sp, stepSize = 1.sp))
            if (!off && !locked) Icon(AppIcons.Edit, null, Modifier.size(18.dp).padding(top = 4.dp), tint = Color.White.copy(alpha = .4f))
        }
        Text(chip, Modifier.clip(CircleShape).background(chipBg).padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = chipInk)
        swaps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(swapIcon(s.name, s.usual), null, Modifier.size(14.dp), tint = Color.White.copy(alpha = .7f))
                Text("${s.name}${if (s.usual.isNotBlank()) " instead of ${s.usual}" else ""} · −${cash(s.savingMinor)}", fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
            }
        }
        if (info != null && info.family > 0) Text("Family paid ${cash(info.family)}, not part of your budget", fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
    }
}

@Composable
private fun CalendarDay(date: LocalDate, active: Boolean, inMonth: Boolean, isToday: Boolean, selected: Boolean, mark: DayMark, description: String, modifier: Modifier, onClick: () -> Unit) {
    val ink = when { !inMonth || !active -> Color.White.copy(alpha = .22f); isToday -> Yellow; else -> Color.White.copy(alpha = .92f) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.aspectRatio(1f / 1.12f).clip(shape)
        .then(if (selected) Modifier.background(Color.White.copy(alpha = .09f)) else Modifier)
        .then(if (active) Modifier.semantics(mergeDescendants = true) { contentDescription = description }.pressable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("${date.dayOfMonth}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, style = TextStyle(fontFeatureSettings = "tnum"))
        Box(Modifier.padding(top = 7.dp).height(6.dp), contentAlignment = Alignment.Center) {
            MarkShape(mark, date.dayOfMonth)
        }
    }
}

/** The mark itself: a red dash, a teal dash or a breathing yellow dot. */
@Composable
private fun MarkShape(mark: DayMark, seed: Int) {
    when (mark) {
        DayMark.Full -> BreathingDot(seed)
        DayMark.Free -> MarkDash(NoSpendRed)
        DayMark.Lite -> MarkDash(TealMark)
        DayMark.None -> Spacer(Modifier.size(6.dp))
    }
}

@Composable
private fun MarkDash(color: Color) {
    Box(Modifier.size(16.dp, 3.dp).drawBehind { drawRoundRect(color.copy(alpha = .35f), size = size.copy(width = size.width + 8.dp.toPx(), height = size.height + 6.dp.toPx()), topLeft = androidx.compose.ui.geometry.Offset(-4.dp.toPx(), -3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())) }
        .background(color, RoundedCornerShape(2.dp)))
}

/** A yellow dot that swells and glows slowly, each day a little out of step so the grid feels alive. */
@Composable
private fun BreathingDot(seed: Int) {
    val reduce = LocalReduceMotion.current
    val breathe by rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse, initialStartOffset = StartOffset((seed * 370) % 1400, StartOffsetType.FastForward)), label = "breath")
    val v = if (reduce) .6f else breathe
    Box(Modifier.size(6.dp).drawBehind {
        val centre = center
        drawCircle(Yellow.copy(alpha = .5f * v), radius = size.minDimension / 2f * (1.6f + 1.4f * v), center = centre)
        drawCircle(Yellow.copy(alpha = .7f + .3f * v), radius = size.minDimension / 2f * (.85f + .3f * v), center = centre)
    })
}

// ---------- editors ----------

/** Bottom sheet for planning one coming day. */
@Composable
fun PlanDayEditor(dateText: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val date = LocalDate.parse(dateText)
    val month = YearMonth.from(date)
    val existing = state.data.budgets.firstOrNull { it.month == month.toString() }?.plans?.get(dateText)
    val result = LedgerEngine.result(state.data, month, state.today)
    val suggested = result.dayShares[date]?.minor ?: 0L
    var amount by rememberSaveable(dateText) { mutableStateOf(existing?.let { Money(it).decimal() } ?: "") }
    EditorSheet(date.format(DayTitle), state.busy, close, saveLabel = "Save plan", onSave = {
        val value = zeroOrPositive(amount)
        model.submit("Plan saved", close) { LedgerEngine.setPlan(it, date, value, state.today) }
    }) {
        Text(if (existing != null) "Planned: ${cash(existing)}. Without a plan this day would get about ${cash(suggested)}."
            else "Right now this day gets about ${cash(suggested)}. Plan a set amount and the other days adjust.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FormText("Planned spend (RM)", amount, { amount = it }, decimal = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = amount == "0", onClick = { amount = "0" }, label = { Text("No spending") })
            FilterChip(selected = false, onClick = { amount = Money(suggested.coerceAtLeast(0)).decimal() }, label = { Text("Use suggested") })
        }
        if (existing != null) TextButton(onClick = { model.submit("Plan removed", close) { LedgerEngine.setPlan(it, date, null, state.today) } }) {
            Text("Remove plan", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Bottom sheet for what you spent on today or a past day. Fixed bills and fund purchases are listed but not editable here;
 * only your own expenses are. With several expenses on the day you pick which to change.
 */
@Composable
fun DaySpendEditor(dateText: String, state: AllowanceState, model: AllowanceViewModel, open: (String) -> Unit, close: () -> Unit) {
    val date = LocalDate.parse(dateText)
    val own = state.data.entries.filter { it.date == dateText && it.type == EntryType.EXPENSE && !it.family }
    val total = own.sumOf { it.amountMinor }
    val other = state.data.entries.filter { it.date == dateText && !it.family && it.type in setOf(EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE) }.sumOf { it.amountMinor }
    var amount by rememberSaveable(dateText) { mutableStateOf(if (own.isNotEmpty()) Money(total).decimal() else "") }
    val extra = if (other > 0) "Bills and fund purchases add ${cash(other)} on top of this." else null
    if (own.size > 1) {
        EditorSheet(date.format(DayTitle), state.busy, close, close, saveLabel = "Done") {
            Text("${own.size} expenses add up to ${cash(total)} on this day. Pick one to change it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            own.forEach { e ->
                OutlinedButton(onClick = { open("entry:${e.id}") }, Modifier.fillMaxWidth()) {
                    Text("${Categories[e.category] ?: e.category}${if (e.note.isNotBlank()) " · ${e.note}" else ""} · ${cash(e.amountMinor)}", maxLines = 1)
                }
            }
            OutlinedButton(onClick = { open("entrydate:$dateText") }, Modifier.fillMaxWidth()) { Text("Add another expense") }
            extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        return
    }
    EditorSheet(date.format(DayTitle), state.busy, close, saveLabel = "Save", onSave = {
        val minor = zeroOrPositive(amount)
        model.submit(if (minor > 0) "Saved" else "Marked as a no-spend day", close) { data ->
            when {
                own.isEmpty() -> if (minor > 0) LedgerEngine.saveEntry(data, LedgerEntry(LedgerEngine.id(), dateText, minor, EntryType.EXPENSE, "OTHER"), state.today) else data
                minor > 0 -> LedgerEngine.saveEntry(data, own[0].copy(amountMinor = minor), state.today)
                else -> LedgerEngine.deleteEntry(data, own[0].id)
            }
        }
    }) {
        FormText("Spent that day (RM)", amount, { amount = it }, decimal = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = amount == "0", onClick = { amount = "0" }, label = { Text("No spending") }) }
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (own.isNotEmpty()) Text("Logged as ${Categories[own[0].category] ?: own[0].category}${if (own[0].note.isNotBlank()) " · ${own[0].note}" else ""}. Changing the amount keeps the rest of the entry.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
