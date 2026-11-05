package com.allowance.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allowance.app.userMessage
import com.allowance.core.ledger.*
import com.allowance.core.model.Money
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

val Orange = Color(0xFFFF8A3D)
val Mint = Color(0xFF34C48B)
val Sun = Color(0xFFFFC53D)
val Coral = Color(0xFFFF6150)
val Ink = Color(0xFF141414)
val Yellow = Color(0xFFFFD83D)
val Taupe = Color(0xFFA89F91)
val Palette = listOf(Yellow, Ink, Taupe, Orange, Mint, Color(0xFF5BB8F0), Color(0xFFFF7FA8))
private val CategoryPalette = listOf(Color(0xFFFFD83D), Color(0xFF8ED1F5), Color(0xFFFFA3C4), Color(0xFFFFA45C), Color(0xFF7ED9B0), Color(0xFFB9A6F2), Color(0xFFD8D2C4))
val Categories = linkedMapOf("FOOD" to "Food & drinks", "TRANSPORT" to "Transport", "SHOPPING" to "Shopping", "FUN" to "Entertainment", "LIVING" to "Living", "HEALTH" to "Health", "OTHER" to "Other")
val Frequencies = linkedMapOf("MONTHLY" to "Monthly", "WEEKLY" to "Weekly", "DAILY" to "Daily")
val AccountTypes = linkedMapOf("BANK" to "Bank", "EWALLET" to "E-wallet", "CASH" to "Cash", "CREDIT" to "Credit card")
val TypeNames = mapOf(EntryType.EXPENSE to "Expense", EntryType.INCOME to "Extra income", EntryType.SCHEDULED_INCOME to "Scheduled income",    EntryType.TRANSFER to "Transfer", EntryType.ADJUSTMENT to "Balance adjustment", EntryType.BILL_PAYMENT to "Bill payment",    EntryType.FUND_ALLOCATION to "Fund top-up", EntryType.FUND_PURCHASE to "Fund purchase", EntryType.FUND_RELEASE to "Fund release",    EntryType.GOAL_DEPOSIT to "Saved to goal", EntryType.GOAL_WITHDRAWAL to "Taken from goal")

fun cash(minor: Long): String = "RM " + NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(java.math.BigDecimal.valueOf(minor, 2))
fun positive(text: String): Long = Money.parse(text).minor.also {
    require(it > 0) { "Amount must be greater than zero" }
    require(it <= 100_000_000_000L) { "Amount is out of range" }
}
fun zeroOrPositive(text: String): Long = Money.parse(text.ifBlank { "0" }).minor.also { require(it >= 0) { "Amount can’t be negative" } }
fun categoryIcon(category: String): ImageVector = when (category) {
    "FOOD" -> AppIcons.Restaurant; "TRANSPORT" -> AppIcons.DirectionsBus
    "SHOPPING" -> AppIcons.ShoppingBag; "FUN" -> AppIcons.Celebration
    "LIVING" -> AppIcons.Home; "HEALTH" -> AppIcons.Favorite
    else -> AppIcons.MoreHoriz
}
fun categoryColor(category: String): Color = CategoryPalette[Categories.keys.indexOf(category).coerceAtLeast(0) % CategoryPalette.size]

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun EmptyCard(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Panel {
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Chips(options: Map<String, String>, value: String, onValue: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, name) -> FilterChip(selected = key == value, onClick = { onValue(key) }, label = { Text(name) }) }
    }
}

@Composable
fun FormText(label: String, value: String, onValue: (String) -> Unit, decimal: Boolean = false, secret: Boolean = false, supporting: String? = null, signed: Boolean = false) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.NumberPassword else if (decimal && !signed) KeyboardType.Decimal else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        supportingText = if (supporting == null) null else ({ Text(supporting) }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onValue: (String) -> Unit, optional: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        placeholder = { Text("YYYY-MM-DD") }, shape = RoundedCornerShape(16.dp),
        trailingIcon = { IconButton(onClick = { open = true }) { Icon(AppIcons.CalendarMonth, "Pick a date") } },
        supportingText = if (optional) ({ Text("Optional") }) else null)
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onValue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }) { Text("Select") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) { DatePicker(picker, showModeToggle = false) }
    }
}

