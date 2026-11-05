package com.allowance.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.app.R
import com.allowance.core.ledger.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SpendTypes = setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE)

/** The four figures on the home card, outermost ring first. A ring and its tile share a colour, so the rings need no legend. */
private enum class Figure(val title: String, val color: Color) {
    Spent("Spent", Color(0xFFFFD83D)), Income("Income", Color(0xFF7ED9B0)), Transfer("Trans", Color(0xFF8ED1F5)), Saved("Saved", Color(0xFFFFA3C4)),
}

/** Money spent, received, moved between accounts and set aside between two dates (inclusive ISO strings). */
private fun periodStats(data: AppData, from: String, to: String): Map<Figure, Long> {
    var spent = 0L; var income = 0L; var transfer = 0L; var saved = 0L
    for (e in data.entries) {
        if (e.date < from || e.date > to) continue
        when {
            !e.family && e.type in SpendTypes -> spent += e.amountMinor
            e.type == EntryType.INCOME || e.type == EntryType.SCHEDULED_INCOME -> income += e.amountMinor
            e.type == EntryType.TRANSFER -> transfer += e.amountMinor
            e.type == EntryType.GOAL_DEPOSIT || e.type == EntryType.FUND_ALLOCATION -> saved += e.amountMinor
            e.type == EntryType.GOAL_WITHDRAWAL || e.type == EntryType.FUND_RELEASE -> saved -= e.amountMinor
            else -> {}
        }
    }
    return mapOf(Figure.Spent to spent, Figure.Income to income, Figure.Transfer to transfer, Figure.Saved to saved.coerceAtLeast(0L))
}

/** Short enough for a small tile: 161.5, 1,250 and then 12.3k from RM 1,000 up. */
private fun shortCash(minor: Long): String =
    if (minor >= 100_000) "%.1fk".format(java.util.Locale.US, minor / 100_000.0).replace(".0k", "k")
    else cash(minor).removePrefix("RM ").removeSuffix(".00").let { if (it.contains('.')) it.trimEnd('0') else it }

private fun signedCash(minor: Long): String = if (minor < 0) "-" + cash(-minor) else cash(minor)

private fun greeting(): String = LocalTime.now().hour.let { if (it < 12) "Good morning" else if (it < 18) "Good afternoon" else "Good evening" }

/** Home: a greeting, one summary card (today's money, concentric rings and four figures for this month), quick actions and recent activity. */
@Composable
fun TodayPage(state: AllowanceState, open: (String) -> Unit, onSettings: () -> Unit = {}, onSeeAll: () -> Unit = {}, onPlan: () -> Unit = {}) {
    val data = state.data
    val today = state.today
    val month = YearMonth.from(today)
    val result = LedgerEngine.result(data, month, today)
    val over = result.remaining.minor < 0 || result.todayRemaining.minor < 0
    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
    val stats = periodStats(data, month.atDay(1).toString(), today.toString())
    val recent = data.entries.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.createdAt }).take(8)
    val nickname = data.settings?.nickname?.ifBlank { null }
    val remaining = result.todayRemaining.minor
    val label = if (remaining < 0) "Over budget today" else if (result.todayAllowance.minor == 0L) "No allowance today" else "Available to spend today"
    val sub = if (remaining < 0) "${cash(-remaining)} over today’s budget" else if (result.todayAllowance.minor == 0L) "Nothing to spend today"
        else "Spent ${cash(result.spentToday.minor)} of ${cash(result.todayAllowance.minor)} today"

    Page(topPadding = 0.dp) {
        item(key = "top") { HomeTop(nickname, onSettings) { open("budget:") } }
        item(key = "card") { SummaryCard(label, remaining, sub, result.remaining.minor, daysLeft, over, stats, onPlan, Modifier.staggerIn(1)) }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Add", AppIcons.AddBold, true, Modifier.weight(1f), "Add entry") { open("entry:") }
                QuickAction("Plan", AppIcons.CalendarMonth, false, Modifier.weight(1f), onClick = onPlan)
                QuickAction("Transfer", AppIcons.SwapHoriz, false, Modifier.weight(1f)) { open("transfer:") }
            }
        }
        item(key = "transactions-title") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).staggerIn(3), verticalAlignment = Alignment.CenterVertically) {
                Text("Transactions", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onSeeAll, Modifier.offset(x = 12.dp)) { Icon(AppIcons.ArrowRight, "See all transactions", Modifier.size(24.dp)) }
            }
        }
        if (recent.isEmpty()) item(key = "empty") { HomeEmptyState(Modifier.staggerIn(4)) { open("entry:") } }
        else itemsIndexed(recent, key = { _, entry -> entry.id }) { index, entry ->
            TransactionCard(entry, data, today, Modifier.animateItem().staggerIn(index + 4)) { open("entry:${entry.id}") }
        }
    }
}

