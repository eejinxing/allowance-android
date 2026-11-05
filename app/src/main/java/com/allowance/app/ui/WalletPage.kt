package com.allowance.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal val WalletBackground = Color.White
internal val WalletHeaderBase = Color(0xFFFFF3B0)
internal val WalletAccent = Ink
private val WalletGood = Color(0xFF1E9E6A)
private val WalletShort = Color(0xFFC2415A)
private val HiddenBalance = "RM ••••••"

private fun spending(data: AppData, from: LocalDate, to: LocalDate): Long = data.entries.filter {
    !it.family && it.type in setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE) && it.date >= from.toString() && it.date <= to.toString()
}.sumOf { it.amountMinor }

private fun goalIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        listOf("travel", "trip", "flight", "holiday", "vacation", "iceland", "japan").any { it in n } -> AppIcons.Plane
        listOf("bike", "bicycle", "cycle").any { it in n } -> AppIcons.Bicycle
        listOf("car", "vehicle", "bmw").any { it in n } -> AppIcons.Car
        listOf("laptop", "computer", "pc", "macbook", "phone").any { it in n } -> AppIcons.Laptop
        else -> AppIcons.Target
    }
}

private fun accountIcon(type: String): ImageVector = when (type) {
    "EWALLET" -> AppIcons.SwapHoriz; "CASH" -> AppIcons.Coin; "CREDIT" -> AppIcons.ReceiptLong; else -> AppIcons.AccountBalanceWallet
}

