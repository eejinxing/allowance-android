package com.allowance.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*

@Composable
internal fun MoreDots(color: Color) {
    Canvas(Modifier.size(width = 26.dp, height = 8.dp)) {
        listOf(.08f, .5f, .92f).forEach { x -> drawCircle(color, 2.6.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * x, size.height / 2)) }
    }
}

@Composable
private fun GoalCard(goal: Goal, saved: Long, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.pressable(onClickLabel = "Goal options", onClick = onClick), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp, 24.dp).clip(CircleShape).background(color))
                Spacer(Modifier.weight(1f))
                if (saved >= goal.targetMinor) ReachedBadge()
            }
            Spacer(Modifier.height(16.dp))
            Text(goal.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(cash(rememberCountUp(saved)), Modifier.fillMaxWidth().padding(top = 4.dp),
                style = TextStyle(fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp), maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 21.sp, stepSize = .5.sp))
            Text("of ${cash(goal.targetMinor).removeSuffix(".00")}", Modifier.padding(top = 2.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Progress((saved.toFloat() / goal.targetMinor), if (saved >= goal.targetMinor) Color(0xFF1E9E6A) else Ink)
        }
    }
}

/** The goal's "peak" moment: a small badge that springs in once the target is met. */
@Composable
private fun ReachedBadge() {
    val pop = remember { androidx.compose.animation.core.Animatable(.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .4f, stiffness = 300f)) }
    Row(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(Color(0xFF1E9E6A))
        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(AppIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
        Text("Reached", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsPage(state: AllowanceState, open: (String) -> Unit, onSettings: () -> Unit = {}, onBack: () -> Unit = {}, removeWish: (String) -> Unit) {
    val data = state.data
    val goals = data.goals.filterNot { it.archived }
    val saved = goals.associate { it.id to LedgerEngine.goalSaved(data, it.id) }
    val colors = buildList {
        goals.forEach { goal ->
            val wanted = Palette[goal.color.mod(Palette.size)]
            add(if (wanted !in this) wanted else Palette.first { it !in this })
        }
    }
    var filterId by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = goals.firstOrNull { it.id == filterId }
    val shown = if (filter == null) goals else listOf(filter)
    val total = shown.sumOf { saved.getValue(it.id) }
    var menu by remember { mutableStateOf(false) }
    var sheetGoalId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetGoal = goals.firstOrNull { it.id == sheetGoalId }
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < .5f
    val ink = MaterialTheme.colorScheme.onSurface

    Page(topPadding = 0.dp) {
        item(key = "header") {
            val top = if (dark) lerp(Color(0xFFFFE45C), Color.Black, .68f) else Color(0xFFFFE45C)
            val bottom = if (dark) lerp(top, background, .5f) else Color(0xFFFFFCEB)
            Column(Modifier.fullBleed().clip(RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp)).heroBackground(dark, bottom)
                .statusBarsPadding().padding(horizontal = 24.dp).padding(bottom = 26.dp)) {
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart).offset(x = (-12).dp)) { Icon(AppIcons.ChevronLeft, "Back", Modifier.size(28.dp)) }
                    Text("Goals", Modifier.align(Alignment.Center), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onSettings, Modifier.align(Alignment.CenterEnd).offset(x = 12.dp)) { Box(Modifier.semantics { contentDescription = "Settings" }) { MoreDots(ink) } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(if (filter == null) "Total saved" else "Saved", fontSize = 18.sp, color = ink.copy(alpha = .8f))
                        BasicText(cash(rememberCountUp(total)), Modifier.fillMaxWidth().padding(top = 2.dp),
                            style = TextStyle(fontFeatureSettings = "tnum", color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, letterSpacing = (-1).sp), maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 42.sp, stepSize = 1.sp))
                    }
                    Box {
                        Surface(Modifier.padding(top = 4.dp).pressable { menu = true }, color = Color.White.copy(alpha = if (dark) .12f else .75f), shape = CircleShape) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(filter?.name ?: "All goals", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                                Icon(AppIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(22.dp))
                            }
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("All goals") }, onClick = { filterId = null; menu = false })
                            goals.forEach { goal -> DropdownMenuItem(text = { Text(goal.name) }, onClick = { filterId = goal.id; menu = false }) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 22.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(254.dp), contentAlignment = Alignment.Center) {
                        GoalRings(shown.take(3).map { goal -> colors[goals.indexOf(goal)] to (saved.getValue(goal.id).toFloat() / goal.targetMinor) }.ifEmpty { listOf(Yellow to 0f) },
                            Modifier.semantics { contentDescription = shown.joinToString(prefix = "Goal progress. ") { "${it.name} ${(saved.getValue(it.id) * 100 / it.targetMinor).coerceAtMost(999)} percent" } })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Your goals", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("Keep saving", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 22.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally)) {
                    shown.take(3).forEach { goal ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(8.dp, 26.dp).clip(CircleShape).background(colors[goals.indexOf(goal)]))
                            Text(goal.name, fontSize = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(0.dp)) }
        if (goals.isEmpty()) item(key = "empty") { EmptyCard("Give your savings a name", "A trip, an emergency fund, or whatever you’re saving up for next.", "Create a goal") { open("goal:") } }
        val cells: List<Goal?> = shown + listOf(null)
        itemsIndexed(cells.chunked(2), key = { index, _ -> "goal-row-$index" }) { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).staggerIn(rowIndex + 1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { goal ->
                    val cell = Modifier.weight(1f).fillMaxHeight().heightIn(min = 150.dp)
                    if (goal == null) Box(cell.pressable(onClickLabel = "Add goal") { open("goal:") }.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Add, null, Modifier.size(22.dp))
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text("Add goal", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Save up for something new", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    } else GoalCard(goal, saved.getValue(goal.id), colors[goals.indexOf(goal)], cell) { sheetGoalId = goal.id }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key = "note") {
            Text("Goals track money you’ve already saved. They don’t change your daily budget or move money between accounts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "wish-title") { SectionTitle("Wish list", "+ Add wish") { open("wish:") } }
        if (data.wishes.isEmpty()) item(key = "wish-empty") { EmptyCard("Jot it down, decide later", "Put things you want to buy here and give yourself time to think it over.") }
        items(data.wishes.sortedByDescending { it.date }, key = { it.id }) { wish ->
            Panel(Modifier.animateItem()) {
                Text(wish.name, fontWeight = FontWeight.Bold)
                Text(cash(wish.priceMinor))
                Row {
                    TextButton(onClick = { open("wish:${wish.id}") }) { Text("Edit") }
                    TextButton(onClick = { removeWish(wish.id) }) { Text("Remove") }
                }
            }
        }
    }
    sheetGoal?.let { goal ->
        val amount = saved.getValue(goal.id)
        ModalBottomSheet(onDismissRequest = { sheetGoalId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(goal.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Saved ${cash(amount)}  /  Target ${cash(goal.targetMinor)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Progress(amount.toFloat() / goal.targetMinor, Ink)
                if (goal.deadline.isNotEmpty()) Text("Target date ${goal.deadline}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Button(onClick = { sheetGoalId = null; open("goalin:${goal.id}") }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Deposit / withdraw") }
                TextButton(onClick = { sheetGoalId = null; open("goal:${goal.id}") }, modifier = Modifier.fillMaxWidth()) { Text("Edit goal") }
            }
        }
    }
}
