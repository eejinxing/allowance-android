package com.allowance.core

import com.allowance.core.assistant.Intent
import com.allowance.core.assistant.Nlu
import com.allowance.core.assistant.SpendingTotal
import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.LedgerEntry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** The local language reader: what each kind of sentence turns into. The same cases as the web app's tests/nlu.test.mjs. */
class NluTest {
    private val today = LocalDate.parse("2026-11-08")
    private fun d(s: String) = LocalDate.parse(s)
    private fun read(text: String) = Nlu.interpret(text, today)
    private fun amount(text: String) = when (val i = read(text)) {
        is Intent.Expense -> i.amount; is Intent.Check -> i.amount; is Intent.SetGoal -> i.amount; is Intent.AddWish -> i.amount; else -> null
    }
    private fun date(text: String) = (read(text) as Intent.Expense).date

    @Test fun `recording - spent RM3 on coffee is an expense of 3,00 in Food, dated today`() {
        assertEquals(Intent.Expense(300, "Coffee", "FOOD", today), read("spent RM3 on coffee"))
        assertEquals(Intent.Expense(1250, "Grab", "TRANSPORT", today), read("I paid RM 12.50 for Grab"))
        assertEquals(Intent.Expense(890, "Bubble tea", "FOOD", today), read("bought bubble tea 8.90"))
    }

    @Test fun `recording in Chinese - 块, 毛, Chinese numerals and the date words`() {
        assertEquals(Intent.Expense(300, "咖啡", "FOOD", today), read("我今天花了3块买咖啡"))
        assertEquals(350L, amount("花了三块五买奶茶"))
        assertEquals(1200L, amount("午餐花了十二块"))
        assertEquals(2500L, amount("买了电影票 25令吉"))
        assertEquals(d("2026-11-07"), date("昨天打车花了18块"))
        assertEquals(d("2026-11-06"), date("前天吃了饭 9块"))
    }

    @Test fun `a written currency wins over a stray number, and sen are sen`() {
        assertEquals(700L, Nlu.findAmount("had 2 coffees for RM 7")?.amount)
        assertEquals(50L, Nlu.findAmount("50 sen")?.amount)
        assertEquals(50L, Nlu.findAmount("50仙")?.amount)
        assertEquals(120000L, Nlu.findAmount("RM 1,200")?.amount)
        assertEquals(1250L, Nlu.findAmount("rm12,50")?.amount)
        assertNull(Nlu.findAmount("no price here"))
    }

    @Test fun `a purchase check - questions, wanting, or just item price`() {
        assertEquals(Intent.Check(12000, "Sneakers", weak = true), read("Sneakers RM 120"))
        assertEquals(Intent.Check(12000, "Sneakers"), read("Can I afford sneakers RM 120?"))
        assertTrue(read("I want a new phone RM 3000") is Intent.Check)
        assertTrue(read("我想买耳机 200块") is Intent.Check)
        assertTrue(read("买得起这个游戏吗 80块") is Intent.Check)
    }

    @Test fun `questions about the numbers need no amount`() {
        assertEquals(Intent.Status, read("how much is left today?"))
        assertEquals(Intent.Status, read("how much can I spend today"))
        assertEquals(Intent.Status, read("今天还剩多少"))
        assertEquals(Intent.Spending("month", "FOOD"), read("how much did I spend on food this month"))
        assertEquals(Intent.Spending("today", null), read("how much did I spend today?"))
        assertEquals(Intent.Spending("week", null), read("这周花了多少"))
    }

    @Test fun `an amount in the sentence makes can I spend a purchase, not a status question`() {
        assertTrue(read("can I spend RM 50 today?") is Intent.Check)
    }

    @Test fun `goals, wishes and undo`() {
        assertEquals(Intent.SetGoal(500000, "Japan trip"), read("save for a Japan trip RM 5000"))
        assertEquals(Intent.AddWish(99900, "AirPods"), read("add AirPods RM 999 to my wish list"))
        assertEquals(Intent.Undo, read("undo"))
        assertEquals(Intent.Undo, read("撤销"))
        assertEquals(Intent.Undo, read("delete the last one"))
    }

    @Test fun `what it does not understand stays null, and never invents an amount`() {
        for (s in listOf("", "hello there", "spent a lot on coffee", "今天心情不错")) assertNull(s, read(s))
    }

    @Test fun `categories - the longest keyword wins, nothing matched is null`() {
        assertEquals("FOOD", Nlu.guessCategory("grab food"))
        assertEquals("TRANSPORT", Nlu.guessCategory("grab to office"))
        assertEquals("HEALTH", Nlu.guessCategory("watsons vitamins"))
        assertEquals("LIVING", Nlu.guessCategory("monthly rent"))
        assertNull(Nlu.guessCategory("a surprise"))
    }

    @Test fun `isChinese tells which language to answer in`() {
        assertTrue(Nlu.isChinese("花了3块"))
        assertFalse(Nlu.isChinese("spent 3"))
    }

