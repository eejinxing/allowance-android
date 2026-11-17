package com.allowance.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.allowance.app.AllowanceState
import com.allowance.app.AllowanceViewModel
import com.allowance.app.userMessage
import com.allowance.core.ledger.*
import com.allowance.core.model.Money
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun EntryEditor(route: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val parts = route.split(":", limit = 3)
    // "prefill:<sen>:<category>:<date>:<note>" is a receipt read from a photo, opened here to be checked before it is saved.
    val prefill = if (parts[0] == "prefill") route.split(":", limit = 5) else null
    val original = if (parts[0] == "entry") state.data.entries.find { it.id == parts.getOrNull(1) } else null
    var amount by rememberSaveable(route) { mutableStateOf(original?.let { Money(it.amountMinor).decimal() }
        ?: if (parts[0] == "purchase" || prefill != null) Money(parts[1].toLong()).decimal() else "") }
    var note by rememberSaveable(route) { mutableStateOf(original?.note ?: if (parts[0] == "purchase") parts.getOrElse(2) { "" } else prefill?.getOrNull(4) ?: "") }
    var category by rememberSaveable(route) { mutableStateOf(original?.category ?: if (parts[0] == "quick") parts[1] else if (parts[0] == "purchase") "SHOPPING"
        else prefill?.getOrNull(2) ?: "FOOD") }
    var type by rememberSaveable(route) { mutableStateOf(original?.type?.name ?: "EXPENSE") }
    var date by rememberSaveable(route) { mutableStateOf(original?.date ?: if (parts[0] == "entrydate") parts[1] else prefill?.getOrNull(3) ?: state.today.toString()) }
    var account by rememberSaveable(route) { mutableStateOf(original?.accountId) }
    var family by rememberSaveable(route) { mutableStateOf(original?.family ?: false) }
    var confirm by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val editable = original == null || original.type in setOf(EntryType.EXPENSE, EntryType.INCOME, EntryType.SCHEDULED_INCOME, EntryType.BILL_PAYMENT)
    if (!editable) {
        EditorSheet("Transaction details", state.busy, close, close, saveLabel = "Done") {
            Text(TypeNames[original.type].orEmpty(), fontWeight = FontWeight.Bold)
            Text(cash(original.amountMinor), style = MaterialTheme.typography.headlineMedium)
            Text(original.date); Text(original.note)
            state.data.accounts.find { it.id == original.accountId }?.let { Text("Account: ${it.name}") }
            state.data.accounts.find { it.id == original.toAccountId }?.let { Text("To: ${it.name}") }
            if (original.type == EntryType.FUND_PURCHASE) Text("Paid from fund: ${cash(original.reservedMinor)}")
            Text("This entry is linked to an account, fund or goal. Deleting it restores those balances too.")
            deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirm = true }, enabled = !state.busy) { Text("Delete entry", color = MaterialTheme.colorScheme.error) }
        }
    } else EditorSheet(if (original == null) "New entry" else "Edit entry", state.busy, close, onSave = {
        val entry = LedgerEntry(original?.id ?: LedgerEngine.id(), LocalDate.parse(date).toString(), positive(amount), EntryType.valueOf(type),
            category, note.trim(), accountId = if (family && type == "EXPENSE") null else account,
            family = family && type == "EXPENSE", createdAt = original?.createdAt ?: System.currentTimeMillis())
        model.submit(onSuccess = close) { LedgerEngine.saveEntry(it, entry, state.today) }
    }) {
        Chips(linkedMapOf("EXPENSE" to "Expense", "INCOME" to "Extra income", "SCHEDULED_INCOME" to "Scheduled income", "BILL_PAYMENT" to "Bill payment"), type) { type = it }
        FormText("Amount (RM)", amount, { amount = it }, decimal = true)
        if (type == "EXPENSE") {
            Chips(Categories, category) { category = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paid by family", Modifier.weight(1f)); Switch(family, { family = it })
            }
        }
        if (type == "SCHEDULED_INCOME") Text("Already counted in your budgeted income. This only updates the receiving account and doesn’t raise your daily budget.", style = MaterialTheme.typography.bodySmall)
        if (type == "BILL_PAYMENT") Text("Only for bills already set aside in your budget, so they aren’t taken from your daily budget twice.", style = MaterialTheme.typography.bodySmall)
        FormText("Note", note, { note = it })
        DateField("Date", date, { date = it })
        if (!(family && type == "EXPENSE")) AccountPicker(state.data, account, { account = it }, label = if (type.contains("INCOME")) "Receiving account" else "Paying account")
        deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (original != null) TextButton(onClick = { confirm = true }, enabled = !state.busy) { Text("Delete entry", color = MaterialTheme.colorScheme.error) }
    }
    if (confirm && original != null) ConfirmDialog("Delete this entry?", "Your budget and account balances will be recalculated. If later fund activity depends on this entry, handle that first.",
        { confirm = false }) {
        deleteError = runCatching { model.submit("Entry deleted", close) { LedgerEngine.deleteEntry(it, original.id) } }
            .exceptionOrNull()?.userMessage("Couldn’t delete this entry")
        confirm = false
    }
}

