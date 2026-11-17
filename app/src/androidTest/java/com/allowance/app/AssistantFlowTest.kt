package com.allowance.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allowance.app.ai.ReceiptScanner
import com.allowance.app.data.AllowanceRepository
import com.allowance.core.ledger.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

/** The assistant's sentence rules and the on-device receipt reader, on a real phone runtime (ICU regex, ONNX Runtime for Android). */
@RunWith(AndroidJUnit4::class)
class AssistantFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var repository: AllowanceRepository
    private val today = LocalDate.now()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun open() {
        repository = AllowanceRepository(context)
        runBlocking { repository.update { LedgerEngine.configure(AppData(), BudgetSettings("Jin", 120_000, savingsMinor = 20_000), emptyList(), today) } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(30_000) { compose.onAllNodesWithText("Available to spend today").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun stop() {
        if (::scenario.isInitialized) scenario.close()
        if (::repository.isInitialized) { runBlocking { repository.update { AppData() } }; repository.close() }
    }

    private fun read() = runBlocking { repository.read() }
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout, condition) } catch (e: Throwable) { compose.onRoot().printToLog("AssistantFlow"); throw e }
    }
    private fun ask(text: String) {
        compose.onNodeWithTag("assistant_input").performTextReplacement(text)
        compose.onNodeWithContentDescription("Send").performClick()
    }

    @Test fun recordsWhatYouSpentInEnglishAndChineseAndUndoesIt() {
        open()
        compose.onNodeWithTag("nav_AI").performClick()
        ask("spent RM3 on coffee")
        waitFor { read().entries.any { it.note == "Coffee" && it.amountMinor == 300L && it.category == "FOOD" } }
        waitFor { compose.onAllNodesWithText("Recorded · Food", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ask("昨天打车花了18块")
        waitFor { read().entries.any { it.note == "打车" && it.amountMinor == 1800L && it.category == "TRANSPORT" && it.date == today.minusDays(1).toString() } || today.dayOfMonth == 1 }
        ask("撤销")
        waitFor { read().entries.none { it.note == "打车" } }
        assertTrue(read().entries.any { it.note == "Coffee" })
        // A number that isn't a price records nothing.
        ask("I walked 3 km today")
        val help = hasText("I can record what you spent", substring = true)
        waitFor { runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(help) }.isSuccess } // the answer may be below the fold
        assertEquals(1, read().entries.size)
    }

    @Test fun theReceiptReaderFindsTheTotalOnThePhone() {
        // A plain printed receipt, drawn the way a till prints one: monospace, dark on light.
        val w = 1080; val h = 1500
        val photo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 25); textSize = 46f; typeface = Typeface.MONOSPACE }
        Canvas(photo).apply {
            drawColor(Color.rgb(242, 240, 235))
            listOf("99 SPEEDMART", "MILO 1KG          17.90", "GARDENIA BREAD     4.50", "NASI LEMAK         7.50", "SUBTOTAL          29.90",
                "SST 6%             1.79", "TOTAL             31.69", "CASH              50.00", "CHANGE            18.31", "THANK YOU")
                .forEachIndexed { i, line -> drawText(line, 90f, 160f + i * 120f, paint) }
        }
        val read = runBlocking { ReceiptScanner(context).scan(photo) }
        assertTrue("rows read: ${read.rows.map { it.text }}", read.rows.size >= 8)
        assertEquals("rows read: ${read.rows.map { it.text }}", 3169L, read.total?.cents)
    }
}
