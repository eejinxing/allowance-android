package com.allowance.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.allowance.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allowance.app.AllowanceState
import com.allowance.app.AllowanceViewModel
import com.allowance.app.userMessage
import com.allowance.core.ledger.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Pages: 0 Home, 1 History, 2 Analytics, 3 Wallet, 4 Goals, 5 Budget assistant, 6 Planning, 7 Swaps.
// The pill shows six; History and Planning open from Home. The AI tab (null icon) shows the tiger.
private val Tabs = listOf(Triple(0, "Home", AppIcons.Home), Triple(2, "Analytics", AppIcons.BarChart),
    Triple<Int, String, ImageVector?>(5, "AI", null), Triple(4, "Goals", AppIcons.Target), Triple(3, "Wallet", AppIcons.AccountBalanceWallet),
    Triple(7, "Swaps", AppIcons.SwapHoriz))

/** iOS-like scale: 17sp body, bold large titles, slightly tight tracking, and tabular figures so amounts don’t jitter while they count. Nothing below 12sp. */
private val AppTypography = Typography().let { t -> t.copy(
    displayLarge = t.displayLarge.tnum(), displayMedium = t.displayMedium.tnum(), headlineLarge = t.headlineLarge.tnum(),
    headlineMedium = t.headlineMedium.tnum(), titleSmall = t.titleSmall.tnum(), bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp).tnum(),
    labelSmall = t.labelSmall.copy(fontSize = 12.sp).tnum(),
    displaySmall = t.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp).tnum(),
    headlineSmall = t.headlineSmall.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp).tnum(),
    titleLarge = t.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp).tnum(),
    titleMedium = t.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp).tnum(),
    bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-.2).sp).tnum(),
    bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = (-.1).sp).tnum(),
    labelLarge = t.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).tnum(),
    labelMedium = t.labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp).tnum(),
) }

private fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")
@Composable
fun AllowanceApp(model: AllowanceViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val dark = state.preferences.theme == "DARK" || (state.preferences.theme == "SYSTEM" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Yellow, onPrimary = Ink, secondary = Yellow, onSecondary = Ink,
        primaryContainer = Color(0xFF4A400F), onPrimaryContainer = Color(0xFFFFE96B),
        secondaryContainer = Color(0xFF5A4D0C), onSecondaryContainer = Color(0xFFFFE96B),
        tertiary = Sun, tertiaryContainer = Color(0xFF51400F), onTertiaryContainer = Sun,
        surfaceContainerLowest = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF1B1B1B),
        surfaceContainer = Color(0xFF202020), surfaceContainerHigh = Color(0xFF262626), surfaceContainerHighest = Color(0xFF323232),
        surfaceTint = Color.Transparent, outline = Color(0xFF8E8E93), outlineVariant = Color(0xFF3A3A3C),
        background = Color(0xFF111111), surface = Color(0xFF1B1B1B), surfaceVariant = Color(0xFF282828), onSurfaceVariant = Color(0xFFA1A1A6))
    else lightColorScheme(primary = Ink, onPrimary = Color.White, secondary = Yellow, onSecondary = Ink, background = Color.White,
        primaryContainer = Color(0xFFFFF3A8), onPrimaryContainer = Ink,
        secondaryContainer = Yellow, onSecondaryContainer = Ink,
        tertiary = Color(0xFF705500), tertiaryContainer = Sun, onTertiaryContainer = Ink,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAF8),
        surfaceContainer = Color(0xFFF5F5F2), surfaceContainerHigh = Color(0xFFEFEFEA), surfaceContainerHighest = Color(0xFFE6E6DF),
        surfaceTint = Color.Transparent, outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFE2E2E6),
        inverseSurface = Ink, inverseOnSurface = Color.White,
        surface = Color.White, surfaceVariant = Color(0xFFEFEFEA), onSurface = Ink, onBackground = Ink, onSurfaceVariant = Color(0xFF6E6E73))
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography) { CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        val currentView = LocalView.current
        SideEffect {
            (currentView.context as? Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, currentView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val mainState = rememberSaveableStateHolder()
        LaunchedEffect(state.data.settings == null) {
            if (state.data.settings == null) mainState.removeState("main")
        }
        LaunchedEffect(model) { model.events.collect { snackbar.showSnackbar(it) } }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Couldn’t open your data: ${state.error}\nClose and reopen the app. Your original data hasn’t been overwritten.")
                }
                state.locked && state.preferences.pinEnabled -> LockScreen(model, snackbar, state.busy)
                state.data.settings == null -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Box(Modifier.padding(padding)) { BudgetEditor(state, model, {}, setup = true) }
                }
                else -> mainState.SaveableStateProvider("main") { MainContent(state, model, snackbar) }
            }
        }
    } }
}

