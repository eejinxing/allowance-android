package com.allowance.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.FileProvider
import com.allowance.app.AllowanceState
import com.allowance.app.AllowanceViewModel
import com.allowance.app.ai.AiState
import com.allowance.app.ai.ReceiptScanner
import com.allowance.app.ai.ScanResult
import com.allowance.app.userMessage
import androidx.compose.ui.unit.Dp
import com.allowance.core.assistant.Intent
import com.allowance.core.assistant.Memory
import com.allowance.core.assistant.Nlu
import com.allowance.core.assistant.Reader
import com.allowance.core.assistant.Receipt
import com.allowance.core.ledger.EntryType
import com.allowance.core.ledger.Goal
import com.allowance.core.ledger.LedgerEngine
import com.allowance.core.ledger.LedgerEntry
import com.allowance.core.ledger.PaybackPlan
import com.allowance.core.ledger.SwapChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** One turn in the assistant thread. Checks are re-evaluated against the live budget each time they draw. */
sealed interface Turn {
    data class Asked(val text: String) : Turn
    data class Note(val text: String) : Turn
    data class Check(val name: String, val amount: Long, val memo: String = "") : Turn
    /** What today's unspent money does for the days ahead. */
    data object Surplus : Turn
    /** What the assistant just wrote to your history, with how much today has left, and a way to take it back. */
    data class Recorded(val id: String, val name: String, val amount: Long, val category: String, val date: LocalDate, val left: Long, val undone: Boolean = false) : Turn
    /** What was read from a receipt photo. Nothing is saved until the person checks it and presses save: a photo can be misread. */
    data class ReceiptRead(val receipt: Receipt) : Turn
    /** "Thinking…" or "Reading row 3 of 20…", taken out again when the answer comes. Not a data class: each one is its own line. */
    class Waiting(text: String) : Turn { var text by mutableStateOf(text) }
}

/**
 * The conversation and what the assistant recorded in it, kept above the page so it survives switching tabs (like the web app's chat).
 * [scope] outlives the page too, so a slow answer or a receipt being read still arrives after you look at another tab.
 */
class AssistantSession(val scope: CoroutineScope) {
    val turns = mutableStateListOf<Turn>()
    /** Ids of the entries recorded in this chat, newest last: "undo" takes back only these. */
    val log = mutableListOf<String>()
    var busy by mutableStateOf(false)
    var input by mutableStateOf("")
}

private val CatZh = mapOf("FOOD" to "餐饮", "TRANSPORT" to "交通", "SHOPPING" to "购物", "FUN" to "娱乐", "HEALTH" to "健康", "LIVING" to "生活", "OTHER" to "其他")
private val PeriodNames = mapOf("today" to ("today" to "今天"), "yesterday" to ("yesterday" to "昨天"), "week" to ("in the last 7 days" to "最近 7 天"), "month" to ("this month" to "这个月"))
private const val HelpEn = "I can record what you spent (“Spent RM 3 on coffee”), check a purchase (“Sneakers RM 120”), tell you what’s left today, or add up your spending (“How much did I spend on food this month?”). Say “undo” to take back the last thing I recorded."
private const val HelpZh = "我可以帮你记账（“今天花了3块买咖啡”）、判断能不能买（“买球鞋 120块”）、告诉你今天还剩多少，或者统计花费（“这个月吃饭花了多少”）。说“撤销”可以取消我刚记的那笔。"
/** "FOOD" → "Food", the way the web app names a category in the assistant's answers. */
private fun catName(c: String) = c.lowercase().replaceFirstChar { it.uppercase() }

/** The small line under the chat: which kind of understanding is in use. Nothing here ever leaves your own devices. */
private fun aiStateText(st: AiState) = when (st.status) {
    "ready" -> "AI is on: ${st.model} on your computer through Ollama${if (st.vision.isNotEmpty()) ", receipts with ${st.vision}" else ", no receipt reading yet"}."
    "loading" -> "Starting the AI… ${Math.round(st.progress * 100)}%. Built-in rules answer meanwhile."
    "error" -> "The AI couldn’t start, so built-in rules are answering. Details are in Settings."
    else -> "Estimates run on your device using your own budget. Settings can connect a free AI on your computer."
}

/**
 * Everything the assistant does with what you typed, said or photographed. Two readers: the rules ([Nlu]) and, when it is connected,
 * a model on Ollama. A record is only written when both agree it is one ([Reader.combine]); the rules alone answer when the AI is off.
 */
private class Brain(val session: AssistantSession, val state: () -> AllowanceState, val model: AllowanceViewModel?) {
    private val turns get() = session.turns

