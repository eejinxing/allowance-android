package com.allowance.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.app.AllowanceViewModel
import com.allowance.core.ledger.*
import com.allowance.core.model.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SwapInk = Color(0xFF181818)
private val SwapGlyphs = listOf(
    Regex("bread|toast|roti|sandwich|\\bbun\\b") to AppIcons.Bread, Regex("train|mrt|lrt|rail|komuter") to AppIcons.Train,
    Regex("\\bbus\\b") to AppIcons.DirectionsBus, Regex("water|bottle") to AppIcons.Bottle, Regex("coffee|tea|kopi|latte|boba") to AppIcons.Cup,
    Regex("lunch|bento|packed|rice|meal") to AppIcons.Lunchbox, Regex("bike|cycle|walk") to AppIcons.Bicycle,
    Regex("\\bcar\\b|grab|taxi|ride|\\bcab\\b") to AppIcons.Car,
)

/** The icon for a swap, picked from what the cheaper option is called (bread, train, coffee…), falling back to the category's. */
fun swapIcon(cheaperName: String, usualName: String = "", category: String = "OTHER"): ImageVector {
    val cheaper = cheaperName.lowercase(); val both = "$cheaper ${usualName.lowercase()}"
    val hit = SwapGlyphs.firstOrNull { it.first.containsMatchIn(cheaper) } ?: SwapGlyphs.firstOrNull { it.first.containsMatchIn(both) }
    return hit?.second ?: if (category == "OTHER") AppIcons.Coin else categoryIcon(category)
}

private val CategoryEmoji = mapOf("TRANSPORT" to "🚌", "FOOD" to "🍽️", "SHOPPING" to "🛍️", "FUN" to "🎬", "OTHER" to "🔁")
private val Mono = FontFamily.Monospace

/** One dark tile: the cheaper option top-left, what it saves under it, a big flat icon at the bottom-left. */
@Composable
private fun SwapTile(title: String, lines: List<Pair<String, Color>>, icon: ImageVector, modifier: Modifier, label: String, onClick: () -> Unit) {
    Surface(modifier.aspectRatio(1f / .96f), color = SwapInk, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxSize().pressable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title.uppercase(Locale.ROOT), color = Yellow, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = .6.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.forEach { (text, color) -> Text(text, color = color, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Icon(icon, null, Modifier.size(56.dp), tint = Yellow)
        }
    }
}

/**
 * Swaps: the cheaper options you keep (the bus instead of the train), as a grid of dark tiles. The assistant draws on these when you
 * overspend, and what it chose each time is listed underneath.
 */
@Composable
fun SwapsPage(state: AllowanceState, open: (String) -> Unit, onSettings: () -> Unit) {
    val swaps = state.data.swaps
    val month = YearMonth.from(state.today).toString()
    val paybacks = state.data.budgets.firstOrNull { it.month == month }?.paybacks.orEmpty().asReversed().take(6)
    val soft = Color.White.copy(alpha = .6f)
    Page(topPadding = 0.dp) {
        item(key = "header") { PageHeader("Swaps", subtitle = "Cheaper ways to do what you already do.", onSettings = onSettings) }
        val tiles: List<SwapTileData?> = swaps.map { SwapTileData(it) } + null // null is the "Add a swap" tile
        items(tiles.chunked(2), key = { row -> row.first()?.swap?.id ?: "add" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    if (tile != null) SwapTile(tile.swap.cheaperName, listOf("instead of ${tile.swap.usualName}" to soft, "saves ${cash(LedgerEngine.swapSaving(tile.swap))}" to Yellow.copy(alpha = .9f)),
                        swapIcon(tile.swap.cheaperName, tile.swap.usualName, tile.swap.category), Modifier.weight(1f),
                        "Edit ${tile.swap.cheaperName} instead of ${tile.swap.usualName}") { open("swap:${tile.swap.id}") }
                    else SwapTile("Add a swap", listOf("your own cheaper option" to soft), AppIcons.Add, Modifier.weight(1f), "Add a swap") { open("swap:") }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (paybacks.isNotEmpty()) {
            item(key = "paid-title") { Text("PAID BACK", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 2.dp, top = 6.dp)) }
            items(paybacks, key = { it.id }) { p ->
                val used = p.days.flatMap { it.swaps }.distinctBy { it.name }.take(3)
                Surface(Modifier.fillMaxWidth(), color = SwapInk, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(p.name.uppercase(Locale.ROOT), color = Yellow, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = .6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${LocalDate.parse(p.date).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))} · over ${p.days.size} ${if (p.days.size == 1) "day" else "days"}",
                                color = soft, fontFamily = Mono, fontSize = 12.sp)
                        }
                        used.forEach { Icon(swapIcon(it.name, it.usual), null, Modifier.size(20.dp), tint = Yellow) }
                        Text(cash(p.amountMinor), color = Color.White, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private data class SwapTileData(val swap: Swap)

/** Add or change one swap: a name and a cost for what you usually do, and for the cheaper option. The category is worked out from the names. */
@Composable
fun SwapEditor(id: String, state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    val old = state.data.swaps.find { it.id == id }
    var usualName by rememberSaveable(id) { mutableStateOf(old?.usualName ?: "") }
    var usual by rememberSaveable(id) { mutableStateOf(old?.let { Money(it.usualMinor).decimal() } ?: "") }
    var cheaperName by rememberSaveable(id) { mutableStateOf(old?.cheaperName ?: "") }
    var cheaper by rememberSaveable(id) { mutableStateOf(old?.let { Money(it.cheaperMinor).decimal() } ?: "") }
    var confirm by remember { mutableStateOf(false) }
    EditorSheet(if (old == null) "New swap" else "Edit swap", state.busy, close, onSave = {
        val usualLabel = usualName.trim(); val cheaperLabel = cheaperName.trim()
        val usualMinor = positive(usual); val cheaperMinor = zeroOrPositive(cheaper)
        require(usualLabel.isNotEmpty() && cheaperLabel.isNotEmpty()) { "Give both a name" }
        require(cheaperMinor < usualMinor) { "The cheaper option has to cost less" }
        val category = if (old != null && old.usualName == usualLabel && old.cheaperName == cheaperLabel) old.category
            else LedgerEngine.guessCategory("$usualLabel $cheaperLabel")
        val swap = Swap(old?.id ?: LedgerEngine.id(), category, if (old != null && category == old.category) old.icon else CategoryEmoji[category] ?: "🔁",
            usualLabel, usualMinor, cheaperLabel, cheaperMinor)
        model.submit(if (old == null) "Swap added" else "Swap updated", close) { data ->
            data.copy(swaps = if (old == null) data.swaps + swap else data.swaps.map { if (it.id == old.id) swap else it })
        }
    }) {
        Text("What you usually do, and a cheaper option for it. The assistant uses these when you overspend.", style = MaterialTheme.typography.bodySmall)
        FormText("Name", usualName, { usualName = it })
        FormText("Cost (RM)", usual, { usual = it }, decimal = true)
        FormText("Cheaper option", cheaperName, { cheaperName = it })
        FormText("Cost (RM)", cheaper, { cheaper = it }, decimal = true)
        if (old != null) TextButton(onClick = { confirm = true }, enabled = !state.busy) { Text("Delete swap", color = MaterialTheme.colorScheme.error) }
    }
    if (confirm && old != null) ConfirmDialog("Delete this swap?", "${old.cheaperName} instead of ${old.usualName} won’t be offered any more. Paybacks that already used it keep their history.",
        { confirm = false }) {
        model.change("Swap removed", close) { data -> data.copy(swaps = data.swaps.filterNot { it.id == old.id }) }; confirm = false
    }
}