    @Test fun `spendingTotal adds up the right entries and skips family-paid ones`() {
        var n = 0
        fun entry(date: String, amount: Long, category: String, family: Boolean = false) = LedgerEntry("e${n++}", date, amount, category = category, family = family)
        val data = AppData(entries = listOf(entry("2026-11-08", 300, "FOOD"), entry("2026-11-08", 1000, "TRANSPORT"), entry("2026-11-07", 500, "FOOD"),
            entry("2026-11-02", 700, "FOOD"), entry("2026-10-30", 900, "FOOD"), entry("2026-11-08", 4000, "FOOD", family = true)), swaps = emptyList())
        assertEquals(SpendingTotal(1300, 2), Nlu.spendingTotal(data, "today", null, today))
        assertEquals(SpendingTotal(500, 1), Nlu.spendingTotal(data, "yesterday", null, today))
        assertEquals(SpendingTotal(2500, 4), Nlu.spendingTotal(data, "week", null, today)) // 11-02 to 11-08 is seven days; 10-30 is out
        assertEquals(SpendingTotal(1500, 3), Nlu.spendingTotal(data, "month", "FOOD", today))
    }

    @Test fun `the chocolate sentences - record it, or ask whether you can buy it, with a clean item name`() {
        assertEquals(Intent.Expense(800, "巧克力", "FOOD", today), read("今天我花了8块钱买巧克力"))
        assertEquals(Intent.Check(800, "巧克力"), read("你看一下我8块钱可不可以买巧克力"))
        assertEquals(Intent.Check(800, "巧克力"), read("我8块钱可不可以买巧克力"))
        assertEquals(Intent.Check(800, "Chocolate"), read("can I buy chocolate for 8 ringgit?"))
    }

    // ---------- found by the web app's evaluation: each of these was a real wrong answer ----------
    @Test fun `a number that is not a price is not read as one`() {
        for (s in listOf("I walked 3 km today", "I slept 8 hours last night", "the meeting is at 3pm", "I have 2 dogs and 1 cat", "my phone number is 0123456789",
            "I got 100% on my exam", "spent 3 hours on this report", "我昨天睡了8个小时", "会议下午3点开始", "我今年25岁", "it is 32 degrees today", "room 305 please",
            "bus 12 was late again", "3 people joined the call", "the file is 12 MB", "level 20 reached", "call me at 5", "the score was 3 to 1",
            "I woke up at 3 this morning")) assertNull(s, read(s))
    }

    @Test fun `a bare number that IS a price still works, including before on or for and with a date word after it`() {
        assertEquals(1500L, amount("spent 15 on parking"))
        assertEquals(500L, amount("paid 5 for the toll"))
        assertEquals(3000L, amount("yesterday I paid 30 for the movie"))
        assertEquals(450L, amount("got coffee for 4.50 this morning"))
        assertEquals(45000L, amount("Sneakers 450"))
        assertEquals(45000L, amount("should I buy sneakers at 450?"))
    }

    @Test fun `spelled-out numbers next to a currency word, and the days the rules can work out`() {
        assertEquals(2000L, amount("I paid twenty ringgit for lunch"))
        assertEquals(5000L, amount("spent fifty bucks on a gift"))
        assertEquals(25000L, amount("paid two hundred and fifty dollars for rent"))
        assertEquals(d("2026-11-06"), date("spent RM 12 on lunch last friday")) // 2026-11-08 is a Sunday: the most recent Friday
        assertEquals(d("2026-11-05"), date("spent RM 12 on lunch 3 days ago"))
        assertEquals(d("2026-10-30"), date("上周五买了书 30块"))                   // Friday of last Monday-to-Sunday week
    }

    @Test fun `past-tense wording, and Malay`() {
        for (s in listOf("ate nasi kandar for 12 ringgit", "tapau mee goreng RM 7", "took the LRT, RM 3.20", "tadi beli kopi RM3", "Grab to the office was RM 14"))
            assertTrue(s, read(s) is Intent.Expense)
        assertTrue(read("boleh beli kasut RM 200?") is Intent.Check)
        assertEquals(Intent.Spending("today", null), read("berapa saya belanja hari ini"))
        assertEquals(Intent.Undo, read("padam yang tadi"))
        assertEquals(Intent.Status, read("baki hari ini"))
    }

    @Test fun `questions about the budget - mine yes, what is a budget no, what is left is not what did I spend`() {
        assertEquals(Intent.Status, read("am I still within budget?"))
        assertEquals(Intent.Status, read("what is my budget for today?"))
        assertNull(read("what is a budget?"))
        assertEquals(Intent.Status, read("还能花多少钱"))
        assertTrue(read("我这个月花了多少") is Intent.Spending)
        assertEquals("yesterday", (read("what did I spend yesterday") as Intent.Spending).period)
        assertEquals(Intent.Undo, read("wrong, remove it"))
        assertTrue(read("I want to save RM 2000 for a laptop") is Intent.SetGoal)
    }

    @Test fun `category words also match their plurals`() {
        assertEquals("FUN", Nlu.guessCategory("two movies"))
        assertEquals("SHOPPING", Nlu.guessCategory("new shoes"))
        assertEquals("FOOD", Nlu.guessCategory("3 ice creams"))
    }

    @Test fun `Chinese next to English words still finds the English keyword`() {
        // A Chinese character is not a word letter on the web, so "coffee" inside 买coffee is a whole word there; it must be here too.
        assertEquals("FOOD", Nlu.guessCategory("买coffee"))
    }
}
