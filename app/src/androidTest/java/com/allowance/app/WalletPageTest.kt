package com.allowance.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.allowance.app.ui.WalletPage
import com.allowance.app.ui.walletDesignState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WalletPageTest {
    @get:Rule val compose = createComposeRule()

    /** The funds sit well down the page, so scroll the list to them before touching them. */
    private fun scrollToFund(name: String) = compose.onNodeWithTag("wallet_page").performScrollToNode(hasTestTag("wallet_fund_$name")).let { compose.onNodeWithTag("wallet_fund_$name") }

    @Test fun fundsShowShortExactAndOverAmounts() {
        compose.setContent { MaterialTheme { WalletPage(walletDesignState(), open = {}) } }
        scrollToFund("short").assertIsDisplayed()
        compose.onNodeWithText("RM 300.00").assertIsDisplayed()
        compose.onNodeWithText("Short by").assertIsDisplayed()
        scrollToFund("over").assertIsDisplayed()
        compose.onNodeWithText("RM 330.00").assertIsDisplayed()
        compose.onNodeWithText("Target RM 310.00").assertIsDisplayed()
        compose.onNodeWithText("OVER +RM 20.00").assertIsDisplayed()
        scrollToFund("ready").assertIsDisplayed()
        compose.onNodeWithText("RM 1,200.00").assertIsDisplayed()
        compose.onNodeWithText("Fully funded").assertIsDisplayed()
    }

    @Test fun fundSheetPreservesFundingRoute() {
        var route = ""
        compose.setContent { MaterialTheme { WalletPage(walletDesignState(), open = { route = it }) } }
        scrollToFund("short").performClick()
        compose.onNodeWithText("Add / release funds").performClick()
        compose.runOnIdle { assertEquals("fundin:short", route) }
    }

    @Test fun accountTileOpensItsEditorAndBalancesCanBeHidden() {
        var route = ""
        compose.setContent { MaterialTheme { WalletPage(walletDesignState(), open = { route = it }) } }
        compose.onNodeWithText("Pocket cash").performClick()
        compose.runOnIdle { assertTrue(route.startsWith("account:") && route != "account:") }
        compose.onNodeWithText("Add account").performClick()
        compose.runOnIdle { assertEquals("account:", route) }
        compose.onNodeWithContentDescription("Hide balances").performClick()
        compose.onAllNodesWithText("RM ••••••").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Show balances").assertIsDisplayed()
    }
}
