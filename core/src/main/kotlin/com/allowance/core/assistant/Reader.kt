package com.allowance.core.assistant

import kotlinx.serialization.json.*
import java.time.LocalDate

/** One model Ollama has. */
data class AiModel(val name: String, val size: Long = 0, val vision: Boolean = false, val embed: Boolean = false)

data class ReceiptItem(val name: String, val minor: Long)

/** A receipt read from a photo, for the person to check. Nothing is saved from it until they press save. */
data class Receipt(
    val merchant: String, val date: LocalDate, val dateKnown: Boolean, val amountMinor: Long, val category: String,
    val items: List<ReceiptItem> = emptyList(), val itemsMinor: Long = 0, val mismatch: Boolean = false,
    /** Set when the on-device reader found it: which kind of row the total came from ("total", "subtotal", "largest"), that row's text, and whether it read cleanly. */
    val deviceSource: String? = null, val deviceText: String = "", val deviceSure: Boolean = true,
)

/**
 * The language-model half of the assistant, without any networking (that lives in the app): the form the model fills in, how its answer
 * becomes an [Intent], and how the two readers (rules and model) agree. The same rules as the web app's llm.js.
 *
 * What the model does and does not do:
 *   - It READS the sentence and fills in a fixed form (what the person wants, the item, the category, the day). Ollama's `format` keeps the
 *     output valid JSON.
 *   - It never touches an amount. Amounts are read from the text by [Nlu], and if the sentence has no readable price the answer is thrown
 *     away. So a small model that gets it wrong can mislabel a purchase, but it can't invent money.
 *   - If the model is off, slow, or answers nonsense, the caller falls back to the plain rules.
 */
object Reader {
    val ACTIONS = listOf("record_expense", "check_purchase", "status", "spending", "undo", "set_goal", "add_wish", "chat")
    private val PERIODS = listOf("today", "yesterday", "week", "month")

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun str(extra: Map<String, JsonElement> = emptyMap()) = JsonObject(mapOf("type" to JsonPrimitive("string")) + extra)

