package com.allowance.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import androidx.compose.ui.unit.Dp
import com.allowance.core.ledger.EntryType
import com.allowance.core.ledger.LedgerEngine
import com.allowance.core.ledger.LedgerEntry
import com.allowance.core.ledger.PaybackPlan
import com.allowance.core.ledger.SwapChoice
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** One turn in the assistant thread. Checks are re-evaluated against the live budget each time they draw. */
private sealed interface Turn {
    data class Asked(val text: String) : Turn
    data class Note(val text: String) : Turn
    data class Check(val name: String, val amount: Long) : Turn
    /** What today's unspent money does for the days ahead. */
    data object Surplus : Turn
}

private val PricePattern = Regex("""(?i)(?:rm\s*)?(\d{1,9}(?:[.,]\d{1,2})?)""")
private val Filler = Regex("""(?i)\b(can i afford|could i buy|should i buy|can i buy|i want|buy|a|an|the|for|rm|today|this)\b|[?!.,]""")

/** "Sneakers RM 120" → ("Sneakers", 12000). Returns null when there is no usable price. */
private fun parsePurchase(text: String): Pair<String, Long>? {
    val match = PricePattern.find(text) ?: return null
    val amount = runCatching { positive(match.groupValues[1].replace(',', '.')) }.getOrNull() ?: return null
    val name = text.removeRange(match.range).replace(Filler, " ").replace(Regex("\\s+"), " ").trim()
    return name.ifBlank { "this" }.replaceFirstChar { it.uppercase() } to amount
}

