package com.allowance.core

import com.allowance.core.assistant.AiModel
import com.allowance.core.assistant.Intent
import com.allowance.core.assistant.Reader
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * The language-model layer, without a model. What matters here is the guard rails: the model's form becomes an intent, amounts always come
 * from the text, and anything unusable falls back to null. The same cases as the web app's tests/llm.test.mjs.
 */
class ReaderTest {
    private val today = LocalDate.parse("2026-11-08")
    private fun d(s: String) = LocalDate.parse(s)
    private fun form(action: String = "chat", item: String = "", category: String = "NONE", daysAgo: Int = 0, period: String = "today", reply: String = "",
        extra: Map<String, JsonElement> = emptyMap()) = JsonObject(mapOf("action" to JsonPrimitive(action), "item" to JsonPrimitive(item),
        "category" to JsonPrimitive(category), "days_ago" to JsonPrimitive(daysAgo), "period" to JsonPrimitive(period), "reply" to JsonPrimitive(reply)) + extra)
    private fun intent(f: JsonElement?, text: String) = Reader.toIntent(f, text, today)

    @Test fun `a recorded expense takes its amount from the text and its other fields from the form`() {
        assertEquals(Intent.Expense(300, "Coffee", "FOOD", today), intent(form("record_expense", "coffee", "FOOD"), "spent RM3 on coffee"))
        assertEquals(Intent.Expense(1800, "打车", "TRANSPORT", d("2026-11-07")), intent(form("record_expense", "打车", "TRANSPORT", 1), "昨天打车花了18块"))
    }

    @Test fun `the model can never change an amount - it is not in the form, and a made-up one is ignored`() {
        assertFalse("amount" in Reader.SCHEMA["properties"]!!.jsonObject)
        val sneaky = intent(form("check_purchase", "Sneakers", extra = mapOf("amount" to JsonPrimitive(1))), "Sneakers RM 120") as Intent.Check
        assertEquals(12000L, sneaky.amount)
    }

    @Test fun `no readable price means no money action`() {
        for (a in listOf("record_expense", "check_purchase", "set_goal", "add_wish")) assertNull(intent(form(a, "coffee"), "I bought some coffee"))
    }

    @Test fun `questions about the budget need no price`() {
        assertEquals(Intent.Status, intent(form("status"), "今天还能花多少"))
        assertEquals(Intent.Undo, intent(form("undo"), "oops delete that"))
        assertEquals(Intent.Spending("week", "FOOD"), intent(form("spending", category = "FOOD", period = "week"), "food this week?"))
    }

    @Test fun `a bad category, day or period is repaired instead of trusted`() {
        fun exp(f: JsonObject, text: String) = intent(f, text) as Intent.Expense
        assertEquals("FOOD", exp(form("record_expense"), "spent RM 3 on kopi").category)
        assertEquals("OTHER", exp(form("record_expense"), "spent RM 3 on a thing").category)
        assertEquals(today, exp(form("record_expense", daysAgo = 400), "spent RM 3").date)
        assertEquals(today, exp(form("record_expense", "bubble tea", daysAgo = 1), "earlier I grabbed a bubble tea, 9 bucks").date)
        assertEquals(d("2026-11-05"), exp(form("record_expense", daysAgo = 3), "spent RM 12 on lunch 3 days ago").date)
        assertEquals(d("2026-11-07"), exp(form("record_expense", daysAgo = 0), "昨天打车花了18块").date)
        assertEquals(d("2026-10-30"), exp(form("record_expense", daysAgo = 5), "上周五买了书 30块").date)
        assertEquals(today, exp(form("record_expense", daysAgo = -2), "spent RM 3").date)
        assertEquals("month", (intent(form("spending", period = "decade"), "how much did I spend") as Intent.Spending).period)
    }

    @Test fun `chat keeps only a short reply, and an empty reply is no answer`() {
        assertEquals(Intent.Chat("Hi! Tell me what you spent."), intent(form(reply = "Hi! Tell me what you spent."), "hi"))
        assertEquals(Intent.Chat(""), intent(form(reply = "   "), "hi"))
        assertEquals(160, (intent(form(reply = "x".repeat(500)), "hi") as Intent.Chat).reply.length)
    }

