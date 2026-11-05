package com.allowance.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.allowance.app.data.AllowanceRepository
import com.allowance.app.data.AppPreferences
import com.allowance.core.ledger.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var repository: AllowanceRepository
    private val today = LocalDate.now()

    @Before fun start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = AllowanceRepository(context)
        runBlocking { repository.update {
            LedgerEngine.configure(AppData(), BudgetSettings("Jin", 120_000, savingsMinor = 20_000), emptyList(), today)
                .copy(accounts = listOf(WalletAccount("bank", "Bank", openingMinor = 100_000), WalletAccount("cash", "Cash", "CASH", 10_000)),
                    entries = listOf(LedgerEntry("breakfast", today.toString(), 450, note = "Breakfast"),
                        LedgerEntry("ride", today.toString(), 700, category = "TRANSPORT", note = "Ride")),
                    funds = listOf(ShoppingFund("shoes", "New sneakers", 8_000)), goals = listOf(Goal("trip", "Japan trip", 500_000)))
        } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Available to spend today").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun stop() { if (::scenario.isInitialized) scenario.close(); if (::repository.isInitialized) repository.close() }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun read() = runBlocking { repository.read() }
    private fun waitForEntry(note: String) = compose.waitUntil(15_000) { read().entries.any { it.note == note } }
    private fun save() { compose.onNodeWithText("Save").performScrollTo().performClick() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun addExpensePersistsAcrossRecreationAndCanBeDeleted() {
        compose.onNodeWithContentDescription("Add entry").performClick()
        field("Amount (RM)").performTextInput("12.50")
        field("Note").performTextInput("Lunch smoke")
        compose.onNodeWithText("Save").performScrollTo()
        compose.onNodeWithText("Bank").performClick()
        screenshot("entry")
        save()
        waitForEntry("Lunch smoke")
        assertEquals(98_750, LedgerEngine.accountBalance(read(), read().accounts.first { it.id == "bank" }))
        scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Available to spend today").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page").performScrollToNode(hasText("Lunch smoke"))
        screenshot("today-recorded")
        compose.onNodeWithText("Lunch smoke").performClick()
        compose.onNodeWithText("Delete entry").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(15_000) { read().entries.none { it.note == "Lunch smoke" } }
        assertEquals(100_000, LedgerEngine.accountBalance(read(), read().accounts.first { it.id == "bank" }))
    }

    @Test fun transferAndFundPurchaseUpdateBothWalletAndLedger() {
        // Transfer is one of the three quick actions on Home; the shopping funds live on the Wallet page.
        compose.onNodeWithText("Transfer").performClick()
        field("Transfer amount (RM)").performTextInput("5.00")
        save()
        compose.waitUntil(15_000) { read().entries.any { it.type == EntryType.TRANSFER } }
        compose.onNodeWithTag("nav_Wallet").performClick()
        fun openFund() {
            compose.waitForIdle()
            compose.onNodeWithTag("wallet_page").performScrollToNode(hasTestTag("wallet_fund_shoes"))
            compose.onNodeWithTag("wallet_fund_shoes").performClick()
        }
        openFund()
        compose.onNodeWithText("Add / release funds").performClick()
        field("Amount (RM)").performTextInput("50.00")
        save()
        compose.waitUntil(15_000) { LedgerEngine.fundSaved(read(), "shoes") == 5_000L }
        openFund()
        compose.onNodeWithText("Mark as purchased").performClick()
        compose.onAllNodesWithText("Bank").onLast().performClick()
        save()
        compose.waitUntil(15_000) { read().funds.single().boughtOn != null }
        val data = read()
        assertEquals(91_500, LedgerEngine.accountBalance(data, data.accounts.first { it.id == "bank" }))
        assertEquals(0, LedgerEngine.fundSaved(data, "shoes"))
        screenshot("wallet-purchased")
    }

    @Test fun allSixPagesAndSettingsRender() {
        screenshot("today")
        // History and the budget assistant open from Home; the pill holds the other four.
        compose.onNodeWithContentDescription("See all transactions").performClick()
        screenshot("ledger")
        listOf("Analytics" to "insights", "Wallet" to "wallet", "Goals" to "goals").forEach { (label, file) ->
            compose.onNodeWithTag("nav_$label").performClick()
            screenshot(file)
        }
        compose.onNodeWithTag("nav_Home").performClick()
        compose.onNodeWithTag("nav_AI").performClick()
        screenshot("advisor")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Income, bills and budget").assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText("Dark").performClick()
        screenshot("settings-dark")
        compose.onNodeWithText("System").performClick()
    }

    @Test fun onboardingCreatesBudgetAndSurvivesRotationBeforeSave() {
        scenario.close()
        runBlocking { repository.update { AppData() } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Let’s get\nstarted").fetchSemanticsNodes().isNotEmpty() }
        field("What should we call you?").performTextInput("Jin")
        compose.onNodeWithText("Income & savings").performClick()
        // The income step opens as a big yellow card whose amount field is labelled for accessibility.
        compose.onNode(hasSetTextAction() and hasContentDescription("Income or allowance in RM")).performTextInput("1200")
        scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction() and hasText("1200")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page").performScrollToNode(hasText("Continue"))
        screenshot("onboarding")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(15_000) { read().settings != null }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Available to spend today").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(120_000, read().settings!!.incomeMinor)
        assertEquals("Jin", read().settings!!.nickname)
    }

    @Test fun pinCanLockUnlockAndBeRemoved() {
        val prefs = AppPreferences(ApplicationProvider.getApplicationContext())
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("page").performScrollToNode(hasText("Set a 6-digit PIN"))
            compose.onNodeWithText("Set a 6-digit PIN").performClick()
            field("New 6-digit PIN").performTextInput("123456")
            field("Confirm PIN").performTextInput("123456")
            save()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Lock now").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Lock now").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
            screenshot("locked")
            field("PIN").performTextInput("000000")
            compose.onNodeWithText("Unlock").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Incorrect PIN").fetchSemanticsNodes().isNotEmpty() }
            field("PIN").performTextInput("123456")
            compose.onNodeWithText("Unlock").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Change or turn off PIN").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Change or turn off PIN").performClick()
            field("Current PIN").performTextInput("123456")
            compose.onNode(isToggleable() and hasAnySibling(hasText("Turn off PIN"))).performClick()
            save()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Set a 6-digit PIN").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) { compose.onRoot().printToLog("PinFlow"); throw e
        } finally { runBlocking { prefs.removePin("123456") } }
    }
}