@Composable
fun AdvisorPage(state: AllowanceState, open: (String) -> Unit, onBack: () -> Unit = {}, onWishList: () -> Unit = {},
    payBack: (String, Long, Map<String, List<String>>?) -> Unit = { _, _, _ -> }, addWish: (String, Long) -> Unit) {
    val result = LedgerEngine.result(state.data, YearMonth.from(state.today), state.today)
    val nickname = state.data.settings?.nickname?.ifBlank { null }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < .5f
    val preview = parsePurchase(input)
    LaunchedEffect(turns.size) {
        if (turns.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(380) // let the new bubble finish expanding first
        list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        turns += Turn.Asked(text)
        turns += parsePurchase(text)?.let { (name, amount) -> Turn.Check(name, amount) }
            ?: Turn.Note("Tell me what you want and its price, like “Sneakers RM 120”, and I’ll check it against your budget.")
        input = ""
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize().heroBackground(dark, background, HeroTop).testTag("page")) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).height(56.dp)) {
            IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) { Icon(AppIcons.ChevronLeft, "Back", Modifier.size(28.dp)) }
            Text("Budget assistant", Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onWishList, Modifier.align(Alignment.CenterEnd)) { Icon(AppIcons.ChatHistory, "Wish list", Modifier.size(28.dp)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item(key = "hero") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TigerMascot(Modifier.fillMaxWidth(.62f))
                    Spacer(Modifier.height(18.dp))
                    Text(if (nickname != null) "Hello $nickname" else "Hello there", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.staggerIn(1))
                    Text("How can I help\nyou today?", Modifier.padding(top = 6.dp).staggerIn(2), fontSize = 38.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, textAlign = TextAlign.Center)
                    Text("Let’s make room for what you want.", Modifier.padding(top = 10.dp).staggerIn(3), fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            item(key = "chip") {
                Surface(Modifier.padding(top = 8.dp).staggerIn(4).pressable {
                    turns += Turn.Asked("Can I afford this today?")
                    if (result.todayRemaining.minor > 0) {
                        turns += Turn.Surplus
                        turns += Turn.Note("Tell me what you want and its price, and I’ll show what it does to the days ahead.")
                    } else turns += Turn.Note(if (result.todayRemaining.minor == 0L) "Today’s allowance is used up. Tell me what you want and its price, and I’ll show what it does to the days ahead."
                        else "You’re ${cash(-result.todayRemaining.minor)} over today’s allowance, so it’s worth waiting until tomorrow.")
                }, color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(AppIcons.ShoppingBag, null, Modifier.size(24.dp))
                        Text("Can I afford this today?", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            item(key = "example") {
                val shown = preview?.second ?: 3000L
                Surface(Modifier.fillMaxWidth(.9f).staggerIn(5), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (preview == null) "Example" else "Your purchase", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("A ${cash(shown).removeSuffix(".00")} purchase spread over 30 days", fontSize = 16.sp)
                        Text("Spend ${cash(shown / 30).removeSuffix(".00")} less per day.", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            itemsIndexed(turns, key = { index, _ -> "turn-$index" }) { _, turn ->
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 3 } + expandVertically()) {
                    when (turn) {
                        is Turn.Asked -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(turn.text, Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(Yellow)
                                .padding(horizontal = 16.dp, vertical = 11.dp), color = Ink, fontSize = 16.sp)
                        }
                        is Turn.Note -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            Text(turn.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 16.sp)
                        }
                        is Turn.Check -> CheckCard(turn, state, result, open, addWish, payBack)
                        is Turn.Surplus -> SurplusCard(state, result)
                    }
                }
            }
            item(key = "footnote") {
                Text("Estimates run on your device using your own budget.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InputBar(input, { input = it }, ::send,
            onCamera = { turns += Turn.Note("Photo input isn’t available yet. Type the item and its price instead.") },
            onVoice = { turns += Turn.Note("Voice input isn’t available yet. Type the item and its price instead.") })
    }
}

// ---------- the picture: one bar per day, teal = what you can still spend, red = what this purchase takes, light = extra ----------
private val BarTeal = Color(0xFF5FD2BA)
private val BarRed = Color(0xFFFF6B63)

/** Whole ringgit when big, sen when small, so a RM 1.19 trim and a RM 114 day both stay readable in a narrow bar. */
private fun rm(minor: Long): String {
    val v = kotlin.math.abs(minor) / 100.0
    val text = if (v == Math.floor(v) || v >= 100) Math.round(v).toString() else if (v < 10) "%.2f".format(Locale.US, v) else "%.1f".format(Locale.US, v)
    return (if (minor < 0) "−" else "") + text
}

/** One day's bar, bottom to top: [keep] (teal), [red], [plus] (light). [top] is the small label above; [today] marks the first bar. */
private class BarCol(val today: Boolean, val date: LocalDate?, val keep: Long, val red: Long = 0, val plus: Long = 0, val top: String = "", val topColor: Color = Color(0xFFB3261E))

@Composable
private fun DayBars(cols: List<BarCol>, description: String) {
    val maxTotal = cols.maxOf { it.keep + it.red + it.plus }.coerceAtLeast(1L)
    val full = 124.dp
    // Red and light slices get a floor so a few sen of difference stays visible; the exact amount is written above the bar.
    fun slice(minor: Long, floor: Dp): Dp = if (minor > 0) maxOf(floor, full * (minor.toFloat() / maxTotal)) else 0.dp
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).semantics { contentDescription = description }.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        cols.forEach { c ->
            Column(Modifier.width(if (c.today) 50.dp else 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.height(18.dp), contentAlignment = Alignment.BottomCenter) { Text(c.top, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.topColor, maxLines = 1) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                    if (c.plus > 0) Box(Modifier.fillMaxWidth().height(slice(c.plus, 14.dp)).background(Color.White.copy(alpha = .85f)))
                    if (c.red > 0) Box(Modifier.fillMaxWidth().height(slice(c.red, 14.dp)).background(BarRed))
                    if (c.keep > 0) {
                        val h = slice(c.keep, 6.dp)
                        Box(Modifier.fillMaxWidth().height(h).background(BarTeal), contentAlignment = Alignment.Center) {
                            if (h >= 18.dp) Text(rm(c.keep), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                        }
                    }
                    if (c.keep + c.red + c.plus <= 0) Box(Modifier.fillMaxWidth().height(6.dp).background(BarRed))
                }
                Text(if (c.today) "Today" else "${c.date?.dayOfMonth}", Modifier.padding(top = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(if (c.today) "" else c.date?.dayOfWeek?.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH).orEmpty(), Modifier.padding(top = 3.dp).height(16.dp), fontSize = 11.sp, color = Ink.copy(alpha = .6f))
            }
        }
    }
}

@Composable
private fun BarKey(vararg items: Pair<Color, String>) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (color, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color)); Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink)
            }
        }
    }
}