    @Test fun `junk is rejected`() {
        assertNull(intent(null, "x"))
        assertNull(intent(JsonObject(mapOf("action" to JsonPrimitive("launch_missiles"))), "x"))
        assertNull(intent(JsonPrimitive("record_expense"), "spent 3"))
        assertNull(Reader.parseReply("not json at all", "spent RM3", today))
        assertEquals(Intent.Expense(300, "Coffee", "FOOD", today), Reader.parseReply(form("record_expense", "coffee", "FOOD").toString(), "spent RM3 on coffee", today))
    }

    @Test fun `hints from past entries reach the model, and are left out when there are none`() {
        assertEquals(Reader.systemPrompt(today), Reader.systemPrompt(today, ""))
        assertTrue(Reader.systemPrompt(today, "Chocolate -> FOOD, usually 5.00 (3x)").contains("past entries that look related (prefer their category for the same thing): Chocolate -> FOOD"))
        assertFalse(Reader.systemPrompt(today).contains("past entries"))
    }

    // ---------- Ollama ----------
    private val models = listOf(AiModel("qwen3:4b", 2_500_000_000), AiModel("qwen2.5:3b", 1_900_000_000), AiModel("nomic-embed-text:latest", 300_000_000, embed = true),
        AiModel("qwen2.5vl:3b", 3_200_000_000, vision = true), AiModel("llava:7b", 4_700_000_000, vision = true))

    @Test fun `pickModels prefers qwen2,5 for text, never an embedding model, and the qwen vision model for pictures`() {
        val (text, vision) = Reader.pickModels(models)
        assertEquals("qwen2.5:3b", text?.name); assertEquals("qwen2.5vl:3b", vision?.name)
        assertEquals("qwen3:4b", Reader.pickModels(models, wantModel = "qwen3:4b").first?.name)
        assertEquals("qwen2.5:3b", Reader.pickModels(models, wantModel = "nomic-embed-text:latest").first?.name)
        assertEquals("llava:7b", Reader.pickModels(models, wantVision = "llava:7b").second?.name)
        assertEquals(models[1] to null, Reader.pickModels(listOf(models[1])))
        assertEquals(null to null, Reader.pickModels(emptyList()))
    }

    @Test fun `Ollama's model list is read with its capabilities, or from the name when it has none`() {
        val tags = Json.parseToJsonElement("""{"models":[{"name":"qwen2.5:3b","size":1900000000,"capabilities":["completion"]},
            {"name":"qwen2.5vl:3b","size":3200000000,"capabilities":["completion","vision"]},{"name":"llava:7b","size":1},{"name":"nomic-embed-text","size":1}]}""")
        val list = Reader.modelsFrom(tags)
        assertEquals(listOf(false, true, true, false), list.map { it.vision })
        assertEquals(listOf(false, false, false, true), list.map { it.embed })
    }

    // ---------- receipts ----------
    private fun receiptForm(json: String) = Json.parseToJsonElement(json)

    @Test fun `toReceipt keeps what is readable and repairs what is not`() {
        val r = Reader.toReceipt(receiptForm("""{"merchant":"  99 Speedmart ","date":"2026-11-06","total":23.4,"category":"FOOD","items":[{"name":"Milk","price":8.9},{"name":"Bread","price":4.5},{"name":"","price":3},{"name":"Free","price":0}]}"""), today)
        assertEquals("99 Speedmart", r.merchant); assertEquals(d("2026-11-06"), r.date); assertTrue(r.dateKnown)
        assertEquals(2340L, r.amountMinor); assertEquals("FOOD", r.category); assertEquals(2, r.items.size); assertEquals(1340L, r.itemsMinor); assertTrue(r.mismatch)
        val loose = Reader.toReceipt(receiptForm("""{"merchant":"","date":"yesterday-ish","total":12,"category":"NOPE","items":[]}"""), today)
        assertEquals(today, loose.date); assertFalse(loose.dateKnown); assertEquals("OTHER", loose.category); assertFalse(loose.mismatch)
        assertFalse(Reader.toReceipt(receiptForm("""{"total":10,"date":"2030-01-01"}"""), today).dateKnown) // the future is not a purchase date
        assertFalse(Reader.toReceipt(receiptForm("""{"total":10,"date":"2020-01-01"}"""), today).dateKnown) // nor a date years ago
    }

