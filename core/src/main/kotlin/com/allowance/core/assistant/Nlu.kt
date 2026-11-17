package com.allowance.core.assistant

import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.EntryType
import java.time.LocalDate

/**
 * What one line means, with amounts in sen. Produced by the rules ([Nlu.interpret]) and by the language model ([Reader.toIntent]).
 *   Expense   "spent RM3 on coffee"
 *   Check     "can I afford sneakers RM 120?", or just "Sneakers RM 120" (then [Check.weak]: a default reading a second reader may overrule)
 *   SetGoal / AddWish   "save for a phone RM 3000", "add AirPods RM 999 to my wish list"
 *   Spending  "how much did I spend on food this month"
 *   Status    "how much is left today"
 *   Undo      "undo", "撤销"
 *   Chat      the model's "this needs no action", with a short reply
 */
sealed interface Intent {
    data class Expense(val amount: Long, val name: String, val category: String, val date: LocalDate) : Intent
    data class Check(val amount: Long, val name: String, val weak: Boolean = false) : Intent
    data class SetGoal(val amount: Long, val name: String) : Intent
    data class AddWish(val amount: Long, val name: String) : Intent
    data class Spending(val period: String, val category: String?) : Intent
    data object Status : Intent
    data object Undo : Intent
    data class Chat(val reply: String) : Intent
}

/** An amount found in a sentence: [money] is true when a currency word or unit was found; [rest] is the text with the amount cut out. */
data class FoundAmount(val amount: Long, val money: Boolean, val rest: String)

data class SpendingTotal(val total: Long, val count: Int)

/**
 * Reads one line of everyday language and says what it means: "spent RM3 on coffee", "我今天花了3块买咖啡", "can I afford sneakers RM 120?",
 * "how much is left today". English and Chinese, plus a small set of everyday Malay words (beli, bayar, baki, padam, boleh…): enough for short
 * sentences, not a Malay reader.
 * Free by design: no AI service, no network, no key. It is a set of rules, so it only knows the sentence shapes below.
 * Its one firm promise is about money: an amount must be WRITTEN in the sentence, and a bare number only counts as a price when nothing in the
 * sentence says it is something else ("3 km", "100%", "bus 12", a phone number). When unsure it does nothing.
 * The same rules as the web app's nlu.js, so both apps read a sentence the same way.
 */
object Nlu {
    val CATEGORIES = listOf("FOOD", "TRANSPORT", "SHOPPING", "FUN", "LIVING", "HEALTH", "OTHER")

    private val Han = Regex("[㐀-鿿]")
    fun isChinese(text: String) = Han.containsMatchIn(text)