    fun showStatus(zh: Boolean) {
        val st = state(); val r = LedgerEngine.result(st.data, YearMonth.from(st.today), st.today); val left = r.todayRemaining.minor
        fun note(en: String, cn: String) { turns += Turn.Note(if (zh) cn else en) }
        when {
            left > 0 -> { turns += Turn.Surplus; note("Tell me what you want and its price, and I’ll show what it does to the days ahead.", "告诉我你想买什么和价格，我会显示它对之后几天的影响。") }
            left == 0L -> note("Today’s allowance is used up. Tell me what you want and its price, and I’ll show what it does to the days ahead.", "今天的额度已经用完了。告诉我你想买什么和价格，我会显示它对之后几天的影响。")
            else -> note("You’re ${cash(-left)} over today’s allowance, so it’s worth waiting until tomorrow.", "今天已经超支 ${cash(-left)}，建议等到明天再买。")
        }
    }

    fun askAfford() { turns += Turn.Asked("Can I afford this today?"); showStatus(false) }

    /** Takes back something the assistant recorded: deletes the entry and turns its card into a line saying so. */
    fun removeRecorded(id: String) {
        session.log.remove(id)
        val i = turns.indexOfFirst { it is Turn.Recorded && it.id == id }
        if (i >= 0) turns[i] = (turns[i] as Turn.Recorded).copy(undone = true)
        if (state().data.entries.any { it.id == id }) model?.change("Removed") { LedgerEngine.deleteEntry(it, id) }
    }

    fun addGoal(name: String, amount: Long) = model?.change("Set as a goal") { it.copy(goals = it.goals + Goal(LedgerEngine.id(), name, amount)) }

    suspend fun respond(text: String, addWish: (String, Long) -> Unit) {
        val zh = Nlu.isChinese(text)
        fun note(en: String, cn: String) { turns += Turn.Note(if (zh) cn else en) }
        val today = state().today
        val rules = Nlu.interpret(text, today)
        var reading: Intent? = null
        val ai = model?.ai
        if (ai != null && ai.ready) {
            val wait = Turn.Waiting(if (zh) "思考中…" else "Thinking…")
            session.busy = true; turns += wait
            // Retrieval first: your own past entries that look like this sentence go to the model as context.
            try { reading = ai.understand(text, today, Memory.hint(Memory.recall(state().data, text))) } finally { session.busy = false; turns.remove(wait) }
        }
        var intent = Reader.combine(rules, reading)
        // Nothing in the words said which category: use the one you gave the same thing before.
        if (intent is Intent.Expense && intent.category == "OTHER") intent = intent.copy(category = Memory.recall(state().data, intent.name.ifEmpty { text })?.category ?: "OTHER")
        when (intent) {
            is Intent.Chat -> if (intent.reply.isNotEmpty()) turns += Turn.Note(intent.reply) else note(HelpEn, HelpZh)
            is Intent.Expense -> {
                val st = state()
                val entry = LedgerEntry(LedgerEngine.id(), intent.date.toString(), intent.amount, EntryType.EXPENSE, intent.category, intent.name)
                val after = try { LedgerEngine.saveEntry(st.data, entry, today) } catch (e: Exception) { val m = e.userMessage(); return note(m, "记不了：$m") }
                model?.change("") { LedgerEngine.saveEntry(it, entry, today) }
                session.log += entry.id
                val left = LedgerEngine.result(after, YearMonth.from(today), today).todayRemaining.minor
                turns += Turn.Recorded(entry.id, intent.name, intent.amount, intent.category, intent.date, left)
            }
            is Intent.Check -> turns += Turn.Check(intent.name, intent.amount, Memory.describe(Memory.recall(state().data, intent.name)))
            Intent.Status -> showStatus(zh)
            is Intent.Spending -> {
                val (total, count) = Nlu.spendingTotal(state().data, intent.period, intent.category, today)
                val (whenEn, whenZh) = PeriodNames.getValue(intent.period)
                val cat = intent.category?.let(::catName).orEmpty(); val catZh = intent.category?.let { CatZh[it] }.orEmpty()
                if (count == 0) note("Nothing recorded${if (cat.isNotEmpty()) " for $cat" else ""} $whenEn.", "$whenZh${catZh}还没有记录。")
                else note("You spent ${cash(total)}${if (cat.isNotEmpty()) " on $cat" else ""} $whenEn ($count ${if (count == 1) "entry" else "entries"}).", "$whenZh${catZh}一共花了 ${cash(total)}（$count 笔）。")
            }
            Intent.Undo -> {
                val id = session.log.lastOrNull()
                    ?: return note("Nothing to undo yet. I can only take back what I recorded in this chat.", "没有可以撤销的记录。我只能撤销在这个对话里记下的内容。")
                removeRecorded(id); note("Taken back.", "已撤销。")
            }
            is Intent.SetGoal -> { addGoal(intent.name, intent.amount); note("Set “${intent.name}” (${cash(intent.amount)}) as a goal. You’ll find it on the Goals page.", "已把“${intent.name}”（${cash(intent.amount)}）设为目标，可以在 Goals 页面看到。") }
            is Intent.AddWish -> { addWish(intent.name, intent.amount); note("Added “${intent.name}” (${cash(intent.amount)}) to your wish list.", "已把“${intent.name}”（${cash(intent.amount)}）加入愿望清单。") }
            null -> note(HelpEn, HelpZh)
        }
    }