@Composable
private fun HomeTop(nickname: String?, onSettings: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        // Tap the avatar and name to edit your name, income and bills.
        Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics(mergeDescendants = true) { contentDescription = "Edit my name, income and bills" }.pressable(onClickLabel = "Edit my name, income and bills", onClick = onProfile)
            .padding(start = 6.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.tiger_head), null, Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFFFE266)), contentScale = ContentScale.Crop)
            Column {
                Text("${greeting()},", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(nickname ?: "there", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(48.dp).semantics { contentDescription = "Settings" }.pressable(onClick = onSettings).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Settings, null, Modifier.size(24.dp))
        }
    }
}

/** The big card: yellow normally, coral when today or the month is overspent. */
@Composable
private fun SummaryCard(label: String, remaining: Long, sub: String, leftThisMonth: Long, daysLeft: Int, over: Boolean, stats: Map<Figure, Long>, onPlan: () -> Unit, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val top = if (over) Color(0xFFFF8A78) else Color(0xFFFFC928)
    val bottom = if (over) Color(0xFFFFF1EE) else Color(0xFFFFFAE3)
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = ink.copy(alpha = .62f)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).heroBackground(dark, if (dark) MaterialTheme.colorScheme.surfaceContainer else bottom, top)
        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = muted)
            Box(Modifier.size(40.dp).semantics { contentDescription = "Plan this month" }.pressable(onClick = onPlan).clip(CircleShape).background(Color.White.copy(alpha = .38f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.CalendarMonth, null, Modifier.size(20.dp), tint = ink)
            }
        }
        BasicText(signedCash(rememberCountUp(remaining)), Modifier.fillMaxWidth().padding(top = 6.dp),
            style = TextStyle(fontFeatureSettings = "tnum", color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 60.sp, letterSpacing = (-2).sp),
            maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = 60.sp, stepSize = 1.sp))
        Text(sub, Modifier.padding(top = 6.dp), fontSize = 15.sp, color = muted)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Left this month", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted); Text(cash(leftThisMonth), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.End) { Text("Days left", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted); Text("$daysLeft ${if (daysLeft == 1) "day" else "days"}", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Rings(stats, Modifier.weight(.8f).aspectRatio(1f))
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(26.dp), shadowElevation = 3.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
                        Text("This month", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("RM", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Figure.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { pair.forEach { FigureTile(it, stats.getValue(it), Modifier.weight(1f)) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FigureTile(figure: Figure, minor: Long, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${figure.title} ${cash(minor)}" }.clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(figure.color))
            Text(figure.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(shortCash(minor), Modifier.padding(top = 3.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-.3).sp)
    }
}

/**
 * Four concentric rings inside a grey outer band. Each ring is one figure drawn as a share of the biggest one (which fills 90% of
 * the circle), so the rings compare sizes at a glance.
 */
@Composable
private fun Rings(stats: Map<Figure, Long>, modifier: Modifier) {
    val grow = rememberGrow(1f, 900, 100)
    val max = Figure.entries.maxOf { stats.getValue(it) }.coerceAtLeast(0L)
    Canvas(modifier.semantics { contentDescription = "This month: " + Figure.entries.joinToString(", ") { "${it.title} ${cash(stats.getValue(it))}" } }) {
        val unit = size.minDimension / 100f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF3A3A3D), 49f * unit, c)
        drawCircle(Color(0xFF141416), 45.5f * unit, c)
        Figure.entries.forEachIndexed { i, figure ->
            val r = (40f - i * 8.5f) * unit
            val stroke = Stroke(6.5f * unit, cap = StrokeCap.Round)
            val topLeft = Offset(c.x - r, c.y - r); val box = Size(r * 2, r * 2)
            drawArc(Color.White.copy(alpha = .08f), -90f, 360f, false, topLeft, box, style = stroke)
            val value = stats.getValue(figure)
            if (value > 0 && max > 0) {
                val sweep = maxOf(4f, value.toFloat() / max * 90f) / 100f * 360f * grow
                drawArc(figure.color, -90f, sweep, false, topLeft, box, style = stroke)
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, primary: Boolean, modifier: Modifier, description: String = label, onClick: () -> Unit) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }.pressable(onClickLabel = label, onClick = onClick).clip(RoundedCornerShape(24.dp))
        .background(if (primary) Yellow else MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (primary) Ink else MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Ink else MaterialTheme.colorScheme.onSurface)
    }
}

/** First-run state: the tiger, one sentence, one action. */
@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.tiger_head), null, Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFFFF4C4)).padding(6.dp))
            Text("No transactions yet", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text("Log your first expense and your budget updates on its own.", Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onAdd, Modifier.padding(top = 20.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Ink), contentPadding = PaddingValues(horizontal = 24.dp)) {
                Icon(AppIcons.AddBold, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add an expense")
            }
        }
    }
}