    val SCHEMA: JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("object"), "additionalProperties" to JsonPrimitive(false),
        "required" to strings(listOf("action", "item", "category", "days_ago", "period", "reply")),
        "properties" to JsonObject(mapOf(
            "action" to str(mapOf("enum" to strings(ACTIONS))),
            "item" to str(mapOf("maxLength" to JsonPrimitive(40))),
            "category" to str(mapOf("enum" to strings(Nlu.CATEGORIES + "NONE"))),
            "days_ago" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
            "period" to str(mapOf("enum" to strings(PERIODS))),
            "reply" to str(mapOf("maxLength" to JsonPrimitive(160))),
        )),
    ))

    fun systemPrompt(today: LocalDate, hints: String = ""): String = """You read messages for a budgeting app. The currency is Malaysian ringgit (RM). Answer with one JSON object and nothing else. Today is $today.
action is one of:
- record_expense: the user says they already spent or paid money ("spent RM3 on coffee", "我今天花了3块买咖啡").
- check_purchase: the user asks if they can afford or should buy something, or just names an item with a price ("Sneakers RM 120", "can I buy a phone for 2000?").
- status: asks how much they can still spend today, or about their budget.
- spending: asks how much they spent (set period to today, yesterday, week or month; set category if one is named).
- undo: wants to cancel or undo the last record.
- set_goal: wants to save up for something.
- add_wish: wants to add something to a wish list.
- chat: anything else, like a greeting or a question about the app. Put one short friendly sentence in reply, in the user's language, and never state any amount of their money.
item is the thing bought, in a few words in the user's language, or "". category is FOOD, TRANSPORT, SHOPPING, FUN, HEALTH, LIVING, OTHER or NONE. days_ago is 0 for today, 1 for yesterday, 2 for the day before. period only matters for spending, otherwise "today". reply is only for chat, otherwise "".
Examples:
"spent RM3 on coffee" -> {"action":"record_expense","item":"Coffee","category":"FOOD","days_ago":0,"period":"today","reply":""}
"昨天打车花了18块" -> {"action":"record_expense","item":"打车","category":"TRANSPORT","days_ago":1,"period":"today","reply":""}
"Sneakers RM 120" -> {"action":"check_purchase","item":"Sneakers","category":"SHOPPING","days_ago":0,"period":"today","reply":""}
"how much did I spend on food this month" -> {"action":"spending","item":"","category":"FOOD","days_ago":0,"period":"month","reply":""}
"今天还能花多少" -> {"action":"status","item":"","category":"NONE","days_ago":0,"period":"today","reply":""}
"oops, delete that" -> {"action":"undo","item":"","category":"NONE","days_ago":0,"period":"today","reply":""}
"hi" -> {"action":"chat","item":"","category":"NONE","days_ago":0,"period":"today","reply":"Hi! Tell me what you spent, or what you want to buy."}
"thanks a lot" -> {"action":"chat","item":"","category":"NONE","days_ago":0,"period":"today","reply":"You're welcome!"}
"今天天气真好" -> {"action":"chat","item":"","category":"NONE","days_ago":0,"period":"today","reply":"是啊！需要记账的话告诉我。"}
"what is a budget?" -> {"action":"chat","item":"","category":"NONE","days_ago":0,"period":"today","reply":"A budget is a plan for how much you can spend. I can check yours if you ask."}""" +
        (if (hints.isNotEmpty()) "\nThe user's own past entries that look related (prefer their category for the same thing): $hints" else "")

    private val Spaces = Regex("\\s+")
    /** Like the web's String(x): numbers and booleans become their text, null becomes "". */
    private fun text(e: JsonElement?): String = when (e) { null, JsonNull -> ""; is JsonPrimitive -> e.content; else -> e.toString() }
    internal fun clean(s: String, max: Int = 40) = s.replace(Spaces, " ").trim().take(max)
    private fun cap(s: String) = if (s.isNotEmpty() && s[0] in 'a'..'z') s[0].uppercaseChar() + s.substring(1) else s
    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { p -> if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull }

    /** The model's form → the same intents [Nlu] produces, or null when it can't be trusted. The amount comes from the text, never from the model. */
    fun toIntent(form: JsonElement?, text: String, today: LocalDate): Intent? {
        val f = form as? JsonObject ?: return null
        val action = (f["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (action !in ACTIONS) return null
        val money = Nlu.findAmount(text)
        val item = cap(clean(text(f["item"]))).ifEmpty { if (money != null) cap(Nlu.nameFrom(money.rest)) else "" }
        val category = text(f["category"]).takeIf { it in Nlu.CATEGORIES }
        return when (action) {
            "record_expense" -> {
                money ?: return null
                // The day is the rules' call too: today, unless the words say yesterday / the day before (the rules know those), or point further back (then the model's count is used).
                val seen = Nlu.dateIn(text, today)
                val raw = (f["days_ago"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull // a whole JSON number, as in the web's Number.isInteger
                val ago = if (raw != null && raw == Math.floor(raw) && raw in 0.0..31.0) raw.toLong() else 0L
                val date = if (seen != today) seen else if (Nlu.FarPast.containsMatchIn(text)) today.minusDays(ago) else today
                // The words decide the category first (like amounts and days); the model's pick is the fallback.
                Intent.Expense(money.amount, item, Nlu.guessCategory(text) ?: category ?: "OTHER", date)
            }
            "check_purchase" -> money?.let { Intent.Check(it.amount, item.ifEmpty { "this" }) }
            "set_goal" -> money?.let { Intent.SetGoal(it.amount, item.ifEmpty { "My goal" }) }
            "add_wish" -> money?.let { Intent.AddWish(it.amount, item.ifEmpty { "Something" }) }
            "status" -> Intent.Status
            "undo" -> Intent.Undo
            // The period is the rules' call, like amounts and days: a small model fills "today" in whenever the question names none.
            "spending" -> Intent.Spending(Nlu.explicitPeriod(text) ?: "month", category ?: Nlu.guessCategory(text))
            else -> Intent.Chat(clean(text(f["reply"]), 160)) // chat: the model's own "this needs no action" (the reply may be empty)
        }
    }

    /** The model's raw reply → an intent, or null when it isn't readable JSON or can't be trusted. */
    fun parseReply(content: String, text: String, today: LocalDate): Intent? =
        runCatching { toIntent(Json.parseToJsonElement(content), text, today) }.getOrNull()

    /**
     * Two readers, one decision. [rules] is [Nlu.interpret]; [model] is the model's reading (null when the model is off, slow or broken).
     *   - A RECORD is a write to your history, so it needs both readers to agree that it is one. If only one thinks so, nothing is written:
     *     you get the "can I buy it?" card instead (which has an "I bought it" button), or whatever the other reader said.
     *   - Everything else only reads or shows a card. A specific rule match is exact, so it wins; when the rules only have their weak default
     *     ("item price" read as a purchase check) or nothing, the model's reading is used, and it can overrule that default.
     *   - No model: the rules alone.
     */
    fun combine(rules: Intent?, model: Intent?): Intent? {
        if (model == null) return rules
        if (rules is Intent.Expense && model is Intent.Expense) return model
        if (rules is Intent.Expense || model is Intent.Expense) {
            val other = if (rules is Intent.Expense) model else rules
            if (other != null && other !is Intent.Check) return other
            val amount = (rules as? Intent.Expense)?.amount ?: (rules as? Intent.Check)?.amount ?: (model as? Intent.Expense)?.amount ?: (model as? Intent.Check)?.amount ?: 0L
            val name = (rules as? Intent.Expense)?.name ?: (rules as? Intent.Check)?.name ?: (model as? Intent.Expense)?.name ?: (model as? Intent.Check)?.name ?: ""
            return Intent.Check(amount, name.ifEmpty { "this" })
        }
        if (rules != null && !(rules is Intent.Check && rules.weak)) return rules
        return model
    }

    // ---------- Ollama's models ----------
    private val VisionName = Regex("(?i)vl\\b|vision|llava|gemma3|minicpm-v|moondream|granite3\\.2-vision")
    private val EmbedName = Regex("(?i)embed")

    /** Ollama's /api/tags answer → the models it has. */
    fun modelsFrom(tags: JsonElement): List<AiModel> = ((tags as? JsonObject)?.get("models") as? JsonArray).orEmpty().mapNotNull { m ->
        val o = m as? JsonObject ?: return@mapNotNull null
        val name = text(o["name"]).ifEmpty { return@mapNotNull null }
        val caps = (o["capabilities"] as? JsonArray)?.map { text(it) }
        AiModel(name, number(o["size"])?.toLong() ?: 0L,
            vision = caps?.contains("vision") ?: VisionName.containsMatchIn(name),
            embed = EmbedName.containsMatchIn(name) || (caps != null && "embedding" in caps && "completion" !in caps))
    }

    /** Which models to use: text = qwen2.5 first (fast, and it follows JSON forms well), then the smallest other text model; vision = the first that can see. */
    fun pickModels(models: List<AiModel>, wantModel: String = "", wantVision: String = ""): Pair<AiModel?, AiModel?> {
        fun by(name: String) = models.find { it.name == name }
        val text = models.filter { !it.vision && !it.embed }
        fun rank(m: AiModel) = when {
            Regex("(?i)qwen2\\.5").containsMatchIn(m.name) -> 0
            Regex("(?i)llama3|gemma|phi|mistral").containsMatchIn(m.name) -> 1
            Regex("(?i)qwen3").containsMatchIn(m.name) -> 2
            else -> 3
        }
        val chosen = by(wantModel)?.takeIf { !it.embed } ?: text.sortedWith(compareBy<AiModel>({ rank(it) }, { it.size })).firstOrNull()
        val qwenVl = Regex("(?i)qwen2\\.5vl")
        val eyes = by(wantVision)?.takeIf { it.vision }
            ?: models.filter { it.vision }.sortedWith(compareBy<AiModel>({ if (qwenVl.containsMatchIn(it.name)) 0 else 1 }, { it.size })).firstOrNull()
        return chosen to eyes
    }

    // ---------- receipts ----------
    val RECEIPT_SCHEMA: JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("object"), "additionalProperties" to JsonPrimitive(false),
        "required" to strings(listOf("merchant", "date", "total", "category", "items")),
        "properties" to JsonObject(mapOf(
            "merchant" to str(mapOf("maxLength" to JsonPrimitive(60))),
            "date" to str(),
            "total" to JsonObject(mapOf("type" to JsonPrimitive("number"))),
            "category" to str(mapOf("enum" to strings(Nlu.CATEGORIES))),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("array"), "maxItems" to JsonPrimitive(12), "items" to JsonObject(mapOf(
                "type" to JsonPrimitive("object"), "additionalProperties" to JsonPrimitive(false), "required" to strings(listOf("name", "price")),
                "properties" to JsonObject(mapOf("name" to str(mapOf("maxLength" to JsonPrimitive(40))), "price" to JsonObject(mapOf("type" to JsonPrimitive("number")))))),
            ))),
        )),
    ))

    fun receiptPrompt(today: LocalDate) = """Read this shop receipt. Answer with one JSON object and nothing else. Today is $today. Currency is Malaysian ringgit (RM).
merchant: the shop name. date: the purchase date as YYYY-MM-DD, or "" if you can't see one. total: the final amount PAID including tax and service charge; not a subtotal, not the cash handed over, not the change. category: FOOD, TRANSPORT, SHOPPING, FUN, HEALTH, LIVING or OTHER. items: up to 12 lines of what was bought, each with its name and price. Use 0 for total if you cannot read it. Never guess a number you can't see."""

    const val NO_TOTAL = "I couldn’t find a total on that receipt. Try a sharper, straight-on photo, or type it."
    private val Iso = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** The vision model's form → a receipt the person can check, or throws when there is no usable total. Nothing is saved from here. */
    fun toReceipt(form: JsonElement?, today: LocalDate): Receipt {
        val f = form as? JsonObject ?: JsonObject(emptyMap())
        val total = number(f["total"])
        require(total != null && total.isFinite() && total > 0 && total < 1e7) { NO_TOTAL }
        val amountMinor = Math.round(total * 100)
        val rawItems = (f["items"] as? JsonArray).orEmpty().map { it as? JsonObject }
        val named = rawItems.mapNotNull { (it?.get("name") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val dateText = (f["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val parsed = if (Iso.matches(dateText)) runCatching { LocalDate.parse(dateText) }.getOrNull() else null
        val dateKnown = parsed != null && parsed <= today && parsed >= today.minusDays(400)
        val items = rawItems.mapNotNull { i ->
            val price = number(i?.get("price")) ?: return@mapNotNull null
            val name = clean(text(i?.get("name")))
            val minor = Math.round(price * 100)
            if (name.isNotEmpty() && price.isFinite() && minor > 0) ReceiptItem(name, minor) else null
        }.take(12)
        val itemsMinor = items.sumOf { it.minor }
        val merchant = clean(text(f["merchant"]), 60)
        // What was bought says more than a small model's guess: bread and chocolate are Food whatever category it picked. The model's pick is the fallback.
        val category = Nlu.guessCategory("$merchant ${named.joinToString(" ")}") ?: text(f["category"]).takeIf { it in Nlu.CATEGORIES } ?: "OTHER"
        return Receipt(merchant, if (dateKnown) parsed!! else today, dateKnown, amountMinor, category, items, itemsMinor,
            items.isNotEmpty() && kotlin.math.abs(itemsMinor - amountMinor) > maxOf(50.0, amountMinor * 0.15))
    }
}
