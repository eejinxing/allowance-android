package com.allowance.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun Page(topPadding: Dp = 8.dp, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}


@Composable
fun LedgerPage(state: AllowanceState, open: (String) -> Unit, onBack: () -> Unit = {}) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(state.today).toString()) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("ALL") }
    val month = YearMonth.parse(monthText)
    val entries = state.data.entries.filter {
        it.date.startsWith(monthText) && (category == "ALL" || (it.category == category && it.type in setOf(EntryType.EXPENSE, EntryType.BILL_PAYMENT, EntryType.FUND_PURCHASE))) &&
            (query.isBlank() || it.note.contains(query, true) || TypeNames[it.type].orEmpty().contains(query, true) ||
                state.data.accounts.find { a -> a.id == it.accountId }?.name?.contains(query, true) == true)
    }.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.createdAt })
    Page(topPadding = 0.dp) {
        item(key = "header") {
            PageHeader("History", subtitle = "Every ringgit, accounted for.", onBack = onBack) {
                MonthPicker(month, YearMonth.from(LocalDate.parse(state.data.settings!!.started)), YearMonth.from(state.today)) { monthText = it.toString() }
            }
        }
        item(key = "search") { FormText("Search notes, types or accounts", query, { query = it }) }
        item(key = "chips") { Chips(linkedMapOf("ALL" to "All") + Categories, category) { category = it } }
        if (entries.isEmpty()) item(key = "empty") { EmptyCard("Nothing matches", "Try another month, category or search.", if (query.isNotBlank() || category != "ALL") "Clear filters" else null) { query = ""; category = "ALL" } }
        entries.groupBy { it.date }.forEach { (date, rows) ->
            item(key = "day-$date") { Text(LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH)), Modifier.animateItem(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.id }) { e -> EntryCard(e, state.data, Modifier.animateItem()) { open("entry:${e.id}") } }
        }
    }
}