/** Your budget as it would be if today also had an extra purchase of [amount]; null if that can't be recorded. */
private fun withSpend(state: AllowanceState, amount: Long): com.allowance.core.calculator.BudgetResult? = runCatching {
    val next = LedgerEngine.saveEntry(state.data, LedgerEntry(LedgerEngine.id(), state.today.toString(), amount, EntryType.EXPENSE, "SHOPPING"), state.today)
    LedgerEngine.result(next, YearMonth.from(state.today), state.today)
}.getOrNull()

/** Unspent today: how much looser each coming day gets because today's money rolls forward. */
@Composable
private fun SurplusCard(state: AllowanceState, result: com.allowance.core.calculator.BudgetResult) {
    val days = result.futureAllowances.keys.sorted()
    val extra = days.map { d -> Triple(d, (result.dayShares[d]?.minor ?: 0L).coerceAtLeast(0L), ((result.futureAllowances[d]?.minor ?: 0L) - (result.dayShares[d]?.minor ?: 0L)).coerceAtLeast(0L)) }
    val avg = if (extra.isEmpty()) 0L else Math.round(extra.sumOf { it.third }.toDouble() / extra.size)
    val left = result.todayRemaining.minor
    val cols = listOf(BarCol(true, null, left)) + extra.map { (d, base, plus) -> BarCol(false, d, base, 0, plus, if (plus > 0) "+${rm(plus)}" else "", Color(0xFF0B6B54)) }
    val big = if (avg > 0) "About ${cash(avg)} more a day" else "${cash(left)} left today"
    val small = if (avg > 0) "Today’s ${cash(left)} unspent is shared over the next ${extra.size} days." else "Spend it today, or it rolls into tomorrow."
    Surface(Modifier.fillMaxWidth(), color = Color(0xFFC8F2DC), shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today · ${cash(result.todayAllowance.minor)} planned", color = Ink.copy(alpha = .7f), fontSize = 14.sp)
            Text("${cash(left)} unspent", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
            Text(big, Modifier.padding(top = 4.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp)
            Text(small, color = Ink.copy(alpha = .72f), fontSize = 14.sp)
            DayBars(cols, "$big. $small")
            BarKey(BarTeal to "You can spend", Color.White.copy(alpha = .85f) to "Extra from today")
        }
    }
}

@Composable
private fun CheckCard(turn: Turn.Check, state: AllowanceState, result: com.allowance.core.calculator.BudgetResult, open: (String) -> Unit, addWish: (String, Long) -> Unit,
    payBack: (String, Long, Map<String, List<String>>?) -> Unit) {
    val amount = turn.amount
    val after = result.remaining.minor - amount
    val fits = amount <= result.todayRemaining.minor
    val color = if (fits) Color(0xFFC8F2DC) else if (after >= 0) Color(0xFFFFEC8A) else Color(0xFFFFC2B8)
    // Over today's allowance: a plan to pay it back over the next days. Nothing is used until you tap a swap (date to swap ids).
    var pick by remember(turn) { mutableStateOf<Map<String, List<String>>?>(null) }
    val plan = if (fits) null else LedgerEngine.payback(state.data, amount, state.today, pick)
    val hasPlan = plan != null && plan.options.isNotEmpty()
    val full = plan != null && plan.days.isNotEmpty() && plan.short == 0L // every sen of the overspend can be paid back over the next days
    val sim = if (hasPlan) null else withSpend(state, amount)
    val days = result.futureAllowances.keys.sorted()
    fun toggle(date: String, id: String) {
        val base = pick ?: plan?.options?.associate { it.date to it.swaps.map { s -> s.id } }.orEmpty() // start from what is on now
        val now = base[date].orEmpty()
        pick = base + (date to if (id in now) now - id else now + id)
    }
    Surface(Modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${turn.name} · ${cash(amount)}", color = Ink.copy(alpha = .7f), fontSize = 14.sp)
            Text(if (fits) "Fits today’s budget" else if (after >= 0) "Maybe wait a little" else "Hold off this month",
                color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
            if (plan != null && hasPlan) PaybackSection(plan, full, ::toggle)
            else if (sim == null) Text("After buying, you’d have ${cash(result.todayRemaining.minor - amount)} left today.", color = Ink, fontSize = 16.sp)
            else {
                // What each coming day would have been (counting today as fully spent) against what it has after this purchase.
                val cuts = days.map { d ->
                    val was = (result.dayShares[d]?.minor ?: 0L).coerceAtLeast(0L); val now = (sim.futureAllowances[d]?.minor ?: 0L).coerceAtLeast(0L)
                    Triple(d, now, (was - now).coerceAtLeast(0L))
                }
                val avgCut = if (cuts.isEmpty()) 0L else Math.round(cuts.sumOf { it.third }.toDouble() / cuts.size)
                val left = result.todayRemaining.minor.coerceAtLeast(0L)
                val cols = listOf(BarCol(true, null, (left - amount).coerceAtLeast(0L), minOf(amount, left), 0, "−${rm(amount)}")) +
                    cuts.map { (d, now, cut) -> BarCol(false, d, now, cut, 0, if (cut > 0) "−${rm(cut)}" else "") }
                val big = if (fits || cuts.isEmpty()) "${cash((result.todayRemaining.minor - amount).coerceAtLeast(0L))} left today" else "Spend about ${cash(avgCut)} less a day"
                val small = if (fits) (if (avgCut > 0) "About ${cash(avgCut)} less a day for the next ${cuts.size} days." else "Nothing changes for the next ${cuts.size} days.")
                    else if (cuts.isNotEmpty()) "For the next ${cuts.size} days, to make up for the ${cash(amount - left)} that doesn’t fit today." else "There are no budgeted days left this month."
                Text(big, Modifier.padding(top = 4.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp)
                Text(small, color = Ink.copy(alpha = .72f), fontSize = 14.sp)
                DayBars(cols, "$big. $small")
                BarKey(BarTeal to "You can spend", BarRed to "Taken by this")
            }
            if (after < 0) Text("That’s ${cash(-after)} over this month’s budget and would eat into your savings.", color = Ink, fontSize = 16.sp)
            if (full) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { payBack(turn.name, amount, pick) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Text("Buy it, pay back over ${dayCount(plan.days.size)}") }
                TextButton(onClick = { addWish(turn.name, amount) }, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = Ink)) { Text("Save to wish list") }
            } else Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { addWish(turn.name, amount) }, enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Text("Save to wish list") }
                TextButton(onClick = { open("purchase:${amount}:${turn.name}") }, colors = ButtonDefaults.textButtonColors(contentColor = Ink)) { Text("I bought it") }
            }
        }
    }
}