    @Test fun `a receipt without a usable total is refused, never guessed`() {
        for (total in listOf("0", "-5", "null", "\"abc\"", "1e9")) {
            val e = runCatching { Reader.toReceipt(receiptForm("""{"total":$total,"items":[]}"""), today) }.exceptionOrNull()
            assertTrue(total, e?.message?.contains("couldn’t find a total") == true)
        }
        assertTrue(runCatching { Reader.toReceipt(receiptForm("""{"items":[]}"""), today) }.isFailure)
    }

    @Test fun `small gaps between items and the total are not flagged, big ones are`() {
        assertFalse(Reader.toReceipt(receiptForm("""{"total":20,"items":[{"name":"a","price":19.5}]}"""), today).mismatch)
        assertTrue(Reader.toReceipt(receiptForm("""{"total":20,"items":[{"name":"a","price":10}]}"""), today).mismatch)
    }

    @Test fun `the receipt prompt and schema ask for a total paid, in the fixed form`() {
        assertTrue(Regex("final amount PAID.*not a subtotal.*not the change", RegexOption.DOT_MATCHES_ALL).containsMatchIn(Reader.receiptPrompt(today)))
        assertEquals(listOf("merchant", "date", "total", "category", "items"), Reader.RECEIPT_SCHEMA["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `a receipt's category follows what was bought, with the model's pick as the fallback`() {
        assertEquals("FOOD", Reader.toReceipt(receiptForm("""{"merchant":"99 SPEEDMART","total":33.5,"category":"SHOPPING","items":[{"name":"MILO 1KG","price":17.9},{"name":"GARDENIA BREAD","price":4.5}]}"""), today).category)
        assertEquals("TRANSPORT", Reader.toReceipt(receiptForm("""{"merchant":"PETRONAS","total":60,"category":"TRANSPORT","items":[{"name":"RON95","price":60}]}"""), today).category)
        assertEquals("OTHER", Reader.toReceipt(receiptForm("""{"merchant":"X","total":5,"category":"NOPE","items":[{"name":"thing","price":5}]}"""), today).category)
    }

    // ---------- combine: two readers, one decision ----------
    private fun exp(amount: Long = 300) = Intent.Expense(amount, "Coffee", "FOOD", today)
    private fun chk(weak: Boolean = false) = Intent.Check(300, "Coffee", weak)

    @Test fun `combine - a record is written only when both readers say it is one`() {
        assertEquals(exp(), Reader.combine(exp(), exp()))
        assertEquals(chk(), Reader.combine(chk(true), exp()))
        assertEquals(chk(), Reader.combine(exp(), chk()))
        assertEquals(Intent.Chat("Nice!"), Reader.combine(exp(), Intent.Chat("Nice!")))
        assertEquals(chk(), Reader.combine(null, exp()))
        assertEquals(Intent.Status, Reader.combine(exp(), Intent.Status))
    }

    @Test fun `combine - with no model the rules decide alone`() {
        assertEquals(exp(), Reader.combine(exp(), null))
        assertNull(Reader.combine(null, null))
    }

    @Test fun `combine - a specific rule match wins, the weak default can be overruled by the model`() {
        assertEquals(Intent.Status, Reader.combine(Intent.Status, Intent.Spending("today", null)))
        assertEquals(Intent.Undo, Reader.combine(Intent.Undo, Intent.Chat("")))
        assertEquals(chk(), Reader.combine(chk(), Intent.Chat("")))
        assertEquals(Intent.Chat(""), Reader.combine(chk(true), Intent.Chat("")))
        assertEquals(Intent.Status, Reader.combine(null, Intent.Status))
    }

    @Test fun `a spending question gets its period from the rules, never from the model`() {
        fun period(f: JsonObject, text: String) = (intent(f, text) as Intent.Spending).period
        assertEquals("month", period(form("spending", period = "today"), "how much have I spent on food"))
        assertEquals("yesterday", period(form("spending", period = "month"), "what did I spend yesterday"))
        assertEquals("week", period(form("spending", period = "month"), "berapa belanja minggu ini"))
    }

    @Test fun `the words decide the category before the model does, and the model is the fallback`() {
        fun cat(f: JsonObject, text: String) = (intent(f, text) as Intent.Expense).category
        assertEquals("FUN", cat(form("record_expense", "Netflix", "OTHER"), "subscribed to Netflix RM 55"))
        assertEquals("HEALTH", cat(form("record_expense", "thing", "HEALTH"), "spent RM 20 on a thing"))
        assertEquals("OTHER", cat(form("record_expense", "thing", "NONE"), "spent RM 20 on a thing"))
    }
}