/**
 * Wallet: a greeting, one yellow card with the total of every account and a tile per account (bank, e-wallet, cash…),
 * a soft yellow card with the money that is free to spend, then goals and shopping funds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletPage(state: AllowanceState, open: (String) -> Unit, onSettings: () -> Unit = {}) {
    val data = state.data
    val accounts = data.accounts.filterNot { it.archived }
    val funds = data.funds.filter { !it.archived && it.boughtOn == null }
    val goals = data.goals.filterNot { it.archived }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var selectedFundId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedFund = funds.firstOrNull { it.id == selectedFundId }
    val total = accounts.sumOf { LedgerEngine.accountBalance(data, it) }
    val today = spending(data, state.today, state.today)
    val week = spending(data, state.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), state.today)
    // Free to spend needs a budget; before setup (or in a design preview) there is none, so show zero rather than fail.
    val freeToSpend = if (data.settings == null) 0L else runCatching { LedgerEngine.result(data, YearMonth.from(state.today), state.today).remaining.minor }.getOrDefault(0L)
    val nickname = data.settings?.nickname?.trim().orEmpty()
    val money: (Long) -> String = { if (hidden) HiddenBalance else cash(it) }
    val outflow: (Long) -> String = { if (hidden) HiddenBalance else "−" + cash(it) }

    LazyColumn(Modifier.fillMaxSize().testTag("wallet_page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(48.dp).semantics { contentDescription = "Budget settings" }.pressable(onClick = { open("budget:") }).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFE36B), Yellow))), contentAlignment = Alignment.Center) {
                    if (nickname.isNotEmpty()) Text(nickname.take(1).uppercase(), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink) else Icon(AppIcons.Person, null, Modifier.size(24.dp), tint = Ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundButton(if (hidden) AppIcons.EyeOff else AppIcons.Eye, if (hidden) "Show balances" else "Hide balances") { hidden = !hidden }
                    RoundButton(AppIcons.Settings, "Settings", onSettings)
                }
            }
        }
        item(key = "hello") {
            Text("Hello,\n${nickname.ifEmpty { "there" }} 👋", Modifier.padding(top = 2.dp, bottom = 4.dp), fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        }
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().staggerIn(1).clip(RoundedCornerShape(30.dp)).drawBehind {
                drawRect(Brush.verticalGradient(0f to Color(0xFFFFE97A), .55f to Yellow, 1f to Color(0xFFF7C924)))
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * .8f))
                drawRect(Brush.radialGradient(listOf(Color(0xFFFFC400).copy(alpha = .35f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * .6f))
            }.padding(start = 14.dp, end = 14.dp, top = 26.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Total balance · ${if (accounts.isEmpty()) "No accounts yet" else "${accounts.size} ${if (accounts.size == 1) "account" else "accounts"}"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.copy(alpha = .62f))
                BasicText(if (hidden) HiddenBalance else cash(rememberCountUp(total)), Modifier.fillMaxWidth().padding(top = 6.dp),
                    style = TextStyle(fontFeatureSettings = "tnum", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, letterSpacing = (-1.6).sp, textAlign = TextAlign.Center),
                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 44.sp, stepSize = 1.sp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(buildStat("Today", outflow(today)), fontSize = 13.sp, color = Ink.copy(alpha = .72f))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(Ink.copy(alpha = .36f)))
                    Text(buildStat("This week", outflow(week)), fontSize = 13.sp, color = Ink.copy(alpha = .72f))
                }
                Column(Modifier.fillMaxWidth().padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tiles: List<WalletAccount?> = accounts + listOf(null)
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { account ->
                                if (account == null) AccountTile(AppIcons.Add, "", "Add account", null, false, Modifier.weight(1f)) { open("account:") }
                                else {
                                    val balance = LedgerEngine.accountBalance(data, account)
                                    AccountTile(accountIcon(account.type), AccountTypes[account.type].orEmpty(), account.name, money(balance), balance < 0, Modifier.weight(1f)) { open("account:${account.id}") }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item(key = "free") {
            Column(Modifier.fillMaxWidth().staggerIn(2).clip(RoundedCornerShape(26.dp)).drawBehind {
                drawRect(Brush.linearGradient(listOf(Color(0xFFFFF6C9), Color(0xFFFFFBEA), Color(0xFFFFE88A))))
                drawRect(Brush.radialGradient(listOf(Color(0xFFFFD83D), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * .55f))
                drawRect(Brush.radialGradient(listOf(Color(0xFFFFE16A), Color.Transparent), center = Offset(0f, size.height), radius = size.width * .55f))
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .95f), Color.Transparent), center = Offset(size.width / 2, size.height * .55f), radius = size.width * .45f))
            }.padding(horizontal = 20.dp, vertical = 40.dp).semantics(mergeDescendants = true) { contentDescription = "Free to spend" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(if (hidden) HiddenBalance else cash(rememberCountUp(freeToSpend.coerceAtLeast(0L))), Modifier.fillMaxWidth(),
                    style = TextStyle(fontFeatureSettings = "tnum", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 54.sp, letterSpacing = (-1.6).sp, textAlign = TextAlign.Center),
                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 54.sp, stepSize = 1.sp))
                Text("Free to spend", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.copy(alpha = .5f))
            }
        }
        item(key = "goals-title") {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("Goals", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
                Row(Modifier.pressable(onClickLabel = "New goal") { open("goal:") }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(AppIcons.Add, null, Modifier.size(16.dp)); Text("New", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (goals.isEmpty()) item(key = "goals-none") { Text("No goals yet. Tap New to start saving for something.", Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else goals.take(3).forEach { goal ->
            item(key = "goal-${goal.id}") {
                Surface(Modifier.fillMaxWidth().pressable(onClickLabel = "Deposit or withdraw") { open("goalin:${goal.id}") }, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) { Icon(goalIcon(goal.name), null, Modifier.size(20.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(goal.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Saved of ${cash(goal.targetMinor).removeSuffix(".00")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (hidden) HiddenBalance else cash(rememberCountUp(LedgerEngine.goalSaved(data, goal.id))), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
        item(key = "fund_list") {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).staggerIn(4).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shopping funds", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Save a little for the things you want.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(onClick = { open("fund:") }) {
                        Icon(AppIcons.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
                    }
                }
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    if (funds.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Icon(AppIcons.ShoppingBag, null, Modifier.size(34.dp))
                            Text("What’s the next thing you want?", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                            Text("Create a shopping fund and save a little each time until it’s within reach.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            TextButton(onClick = { open("fund:") }, contentPadding = PaddingValues(0.dp)) { Text("Create a shopping fund ↗") }
                        }
                    }
                    funds.forEachIndexed { index, fund ->
                        FundListRow(fund, LedgerEngine.fundSaved(data, fund.id)) { selectedFundId = fund.id }
                        if (index < funds.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Text("Setting money aside lowers your spendable budget. Your account is only charged when you buy.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }
        val bought = data.funds.filter { !it.archived && it.boughtOn != null }
        if (bought.isNotEmpty()) item(key = "bought_funds") {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Already bought", fontWeight = FontWeight.Bold)
                bought.forEach { Text("✓  ${it.name} · ${it.boughtOn}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            }
        }
    }
    selectedFund?.let { fund ->
        val saved = LedgerEngine.fundSaved(data, fund.id)
        val remaining = (fund.priceMinor - saved).coerceAtLeast(0)
        ModalBottomSheet(onDismissRequest = { selectedFundId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(fund.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Saved ${cash(saved)}  /  Target ${cash(fund.priceMinor)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (remaining > 0) "Short by ${cash(remaining)}" else if (saved > fund.priceMinor) "✓ Fully funded · OVER ${cash(saved - fund.priceMinor)}" else "✓ Fully funded",
                    color = if (remaining > 0) WalletShort else WalletGood, fontWeight = FontWeight.Bold)
                if (fund.deadline.isNotBlank()) {
                    val days = (ChronoUnit.DAYS.between(state.today, LocalDate.parse(fund.deadline)) + 1).coerceAtLeast(1)
                    Text("Target date ${fund.deadline} · about ${cash((remaining + days - 1) / days)} per day", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                fun navigate(route: String) { selectedFundId = null; open(route) }
                Button(onClick = { navigate("fundin:${fund.id}") }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add / release funds") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { navigate("buy:${fund.id}") }) { Text("Mark as purchased") }
                    TextButton(onClick = { navigate("fund:${fund.id}") }) { Text("Edit fund") }
                }
            }
        }
    }
}

private fun buildStat(label: String, value: String) = androidx.compose.ui.text.buildAnnotatedString {
    append("$label ")
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(value); pop()
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = description }.pressable(onClick = onClick).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLowest).then(Modifier), contentAlignment = Alignment.Center) {
        Surface(Modifier.matchParentSize(), shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
        Icon(icon, null, Modifier.size(20.dp))
    }
}

/** One white tile per account: round icon top-right, type, name and balance bottom-left. A null [balance] makes it the "add" tile. */
@Composable
private fun AccountTile(icon: ImageVector, type: String, name: String, balance: String?, negative: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f / .78f).pressable(onClickLabel = if (balance == null) "Add account" else "Edit $name", onClick = onClick).clip(RoundedCornerShape(22.dp)).background(Color.White)) {
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(36.dp).clip(CircleShape).background(Color(0xFFF4F1E6)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = Ink) }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (type.isNotEmpty()) Text(type, fontSize = 12.sp, color = Ink.copy(alpha = .55f))
            Text(name, fontSize = 14.sp, fontWeight = if (balance == null) FontWeight.Medium else FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (balance != null) Text(balance, Modifier.padding(top = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp,
                color = if (negative) Color(0xFFC2415A) else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun FundListRow(fund: ShoppingFund, saved: Long, onClick: () -> Unit) {
    val ready = saved >= fund.priceMinor
    val over = saved > fund.priceMinor
    val remaining = (fund.priceMinor - saved).coerceAtLeast(0)
    val accent = Palette.map { if (it == Ink || it == Taupe) Color(0xFF6B6558) else it }[fund.color.mod(Palette.size)]
    Row(Modifier.fillMaxWidth().testTag("wallet_fund_${fund.id}").pressable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
            Text(fund.name.take(1).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
            Text(fund.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ready) "Target ${cash(fund.priceMinor)}" else "Saved ${cash(saved)} / ${cash(fund.priceMinor)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) Icon(AppIcons.Check, "Fully funded", Modifier.size(14.dp), tint = WalletGood)
                Text(cash(if (ready) saved else remaining), color = if (ready) WalletGood else WalletShort,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (ready) 3.dp else 0.dp))
            }
            Text(if (over) "OVER +${cash(saved - fund.priceMinor)}" else if (ready) "Fully funded" else "Short by",
                color = if (ready) WalletGood else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}