@Composable
private fun LockScreen(model: AllowanceViewModel, snackbar: SnackbarHostState, busy: Boolean) {
    var pin by remember { mutableStateOf("") }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Allowance", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(16.dp))
            Text("Welcome back. Enter your 6-digit PIN to keep looking after your budget.")
            Spacer(Modifier.height(24.dp))
            FormText("PIN", pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, secret = true)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { model.unlock(pin); pin = "" }, enabled = pin.length == 6 && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking…" else "Unlock") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(state: AllowanceState, model: AllowanceViewModel, snackbar: SnackbarHostState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    var removeWish by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<AppData?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The assistant's conversation stays while you look at other tabs, like the web app's.
    val assistant = remember(scope) { AssistantSession(scope) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(BackupCodec.encode(model.state.value.data)) }
                    ?: error("Couldn’t write the file")
            } }.onSuccess { model.markBackup(state.today); model.notify("Backup saved (PIN not included)") }.onFailure { model.notify("Export failed: ${it.userMessage("Couldn’t write the file")}") }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(LedgerEngine.csv(model.state.value.data)) }
                    ?: error("Couldn’t write the file")
            } }.onSuccess { model.notify("CSV exported") }.onFailure { model.notify("Export failed: ${it.userMessage("Couldn’t write the file")}") }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer); if (count < 0) break
                        result.append(buffer, 0, count); require(result.length <= 10_000_000) { "Backup is larger than 10 MB" }
                    }; result.toString()
                } ?: error("Couldn’t read the file")
                LedgerEngine.ensureMonths(BackupCodec.decode(text), state.today)
            } }.onSuccess { pendingImport = it }.onFailure { model.notify("Couldn’t restore backup: ${it.userMessage("The file couldn’t be read").take(120)}") }
        }
    }
    BackHandler(enabled = settings && route == null) { settings = false }
    BackHandler(enabled = !settings && tab != 0 && route == null) { tab = 0 }
    val walletView = LocalView.current
    val appDark = state.preferences.theme == "DARK" || (state.preferences.theme == "SYSTEM" && isSystemInDarkTheme())
    SideEffect {
        (walletView.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, walletView).apply {
                isAppearanceLightStatusBars = !appDark
                isAppearanceLightNavigationBars = !appDark
            }
        }
    }
    val open: (String) -> Unit = { route = it }
    val close: () -> Unit = { route = null }
    val openSettings = { settings = true }
    Scaffold(
        bottomBar = { AnimatedVisibility(!settings && tab != 5, enter = slideInVertically(tween(320)) { it } + fadeIn(), exit = slideOutVertically(tween(220)) { it } + fadeOut()) {
            BottomNav(tab) { tab = it }
        } },
        floatingActionButton = { AnimatedVisibility(!settings && tab in 1..2, enter = scaleIn(tween(240, easing = EaseOutExpo), initialScale = .8f) + fadeIn(tween(200)), exit = scaleOut(tween(140)) + fadeOut(tween(120))) {
            FloatingActionButton(onClick = { route = "entry:" }, containerColor = Yellow, contentColor = Ink, shape = RoundedCornerShape(20.dp)) { Icon(AppIcons.Add, "Add entry") }
        } },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                AnimatedContent(targetState = if (settings) -1 else tab, label = "pages", transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(280, 70)) + slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { direction * it / 7 }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -direction * it / 7 })
                }) { page ->
                    if (page == -1) SettingsPage(state, model, open, onBack = { settings = false },
                        export = { model.beginExternalTask(); exportBackup.launch("Allowance-${state.today}.json") },
                        csv = { model.beginExternalTask(); exportCsv.launch("Allowance-${state.today}.csv") },
                        restore = { model.beginExternalTask(); importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        reset = { reset = true }, onPlan = { settings = false; tab = 6 })
                    else when (page) {
                        0 -> TodayPage(state, open, openSettings, onSeeAll = { tab = 1 }, onPlan = { tab = 6 })
                        6 -> PlanPage(state, model, open, onBack = { tab = 0 })
                        1 -> LedgerPage(state, open, onBack = { tab = 0 })
                        2 -> InsightsPage(state, openSettings, onBack = { tab = 0 })
                        3 -> WalletPage(state, open, onSettings = openSettings)
                        4 -> GoalsPage(state, open, openSettings, onBack = { tab = 0 }) { removeWish = it }
                        7 -> SwapsPage(state, open, onSettings = openSettings)
                        else -> AdvisorPage(state, open, onBack = { tab = 0 }, onWishList = { tab = 4 }, payBack = { name, amount, pick ->
                            // Records the purchase and lowers the next few days by the planned amounts, in one step.
                            model.change("Added. The next few days are adjusted to match") { data ->
                                val plan = LedgerEngine.payback(data, amount, state.today, pick)
                                var out = LedgerEngine.saveEntry(data, LedgerEntry(LedgerEngine.id(), state.today.toString(), amount, EntryType.EXPENSE, "SHOPPING", name), state.today)
                                // A day that only swaps a bill keeps its own money, so it gets no plan.
                                plan?.days?.forEach { c -> if (c.cut > 0) out = LedgerEngine.setPlan(out, java.time.LocalDate.parse(c.date), c.now, state.today) }
                                // Remember which swaps were chosen for which days (by name, so deleting a swap later doesn't change history).
                                if (plan != null && plan.days.isNotEmpty()) out = LedgerEngine.addPayback(out, Payback(LedgerEngine.id(), state.today.toString(), name, amount,
                                    plan.days.map { c -> PaybackDay(c.date, c.cut, c.swaps.map { s -> PaybackSwap(s.swap.cheaperName, s.swap.usualName, s.swap.icon, s.saving) }) }))
                                out
                            }
                        }, model = model, session = assistant) { name, price ->
                            model.change("Added to your wish list") { it.copy(wishes = it.wishes + Wish(LedgerEngine.id(), name, price, state.today.toString())) }
                        }
                    }
                }
            }
        }
    }
    route?.let { current ->
        key(current) {
            val kind = current.substringBefore(":")
            val id = current.substringAfter(":", "")
            when (kind) {
                "entry", "quick", "purchase", "entrydate", "prefill" -> EntryEditor(current, state, model, close)
                "account" -> AccountEditor(id, state, model, close)
                "balance" -> BalanceEditor(id, state, model, close)
                "transfer" -> TransferEditor(state, model, close)
                "fund", "goal", "wish" -> TargetEditor(kind, id, state, model, close)
                "fundin", "goalin", "buy" -> TargetMoveEditor(kind, id, state, model, close)
                "swap" -> SwapEditor(id, state, model, close)
                "budget" -> BudgetEditor(state, model, close)
                "plan" -> PlanDayEditor(id, state, model, close)
                "dayspend" -> DaySpendEditor(id, state, model, open, close)
                "pin" -> PinEditor(state, model, close)
            }
        }
    }
    if (reset) ConfirmDialog("Erase all budget data?", "Income settings, accounts, transactions, goals and funds will be deleted. Theme and PIN are kept. This can’t be undone, so export a JSON backup first.",
        { reset = false }) { model.change("Data erased") { AppData() }; reset = false }
    removeWish?.let { id -> ConfirmDialog("Remove this wish?", "You can add it again later.", { removeWish = null }) {
        model.change("Wish removed") { it.copy(wishes = it.wishes.filterNot { wish -> wish.id == id }) }; removeWish = null
    } }
    pendingImport?.let { imported -> ConfirmDialog("Restore this backup?", "Your current data will be replaced by ${imported.entries.size} transactions and ${imported.accounts.size} accounts from the backup. PIN and theme stay the same.",
        { pendingImport = null }) { model.change("Backup restored", { pendingImport = null; route = null; tab = 0 }) { imported } } }
}