@Composable
fun AccountEditor(id: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val old = state.data.accounts.find { it.id == id }
    var name by rememberSaveable(id) { mutableStateOf(old?.name ?: "") }
    var balance by rememberSaveable(id) { mutableStateOf(old?.let { Money(LedgerEngine.accountBalance(state.data, it)).decimal() } ?: "") }
    var type by rememberSaveable(id) { mutableStateOf(old?.type ?: "BANK") }
    var color by rememberSaveable(id) { mutableIntStateOf(old?.color ?: 0) }
    var confirm by remember { mutableStateOf(false) }
    EditorSheet(if (old == null) "Add account" else "Edit account", state.busy, close, onSave = {
        require(name.isNotBlank()) { "Enter an account name" }
        val amount = Money.parse(balance.ifBlank { "0" }).minor
        val account = WalletAccount(old?.id ?: LedgerEngine.id(), name.trim(), type, old?.openingMinor ?: amount, color, old?.archived ?: false)
        model.submit(onSuccess = close) { data ->
            val next = data.copy(accounts = data.accounts.filterNot { it.id == account.id } + account)
            if (old == null) next else LedgerEngine.setBalance(next, old.id, amount, state.today)
        }
    }) {
        FormText("Account name", name, { name = it })
        Chips(AccountTypes, type) { type = it }
        FormText("Current balance (RM)", balance, { balance = it }, decimal = true, signed = true, supporting = "For credit card debt, enter a negative number, e.g. -250.00")
        ColorPicker(color) { color = it }
        if (old != null) TextButton(onClick = { confirm = true }) { Text("Remove account", color = MaterialTheme.colorScheme.error) }
    }
    if (confirm && old != null) ConfirmDialog("Remove ${old.name}?", "The account will be hidden. Past transactions and the account name are kept.",
        { confirm = false }) {
        model.change("Account removed", close) { data -> data.copy(accounts = data.accounts.map { if (it.id == old.id) it.copy(archived = true) else it }) }; confirm = false
    }
}

@Composable
fun BalanceEditor(id: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val account = state.data.accounts.first { it.id == id }
    var amount by rememberSaveable(id) { mutableStateOf(Money(LedgerEngine.accountBalance(state.data, account)).decimal()) }
    EditorSheet("Adjust ${account.name} balance", state.busy, close, onSave = {
        val value = Money.parse(amount).minor
        model.submit(onSuccess = close) { LedgerEngine.setBalance(it, id, value, state.today) }
    }) {
        Text("Enter the balance your bank or wallet actually shows. The difference is recorded as an adjustment and doesn’t affect your daily budget.")
        FormText("Current balance (RM)", amount, { amount = it }, decimal = true, signed = true)
    }
}

@Composable
fun TransferEditor(state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val accounts = state.data.accounts.filterNot { it.archived }
    var from by rememberSaveable { mutableStateOf(accounts.getOrNull(0)?.id) }
    var to by rememberSaveable { mutableStateOf(accounts.getOrNull(1)?.id) }
    var amount by rememberSaveable { mutableStateOf("") }
    EditorSheet("Transfer between accounts", state.busy, close, onSave = {
        val value = positive(amount)
        require(from != null && to != null && from != to) { "Choose two different accounts" }
        model.submit(onSuccess = close) { LedgerEngine.transfer(it, from!!, to!!, value, state.today) }
    }) {
        Text("Top-ups, withdrawals or moving money between accounts. Not counted as spending and doesn’t change your daily budget.")
        FormText("Transfer amount (RM)", amount, { amount = it }, decimal = true)
        AccountPicker(state.data, from, { from = it }, false, "From")
        AccountPicker(state.data, to, { to = it }, false, "To")
    }
}

