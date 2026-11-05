package com.allowance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.core.ledger.LedgerEngine
import com.allowance.core.model.Money

// First-run setup: the income step and the bills step open as big yellow cards, like a wallet balance screen.

private val Glass = Color.White.copy(alpha = .38f)
private val PeriodName = mapOf("MONTHLY" to "Month", "WEEKLY" to "Week", "DAILY" to "Day")
/** Sen added by the ↑ / ↓ buttons for each period. */
private val Bump = mapOf("MONTHLY" to 5000L, "WEEKLY" to 1000L, "DAILY" to 500L)
private val NextPeriod = mapOf("MONTHLY" to "WEEKLY", "WEEKLY" to "DAILY", "DAILY" to "MONTHLY")
private val PerMonth = mapOf("MONTHLY" to 1.0, "WEEKLY" to 52.0 / 12, "DAILY" to 365.0 / 12)

/** Yellow card used for the two big setup steps. */
private fun Modifier.yellowCard(): Modifier = this.clip(RoundedCornerShape(36.dp)).drawBehind {
    drawRect(Brush.verticalGradient(0f to Color(0xFFFFC928), .55f to Color(0xFFFFE266), 1f to Color(0xFFFFFAE3)))
    drawRect(Brush.radialGradient(listOf(Color(0xFFFFB648).copy(alpha = .42f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * .9f))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent), center = Offset(size.width * .1f, size.height * .22f), radius = size.width * .6f))
}

@Composable
private fun GlassButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = description }.pressable(onClick = onClick).clip(CircleShape).background(Glass), contentAlignment = Alignment.Center) { content() }
}