    // ---------- money ----------
    private val CnDigit = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 三十五 → 35, 十二 → 12, 两百 → 200. */
    private fun cnNumber(text: String): Int {
        var total = 0; var cur = 0
        for (ch in text) {
            val d = CnDigit[ch]
            if (d != null) cur = d
            else if (ch == '十') { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
            else if (ch == '百') { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
        }
        return total + cur
    }

    // English number words, 1 to 999, only when a currency word follows ("twenty ringgit", "fifty bucks", "two hundred and fifty dollars").
    private val EnOnes = linkedMapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19)
    private val EnTens = linkedMapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val EnWord = "(?:${(EnOnes.keys + EnTens.keys + listOf("hundred", "a hundred")).joinToString("|")})"
    private val EnNumber = jsRegex("""\b($EnWord(?:[\s-]+(?:and\s+)?$EnWord)*)\s+(?=(?:ringgit|bucks?|dollars?|rm|myr)\b)""", true)

    /** Null when a word isn't a number word. */
    private fun enNumber(words: String): Int? {
        var total = 0; var cur = 0
        for (w in words.lowercase().split(Regex("[\\s-]+"))) {
            when {
                w in EnOnes -> cur += EnOnes.getValue(w)
                w in EnTens -> cur += EnTens.getValue(w)
                w == "hundred" -> cur = (if (cur == 0) 1 else cur) * 100
                w == "a" -> cur = 1
                w != "and" -> return null
            }
            if (cur >= 100) { total += cur; cur = 0 }
        }
        return total + cur
    }

    private val CnBeforeUnit = Regex("([零一二两三四五六七八九十百]+)(?=\\s*(?:块|元|令吉|仙))")
    private val CnAfterUnit = Regex("(块|元)([一二两三四五六七八九])(?![零一二两三四五六七八九十\\d])")

    /** "三块五" → "3块5", "十二块" → "12块", "twenty ringgit" → "20 ringgit", so the amount rules below only have to read digits. */
    private fun digitise(text: String): String = text
        .replace(CnBeforeUnit) { cnNumber(it.value).toString() }
        .replace(CnAfterUnit) { it.groupValues[1] + CnDigit.getValue(it.groupValues[2][0]) }
        .replace(EnNumber) { m -> enNumber(m.groupValues[1])?.takeIf { it > 0 }?.let { "$it " } ?: m.value }

    private val Thousands = Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val PlainMoney = Regex("^-?\\d+(\\.\\d{1,2})?$")

    /** "1,200" is a thousand; "12,50" is a decimal comma. Null when it isn't a usable positive amount (the same checks as entering one by hand). */
    private fun toMinor(raw: String): Long? {
        val t = (if (Thousands.matches(raw)) raw.replace(",", "") else raw.replaceFirst(',', '.')).trim().replace(",", "")
        if (!PlainMoney.matches(t)) return null
        val whole = t.substringBefore('.').toLongOrNull() ?: return null
        val frac = (t.substringAfter('.', "") + "00").take(2).toLong()
        if (whole > 1_000_000_000L) return null
        val minor = whole * 100 + frac
        return minor.takeIf { it in 1..100_000_000_000L }
    }

    private const val NUM = """\d[\d,]*(?:\.\d{1,2})?"""

    private class AmountRule(val re: Regex, val minor: (MatchResult) -> Long?)

    // Most specific first, so "RM 7" wins over the "2" in "2 coffees". All of these count as a written currency.
    private val Amounts = listOf(
        AmountRule(Regex("""(?i)(\d+(?:\.\d+)?)\s*(?:sen|仙)(?![a-z])""")) { m -> Math.round(m.groupValues[1].toDouble()).takeIf { it > 0 } },
        AmountRule(Regex("""(\d+(?:[.,]\d{1,2})?)\s*(?:块|元|令吉)\s*(\d{1,2})?\s*(?:毛|角)?""")) { m ->
            val base = toMinor(m.groupValues[1]) ?: return@AmountRule null
            val small = m.groupValues[2]
            if (small.isEmpty()) base else base + if (small.length == 1) small.toLong() * 10 else small.toLong()
        },
        AmountRule(Regex("""(?i)(?:rm|myr)\s*($NUM)""")) { toMinor(it.groupValues[1]) },
        AmountRule(Regex("""(?i)($NUM)\s*(?:rm|myr|ringgit|bucks?|dollars?)(?![a-z])""")) { toMinor(it.groupValues[1]) },
    )

    // A number with no currency word ("Sneakers 450") is only a price when nothing says it is something else. What may FOLLOW it is a short
    // list (the end, a question particle, politeness, or on/for/to/at: "spent 15 on parking"); anything else after it ("3 km", "2 dogs",
    // "12 MB", "岁", "点") means it counts something other than money. A list of what is allowed generalises better than every unit there is.
    private val BareAfter = jsRegex("""^\s*(?:$|[?？!！.,，。;:)”"'’]|(?:on|for|to|at|towards)\b(?!\s*\d)|(?:worth|only|each|total|today|tonight|now|please|pls|lah|leh|ya|ok|okay|thanks|thx|just now|tadi|ke|kan)\b|[吗嘛呢吧啊么呀哦]|能不能|可不可以|行不行|好不好|够不够|买得起|可以吗|够吗|行吗)""", true)
    // Words that name a number rather than a price when they come right before it: "room 305", "bus 12", "level 20", "page 4", "#7".
    private val BareLabel = jsRegex("""(?:\b(?:room|level|bus|table|number|no\.?|route|gate|flight|unit|floor|chapter|page|version|ver|seat|lot|bay|ticket|order|id|line|exit|track|platform|lane)|#|第)\s*$""", true)
    // "at 5", "by 8", "until 10": a clock time, not a price (only small whole numbers; "sneakers at 450" is still a price).
    private val TimeBefore = jsRegex("""\b(?:at|by|until|till|since|from|before|after)\s*$""", true)
    // "3 to 1", "10-5", "3:1", "5/6": the second number of a score, ratio, range or time, not a price.
    private val RatioBefore = Regex("""(?i)\d\s*(?:to|vs\.?|v|:|-|/|x)\s*$""")
    // After a date word ("4.50 this morning") the number only counts as a price if a price word comes right before it.
    private val PriceBefore = jsRegex("""\b(?:for|cost|costs|costing|worth|total|paid|pay|spent|spend|bought)\s*$""", true)
    private val BareNumber = Regex("""(\d+(?:[.,]\d{1,2})?)""")
    private val EndsInNumberPart = Regex("""[\d.,]$""")
    private val StartsWithDigit = Regex("""^[.,]?\d""")
    private val LeadingZero = Regex("""^0\d""")
    private val EndsInLetter = Regex("""(?i)[a-z]$""")
    private val SmallWhole = Regex("""^\d{1,2}$""")

    private fun bareAmount(t: String): Triple<Long, Int, Int>? {
        for (m in BareNumber.findAll(t)) {
            val raw = m.groupValues[1]; val start = m.range.first; val end = m.range.last + 1
            val before = t.substring(0, start); val after = t.substring(end)
            if (EndsInNumberPart.containsMatchIn(before) || StartsWithDigit.containsMatchIn(after)) continue      // inside a longer number
            if (LeadingZero.containsMatchIn(raw) || raw.count(Char::isDigit) > 7) continue                       // a phone number, not RM 123 million
            if (EndsInLetter.containsMatchIn(before)) continue                                                    // glued to letters: "v2", "mp3", "iphone15"
            val bare = after.replace(DateWords, " ")                                                              // what follows once date words are set aside
            if (BareLabel.containsMatchIn(before) || !BareAfter.containsMatchIn(bare)) continue
            if (bare != after && !PriceBefore.containsMatchIn(before)) continue
            if (RatioBefore.containsMatchIn(before)) continue
            if (TimeBefore.containsMatchIn(before) && SmallWhole.matches(raw) && raw.toInt() <= 24) continue
            val amount = toMinor(raw) ?: continue
            return Triple(amount, start, end - start)
        }
        return null
    }

    /** The amount written in [text], or null when there is no usable one. */
    fun findAmount(text: String): FoundAmount? {
        val t = digitise(text)
        for (a in Amounts) {
            val m = a.re.find(t) ?: continue
            val amount = a.minor(m) ?: continue
            return FoundAmount(amount, true, t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
        }
        val (amount, index, length) = bareAmount(t) ?: return null
        return FoundAmount(amount, false, t.substring(0, index) + " " + t.substring(index + length))
    }

    private fun hasMoneyWord(text: String): Boolean {
        val t = digitise(text)
        return Amounts.any { a -> a.re.find(t)?.let { a.minor(it) != null } == true }
    }

    // ---------- categories ----------
    // The longest keyword that matches wins, so "grab food" is Food even though "grab" alone is Transport.
    private val Keywords = listOf(
        "FOOD" to jsRegex("""\b(?:food|lunch|dinner|breakfast|brunch|supper|snacks?|meals?|eat|ate|drinks?|coffee|kopi|teh|tea|latte|boba|bubble tea|milk tea|nasi\w*|mee|roti\w*|mamak|restaurant|cafe|groceries|grocery|mcd|kfc|burger|pizza|chicken rice|grab ?food|foodpanda|chocolates?|candy|sweets|ice creams?|bread|cake|dessert|noodles?|makan|minum|sarapan|makanan)(?:s|es)?\b|吃|喝|饭|面|餐|咖啡|奶茶|茶|早餐|午餐|晚餐|宵夜|零食|饮料|外卖|美食|食物|水果|巧克力|糖果|面包|蛋糕|冰淇淋|甜点""", true),
        "TRANSPORT" to jsRegex("""\b(?:bus|train|mrt|lrt|monorail|grab|taxi|cab|uber|ride|petrol|fuel|parking|toll|tng|touch n go|flight|ferry|transport)(?:s|es)?\b|地铁|公交|巴士|车费|打车|停车|过路费|机票|交通|油费|加油""", true),
        "SHOPPING" to jsRegex("""\b(?:shirt|shoes?|sneakers?|clothes|bag|shop|shopee|lazada|mall|uniqlo|shopping|phone case|gadget)(?:s|es)?\b|衣服|鞋|包包|购物|网购|买东西""", true),
        "FUN" to jsRegex("""\b(?:movie|cinema|games?|karaoke|bowling|concert|netflix|spotify|fun|party|steam)(?:s|es)?\b|电影|游戏|唱歌|娱乐|演唱会""", true),
        "HEALTH" to jsRegex("""\b(?:pharmacy|clinic|doctor|medicine|medical|hospital|dentist|gym|vitamins?|watsons)(?:s|es)?\b|药|医院|诊所|看病|牙医|健身""", true),
        "LIVING" to jsRegex("""\b(?:rent|electric|electricity|water bill|utilities|internet|wifi|laundry|detergent|household|bil|sewa)(?:s|es)?\b|房租|水电|电费|水费|网费|洗衣|日用""", true),
    )

    /** The best-matching category name, or null when nothing in the text points to one. */
    fun guessCategory(text: String): String? {
        var best: String? = null; var len = 0
        for ((cat, re) in Keywords) for (m in re.findAll(text)) if (m.value.length > len) { best = cat; len = m.value.length }
        return best
    }

    // ---------- dates ----------
    /** Wording that points to a day further back than yesterday, which only the language model is asked to work out ("3 days ago", "上周"). */
    val FarPast = jsRegex("""\b\d{1,2}\s*days?\s*ago\b|\blast\s+(?:week|month|night|mon|tue|wed|thu|fri|sat|sun)\w*|\b(?:mon|tues?|wednes|thurs?|fri|satur|sun)day\b|\d{1,2}\s*天前|前\d{1,2}天|上(?:周|个?星期|个?礼拜)|星期[一二三四五六日天]|周[一二三四五六日]""", true)
    private val WdEn = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7)
    private val WdZh = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val DayBefore = Regex("(?i)day before yesterday|前天")
    private val Yesterday = Regex("(?i)yesterday|last night|昨天|昨日|昨晚|semalam")
    private val DaysAgo = jsRegex("""\b(\d{1,2})\s*days?\s*ago\b|(\d{1,2})\s*天前|前\s*(\d{1,2})\s*天""", true)
    private val LastWeekday = jsRegex("""\blast\s+(mon|tue|wed|thu|fri|sat|sun)[a-z]*""", true)
    private val LastWeekZh = Regex("上(?:个)?(?:周|星期|礼拜)([一二三四五六日天])")

    /** The day a sentence is about: yesterday, the day before, "N days ago", "last friday" (the most recent one), "上周五" (that day of last Monday-to-Sunday week). Today otherwise. */
    fun dateIn(text: String, today: LocalDate): LocalDate {
        if (DayBefore.containsMatchIn(text)) return today.minusDays(2)
        if (Yesterday.containsMatchIn(text)) return today.minusDays(1)
        DaysAgo.find(text)?.let { n ->
            val k = n.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt() ?: 0
            if (k in 1..31) return today.minusDays(k.toLong())
        }
        val dow = today.dayOfWeek.value
        LastWeekday.find(text)?.let { m ->
            val back = (dow - WdEn.getValue(m.groupValues[1].lowercase()) + 7) % 7
            return today.minusDays((if (back == 0) 7 else back).toLong())
        }
        LastWeekZh.find(text)?.let { m -> return today.minusDays((dow - 1 + 7 - (WdZh.getValue(m.groupValues[1][0]) - 1)).toLong()) }
        return today
    }

    /** Date words, so they don't end up inside an item's name ("Lunch last friday"). */
    private val DateWords = jsRegex("""\blast\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*|\b\d{1,2}\s*days?\s*ago\b|\bday before yesterday\b|\bthis (?:morning|afternoon|evening)\b|\blast night\b|\bjust now\b|\bearlier\b|\byesterday\b|\btonight\b|\btoday\b|\d{1,2}\s*天前|前\s*\d{1,2}\s*天|上(?:个)?(?:周|星期|礼拜)[一二三四五六日天]|早上|中午|晚上|semalam""", true)

    // ---------- naming ----------
    private val FillerEn = jsRegex("""\b(?:i|i'm|im|i've|ive|my|me|just|today|yesterday|tonight|now|spent|spend|spending|paid|pay|bought|buy|ordered|order|had|got|get|used|cost|costs|on|for|at|to|a|an|the|of|rm|myr|ringgit|sen|can|could|should|may|afford|want|wanna|need|plan|planning|thinking|is|it|its|ok|okay|worth|add|record|log|put|please|with|in|this|that|and|some|about|around|only|already|just now|was|were|do|have|enough|too|much|still|beli|membeli|bayar|membayar|untuk|tadi|baru|sudah|dah|ke|yang|saya|aku|boleh|bolehkah|belanja|habis|guna|makan|minum|kat|di|dengan)\b""", true)
    private val FillerZh = Regex("刚|给|充了|充值|可不可以|行不行|好不好|你看一下|帮我看一下|帮我看|看一下|看看|你看|请问|告诉我|我|今天|今日|昨天|昨日|昨晚|前天|刚刚|刚才|花了|花|买得起|买了|买|付了|付|用了|吃了|喝了|消费|想|要|打算|可以|能不能|不能|够不够|吗|嘛|呢|啊|吧|的|了|在|去|一杯|一个|一份|一碗|一次|记一下|记录|记账|记下|块钱|块|元|令吉|仙|钱|帮我|请|毛|角")
    private val Punctuation = Regex("[?？!！.,，。、:;\"“”'’()]")
    private val Spaces = Regex("\\s+")
    private val AsciiLetter = Regex("(?i)[a-z]")

    fun nameFrom(rest: String): String {
        val name = rest.replace(DateWords, " ").replace(FillerEn, " ").replace(FillerZh, " ").replace(Punctuation, " ").replace(Spaces, " ").trim()
        return if (name.isNotEmpty() && AsciiLetter.matches(name.substring(0, 1))) name[0].uppercaseChar() + name.substring(1) else name
    }

    // ---------- what is being asked ----------
    private val UndoRe = jsRegex("""^(?:please\s+)?(?:undo|oops|cancel that|(?:delete|remove|cancel)\s+(?:that|it|this|the last(?: one)?|last(?: one)?))\b|\btake (?:that|it) back\b|\b(?:wrong|mistake|oops|sorry)\b.*\b(?:delete|remove|cancel|undo)\b|\b(?:padam|batal|buang)\b|撤销|撤回|删掉刚|删除刚|取消刚|记错了|刚才.*(?:错|不要)""", true)
    private val WishRe = Regex("(?i)wish\\s?list|愿望|心愿")
    private val GoalRe = jsRegex("""\b(?:save(?:\s+up)?\s+for|saving\s+for|goal|want to save)\b|\bsav(?:e|ing)\b.*\bfor\b|存钱买|攒钱|目标""", true)
    // "what is still ahead of me": asked before the "what did I spend" patterns, because 还能花多少 contains both 花 and 多少.
    private val StatusNow = jsRegex("""还能|能花|可以花|还可以花|还剩|剩多少|剩下|\bcan i (?:still )?spend\b|\bhow much (?:more )?(?:left|can i|do i have|is left)\b|berapa lagi|\bbaki\b|tinggal berapa""", true)
    private val SpendingRe = jsRegex("""\b(?:how much|total|summary|breakdown|report)\b.*\b(?:spent|spending|did i spend|have i spent|do i spend)\b|\b(?:spent|spending)\b.*\b(?:how much|total)\b|\bwhat (?:did|have|do) i (?:spend|spent)\b|\b(?:show|see|check)\b.*\bspending\b|\bspending\b.*\b(?:summary|report|so far)\b|\bmy spending\b|berapa.*(?:belanja|habis|guna)|(?:belanja|habis).*berapa|多少.*(?:花|消费|支出)|(?:花|消费|支出).*多少|总共|统计|总结""", true)
    private val StatusRe = jsRegex("""\b(?:how much|what)\b.*\b(?:left|remain\w*|have|can i spend|allowed)\b|\b(?:left|remaining)\b.*\b(?:today|now)\b|\btoday['’]?s?\b.*\b(?:budget|allowance)\b|\b(?:my|the|remaining|over|under)\s+(?:budget|allowance)\b|\b(?:budget|allowance)\s+(?:left|today|remaining)\b|\b(?:within|over|under|on)\s+(?:my\s+)?budget\b|\bstatus\b|预算|额度|余额""", true)
    private val QuestionRe = jsRegex("""\?|？|\b(?:can i|could i|should i|may i|afford|worth it|ok to|okay to|is it ok|do i|boleh|bolehkah|patut|mampu)\b|吗|嘛|能不能|可不可以|可以买|买得起|够不够|想买|要买|打算买|\bwant\b|\bwanna\b|\bplan(?:ning)? to\b|\bthinking of\b""", true)
    // Words that say the money is already gone: a past-tense verb, a time that has passed, or the Malay equivalents.
    private val RecordRe = jsRegex("""\b(?:spent|paid|bought|ordered|had|got|used|cost|record|log|add|was|were|just now|earlier|this (?:morning|afternoon|evening)|last night|yesterday|topped up|top up|ate|eaten|drank|took|grabbed|tapau|takeaway|takeout|booked|subscribed|renewed|donated|tipped|rented|beli|membeli|bayar|membayar|belanja|makan|minum|tadi|sudah|dah|semalam)\b|花了|买了|付了|用了|吃了|喝了|消费了|刚刚|刚才|充了|充值|昨天|昨日|昨晚|前天|记一下|记录|记账|记下|添加""", true)
    private val WishFiller = jsRegex("""\b(?:my|to|add|put)\b""", true)

    private val PeriodYesterday = Regex("(?i)yesterday|昨天|昨日|semalam")
    private val PeriodToday = Regex("(?i)today|今天|今日|hari ini")
    private val PeriodWeek = jsRegex("""\bweek\b|7 days|seven days|这周|本周|这个星期|一周|这星期|minggu ini""", true)
    private val PeriodMonth = jsRegex("""\bmonth\b|这个月|本月|bulan ini""", true)

    /** The period a spending question names, or null when it names none. Decided by the rules, like amounts and days, not by the model. */
    fun explicitPeriod(text: String): String? = when {
        PeriodYesterday.containsMatchIn(text) -> "yesterday"
        PeriodToday.containsMatchIn(text) -> "today"
        PeriodWeek.containsMatchIn(text) -> "week"
        PeriodMonth.containsMatchIn(text) -> "month"
        else -> null
    }

    /** What one line means, or null when it isn't understood. */
    fun interpret(input: String?, today: LocalDate): Intent? {
        val text = input.orEmpty().trim()
        if (text.isEmpty()) return null
        if (UndoRe.containsMatchIn(text)) return Intent.Undo
        val found = findAmount(text)
        val named = hasMoneyWord(text)
        // Questions about the numbers. A written amount ("can I spend RM 50") makes it a purchase check instead.
        if (!named && StatusNow.containsMatchIn(text)) return Intent.Status
        if (!named && SpendingRe.containsMatchIn(text)) return Intent.Spending(explicitPeriod(text) ?: "month", guessCategory(text))
        if (!named && StatusRe.containsMatchIn(text)) return Intent.Status
        if (found == null) return null
        val name = nameFrom(found.rest)
        if (WishRe.containsMatchIn(text)) return Intent.AddWish(found.amount, nameFrom(found.rest.replaceFirst(WishRe, " ").replace(WishFiller, " ")).ifEmpty { "Something" })
        if (GoalRe.containsMatchIn(text)) return Intent.SetGoal(found.amount, nameFrom(found.rest.replaceFirst(GoalRe, " ")).ifEmpty { "My goal" })
        if (QuestionRe.containsMatchIn(text)) return Intent.Check(found.amount, name.ifEmpty { "this" })
        if (RecordRe.containsMatchIn(text)) return Intent.Expense(found.amount, name, guessCategory(text) ?: "OTHER", dateIn(text, today))
        // "Sneakers RM 120" on its own has always meant: can I buy it? It is a default, not a sure reading, so it is marked weak.
        return Intent.Check(found.amount, name.ifEmpty { "this" }, weak = true)
    }

    /** The money spent in a period, optionally for one category. Family-paid entries are left out, like the daily budget does. */
    fun spendingTotal(data: AppData, period: String, category: String?, today: LocalDate): SpendingTotal {
        val from = when (period) {
            "today" -> today; "yesterday" -> today.minusDays(1); "week" -> today.minusDays(6); else -> today.withDayOfMonth(1)
        }.toString()
        val to = if (period == "yesterday") from else today.toString()
        val rows = data.entries.filter { it.type == EntryType.EXPENSE && !it.family && it.date >= from && it.date <= to && (category == null || it.category == category) }
        return SpendingTotal(rows.sumOf { it.amountMinor }, rows.size)
    }
}