    /** The on-device reader's answer, as a receipt the person can check (the same card and entry sheet as the vision model's). */
    private fun deviceReceipt(read: ScanResult, today: LocalDate): Receipt? = read.total?.let { t ->
        Receipt("", today, false, t.cents, LedgerEngine.guessCategory(read.rows.joinToString(" ") { it.text }),
            deviceSource = t.source, deviceText = t.text, deviceSure = t.conf >= .6)
    }

    /** A receipt photo: read on this phone first; if no total is found and a vision model is connected, ask it. Nothing is saved from here. */
    suspend fun receiptFrom(context: android.content.Context, uri: Uri) {
        val vm = model ?: return
        turns += Turn.Asked("Receipt photo")
        val wait = Turn.Waiting("Reading the receipt on this device…")
        session.busy = true; turns += wait
        try {
            val today = state().today
            val photo = withContext(Dispatchers.IO) { ReceiptScanner.load(context, uri) }
            var receipt: Receipt? = null; var why = ""
            try { receipt = deviceReceipt(vm.scanner.scan(photo) { wait.text = it }, today) } catch (e: Exception) { why = e.message.orEmpty() }
            turns.remove(wait)
            if (receipt == null && vm.ai.canReadReceipts) {
                val slow = Turn.Waiting("No clear total found. Asking the vision model on your computer; this can take a few minutes…"); turns += slow
                try { receipt = vm.ai.readReceipt(withContext(Dispatchers.Default) { ReceiptScanner.base64Jpeg(photo) }, today) } catch (e: Exception) { why = e.message.orEmpty() } finally { turns.remove(slow) }
            }
            turns += receipt?.let(Turn::ReceiptRead)
                ?: Turn.Note(why.ifEmpty { "I couldn’t find a total on that receipt. Try a sharper, straight-on photo with the whole receipt in the frame, or type it." })
        } catch (e: Exception) { turns.remove(wait); turns += Turn.Note(e.message ?: "Couldn’t read that photo.") } finally { session.busy = false }
    }
}