/** The income step: a big amount you can type or nudge with ↑ / ↓, a round button that cycles the period, and the monthly savings. */
@Composable
internal fun IncomeHero(income: String, onIncome: (String) -> Unit, frequency: String, onFrequency: (String) -> Unit, savings: String, onSavings: (String) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val savingsShown = if (savings == "0") "" else savings
    val bump = { dir: Int ->
        val current = runCatching { Money.parse(income.trim().ifBlank { "0" }).minor }.getOrDefault(0L)
        onIncome(Money((current + dir * (Bump[frequency] ?: 5000L)).coerceAtLeast(0L)).decimal())
    }
    val size = when { income.length <= 6 -> 72.sp; income.length <= 8 -> 56.sp; else -> 44.sp }
    Column(Modifier.fillMaxWidth().yellowCard().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassButton("Back", onBack) { Icon(AppIcons.ChevronLeft, null, Modifier.size(26.dp), tint = Ink) }
            Text("Your allowance", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.size(48.dp))
        }
        Text("Balance", Modifier.padding(top = 28.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.copy(alpha = .55f))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Text("RM", Modifier.padding(bottom = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.copy(alpha = .5f))
            BasicTextField(income, onIncome, Modifier.width(IntrinsicSize.Min).widthIn(min = 120.dp).semantics { contentDescription = "Income or allowance in RM" }, singleLine = true,
                textStyle = TextStyle(fontFeatureSettings = "tnum", fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, color = Ink),
                cursorBrush = SolidColor(Ink), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) { if (income.isEmpty()) Text("0.00", fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, color = Ink.copy(alpha = .3f)); field() } })
        }
        Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(Glass), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(70.dp).fillMaxHeight().semantics { contentDescription = "Increase" }.pressable { bump(1) }, contentAlignment = Alignment.Center) { Icon(AppIcons.ArrowUp, null, Modifier.size(24.dp), tint = Ink) }
                Box(Modifier.width(1.dp).height(26.dp).background(Ink.copy(alpha = .12f)))
                Box(Modifier.width(70.dp).fillMaxHeight().semantics { contentDescription = "Decrease" }.pressable { bump(-1) }, contentAlignment = Alignment.Center) { Icon(AppIcons.ArrowUp, null, Modifier.size(24.dp).rotate(180f), tint = Ink) }
            }
            Column(Modifier.size(66.dp).clip(CircleShape).background(Glass).semantics { contentDescription = "Income period: ${PeriodName[frequency]}. Tap to change" }.pressable { onFrequency(NextPeriod[frequency] ?: "MONTHLY") },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(AppIcons.CalendarMonth, null, Modifier.size(20.dp), tint = Ink)
                Text(PeriodName[frequency].orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
        Text("Monthly savings to set aside", Modifier.padding(top = 26.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.copy(alpha = .6f))
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(54.dp).clip(RoundedCornerShape(27.dp)).background(Glass).border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(27.dp)), contentAlignment = Alignment.Center) {
            BasicTextField(savingsShown, onSavings, Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "Monthly savings in RM" }, singleLine = true,
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center), cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { field -> Box(contentAlignment = Alignment.Center) { if (savingsShown.isEmpty()) Text("Optional", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = .35f)); field() } })
        }
        Button(onClick = onNext, Modifier.padding(top = 22.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 36.dp)) {
            Text("Proceed to next", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun billIcon(frequency: String, family: Boolean): ImageVector = if (family) AppIcons.People else when (frequency) { "WEEKLY" -> AppIcons.SwapHoriz; "DAILY" -> AppIcons.Clock; else -> AppIcons.CalendarMonth }

/** The bills step: a big monthly total, one row per bill, an "Add bill" row and an "up next" bar that finishes setup. */
@Composable
internal fun BillsSheet(bills: List<BillDraft>, onEdit: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, onFinish: () -> Unit, busy: Boolean) {
    val total = bills.filter { !it.family }.sumOf { b -> runCatching { Math.round(Money.parse(b.amount.trim()).minor * (PerMonth[b.frequency] ?: 1.0)) }.getOrDefault(0L) }
    Column(Modifier.fillMaxWidth().yellowCard()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
            GlassButton("Back", onBack) { Icon(AppIcons.ChevronLeft, null, Modifier.size(26.dp), tint = Ink) }
            BasicText(cash(total), Modifier.fillMaxWidth().padding(top = 36.dp), style = TextStyle(fontFeatureSettings = "tnum", fontSize = 48.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = Ink), maxLines = 1)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Ink)) { append("Fixed bills") }
                append(" · ${if (bills.isEmpty()) "none yet" else "${bills.size} added"} · per month")
            }, Modifier.padding(top = 6.dp), fontSize = 16.sp, color = Ink.copy(alpha = .62f))
            Column(Modifier.padding(top = 22.dp, bottom = 12.dp)) {
                bills.forEach { b -> BillRow(billIcon(b.frequency, b.family), b.name.trim().ifBlank { "Unnamed bill" }, "${cash(runCatching { Money.parse(b.amount.trim()).minor }.getOrDefault(0L))} · ${Frequencies[b.frequency].orEmpty()}${if (b.family) " · Family" else ""}", false) { onEdit(b.id) } }
                BillRow(AppIcons.Add, "Add bill", "Rent, phone, apps", true, onAdd)
            }
        }
        HorizontalDivider(color = Ink.copy(alpha = .08f))
        Row(Modifier.fillMaxWidth().background(Color(0xFFFFF7DA)).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(54.dp).clip(CircleShape).border(2.dp, Ink.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) { Icon(AppIcons.AccountBalanceWallet, null, Modifier.size(22.dp), tint = Ink) }
            Column(Modifier.weight(1f)) { Text("Your budget", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Up next", fontSize = 13.sp, color = Ink.copy(alpha = .6f)) }
            Button(onClick = onFinish, modifier = Modifier.height(48.dp), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 22.dp)) {
                Icon(AppIcons.ArrowRight, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (busy) "Setting up…" else "Next", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BillRow(icon: ImageVector, title: String, sub: String, plus: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClickLabel = if (plus) "Add bill" else "Edit $title", onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(if (plus) Ink else Color.White.copy(alpha = .38f)).then(if (plus) Modifier else Modifier.border(2.dp, Ink.copy(alpha = .15f), CircleShape)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(if (plus) 26.dp else 24.dp), tint = if (plus) Color.White else Ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 15.sp, color = Ink.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppIcons.MoreHoriz, null, Modifier.size(24.dp), tint = Ink)
        Icon(AppIcons.ChevronRight, null, Modifier.size(22.dp), tint = Ink.copy(alpha = .35f))
    }
}

/** Bottom sheet for adding or editing one bill in the bills step. */
@Composable
internal fun BillEditorSheet(bill: BillDraft, busy: Boolean, onChange: (BillDraft) -> Unit, onRemove: () -> Unit, onDone: () -> Unit) {
    EditorSheet("Fixed bill", busy, onDone, onDone, saveLabel = "Done") {
        FormText("Bill name", bill.name, { onChange(bill.copy(name = it)) })
        FormText("Amount (RM)", bill.amount, { onChange(bill.copy(amount = it)) }, decimal = true)
        Chips(Frequencies, bill.frequency) { onChange(bill.copy(frequency = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Paid by family (not from my budget)", Modifier.weight(1f)); Switch(bill.family, { onChange(bill.copy(family = it)) }) }
        TextButton(onClick = onRemove) { Text("Remove this bill", color = MaterialTheme.colorScheme.error) }
    }
}

/** The fine print under the step cards, as a short "how it works" list. */
@Composable
internal fun HowItWorks(error: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(AppIcons.AutoAwesome, null, Modifier.size(16.dp), tint = Color(0xFFC9A300)); Text("How it works", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        listOf("Starting mid-month?" to " Your budget covers the whole month, so you can still log what you spent on earlier days.",
            "Weekly amounts" to " are counted per day (÷ 7).",
            "Income isn’t added to your bank balance automatically." to " Log it as Scheduled income when it arrives.").forEach { (bold, rest) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Yellow))
                Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(bold) }; append(rest) }, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