@Composable
fun TargetEditor(kind: String, id: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val fund = state.data.funds.find { it.id == id }
    val goal = state.data.goals.find { it.id == id }
    val wish = state.data.wishes.find { it.id == id }
    var name by rememberSaveable(kind, id) { mutableStateOf(fund?.name ?: goal?.name ?: wish?.name ?: "") }
    var amount by rememberSaveable(kind, id) { mutableStateOf((fund?.priceMinor ?: goal?.targetMinor ?: wish?.priceMinor)?.let { Money(it).decimal() } ?: "") }
    var deadline by rememberSaveable(kind, id) { mutableStateOf(fund?.deadline ?: goal?.deadline ?: "") }
    var color by rememberSaveable(kind, id) { mutableIntStateOf(fund?.color ?: goal?.color ?: 1) }
    var confirm by remember { mutableStateOf(false) }
    val title = when (kind) { "fund" -> "shopping fund"; "goal" -> "savings goal"; else -> "wish" }
    EditorSheet((if (id.isEmpty()) "New " else "Edit ") + title, state.busy, close, onSave = {
        require(name.isNotBlank()) { "Enter a name" }
        val value = positive(amount)
        if (deadline.isNotBlank()) LocalDate.parse(deadline)
        val recordId = id.ifBlank { LedgerEngine.id() }
        model.submit(onSuccess = close) { data -> when (kind) {
            "fund" -> data.copy(funds = data.funds.filterNot { it.id == recordId } + ShoppingFund(recordId, name.trim(), value, deadline, color, fund?.boughtOn, fund?.archived ?: false))
            "goal" -> data.copy(goals = data.goals.filterNot { it.id == recordId } + Goal(recordId, name.trim(), value, deadline, color, goal?.archived ?: false))
            else -> data.copy(wishes = data.wishes.filterNot { it.id == recordId } + Wish(recordId, name.trim(), value, wish?.date ?: state.today.toString()))
        } }
    }) {
        FormText("Name", name, { name = it })
        FormText(if (kind == "goal") "Target amount (RM)" else "Price (RM)", amount, { amount = it }, decimal = true)
        if (kind != "wish") { DateField("Target date", deadline, { deadline = it }, true); ColorPicker(color) { color = it } }
        if (fund != null && LedgerEngine.fundSaved(state.data, fund.id) > 0) Text("To release part of the balance, open the fund, choose Add / release funds, then Release. Removing the fund releases all of it.", style = MaterialTheme.typography.bodySmall)
        if (id.isNotBlank()) TextButton(onClick = { confirm = true }) { Text("Remove $title", color = MaterialTheme.colorScheme.error) }
    }
    if (confirm) ConfirmDialog("Remove this $title?", if (kind == "fund") "What’s left in the fund goes back to your spendable budget. Past entries are kept." else "Past entries are kept.",
        { confirm = false }) {
        model.change("Removed", close) { data -> when (kind) {
            "fund" -> LedgerEngine.archiveFund(data, id, state.today)
            "goal" -> data.copy(goals = data.goals.map { if (it.id == id) it.copy(archived = true) else it })
            else -> data.copy(wishes = data.wishes.filterNot { it.id == id })
        } }; confirm = false
    }
}

@Composable
fun TargetMoveEditor(kind: String, id: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val fund = state.data.funds.find { it.id == id }
    val goal = state.data.goals.find { it.id == id }
    var amount by rememberSaveable(kind, id) { mutableStateOf(if (kind == "buy") Money(fund!!.priceMinor).decimal() else "") }
    var account by rememberSaveable(kind, id) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(kind, id) { mutableStateOf("IN") }
    val saved = if (fund != null) LedgerEngine.fundSaved(state.data, id) else LedgerEngine.goalSaved(state.data, id)
    EditorSheet(if (kind == "buy") "Bought: ${fund?.name}" else fund?.name ?: goal?.name.orEmpty(), state.busy, close, onSave = {
        val value = positive(amount)
        model.submit(onSuccess = close) { data -> when (kind) {
            "buy" -> LedgerEngine.buyFund(data, id, value, account, state.today)
            "fundin" -> LedgerEngine.fundMove(data, id, value, mode == "OUT", state.today)
            else -> LedgerEngine.goalMove(data, id, value, mode == "OUT", state.today)
        } }
    }) {
        Text("Saved so far: ${cash(saved)}", fontWeight = FontWeight.Bold)
        if (kind != "buy") Chips(linkedMapOf("IN" to if (kind == "fundin") "Set aside" else "Deposit", "OUT" to if (kind == "fundin") "Release" else "Withdraw"), mode) { mode = it }
        FormText(if (kind == "buy") "Actual price paid (RM)" else "Amount (RM)", amount, { amount = it }, decimal = true)
        if (kind == "buy") {
            AccountPicker(state.data, account, { account = it })
            val price = runCatching { positive(amount) }.getOrDefault(0)
            Text("The fund pays ${cash(minOf(saved, price))}; the remaining ${cash((price - saved).coerceAtLeast(0))} comes out of your daily budget.")
            if (saved > price && price > 0) Text("The leftover ${cash(saved - price)} goes back to your spendable budget.")
        } else Text(if (kind == "fundin") "Setting aside lowers your spendable budget and releasing raises it. Account balances don’t change."
            else "Tracks money you’ve already saved without touching your daily budget. Record a transfer if money actually moves between accounts.")
    }
}