@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val navDark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().height(74.dp).shadow(16.dp, RoundedCornerShape(37.dp), clip = false, ambientColor = Ink.copy(alpha = .35f), spotColor = Color(0xFF6B5A00).copy(alpha = .35f))
            .clip(RoundedCornerShape(37.dp)).background(if (navDark) Color(0xFF2A2A2A) else Ink)
            .then(if (navDark) Modifier.border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(37.dp)) else Modifier)
            .padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Tabs.forEach { (page, name, icon) ->
                val selected = (if (tab == 1 || tab == 6) 0 else tab) == page
                val weight by animateFloatAsState(if (selected) 1.35f else 1f, spring(dampingRatio = .8f, stiffness = 330f), label = "nav-weight")
                val fill by animateColorAsState(if (selected) NavYellow else Color.Transparent, tween(240), label = "nav-fill")
                val content by animateColorAsState(if (selected) Ink else Color.White.copy(alpha = .86f), tween(240), label = "nav-content")
                val lift by animateFloatAsState(if (selected) 1.08f else 1f, spring(dampingRatio = .8f, stiffness = 500f), label = "nav-lift")
                Column(Modifier.weight(weight).fillMaxHeight().testTag("nav_$name").pressable(role = Role.Tab) { onTab(page) }.clip(RoundedCornerShape(30.dp)).background(fill),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (icon != null) Icon(icon, null, Modifier.size(26.dp).graphicsLayer { scaleX = lift; scaleY = lift }, tint = content)
                    else Image(painterResource(R.drawable.tiger_head), null, Modifier.size(30.dp).graphicsLayer { scaleX = lift * 1.1f; scaleY = lift * 1.1f }.clip(CircleShape))
                    // Six tabs share the pill, so the names are a little smaller than they were with five.
                    Text(name, color = content, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private val NavYellow = Color(0xFFFFDE3D)

@Composable
private fun SettingsPage(state: AllowanceState, model: AllowanceViewModel, open: (String) -> Unit, onBack: () -> Unit,
    export: () -> Unit, csv: () -> Unit, restore: () -> Unit, reset: () -> Unit, onPlan: () -> Unit = {}) {
    Page(topPadding = 0.dp) {
        item { PageHeader("Settings", onBack = onBack) }
        item {
            Panel {
                Text(state.data.settings?.nickname?.ifBlank { "My budget" }.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Since ${state.data.settings?.started} · MYR (Malaysian ringgit)")
                Button(onClick = { open("budget:") }, modifier = Modifier.fillMaxWidth()) { Text("Income, bills and budget") }
                OutlinedButton(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text("Plan this month") }
            }
        }
        item {
            Panel {
                SectionTitle("Appearance")
                Chips(linkedMapOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark"), state.preferences.theme, model::setTheme)
            }
        }
        item {
            Panel {
                SectionTitle("App lock")
                Text(if (state.preferences.pinEnabled) "On. You’ll need your PIN after leaving the app." else "No PIN set yet.")
                Text("The PIN guards the app’s entrance; the local database isn’t separately encrypted. Forgetting your PIN means clearing app data, so back up regularly.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open("pin:") }) { Text(if (state.preferences.pinEnabled) "Change or turn off PIN" else "Set a 6-digit PIN") }
                if (state.preferences.pinEnabled) TextButton(onClick = model::lock) { Text("Lock now") }
            }
        }
        item { AiPanel(state, model) }
        item {
            Panel {
                SectionTitle("Data and backup")
                Text("Your data lives on this device and nowhere else. A JSON backup restores everything; CSV is for spreadsheets.")
                Text(backupLine(state.preferences.lastBackup, state.today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = export, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export JSON backup") }
                OutlinedButton(onClick = restore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Restore JSON backup") }
                OutlinedButton(onClick = csv, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export all transactions as CSV") }
                TextButton(onClick = reset, enabled = !state.busy) { Text("Erase all budget data", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Panel {
                SectionTitle("About Allowance")
                Text("Offline budgeting · native Kotlin / Jetpack Compose\nVersion 1.0.0")
                Text("The budget assistant runs on your device: built-in rules, plus an optional AI on your own computer that you can connect above. This version doesn’t connect to banks, sync to the cloud or use any online AI service.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The On-device AI card: which AI is connected, or how to connect one. The same choices as the web app, except the in-browser model. */
@Composable
private fun AiPanel(state: AllowanceState, model: AllowanceViewModel) {
    val ai by model.ai.state.collectAsStateWithLifecycle()
    var url by rememberSaveable(state.preferences.ollamaUrl) { mutableStateOf(state.preferences.ollamaUrl) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel {
        SectionTitle("On-device AI")
        when {
            ai.status == "loading" -> {
                Text("Connecting to Ollama… ${Math.round(ai.progress * 100)}%")
                Text(ai.text, style = MaterialTheme.typography.bodySmall, color = muted)
                LinearProgressIndicator(progress = { ai.progress }, modifier = Modifier.fillMaxWidth())
            }
            ai.ready -> {
                Text("Connected to Ollama on your computer. Text: ${ai.model}. Receipt photos: " +
                    (if (ai.vision.isNotEmpty()) ai.vision else "only the reader on this phone; for a second opinion run ollama pull qwen2.5vl:3b on the computer, then press Connect again.") +
                    " It is free and private. It reads your sentences and photos, but an amount typed in words always comes from your own words, and a receipt is only saved after you check it.")
                if (ai.vision.isEmpty()) Button(onClick = { model.connectOllama(url.ifBlank { state.preferences.ollamaUrl }) }, modifier = Modifier.fillMaxWidth()) { Text("Connect again") }
                OutlinedButton(onClick = model::disconnectAi, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            }
            else -> {
                if (ai.status == "error") Text("Couldn’t start it: ${ai.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Make the assistant a real AI that understands loose sentences in English or 中文, and reads receipt photos with a vision model. Free, private, no account, no limits. The built-in rules stay as the backup, and receipts are always read on this phone first.")
                Text("Ollama on your computer", fontWeight = FontWeight.Bold)
                Text("Install Ollama (ollama.com), run ollama pull qwen2.5:3b, and start it with OLLAMA_HOST=0.0.0.0 so this phone can reach it on the same Wi-Fi. " +
                    "Then enter the computer’s address, like http://192.168.1.20:11434 (in the Android emulator: http://10.0.2.2:11434).", style = MaterialTheme.typography.bodySmall, color = muted)
                FormText("Ollama address", url, { url = it })
                Button(onClick = { model.connectOllama(url) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect to Ollama") }
                Text("A model that runs inside the phone itself isn’t in the Android app yet; the web app can download one.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** When you last saved a backup file, with a nudge if it's been a while. */
private fun backupLine(last: String, today: java.time.LocalDate): String {
    if (last.isBlank()) return "You haven’t saved a backup file yet. Do it once now, and after big changes."
    val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(last), today) }.getOrDefault(0L)
    return when { days <= 0 -> "Last backup: today."; days < 14 -> "Last backup: $days day${if (days == 1L) "" else "s"} ago."; else -> "Last backup: $days days ago. Worth saving a fresh one." }
}

@Composable
private fun PinEditor(state: AllowanceState, model: AllowanceViewModel, close: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var remove by rememberSaveable { mutableStateOf(false) }
    EditorSheet("App lock", state.busy, close, onSave = {
        if (remove) model.removePin(current, close)
        else {
            require(pin.matches(Regex("[0-9]{6}"))) { "Enter a 6-digit PIN" }
            require(pin == confirm) { "The two PINs don’t match" }
            model.setPin(pin, confirm, current, close)
        }
    }) {
        if (state.preferences.pinEnabled) {
            FormText("Current PIN", current, { if (it.length <= 6 && it.all(Char::isDigit)) current = it }, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Turn off PIN", Modifier.weight(1f)); Switch(remove, { remove = it }) }
        }
        if (!remove) {
            FormText("New 6-digit PIN", pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, secret = true)
            FormText("Confirm PIN", confirm, { if (it.length <= 6 && it.all(Char::isDigit)) confirm = it }, secret = true)
        }
    }
}