private fun entryIcon(entry: LedgerEntry): ImageVector = when (entry.type) {
    EntryType.TRANSFER -> AppIcons.SwapHoriz
    EntryType.GOAL_DEPOSIT, EntryType.GOAL_WITHDRAWAL -> AppIcons.Savings
    EntryType.FUND_ALLOCATION, EntryType.FUND_RELEASE -> AppIcons.AccountBalanceWallet
    EntryType.ADJUSTMENT -> AppIcons.Tune
    EntryType.INCOME, EntryType.SCHEDULED_INCOME -> AppIcons.Download
    else -> categoryIcon(entry.category)
}

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun whenLabel(entry: LedgerEntry, today: LocalDate): String {
    val date = LocalDate.parse(entry.date)
    val created = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault())
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateFormat)
    }
    return if (date == created.toLocalDate()) "$label, ${created.format(TimeFormat)}" else label
}

@Composable
private fun TransactionCard(entry: LedgerEntry, data: AppData, today: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    val sign = when (entry.type) {
        EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE -> "-"
        EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.FUND_RELEASE, EntryType.GOAL_WITHDRAWAL -> "+"
        else -> ""
    }
    val account = data.accounts.find { it.id == entry.accountId }?.name
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(entryIcon(entry), null, Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.note.ifBlank { TypeLabels[entry.type].orEmpty() }, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(whenLabel(entry, today) + if (entry.family) " · Paid by family" else "", account).joinToString(" · "),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(sign + cash(entry.amountMinor), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private val DateFormat = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)

private val TypeLabels = mapOf(EntryType.EXPENSE to "Expense", EntryType.INCOME to "Extra income", EntryType.SCHEDULED_INCOME to "Scheduled income",
    EntryType.TRANSFER to "Transfer", EntryType.ADJUSTMENT to "Balance adjustment", EntryType.BILL_PAYMENT to "Bill payment",
    EntryType.FUND_ALLOCATION to "Fund top-up", EntryType.FUND_PURCHASE to "Fund purchase", EntryType.FUND_RELEASE to "Fund release",
    EntryType.GOAL_DEPOSIT to "Saved to goal", EntryType.GOAL_WITHDRAWAL to "Taken from goal")