internal data class BillDraft(val id: String, val name: String = "", val amount: String = "", val frequency: String = "MONTHLY", val family: Boolean = false)
private val BillDraftSaver = listSaver<List<BillDraft>, String>(
    save = { bills -> bills.flatMap { listOf(it.id, it.name, it.amount, it.frequency, it.family.toString()) } },
    restore = { values -> values.chunked(5).map { BillDraft(it[0], it[1], it[2], it[3], it[4].toBoolean()) } },
)

@Composable
fun BudgetEditor(state: AllowanceState, model: AllowanceViewModel, close: () -> Unit, setup: Boolean = false) {
    val previous = state.data.settings
    var name by rememberSaveable { mutableStateOf(previous?.nickname ?: "") }
    var income by rememberSaveable { mutableStateOf(previous?.let { Money(it.incomeMinor).decimal() } ?: "") }
    var frequency by rememberSaveable { mutableStateOf(previous?.incomeFrequency ?: "MONTHLY") }
    var savings by rememberSaveable { mutableStateOf(previous?.let { Money(it.savingsMinor).decimal() } ?: "0") }
    var weights by rememberSaveable { mutableStateOf(previous?.weights ?: List(7) { 1 }) }
    var bills by rememberSaveable(stateSaver = BillDraftSaver) { mutableStateOf(state.data.bills.map { BillDraft(it.id, it.name, Money(it.amountMinor).decimal(), it.frequency, it.family) }) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = {
        try {
            val settings = BudgetSettings(name.trim(), positive(income), frequency, zeroOrPositive(savings), previous?.started ?: state.today.toString(), weights)
            val planned = bills.map { require(it.name.isNotBlank()) { "Name each bill, or remove the empty one" }; PlannedBill(it.id, it.name.trim(), positive(it.amount), it.frequency, it.family) }
            model.submit(if (setup) "Your budget is ready" else "Budget updated", close) { LedgerEngine.configure(it, settings, planned, state.today) }
        } catch (e: Exception) { error = e.userMessage("Check the amounts and formats") }
    }
    val nameFields: @Composable ColumnScope.() -> Unit = {
        FormText("What should we call you?", name, { name = it })
    }
    val incomeFields: @Composable ColumnScope.() -> Unit = {
        FormText("Income or allowance (RM)", income, { income = it }, decimal = true)
        Chips(Frequencies, frequency) { frequency = it }
        FormText("Monthly savings to set aside (RM)", savings, { savings = it }, decimal = true)
        Text("After setup, open Plan to see your weekly pattern and plan specific days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val billFields: @Composable ColumnScope.() -> Unit = {
        if (bills.isEmpty()) Text("Rent, phone bills and subscriptions all go here.", style = MaterialTheme.typography.bodySmall)
        bills.forEach { bill ->
            fun update(value: BillDraft) { bills = bills.map { if (it.id == bill.id) value else it } }
            HorizontalDivider()
            FormText("Bill name", bill.name, { update(bill.copy(name = it)) })
            FormText("Amount (RM)", bill.amount, { update(bill.copy(amount = it)) }, decimal = true)
            Chips(Frequencies, bill.frequency) { update(bill.copy(frequency = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paid by family (not from my budget)", Modifier.weight(1f)); Switch(bill.family, { update(bill.copy(family = it)) })
            }
            TextButton(onClick = { bills = bills.filterNot { it.id == bill.id } }) { Text("Remove this bill", color = MaterialTheme.colorScheme.error) }
        }
        if (setup) TextButton(onClick = { bills = bills + BillDraft(LedgerEngine.id()) }) { Text("+ Add bill") }
    }
    val notes: @Composable ColumnScope.() -> Unit = {
        Text("Your budget covers the whole month, so you can still log what you spent on earlier days. Weekly amounts are estimated per day ÷ 7. Budgeted income isn’t added to your bank balance automatically, so log it as Scheduled income when it arrives.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (setup) {
        var open by rememberSaveable { mutableStateOf("name") }
        fun toggle(key: String) { open = if (open == key) "" else key }
        val money = { value: String -> "RM " + value.ifBlank { "0" } }
        val steps = listOf("name", "income", "bills")
        val step = steps.indexOf(open)
        BackHandler(enabled = step > 0) { open = steps[step - 1] }
        var editingBill by rememberSaveable { mutableStateOf<String?>(null) }
        val bigCard = open == "income" || open == "bills"   // these two steps open as a big yellow card instead of an inline step
        Page {
            item {
                if (step > 0 && !bigCard) TextButton(onClick = { open = steps[step - 1] }) {
                    Icon(AppIcons.ChevronLeft, null, Modifier.size(20.dp)); Text("Back")
                } else Spacer(Modifier.height(48.dp))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Let’s get\nstarted", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    Text("Starting today, give your allowance a plan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SetupSection(AppIcons.Person, "Name", name.trim().ifEmpty { "What should we call you?" }, open == "name", { toggle("name") }, onNext = { open = "income" }, content = nameFields)
            }
            item {
                if (open == "income") IncomeHero(income, { income = it; error = null }, frequency, { frequency = it }, savings, { savings = it.ifBlank { "0" } },
                    onBack = { open = "name" }, onNext = { open = "bills" })
                else SetupSection(AppIcons.AccountBalanceWallet, "Income & savings",
                    if (income.isBlank()) "Allowance, period and savings" else "${money(income)} ${Frequencies[frequency].orEmpty().lowercase()} · save ${money(savings)}",
                    false, { toggle("income") }, onNext = { open = "bills" }, content = incomeFields)
            }
            item {
                if (open == "bills") BillsSheet(bills, onEdit = { editingBill = it },
                    onAdd = { val bill = BillDraft(LedgerEngine.id()); bills = bills + bill; editingBill = bill.id },
                    onBack = { open = "income" }, onFinish = save, busy = state.busy)
                else SetupSection(AppIcons.ReceiptLong, "Fixed bills",
                    if (bills.isEmpty()) "Rent, phone, subscriptions" else "${bills.size} ${if (bills.size == 1) "bill" else "bills"}",
                    false, { toggle("bills") }, content = billFields)
            }
            item { HowItWorks(error) }
            if (open != "bills") item { Button(onClick = save, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state.busy) "Setting up…" else "Continue") } }
        }
        bills.firstOrNull { it.id == editingBill }?.let { bill ->
            // A bill that was just added and left empty is dropped when the sheet closes.
            val done = { if (bill.name.isBlank() && bill.amount.isBlank()) bills = bills.filterNot { it.id == bill.id }; editingBill = null }
            BillEditorSheet(bill, state.busy, { value -> bills = bills.map { if (it.id == bill.id) value else it } },
                { bills = bills.filterNot { it.id == bill.id }; editingBill = null }, done)
        }
    } else EditorSheet("Budget settings", state.busy, close, save) {
        Text("Changes apply to this month’s budget. Past months stay as they were.")
        nameFields(); incomeFields()
        SectionTitle("Fixed bills", "+ Add") { bills = bills + BillDraft(LedgerEngine.id()) }
        billFields(); notes()
    }
}

/** One collapsible card of the first-run setup: a summary row that expands to show its fields. */
@Composable
private fun SetupSection(icon: ImageVector, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    onNext: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(Modifier.fillMaxWidth().animateContentSize(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().pressable(onClickLabel = if (expanded) "Collapse $title" else "Expand $title", onClick = onToggle).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Yellow, shape = RoundedCornerShape(14.dp)) { Icon(icon, null, Modifier.padding(11.dp).size(22.dp), tint = Ink) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!expanded) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(AppIcons.ChevronDown, null, Modifier.rotate(turn))
            }
            if (expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
                if (onNext != null) OutlinedButton(onClick = onNext, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Next") }
            }
        }
    }
}