// ---------- paying an overspend back: bars per day, with the swaps you can tap under each date ----------
private fun dayCount(n: Int) = "$n day${if (n == 1) "" else "s"}"
private fun nextDays(n: Int) = if (n == 1) "the next day" else "the next $n days"
private fun signed(minor: Long) = (if (minor > 0) "+" else if (minor < 0) "−" else "") + rm(kotlin.math.abs(minor))
/** "2026-11-04" → "11/04", the way the date sits under each bar. */
private fun md(date: String) = "${date.substring(5, 7)}/${date.substring(8)}"

private class BarLayer(val text: String, val v: Long, val brush: Brush, val sub: String = "", val color: Color = Ink)
private class PlanCol(val today: Boolean, val date: String?, val badge: Long, val layers: List<BarLayer>, val cands: List<SwapChoice>, val picked: Set<String>)

private val BarPink = Color(0xFFFFC3C9)
private val BarMint = Color(0xFFB4F5E2)
/** The block a swap for one of your fixed bills adds to a day, and the disc it lights up: the same yellow-to-white. */
private val BillBrush = Brush.verticalGradient(listOf(Color(0xFFFFD91F), Color(0xFFFFEB85), Color(0xFFFFF6C9)))
private val LitBrush = Brush.linearGradient(listOf(Color(0xFFFFD91F), Color(0xFFFFF3A6), Color.White))

