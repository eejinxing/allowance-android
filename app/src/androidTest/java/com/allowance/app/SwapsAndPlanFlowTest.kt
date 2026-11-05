package com.allowance.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allowance.app.data.AllowanceRepository
import com.allowance.core.ledger.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The swaps, the assistant's pay-back and the calendar marks, driven through the real screens. */
@RunWith(AndroidJUnit4::class)
class SwapsAndPlanFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var repository: AllowanceRepository
    private val today = LocalDate.now()
    private val dayTitle = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

    @Before fun start() {
        // The pay-back spreads over the days left in the month and the calendar test needs tomorrow in this month.
        Assume.assumeTrue("needs a few days left in the month", today.plusDays(5).month == today.month)
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = AllowanceRepository(context)
        runBlocking { repository.update {
            LedgerEngine.configure(AppData(), BudgetSettings("Jin", 120_000, savingsMinor = 20_000), listOf(PlannedBill("b", "Breakfast", 600, "DAILY")), today)
        } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(30_000) { compose.onAllNodesWithText("Available to spend today").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun stop() {
        if (::scenario.isInitialized) scenario.close()
        if (::repository.isInitialized) { runBlocking { repository.update { AppData() } }; repository.close() }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun read() = runBlocking { repository.read() }
    private fun save() { compose.onNodeWithText("Save").performScrollTo().performClick() }
    /** Waits for [condition]; when it never comes true, the screen's semantics tree goes to logcat (tag SwapsFlow) so the failure can be diagnosed. */
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout, condition) } catch (e: Throwable) { compose.onRoot().printToLog("SwapsFlow"); throw e }
    }

    @Test fun swapsPageListsAddsEditsAndDeletesASwap() {
        compose.onNodeWithTag("nav_Swaps").performClick()
        waitFor(15_000) { compose.onAllNodesWithText("BREAD AT HOME").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ADD A SWAP").performScrollTo().performClick()
        // A swap needs a cheaper cost than the usual one.
        field("Name").performTextInput("Cinema")
        field("Cheaper option").performTextInput("Streaming")
        compose.onAllNodes(hasSetTextAction() and hasText("Cost (RM)"))[0].performTextInput("20")
        compose.onAllNodes(hasSetTextAction() and hasText("Cost (RM)"))[1].performTextInput("25")
        save()
        compose.onNodeWithText("The cheaper option has to cost less").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction() and hasText("Cost (RM)"))[1].performTextReplacement("5")
        save()
        waitFor(15_000) { read().swaps.any { it.cheaperName == "Streaming" } }
        val added = read().swaps.last()
        assertEquals("FUN", added.category)
        assertEquals(LedgerEngine.defaultSwaps().size + 1, read().swaps.size)
        compose.onNodeWithText("STREAMING").performScrollTo().performClick()
        compose.onNodeWithText("Delete swap").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        waitFor(15_000) { read().swaps.none { it.cheaperName == "Streaming" } }
        assertEquals(LedgerEngine.defaultSwaps(), read().swaps)
    }

    @Test fun assistantPreviewsASwapAndOnlyBuyingPaysBack() {
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("assistant_input").performTextInput("Sneakers RM 100")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor(20_000) { compose.onAllNodesWithText("PLAN · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val label = "Bread at home instead of Breakfast, saves RM 4.00"
        // The swap icons sit at the bottom of a tall card. performScrollTo only moves the chart's own horizontal scroll, so scroll the page first,
        // the way a finger would: to the card, then up until the icon is on screen.
        val page = compose.onNode(hasScrollToNodeAction())
        page.performScrollToNode(hasText("PLAN · ", substring = true))
        val dot = compose.onAllNodesWithContentDescription(label)[0]
        var swipes = 0
        while (swipes < 5 && !runCatching { dot.assertIsDisplayed() }.isSuccess) { page.performTouchInput { swipeUp() }; swipes++ }
        dot.assertIsDisplayed().performClick()
        waitFor(15_000) { compose.onAllNodesWithText("→ RM 2.00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // A preview: nothing was recorded.
        assertTrue(read().entries.isEmpty())
        assertTrue(read().budgets.all { it.paybacks.isEmpty() })
        compose.onNodeWithText("Buy it, pay back over", substring = true).performScrollTo().performClick()
        waitFor(20_000) { read().entries.any { it.note == "Sneakers" } }
        val month = read().budgets.first { it.month == java.time.YearMonth.from(today).toString() }
        val payback = month.paybacks.single()
        assertEquals(10_000L, payback.amountMinor)
        assertTrue(payback.days.any { d -> d.swaps.any { it.name == "Bread at home" && it.usual == "Breakfast" && it.savingMinor == 400L } })
        // The bill swap keeps the day's own money; the rest of the overspend is trimmed from the plans of the following days.
        assertTrue(month.plans.isNotEmpty())
    }

    @Test fun tappingTheSelectedDayMovesItThroughRedTealYellowAndBack() {
        val tomorrow = today.plusDays(1)
        compose.onNodeWithText("Plan").performClick()
        // The day also appears in the header of the selected day ("... Tap to edit"), so match the grid cell only.
        val cell = hasContentDescription(tomorrow.format(dayTitle), substring = true) and !hasContentDescription("Tap to edit", substring = true)
        waitFor(15_000) { compose.onAllNodes(cell).fetchSemanticsNodes().isNotEmpty() }
        fun mark() = read().budgets.firstOrNull { it.month == java.time.YearMonth.from(tomorrow).toString() }?.marks?.get(tomorrow.toString())
        compose.onNode(cell).performScrollTo().performClick() // selects it
        assertNull(mark())
        compose.onNode(cell).performClick() // red: nothing spent
        waitFor(15_000) { mark() == "RED" }
        assertEquals(0L, read().budgets.first { it.month == java.time.YearMonth.from(tomorrow).toString() }.plans[tomorrow.toString()])
        compose.onNode(cell).performClick() // teal: a starting amount
        waitFor(15_000) { mark() == "TEAL" }
        assertTrue(read().budgets.first { it.month == java.time.YearMonth.from(tomorrow).toString() }.plans.getValue(tomorrow.toString()) > 0)
        compose.onNode(cell).performClick() // yellow
        waitFor(15_000) { mark() == "YELLOW" }
        compose.onNode(cell).performClick() // back to no mark, and the plan goes with it
        waitFor(15_000) { mark() == null }
        assertNull(read().budgets.first { it.month == java.time.YearMonth.from(tomorrow).toString() }.plans[tomorrow.toString()])
    }
}