@Composable
fun AccountPicker(data: AppData, selected: String?, onValue: (String?) -> Unit, allowNone: Boolean = true, label: String = "Paying account") {
    Text(label, style = MaterialTheme.typography.labelLarge)
    val options = linkedMapOf<String, String>()
    if (allowNone) options[""] = "Don’t track an account"
    data.accounts.filter { !it.archived || it.id == selected }.forEach { options[it.id] = it.name }
    if (options.isEmpty()) Text("Add an account in Wallet first", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Chips(options, selected.orEmpty()) { onValue(it.ifEmpty { null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(title: String, busy: Boolean, onDismiss: () -> Unit, onSave: () -> Unit,
    saveLabel: String = "Save", content: @Composable ColumnScope.() -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { try { error = null; onSave() } catch (e: Exception) { error = e.userMessage("Check what you entered") } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Saving…" else saveLabel) }
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun EntryRow(entry: LedgerEntry, data: AppData, onClick: () -> Unit) {
    val income = entry.type in setOf(EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.FUND_RELEASE, EntryType.GOAL_WITHDRAWAL)
    val color = if (entry.type == EntryType.EXPENSE) categoryColor(entry.category) else Yellow
    Row(Modifier.fillMaxWidth().pressable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = color, shape = RoundedCornerShape(14.dp)) {
            Icon(when {
                entry.type == EntryType.TRANSFER -> AppIcons.SwapHoriz
                entry.type in setOf(EntryType.GOAL_DEPOSIT, EntryType.GOAL_WITHDRAWAL) -> AppIcons.Savings
                entry.type in setOf(EntryType.FUND_ALLOCATION, EntryType.FUND_RELEASE) -> AppIcons.AccountBalanceWallet
                entry.type == EntryType.ADJUSTMENT -> AppIcons.Tune
                income -> AppIcons.SouthWest
                else -> categoryIcon(entry.category)
            },
                null, Modifier.padding(12.dp).size(20.dp), tint = Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.note.ifBlank { TypeNames[entry.type].orEmpty() }, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(listOfNotNull(TypeNames[entry.type], if (entry.family) "Paid by family" else null,
                data.accounts.find { it.id == entry.accountId }?.name,
                data.accounts.find { it.id == entry.toAccountId }?.name?.let { "→ $it" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text((if (income) "+" else "") + cash(entry.amountMinor), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold, color = if (income) Color(0xFF1E9E6A) else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun EntryCard(entry: LedgerEntry, data: AppData, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        EntryRow(entry, data, onClick)
    }
}

/** Yellow gradient page header with the title, a settings button and optional hero content. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null, tint: Color = Color(0xFFFFE55C), rounded: Boolean = false, bleed: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit = {}) {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < .5f
    val top = if (dark) lerp(tint, Color.Black, .68f) else tint
    val bottom = when { !rounded -> background; dark -> lerp(top, background, .5f); else -> lerp(tint, Color.White, .82f) }
    val shape = if (rounded) RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp) else RoundedCornerShape(0.dp)
    Column(modifier.fullBleed(bleed).clip(shape).heroBackground(dark, bottom, tint)
        .statusBarsPadding().padding(horizontal = 22.dp).padding(bottom = if (rounded) 26.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, Modifier.offset(x = (-12).dp)) { Icon(AppIcons.ChevronLeft, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onSettings != null) IconButton(onClick = onSettings, Modifier.offset(x = 12.dp)) { Icon(AppIcons.Tune, "Settings") }
        }
        content()
    }
}

@Composable
fun MonthPicker(month: YearMonth, earliest: YearMonth, latest: YearMonth, onValue: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onValue(month.minusMonths(1)) }, enabled = month > earliest) { Icon(AppIcons.ChevronLeft, "Previous month") }
        Text(month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onValue(month.plusMonths(1)) }, enabled = month < latest) { Icon(AppIcons.ChevronRight, "Next month") }
    }
}

@Composable
fun Progress(value: Float, color: Color = Ink) {
    val fill = rememberGrow(value.coerceIn(0f, 1f), 900, 120)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun ColorPicker(selected: Int, onValue: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Palette.forEachIndexed { index, color ->
            IconButton(onClick = { onValue(index) }, modifier = Modifier.size(44.dp)) {
                Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(32.dp)) {
                    if (selected == index) Icon(AppIcons.Check, "Selected colour ${index + 1}", Modifier.padding(6.dp), tint = if (index == 1) Color.White else Ink)
                }
            }
        }
    }
}