@Composable
private fun PaybackSection(plan: PaybackPlan, full: Boolean, onToggle: (String, String) -> Unit) {
    val avg = if (plan.days.isEmpty()) 0L else Math.round(plan.covered.toDouble() / plan.days.size)
    val used = plan.days.any { it.swaps.isNotEmpty() }
    val big = "RM ${rm(plan.total - plan.over)} + ${rm(plan.over)} = RM ${rm(plan.total)}"
    val small = if (used) "Swap in cheaper options over ${nextDays(plan.days.size)} to make up the ${cash(plan.covered)}."
        else "${if (full) "Pay it back over" else "Trim"} ${nextDays(plan.days.size)}: about ${cash(avg)} less a day."
    // Today: your usual day plus the extra. Each coming day: your usual day, what is deducted from it, then a block for each fixed-bill swap in use.
    val cols = buildList {
        add(PlanCol(true, null, plan.total, listOf(BarLayer(signed(plan.over), plan.over, SolidColor(BarRed)), BarLayer(rm(plan.total - plan.over), plan.total - plan.over, SolidColor(BarPink))), emptyList(), emptySet()))
        plan.options.forEach { c ->
            val layers = buildList {
                add(BarLayer(rm(c.was), c.now, SolidColor(BarTeal)))
                if (c.cut > 0) add(BarLayer(signed(-c.cut), c.cut, SolidColor(BarMint)))
                c.swaps.filter { it.fromBill }.forEach { s ->
                    add(BarLayer(signed(-s.saving), s.saving, BillBrush, s.swap.usualName.trim().split(Regex("\\s+")).first().take(9).uppercase(Locale.ROOT), Color(0xFF3A2E00)))
                }
            }
            add(PlanCol(false, c.date, c.now, layers, c.cands, c.swaps.map { it.id }.toSet()))
        }
    }
    Text(big, Modifier.padding(top = 4.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp)
    Text(small, color = Ink.copy(alpha = .72f), fontSize = 14.sp)
    Text("PLAN · ${dayCount(plan.days.size)} to pay back", color = Ink.copy(alpha = .7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
    PaybackChart(cols, "$big. $small", onToggle)
    // Once tapped it says what changes ("Breakfast RM 10.00 → RM 2.00 on 11/06"); before that it explains what the icon would do.
    val lit = linkedMapOf<String, Pair<SwapChoice, List<String>>>()
    for (o in plan.options) for (s in o.swaps) if (s.fromBill) lit[s.id] = s to (lit[s.id]?.second.orEmpty() + md(o.date))
    val billSwap = plan.options.flatMap { it.cands }.firstOrNull { it.fromBill }
    if (lit.isNotEmpty()) Text(buildAnnotatedString {
        lit.values.forEach { (s, dates) ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.swap.usualName) }
            append(" ${cash(s.swap.usualMinor)} → ${cash(s.swap.cheaperMinor)} on ${dates.joinToString(", ")} (−${cash(s.saving)} a day)\n")
        }
        append("Nothing changes until you buy.")
    }, color = Ink.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 18.sp)
    else if (billSwap != null) Text("From your ${billSwap.swap.usualName} bill (${cash(billSwap.swap.usualMinor)} a day): ${billSwap.swap.cheaperName} is ${cash(billSwap.swap.cheaperMinor)}, " +
        "so it saves ${cash(billSwap.saving)}. Tap its icon under a date to use it that day. Nothing changes until you buy.", color = Ink.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 18.sp)
    if (plan.short > 0) Text("${cash(plan.short)} more can’t be paid back from the days left.", color = Ink, fontSize = 15.sp)
}

private val PlotHeight = 190.dp

@Composable
private fun PaybackChart(cols: List<PlanCol>, description: String, onToggle: (String, String) -> Unit) {
    val maxTotal = cols.maxOf { c -> c.layers.sumOf { it.v.coerceAtLeast(0L) } }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).semantics { contentDescription = description }.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        cols.forEach { c ->
            Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Each layer is as tall as its number, with room enough to be read.
                val heights = c.layers.map { maxOf(28.dp, PlotHeight * (it.v.coerceAtLeast(0L).toFloat() / maxTotal)) }
                val barHeight = maxOf(60.dp, heights.fold(0.dp) { a, b -> a + b })
                Box(Modifier.fillMaxWidth().height(PlotHeight + 70.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().height(barHeight)) {
                        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))) {
                            c.layers.forEachIndexed { i, l ->
                                Column(Modifier.fillMaxWidth().height(heights[i]).background(l.brush), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(l.text, color = l.color, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                    if (l.sub.isNotEmpty()) Text(l.sub, color = l.color.copy(alpha = .75f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Box(Modifier.align(Alignment.TopCenter).offset(y = (-13).dp).size(26.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
                            Text(rm(c.badge), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                // The swaps you can use that day, overlapping like the logos under a stats bar. Tap one to switch it on or off.
                Row(Modifier.height(34.dp), horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.cands.isEmpty()) Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.copy(alpha = .2f)))
                    c.cands.forEach { s -> SwapDot(s, s.id in c.picked) { onToggle(c.date.orEmpty(), s.id) } }
                }
                Text(if (c.today) "Today" else md(c.date.orEmpty()), Modifier.padding(top = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = .7f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SwapDot(s: SwapChoice, lit: Boolean, onClick: () -> Unit) {
    val label = "${s.swap.cheaperName} instead of ${s.swap.usualName}, saves ${cash(s.saving)}"
    Box(Modifier.size(30.dp).zIndex(if (lit) 3f else 2f).alpha(if (s.fits) 1f else .25f)
        .then(if (lit) Modifier.border(2.dp, Ink, CircleShape) else Modifier).clip(CircleShape)
        .background(if (lit) LitBrush else SolidColor(Ink)).pressable(enabled = s.fits, role = Role.Switch, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label; selected = lit }, contentAlignment = Alignment.Center) {
        Icon(swapIcon(s.swap.cheaperName, s.swap.usualName, s.swap.category), null, Modifier.size(16.dp), tint = if (lit) Ink else Color.White)
    }
}

@Composable
private fun InputBar(value: String, onValue: (String) -> Unit, onSend: () -> Unit, onCamera: () -> Unit, onVoice: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f), CircleShape),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = CircleShape, shadowElevation = 6.dp) {
            Row(Modifier.height(68.dp).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCamera) { Icon(AppIcons.Camera, "Add a photo", Modifier.size(28.dp)) }
                BasicTextField(value, onValue, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("assistant_input").semantics { contentDescription = "Ask the budget assistant" }, singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text("Ask anything...", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
                            field()
                        }
                    })
                IconButton(onClick = onVoice) { Icon(AppIcons.Waveform, "Voice input", Modifier.size(26.dp)) }
                Box(Modifier.size(52.dp).pressable(enabled = value.isNotBlank(), onClickLabel = "Send", onClick = onSend).clip(CircleShape)
                    .background(if (value.isNotBlank()) Yellow else Yellow.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowUp, "Send", Modifier.size(26.dp), tint = Ink)
                }
            }
        }
    }
}