@Composable
fun AdvisorPage(state: AllowanceState, open: (String) -> Unit, onBack: () -> Unit = {}, onWishList: () -> Unit = {},
    payBack: (String, Long, Map<String, List<String>>?) -> Unit = { _, _, _ -> }, model: AllowanceViewModel? = null,
    session: AssistantSession = rememberCoroutineScope().let { scope -> remember { AssistantSession(scope) } }, addWish: (String, Long) -> Unit) {
    val result = LedgerEngine.result(state.data, YearMonth.from(state.today), state.today)
    val nickname = state.data.settings?.nickname?.ifBlank { null }
    val turns = session.turns
    val current by rememberUpdatedState(state)
    val brain = remember(session, model) { Brain(session, { current }, model) }
    val aiState by (model?.ai?.state ?: remember { MutableStateFlow(AiState()) }).collectAsState()
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < .5f
    LaunchedEffect(model) { model?.startAi() } // whichever AI was turned on in Settings starts loading; the rules answer meanwhile
    LaunchedEffect(turns.size) {
        if (turns.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(380) // let the new answer finish expanding first
        list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }

    fun send() {
        val text = session.input.trim()
        if (text.isEmpty() || session.busy) return // one question at a time while the model thinks
        turns += Turn.Asked(text)
        session.input = ""
        focus.clearFocus()
        session.scope.launch { runCatching { brain.respond(text, addWish) }.onFailure { turns += Turn.Note(it.message ?: "Something went wrong.") } }
    }
    /** Puts an example into the box and opens the keyboard, so one tap turns an idea into a question you can edit and send. */
    fun tryPrompt(text: String) { session.input = text; runCatching { field.requestFocus() } }
    fun readPhoto(uri: Uri) { if (!session.busy) session.scope.launch { brain.receiptFrom(context.applicationContext, uri) } }

    var photoFile by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> photoFile?.let { if (ok) readPhoto(Uri.parse(it)) } }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::readPhoto) }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (res.resultCode == Activity.RESULT_OK && !heard.isNullOrEmpty()) { session.input = heard; send() } // sent when you stop talking, like the web app
    }
    fun camera(take: Boolean) {
        if (model == null) return
        try {
            model.beginExternalTask() // the camera and the picker are other screens: don't lock the app while they are open
            if (take) {
                val file = File(context.cacheDir, "receipts/receipt.jpg").apply { parentFile?.mkdirs() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                photoFile = uri.toString(); takePhoto.launch(uri)
            } else pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) { turns += Turn.Note("There’s no camera app to take the photo. Choose a photo instead, or type the amount.") }
    }
    fun voice() {
        val ask = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you spent, or what you want to buy")
        try { model?.beginExternalTask(); listen.launch(ask) }
        catch (e: ActivityNotFoundException) { turns += Turn.Note("This phone can’t do voice input. Use the microphone on your keyboard, or type the item and its price.") }
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
                }
            }
            item(key = "todo") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).staggerIn(3).semantics { contentDescription = "Things you can do" }) {
                    Text("Things you can do", Modifier.padding(start = 2.dp, bottom = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = .62f))
                    TodoRow(AppIcons.ReceiptLong, "Record what you spent", "Say it like “spent RM 3 on coffee”, or in 中文.") { tryPrompt("Spent RM 3 on coffee") }
                    TodoRow(AppIcons.ShoppingBag, "Can I afford this today?", "See what today’s budget has left for it.") { brain.askAfford() }
                    TodoRow(AppIcons.SwapHoriz, "Pay an overspend back", "Spread it over the next days with cheaper swaps.") { tryPrompt("Sneakers RM 120") }
                    TodoRow(AppIcons.Target, "Turn a want into a goal", "Save up for it instead of buying it now.") { tryPrompt("Movie RM 25") }
                }
            }
            itemsIndexed(turns, key = { index, _ -> "turn-$index" }) { index, turn ->
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 3 } + expandVertically()) {
                    when (turn) {
                        // The conversation reads like a page, not a chat: "Question 1" and the question in big type, then the answer under it.
                        is Turn.Asked -> Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text("Question ${turns.take(index + 1).count { it is Turn.Asked }}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = .5f))
                            Text(turn.text, Modifier.padding(top = 5.dp), fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
                        }
                        is Turn.Note -> AnswerText(turn.text)
                        is Turn.Waiting -> AnswerText(turn.text, muted = true)
                        is Turn.Check -> CheckCard(turn, state, result, open, { n, a -> brain.addGoal(n, a) }, payBack)
                        is Turn.Surplus -> SurplusCard(state, result)
                        is Turn.Recorded -> RecordedCard(turn, state.today) { brain.removeRecorded(turn.id) }
                        is Turn.ReceiptRead -> ReceiptCard(turn.receipt) {
                            val r = turn.receipt
                            open("prefill:${r.amountMinor}:${r.category}:${r.date}:${r.merchant.ifEmpty { r.items.firstOrNull()?.name ?: "Receipt" }}")
                        }
                    }
                }
            }
            item(key = "footnote") {
                Text(aiStateText(aiState), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        InputBar(session.input, { session.input = it }, ::send, field, enabled = !session.busy,
            onCamera = { camera(it) }, onVoice = ::voice)
    }
}

@Composable
private fun AnswerText(text: String, muted: Boolean = false) {
    Text(text, Modifier.fillMaxWidth(), fontSize = 18.sp, lineHeight = 25.sp, letterSpacing = (-.2).sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) .5f else .78f))
}

@Composable
private fun TodoRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .55f))
        .pressable(onClickLabel = title, onClick = onClick).padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = Ink) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.25).sp, color = Ink)
            Text(sub, fontSize = 14.sp, lineHeight = 19.sp, color = Ink.copy(alpha = .56f))
        }
    }
}

/** "2026-11-04" → "11/04". */
private fun md(date: LocalDate) = "%02d/%02d".format(date.monthValue, date.dayOfMonth)

@Composable
private fun RecordedCard(turn: Turn.Recorded, today: LocalDate, onUndo: () -> Unit) {
    val cat = catName(turn.category)
    if (turn.undone) return AnswerText("Removed ${turn.name.ifEmpty { cat }} · ${cash(turn.amount)} from your history.")
    Surface(Modifier.fillMaxWidth(), color = if (turn.left >= 0) Color(0xFFC8F2DC) else Color(0xFFFFC2B8), shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recorded · $cat${if (turn.date == today) "" else " · ${md(turn.date)}"}", color = Ink.copy(alpha = .7f), fontSize = 14.sp)
            Text(cash(turn.amount) + if (turn.name.isNotEmpty()) " · ${turn.name}" else "", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
            Text(if (turn.left >= 0) "${cash(turn.left)} left today." else "${cash(-turn.left)} over today’s allowance.", color = Ink, fontSize = 16.sp)
            TextButton(onClick = onUndo, colors = ButtonDefaults.textButtonColors(contentColor = Ink)) { Text("Undo") }
        }
    }
}

@Composable
private fun ReceiptCard(r: Receipt, onSave: () -> Unit) {
    val small = Ink.copy(alpha = .72f)
    Surface(Modifier.fillMaxWidth(), color = Color(0xFFFFF3B8), shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Receipt${if (r.merchant.isNotEmpty()) " · ${r.merchant}" else ""} · ${catName(r.category)} · ${md(r.date)}", color = Ink.copy(alpha = .7f), fontSize = 14.sp)
            Text(cash(r.amountMinor), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
            if (r.items.isNotEmpty()) Text(r.items.take(6).joinToString("\n") { "${it.name} ${cash(it.minor)}" } + if (r.items.size > 6) "\n…" else "", color = small, fontSize = 13.sp)
            if (r.mismatch) Text("The items add up to ${cash(r.itemsMinor)}, not the total. Tax or a service charge can explain that, so check it.", color = small, fontSize = 13.sp)
            if (!r.dateKnown) Text("I couldn’t read the date, so it says today. Change it if needed.", color = small, fontSize = 13.sp)
            r.deviceSource?.let { src ->
                val lead = when (src) { "largest" -> "I couldn’t find a row that says TOTAL, so this is the biggest amount that isn’t cash or change."; "subtotal" -> "Only a SUBTOTAL row was readable."; else -> "From the row" }
                Text("$lead “${r.deviceText}”.${if (r.deviceSure) "" else " Some of that row was hard to read."}", color = small, fontSize = 13.sp)
            }
            Text("Read from a photo, so it can be wrong. Check the amount, then save.", color = small, fontSize = 13.sp)
            Button(onClick = onSave, modifier = Modifier.padding(top = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Text("Check and save") }
        }
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
private fun CheckCard(turn: Turn.Check, state: AllowanceState, result: com.allowance.core.calculator.BudgetResult, open: (String) -> Unit, addGoal: (String, Long) -> Unit,
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
            // What you usually pay for the same thing, from your own history ("You usually pay about RM 4.00 for Chocolate…").
            if (turn.memo.isNotEmpty()) Text(turn.memo, color = Ink.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 18.sp)
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
                TextButton(onClick = { addGoal(turn.name, amount) }, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = Ink)) { Text("Set as goal") }
            } else Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { addGoal(turn.name, amount) }, enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Text("Set as goal") }
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
/** [onCamera] gets true to take a photo, false to choose one. [enabled] is false while the assistant is still answering. */
private fun InputBar(value: String, onValue: (String) -> Unit, onSend: () -> Unit, focus: FocusRequester, enabled: Boolean, onCamera: (Boolean) -> Unit, onVoice: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val canSend = enabled && value.isNotBlank()
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f), CircleShape),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = CircleShape, shadowElevation = 6.dp) {
            Row(Modifier.height(68.dp).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) { Icon(AppIcons.Camera, "Read a receipt photo", Modifier.size(28.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Take a photo of a receipt") }, onClick = { menu = false; onCamera(true) })
                        DropdownMenuItem(text = { Text("Choose a receipt photo") }, onClick = { menu = false; onCamera(false) })
                    }
                }
                BasicTextField(value, onValue, Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(focus).testTag("assistant_input").semantics { contentDescription = "Ask the budget assistant" }, singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text("Ask anything...", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
                            field()
                        }
                    })
                IconButton(onClick = onVoice, enabled = enabled) { Icon(AppIcons.Waveform, "Voice input", Modifier.size(26.dp)) }
                Box(Modifier.size(52.dp).pressable(enabled = canSend, onClickLabel = "Send", onClick = onSend).clip(CircleShape)
                    .background(if (canSend) Yellow else Yellow.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowUp, "Send", Modifier.size(26.dp), tint = Ink)
                }
            }
        }
    }
}
